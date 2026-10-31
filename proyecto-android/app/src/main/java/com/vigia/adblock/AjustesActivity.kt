package com.vigia.adblock

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigia.core.R
import com.vigia.core.red.UtilRed
import com.vigia.core.red.VpnController
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import com.vigia.mitm.CaManager

class AjustesActivity : ActividadBase() {

    private lateinit var vpn: VpnController
    private var pendienteListas = false

    // Exportar la CA: el sistema de archivos del móvil la guarda donde elija el usuario (no hace falta ningún permiso).
    private val guardarCa = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(CaManager.exportarDer()) }
            Dialogos.informar(
                this, "Certificado guardado",
                "Ahora instálalo: Ajustes de Android › Seguridad › Cifrado y credenciales › Instalar un certificado › " +
                    "Certificado de CA. Elige el archivo que acabas de guardar."
            )
        } catch (e: Exception) {
            Dialogos.informar(this, "No se pudo guardar", e.message ?: "Error desconocido")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpn = VpnController.crear(this, AdBlockVpnService::class.java)
        construirUi()
    }

    override fun onPause() {
        if (pendienteListas) {
            pendienteListas = false
            ListasManager.actualizarAsync(this)
        }
        super.onPause()
    }

    // ------------------------------------------------------------------ construcción

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Ajustes"), Vistas.anchoCompleto(this, 12))

        // --- Listas
        val l = seccion(raiz, "Listas de bloqueo")
        l.addView(Vistas.texto(this, "Los cambios se aplican al salir de esta pantalla. Mantén pulsada una lista propia para quitarla.", true), Vistas.anchoCompleto(this, 8))
        for (lista in Ajustes.todasLasListas(this)) {
            val marcada = Ajustes.listasActivas(this).contains(lista.id)
            val fila = casilla(lista.nombre, lista.descripcion, marcada) { v ->
                Ajustes.setListaActiva(this, lista.id, v)
                pendienteListas = true
            }
            if (lista.propia) {
                fila.setOnLongClickListener {
                    Dialogos.confirmar(this, "Quitar lista", "¿Quitar «" + lista.nombre + "»?", "Quitar") {
                        Ajustes.quitarPropia(this, lista.id)
                        pendienteListas = true
                        recreate()
                    }
                    true
                }
            }
            l.addView(fila, Vistas.anchoCompleto(this, 6))
        }
        l.addView(Vistas.boton(this, "Añadir lista por URL (https)", TipoBoton.TONAL) {
            Dialogos.pedirTexto(this, "URL de la lista", "https://…") { url ->
                if (Ajustes.agregarPropia(this, url) == null) {
                    Dialogos.informar(this, "URL no válida", "Debe empezar por https://")
                } else {
                    pendienteListas = true
                    recreate()
                }
            }
        }, Vistas.anchoCompleto(this, 0))

        // --- Dominios propios
        val d = seccion(raiz, "Tus dominios")
        d.addView(Vistas.boton(this, "Bloquear dominios extra…", TipoBoton.TONAL) {
            editarTexto("Dominios bloqueados extra", Ajustes.bloqueExtra(this)) { Ajustes.setBloqueExtra(this, it); pendienteListas = true }
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Dominios permitidos…", TipoBoton.TONAL) {
            editarTexto("Dominios permitidos (nunca se bloquean)", Ajustes.permitidosExtra(this)) { Ajustes.setPermitidosExtra(this, it); pendienteListas = true }
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Comprobar un dominio", TipoBoton.TONAL) { comprobar() }, Vistas.anchoCompleto(this, 8))
        d.addView(
            casilla(
                "Registro DNS por app",
                "Guarda en memoria qué dominios consulta cada app, para bloquear los de sus anuncios. Gasta algo más de batería.",
                Ajustes.registroDns(this)
            ) { v ->
                Ajustes.setRegistroDns(this, v)
                RegistroDns.activo = v
                if (!v) RegistroDns.vaciar()
            }, Vistas.anchoCompleto(this, 8)
        )
        d.addView(Vistas.boton(this, "Ver registro DNS por app", TipoBoton.TONAL) {
            startActivity(Intent(this, RegistroDnsActivity::class.java))
        }, Vistas.anchoCompleto(this, 0))

        // --- Red
        val r = seccion(raiz, "Red")
        r.addView(Vistas.boton(this, "Servidor DNS: " + nombreDns(Ajustes.dnsUpstream(this)), TipoBoton.TONAL) { elegirDns() }, Vistas.anchoCompleto(this, 8))
        r.addView(casilla("Responder NXDOMAIN en lugar de 0.0.0.0", "Algunas apps reintentan menos con NXDOMAIN.", Ajustes.bloqueoNxdomain(this)) { v ->
            Ajustes.setBloqueoNxdomain(this, v)
            reiniciarSiActiva()
        }, Vistas.anchoCompleto(this, 8))
        val profundo = Ajustes.modoProfundo(this)
        r.addView(
            casilla(
                "Bloquear DNS cifrado (DoT/DoH)",
                "Cierra el atajo de apps que usan su propio DNS cifrado y se saltan el filtro. Los dominios de resolvedores " +
                    "conocidos se bloquean siempre; el puerto 853 y sus IP solo en modo SNI o Profundo. Si en Android tienes " +
                    "«DNS privado» en modo estricto, desactiva esto o te quedarás sin DNS.",
                Ajustes.bloquearDnsCifrado(this)
            ) { v ->
                Ajustes.setBloquearDnsCifrado(this, v)
                AdBlockEstado.filtro.bloquearDnsCifrado = v
                ListasManager.reconstruirAsync(this)
                reiniciarSiActiva()
            }, Vistas.anchoCompleto(this, 8)
        )
        r.addView(Vistas.boton(this, "Bloqueo por SNI (experimental): " + (if (profundo) "activado" else "desactivado"), TipoBoton.TONAL) { alternarProfundo() }, Vistas.anchoCompleto(this, 8))
        r.addView(Vistas.boton(this, "Apps excluidas (" + Ajustes.excluidas(this).size + ")", TipoBoton.TONAL) { elegirExcluidas() }, Vistas.anchoCompleto(this, 0))

        // --- Modo Profundo (MitM)
        construirMitm(raiz)

        // --- Sistema
        val s = seccion(raiz, "Sistema")
        s.addView(casilla("Activar al encender el móvil", "Si la protección estaba activa, se reanuda sola.", Ajustes.arranqueAuto(this)) { Ajustes.setArranqueAuto(this, it) }, Vistas.anchoCompleto(this, 8))
        s.addView(casilla("Actualizar listas solo con Wi-Fi", "La actualización diaria (hacia las 4:00) evita los datos móviles.", Ajustes.soloWifi(this)) { v ->
            Ajustes.setSoloWifi(this, v)
            Programador.programar(this, true)
        }, Vistas.anchoCompleto(this, 8))
        s.addView(Vistas.boton(this, "VPN siempre activa (recomendado)", TipoBoton.TONAL) { abrirVpnSiempreActiva() }, Vistas.anchoCompleto(this, 8))
        s.addView(Vistas.boton(this, "Reiniciar estadísticas", TipoBoton.PELIGRO) {
            Dialogos.confirmar(this, "Reiniciar estadísticas", "Se pondrán a cero los contadores de hoy, de los últimos días y el total.", "Reiniciar") {
                Estadisticas.reiniciar(this)
            }
        }, Vistas.anchoCompleto(this, 0))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    // ------------------------------------------------------------------ Modo Profundo (MitM)

    private fun construirMitm(raiz: LinearLayout) {
        val m = seccion(raiz, "Modo Profundo (MitM)")
        m.addView(
            Vistas.texto(
                this,
                "Descifra localmente el tráfico HTTPS (443) para ocultar los huecos de anuncios y vaciar scripts e imágenes " +
                    "bloqueados sin romper la web. Necesita instalar el certificado CA de esta app. Las apps que lo rechazan " +
                    "(SSL pinning) se dejan sin descifrar automáticamente. Apagado = bloqueo DNS/SNI normal.",
                true
            ), Vistas.anchoCompleto(this, 8)
        )
        val activo = Ajustes.mitmActivo(this)
        m.addView(Vistas.boton(this, "Modo Profundo (MitM): " + (if (activo) "activado" else "desactivado"), TipoBoton.TONAL) { alternarMitm(!activo) }, Vistas.anchoCompleto(this, 8))

        val estado = Vistas.texto(this, "Certificado CA: comprobando…", true)
        m.addView(estado, Vistas.anchoCompleto(this, 8))
        conCa {
            estado.text = if (CaManager.estaInstalada()) "Certificado CA: instalado ✔\nSHA-256: " + CaManager.huellaSha256().take(47) + "…"
            else "Certificado CA: NO instalado (el Modo Profundo no arrancará hasta instalarlo)"
        }

        m.addView(Vistas.boton(this, "Exportar certificado CA (.crt)", TipoBoton.TONAL) {
            conCa { guardarCa.launch("AntiAds-CA.crt") }
        }, Vistas.anchoCompleto(this, 8))
        m.addView(Vistas.boton(this, "Abrir ajustes de seguridad de Android", TipoBoton.TONAL) {
            try {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e: Exception) {
                Dialogos.informar(this, "No se pudo abrir", "Ve a Ajustes › Seguridad › Cifrado y credenciales.")
            }
        }, Vistas.anchoCompleto(this, 8))
        m.addView(Vistas.boton(this, "Apps sin descifrar (" + Ajustes.mitmExentas(this).size + ")", TipoBoton.TONAL) { verExentas() }, Vistas.anchoCompleto(this, 0))
    }

    /** La CA tarda un momento la primera vez (RSA-2048): fuera del hilo principal. */
    private fun conCa(alTerminar: () -> Unit) {
        Thread {
            try {
                CaManager.iniciar(this)
                runOnUiThread { if (!isFinishing) alTerminar() }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing) Dialogos.informar(this, "Error con el certificado", e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun alternarMitm(activar: Boolean) {
        if (!activar) {
            Ajustes.setMitmActivo(this, false)
            reiniciarSiActiva()
            recreate()
            return
        }
        Dialogos.confirmar(
            this, "Modo Profundo (MitM)",
            "Esta app podrá leer el tráfico HTTPS de las apps que acepten su certificado. Solo se procesa en el móvil. " +
                "Desde Android 7 la mayoría de las apps NO aceptan certificados instalados por el usuario (los navegadores " +
                "sí): esas se dejarán sin descifrar. Si quitas la app, quita también el certificado. ¿Activarlo?",
            "Activar"
        ) {
            conCa {
                Ajustes.setMitmActivo(this, true)
                if (!CaManager.estaInstalada()) {
                    Dialogos.informar(
                        this, "Falta instalar el certificado",
                        "Pulsa «Exportar certificado CA», guárdalo e instálalo desde Ajustes de Android › Seguridad › " +
                            "Cifrado y credenciales › Instalar un certificado › Certificado de CA. Hasta entonces se usa el bloqueo DNS."
                    )
                }
                reiniciarSiActiva()
                recreate()
            }
        }
    }

    private fun verExentas() {
        val lista = Ajustes.mitmExentas(this).sorted()
        if (lista.isEmpty()) {
            Dialogos.informar(this, "Apps sin descifrar", "Ninguna todavía. Cuando una app rechace el certificado (SSL pinning) aparecerá aquí.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Apps sin descifrar")
            .setMessage(lista.joinToString("\n"))
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Vaciar lista") { _, _ ->
                Ajustes.setMitmExentas(this, emptySet())
                Toast.makeText(this, "Se volverá a intentar descifrar todas", Toast.LENGTH_SHORT).show()
                reiniciarSiActiva()
                recreate()
            }
            .show()
    }

    /**
     * Tarjeta plegable: el título es una cabecera que se toca para abrir o cerrar. Devuelve el contenedor del contenido,
     * así que el resto del código añade vistas igual que antes. Empiezan todas cerradas; lo abierto se recuerda
     * mientras dure el proceso (también tras recreate()).
     */
    private fun seccion(raiz: LinearLayout, titulo: String): LinearLayout {
        val t = Vistas.tarjeta(this)
        val cab = Vistas.subtitulo(this, titulo)
        cab.setPadding(0, Vistas.dp(this, 6), 0, Vistas.dp(this, 6))
        val contenido = LinearLayout(this)
        contenido.orientation = LinearLayout.VERTICAL
        contenido.setPadding(0, Vistas.dp(this, 8), 0, 0)
        fun pintar() {
            val abierta = abiertas.contains(titulo)
            cab.text = (if (abierta) "▾  " else "▸  ") + titulo
            contenido.visibility = if (abierta) View.VISIBLE else View.GONE
        }
        cab.setOnClickListener {
            if (!abiertas.add(titulo)) abiertas.remove(titulo)
            pintar()
        }
        pintar()
        t.addView(cab, Vistas.anchoCompleto(this, 0))
        t.addView(contenido, Vistas.anchoCompleto(this, 0))
        raiz.addView(t, Vistas.anchoCompleto(this, 12))
        return contenido
    }

    companion object {
        private val abiertas = HashSet<String>()
    }

    private fun casilla(texto: String, sub: String?, marcada: Boolean, alCambiar: (Boolean) -> Unit): LinearLayout {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.VERTICAL
        val cb = MaterialCheckBox(this)
        cb.text = texto
        cb.setTextColor(Vistas.color(this, R.color.vc_texto))
        cb.isChecked = marcada
        cb.setOnCheckedChangeListener { _, v -> alCambiar(v) }
        fila.addView(cb)
        if (sub != null) {
            val t = Vistas.texto(this, sub, true)
            t.setPadding(Vistas.dp(this, 48), 0, 0, 0)
            fila.addView(t)
        }
        // La pulsación larga debe llegar a la fila entera (para quitar listas propias).
        cb.setOnLongClickListener { fila.performLongClick() }
        return fila
    }

    // ------------------------------------------------------------------ acciones

    private fun reiniciarSiActiva() {
        if (!vpn.activa.value) return
        vpn.stop()
        Handler(Looper.getMainLooper()).postDelayed({ vpn.start() }, 900L)
    }

    private fun nombreDns(valor: String): String {
        return when (valor) {
            "sistema" -> "el de tu red"
            "1.1.1.1" -> "Cloudflare"
            "9.9.9.9" -> "Quad9"
            "94.140.14.14" -> "AdGuard"
            else -> valor
        }
    }

    private fun elegirDns() {
        val opciones = listOf("El de tu red (recomendado)", "Cloudflare · 1.1.1.1", "Quad9 · 9.9.9.9", "AdGuard · 94.140.14.14", "Otro…")
        Dialogos.elegir(this, "Servidor DNS", opciones) { i ->
            when (i) {
                0 -> cambiarDns("sistema")
                1 -> cambiarDns("1.1.1.1")
                2 -> cambiarDns("9.9.9.9")
                3 -> cambiarDns("94.140.14.14")
                else -> Dialogos.pedirTexto(this, "Dirección IPv4 del servidor DNS", "8.8.8.8") { t ->
                    if (UtilRed.esIpv4Valida(t.trim())) cambiarDns(t.trim())
                    else Dialogos.informar(this, "Dirección no válida", "Escribe una IPv4, por ejemplo 8.8.8.8")
                }
            }
        }
    }

    private fun cambiarDns(valor: String) {
        Ajustes.setDnsUpstream(this, valor)
        reiniciarSiActiva()
        recreate()
    }

    private fun alternarProfundo() {
        if (Ajustes.modoProfundo(this)) {
            Ajustes.setModoProfundo(this, false)
            reiniciarSiActiva()
            recreate()
            return
        }
        Dialogos.confirmar(
            this, "Modo profundo (experimental)",
            "Hace pasar todo el tráfico por la app para también bloquear por nombre de servidor TLS (SNI) y bloquear QUIC. " +
                "Consume más batería y puede fallar con algunas redes. No sirve para anuncios que salen de los mismos " +
                "dominios que el contenido (como YouTube). ¿Activarlo?",
            "Activar"
        ) {
            Ajustes.setModoProfundo(this, true)
            reiniciarSiActiva()
            recreate()
        }
    }

    private fun elegirExcluidas() {
        val pm = packageManager
        val intento = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intento, 0)
            .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        val actuales = Ajustes.excluidas(this)
        val marcadas = BooleanArray(apps.size) { actuales.contains(apps[it].first) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Apps sin filtrar")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton("Guardar") { _, _ ->
                val nuevas = HashSet<String>()
                for (i in apps.indices) if (marcadas[i]) nuevas.add(apps[i].first)
                // Se conservan las que ya no tienen icono de lanzador (no aparecen en la lista).
                for (p in actuales) if (apps.none { it.first == p }) nuevas.add(p)
                Ajustes.setExcluidas(this, nuevas)
                reiniciarSiActiva()
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editarTexto(titulo: String, actual: String, alGuardar: (String) -> Unit) {
        val campo = EditText(this)
        campo.setText(actual)
        campo.hint = "Un dominio por línea"
        campo.minLines = 6
        campo.gravity = Gravity.TOP or Gravity.START
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        campo.setTextColor(Vistas.color(this, R.color.vc_texto))
        val marco = FrameLayout(this)
        val m = Vistas.dp(this, 20)
        marco.setPadding(m, Vistas.dp(this, 8), m, 0)
        marco.addView(campo)
        MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setView(marco)
            .setPositiveButton("Guardar") { _, _ -> alGuardar(campo.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun comprobar() {
        Dialogos.pedirTexto(this, "Dominio a comprobar", "ads.ejemplo.com") { t ->
            val d = UtilRed.normalizarDominio(t)
            if (d == null) {
                Dialogos.informar(this, "Dominio no válido", "Escribe un dominio como ads.ejemplo.com")
            } else if (!AdBlockEstado.filtro.cargado) {
                Dialogos.informar(this, d, "El filtro todavía no está cargado. Actualiza las listas primero.")
            } else {
                val texto = when (AdBlockEstado.filtro.consultar(d)) {
                    FiltroAdBlock.BLOQUEADO -> "BLOQUEADO por las listas activas o por tus dominios extra."
                    FiltroAdBlock.PERMITIDO -> "PERMITIDO: una excepción lo protege del bloqueo."
                    else -> "No está en ninguna lista: no se bloquea."
                }
                Dialogos.informar(this, d, texto)
            }
        }
    }

    private fun abrirVpnSiempreActiva() {
        Dialogos.confirmar(
            this, "VPN siempre activa",
            "Android puede mantener AdBlock encendido y reiniciarlo si se cierra. En la pantalla que se abre, toca la rueda " +
                "junto a «AdBlock Soberano» y activa «VPN siempre activa». No actives «Bloquear conexiones sin VPN» " +
                "si quieres poder apagar la protección sin perder internet.",
            "Abrir ajustes"
        ) {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                Dialogos.informar(this, "No se pudo abrir", "Ve a Ajustes › Red › VPN.")
            }
        }
    }
}
