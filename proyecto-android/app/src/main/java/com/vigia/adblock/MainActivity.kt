package com.vigia.adblock

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.vigia.core.R
import com.vigia.core.red.VpnController
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import com.vigia.core.util.Formato
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Pantalla única: interruptor grande, contadores, últimos 7 días y los dominios más bloqueados hoy. */
class MainActivity : ActividadBase() {

    private lateinit var vpn: VpnController
    private lateinit var botonProteccion: MaterialButton
    private lateinit var botonActualizar: MaterialButton
    private lateinit var textoFiltro: TextView
    private lateinit var numHoy: TextView
    private lateinit var numTotal: TextView
    private lateinit var barras: LinearLayout
    private lateinit var listaTop: LinearLayout
    private lateinit var textoProgreso: TextView

    private val numeros: NumberFormat = NumberFormat.getIntegerInstance()
    private var firmaBarras = ""
    private var firmaTop = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpn = VpnController.crear(this, AdBlockVpnService::class.java)
        Estadisticas.iniciar(this)
        Programador.programar(this, false)
        ListasManager.asegurarCargado(this)
        pedirPermisoNotificaciones()
        construirUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refrescar()
                    delay(1500L)
                }
            }
        }
        lifecycleScope.launch { ListasManager.estado.collect { textoProgreso.text = it } }
        lifecycleScope.launch { ListasManager.actualizando.collect { botonActualizar.isEnabled = !it } }
        lifecycleScope.launch { vpn.mensaje.collect { m -> if (m != null) textoProgreso.text = m } }
    }

    override fun onStop() {
        Estadisticas.guardar(this)
        super.onStop()
    }

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "AntiAds"), Vistas.anchoCompleto(this, 2))
        raiz.addView(Vistas.texto(this, "Bloqueo de anuncios y rastreadores en tu teléfono. Sin cuentas, sin nubes.", true), Vistas.anchoCompleto(this, 16))

        botonProteccion = Vistas.boton(this, "Protección desactivada") { alternar() }
        botonProteccion.textSize = 22f
        val lpGrande = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Vistas.dp(this, 112))
        lpGrande.bottomMargin = Vistas.dp(this, 8)
        raiz.addView(botonProteccion, lpGrande)

        textoFiltro = Vistas.texto(this, "", true)
        textoFiltro.gravity = Gravity.CENTER
        raiz.addView(textoFiltro, Vistas.anchoCompleto(this, 16))

        val contadores = Vistas.tarjeta(this)
        numHoy = Vistas.titulo(this, "0")
        numHoy.textSize = 40f
        contadores.addView(numHoy, Vistas.anchoCompleto(this, 0))
        contadores.addView(Vistas.texto(this, "anuncios bloqueados hoy", true), Vistas.anchoCompleto(this, 8))
        numTotal = Vistas.texto(this, "")
        contadores.addView(numTotal, Vistas.anchoCompleto(this, 12))
        barras = LinearLayout(this)
        barras.orientation = LinearLayout.HORIZONTAL
        barras.gravity = Gravity.BOTTOM
        contadores.addView(barras, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Vistas.dp(this, 96)))
        raiz.addView(contadores, Vistas.anchoCompleto(this, 12))

        val top = Vistas.tarjeta(this)
        top.addView(Vistas.subtitulo(this, "Más bloqueados hoy"), Vistas.anchoCompleto(this, 4))
        top.addView(Vistas.texto(this, "Toca un dominio si una app deja de funcionar para permitirlo.", true), Vistas.anchoCompleto(this, 8))
        listaTop = LinearLayout(this)
        listaTop.orientation = LinearLayout.VERTICAL
        top.addView(listaTop, Vistas.anchoCompleto(this, 0))
        raiz.addView(top, Vistas.anchoCompleto(this, 12))

        botonActualizar = Vistas.boton(this, "Actualizar listas ahora", TipoBoton.TONAL) { actualizar() }
        raiz.addView(botonActualizar, Vistas.anchoCompleto(this, 4))
        textoProgreso = Vistas.texto(this, "", true)
        raiz.addView(textoProgreso, Vistas.anchoCompleto(this, 12))
        raiz.addView(
            Vistas.boton(this, "Laboratorio", TipoBoton.TONAL) { startActivity(Intent(this, LaboratorioActivity::class.java)) },
            Vistas.anchoCompleto(this, 8)
        )
        raiz.addView(
            Vistas.boton(this, "Registro DNS por app", TipoBoton.TONAL) { startActivity(Intent(this, RegistroDnsActivity::class.java)) },
            Vistas.anchoCompleto(this, 8)
        )
        raiz.addView(
            Vistas.boton(this, "Ajustes", TipoBoton.TONAL) { startActivity(Intent(this, AjustesActivity::class.java)) },
            Vistas.anchoCompleto(this, 0)
        )

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun alternar() {
        if (vpn.activa.value) {
            Ajustes.setActiva(this, false)
            vpn.stop()
            return
        }
        asegurarVpn(vpn) {
            Ajustes.setActiva(this, true)
            if (!AdBlockEstado.filtro.cargado && !ListasManager.hayListasDescargadas(this)) {
                ListasManager.actualizarAsync(this)
            }
            vpn.start()
        }
    }

    private fun actualizar() {
        if (ListasManager.actualizando.value) return
        if (ListasManager.redMedida(this)) {
            Dialogos.confirmar(
                this, "Datos móviles",
                "Estás en una red de pago por uso y la descarga puede ocupar varios MB. ¿Continuar?", "Descargar"
            ) { ListasManager.actualizarAsync(this) }
        } else {
            ListasManager.actualizarAsync(this)
        }
    }

    private fun refrescar() {
        val activa = vpn.activa.value
        // Un solo botón para las dos cosas: verde = activa (tócalo para desactivar), rojo = desactivada (tócalo para activar).
        val titulo = if (activa) "Protección activa" else "Protección desactivada"
        val pista = if (activa) "Toca para desactivar" else "Toca para activar"
        val texto = SpannableStringBuilder(titulo + "\n" + pista)
        texto.setSpan(RelativeSizeSpan(0.6f), titulo.length + 1, texto.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        botonProteccion.text = texto
        val fondo = if (activa) Vistas.color(this, R.color.vc_ok) else Vistas.color(this, R.color.vc_peligro)
        val letra = if (activa) Vistas.color(this, R.color.vc_fondo) else Color.WHITE
        botonProteccion.backgroundTintList = ColorStateList.valueOf(fondo)
        botonProteccion.setTextColor(letra)

        val n = AdBlockEstado.filtro.totalDominios
        val ultima = Ajustes.ultimaActualizacion(this)
        val base = if (n == 0) {
            "Sin listas cargadas. Pulsa «Actualizar listas ahora»."
        } else {
            numeros.format(n) + " dominios en el filtro" + (if (ultima > 0L) " · listas del " + Formato.fecha(ultima) else "")
        }
        textoFiltro.text = base + textoMitm(activa)

        numHoy.text = numeros.format(Estadisticas.hoy())
        numTotal.text = numeros.format(Estadisticas.total()) + " bloqueados en total"
        pintarBarras()
        pintarTop()
    }

    /** Línea de estado del Modo Profundo: si no sube «descifradas» al navegar, no está funcionando. */
    private fun textoMitm(activa: Boolean): String {
        if (!Ajustes.mitmActivo(this)) return ""
        if (!activa) return "\nModo Profundo (MitM): activado en Ajustes, la protección está parada"
        if (!MitmEstado.activo) return "\nModo Profundo (MitM): NO activo · falta instalar el certificado CA (usa bloqueo DNS)"
        return "\nModo Profundo (MitM): activo\n" +
            numeros.format(MitmEstado.descifradas.get()) + " conexiones descifradas · " +
            numeros.format(MitmEstado.vaciados.get()) + " recursos vaciados · " +
            numeros.format(MitmEstado.paginasLimpias.get()) + " páginas limpiadas\n" +
            Ajustes.mitmExentas(this).size.toString() + " apps sin descifrar (rechazan el certificado)"
    }

    private fun pintarBarras() {
        val datos = Estadisticas.ultimosDias()
        val firma = datos.joinToString(",")
        if (firma == firmaBarras) return
        firmaBarras = firma
        barras.removeAllViews()
        var max = 1L
        for (v in datos) if (v > max) max = v
        for (k in datos.indices) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val alto = Vistas.dp(this, 4) + (Vistas.dp(this, 56) * datos[k] / max).toInt()
            val barra = View(this)
            val fondo = GradientDrawable()
            fondo.cornerRadius = Vistas.dp(this, 4).toFloat()
            fondo.setColor(Vistas.color(this, if (k == datos.size - 1) R.color.vc_acento else R.color.vc_superficie_alta))
            barra.background = fondo
            col.addView(barra, LinearLayout.LayoutParams(Vistas.dp(this, 22), alto))
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -(datos.size - 1 - k))
            val letra = SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time).take(1).uppercase()
            col.addView(Vistas.texto(this, letra, true))
            barras.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun pintarTop() {
        val top = Estadisticas.masBloqueadosHoy(8)
        val firma = top.joinToString("|") { it.first + ":" + it.second }
        if (firma == firmaTop) return
        firmaTop = firma
        listaTop.removeAllViews()
        if (top.isEmpty()) {
            listaTop.addView(Vistas.texto(this, "Todavía no se ha bloqueado nada hoy.", true))
            return
        }
        for (e in top) {
            val fila = Vistas.texto(this, e.first + "  ·  " + numeros.format(e.second.toLong()))
            fila.setPadding(0, Vistas.dp(this, 6), 0, Vistas.dp(this, 6))
            fila.setOnClickListener { permitir(e.first) }
            listaTop.addView(fila)
        }
    }

    private fun permitir(dominio: String) {
        Dialogos.confirmar(
            this, "Permitir dominio",
            "¿Dejar de bloquear " + dominio + " y sus subdominios? Podrás revertirlo en Ajustes › Dominios permitidos.", "Permitir"
        ) {
            Ajustes.agregarPermitido(this, dominio)
            ListasManager.reconstruirAsync(this)
            textoProgreso.text = dominio + " permitido."
        }
    }
}
