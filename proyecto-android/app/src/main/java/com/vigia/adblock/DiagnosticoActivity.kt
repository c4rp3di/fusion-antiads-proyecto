package com.vigia.adblock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigia.core.R
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico: el registro ÚNICO de todo lo que hace AntiAds. Cada línea se puede tocar para ver su detalle y actuar sobre ella
 * (bloquear, permitir, dejar una app sin descifrar…). Lo que cambia el comportamiento está en Ajustes › Laboratorio.
 */
class DiagnosticoActivity : ActividadBase() {

    private val filtro = Diagnostico.Filtro()
    private var mostrar = 60
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var contadores: TextView
    private lateinit var registro: TextView
    private lateinit var cabeceraRegistro: TextView
    private lateinit var botonCats: MaterialButton
    private lateinit var botonGrav: MaterialButton
    private lateinit var botonVentana: MaterialButton
    private lateinit var botonApp: MaterialButton
    private lateinit var botonTexto: MaterialButton
    private lateinit var botonLineas: MaterialButton
    private lateinit var botonSesion: MaterialButton
    private lateinit var estadoSesion: TextView
    private lateinit var resumenComprobaciones: TextView
    private lateinit var listaComprobaciones: LinearLayout
    private lateinit var rastreadores: TextView
    private lateinit var efectividad: TextView
    private val botonesRapidos = ArrayList<Pair<MaterialButton, Rapido>>()

    private var firmaRegistro = ""
    private var ultimoRanking = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            pintar()
            ui.postDelayed(this, 2000L)
        }
    }

    private val opcionesLineas = intArrayOf(200, 450, 1000, 2500)
    private var lineas = 450
    private val ventanas = longArrayOf(0L, 5L * 60000L, 60L * 60000L)

    /** Un filtro rápido: un toque en vez de abrir varios diálogos. */
    private class Rapido(val nombre: String, val aplicar: (Diagnostico.Filtro) -> Unit, val coincide: (Diagnostico.Filtro) -> Boolean)

    private fun soloCats(f: Diagnostico.Filtro, vararg c: String): Boolean =
        f.cats == c.toSet() && f.gravMin == Diagnostico.INFO && f.ventanaMs == 0L && f.app == null && f.texto.isEmpty()

    private val rapidos = listOf(
        Rapido("Todo", { it.limpiar() }, { !it.activo() }),
        Rapido(
            "Problemas", { it.limpiar(); it.gravMin = Diagnostico.AVISO },
            { it.gravMin == Diagnostico.AVISO && it.cats == null && it.ventanaMs == 0L && it.app == null && it.texto.isEmpty() }
        ),
        Rapido("Anuncios", { it.limpiar(); it.cats = setOf("bloqueo", "inventada") }, { soloCats(it, "bloqueo", "inventada") }),
        Rapido("Red", { it.limpiar(); it.cats = setOf("conexion", "dns", "cert") }, { soloCats(it, "conexion", "dns", "cert") }),
        Rapido("Página", { it.limpiar(); it.cats = setOf("pagina", "peticion") }, { soloCats(it, "pagina", "peticion") })
    )

    private val guardar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(informe(2500).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Informe guardado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Dialogos.informar(this, "No se pudo guardar", e.message ?: e.javaClass.simpleName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Diagnóstico"), Vistas.anchoCompleto(this, 4))
        raiz.addView(
            Vistas.texto(
                this,
                "Todo lo que registra AntiAds, con filtros. Toca una línea para ver su detalle y actuar. Está solo en memoria. " +
                    "Lo que cambia el comportamiento está en Ajustes › Laboratorio.",
                true
            ), Vistas.anchoCompleto(this, 12)
        )

        // ---- Comprobaciones
        val comp = Paneles.plegable(this, raiz, "diag/comprobaciones", "Comprobaciones", true, true)
        resumenComprobaciones = Vistas.texto(this, "Comprobando…", true)
        comp.addView(resumenComprobaciones, Vistas.anchoCompleto(this, 6))
        listaComprobaciones = LinearLayout(this)
        listaComprobaciones.orientation = LinearLayout.VERTICAL
        comp.addView(listaComprobaciones, Vistas.anchoCompleto(this, 6))
        comp.addView(Vistas.boton(this, "Volver a comprobar", TipoBoton.TONAL) { comprobar() }, Vistas.anchoCompleto(this, 0))

        // ---- Estado
        val estado = Paneles.plegable(this, raiz, "diag/estado", "Estado", true, true)
        contadores = Vistas.texto(this, "", true)
        estado.addView(contadores, Vistas.anchoCompleto(this, 0))

        // ---- Sesión de prueba
        val ses = Paneles.plegable(this, raiz, "diag/sesion", "Sesión de prueba", true, true)
        estadoSesion = Vistas.texto(this, "", true)
        ses.addView(estadoSesion, Vistas.anchoCompleto(this, 8))
        botonSesion = Vistas.boton(this, "Iniciar prueba", TipoBoton.TONAL) { alternarSesion() }
        ses.addView(botonSesion, Vistas.anchoCompleto(this, 8))
        ses.addView(Vistas.boton(this, "Ver la última prueba", TipoBoton.TONAL) {
            val r = Sesiones.ultimas().lastOrNull()
            if (r == null) Toast.makeText(this, "Todavía no hay ninguna prueba terminada", Toast.LENGTH_SHORT).show()
            else mostrarTexto("Última prueba", Sesiones.texto(r))
        }, Vistas.anchoCompleto(this, 8))
        ses.addView(Vistas.boton(this, "Comparar las dos últimas", TipoBoton.TONAL) {
            val l = Sesiones.ultimas()
            if (l.size < 2) Toast.makeText(this, "Hacen falta dos pruebas terminadas", Toast.LENGTH_SHORT).show()
            else mostrarTexto("Comparación", Sesiones.comparar(l[l.size - 2], l[l.size - 1]))
        }, Vistas.anchoCompleto(this, 0))

        // ---- Capturas
        val capturas = Paneles.plegable(this, raiz, "diag/capturas", "Capturas", true)
        capturas.addView(
            Vistas.texto(
                this,
                "Lo que se anota de más. Cuestan algo de batería: vienen apagadas y se apagan solas a los 30 minutos. Los bloqueos, " +
                    "errores, avisos del certificado y eventos del sistema se registran siempre (es barato).",
                true
            ), Vistas.anchoCompleto(this, 8)
        )
        PanelesLab.capturas(this, capturas)
        capturas.addView(Vistas.boton(this, "Registro DNS por app (bloquear o permitir dominios)", TipoBoton.TONAL) {
            startActivity(Intent(this, RegistroDnsActivity::class.java))
        }, Vistas.anchoCompleto(this, 0))

        // ---- Filtros
        val f = Paneles.plegable(this, raiz, "diag/filtros", "Filtros", true, true)
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        for (r in rapidos) {
            val b = Vistas.boton(this, r.nombre, TipoBoton.TONAL) {
                r.aplicar(filtro)
                firmaRegistro = ""
                pintar()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = Vistas.dp(this, 8)
            fila.addView(b, lp)
            botonesRapidos.add(Pair(b, r))
        }
        val desplazable = HorizontalScrollView(this)
        desplazable.isHorizontalScrollBarEnabled = false
        desplazable.addView(fila)
        f.addView(desplazable, Vistas.anchoCompleto(this, 8))

        botonCats = Vistas.boton(this, "", TipoBoton.TONAL) { dialogoCategorias() }
        f.addView(botonCats, Vistas.anchoCompleto(this, 6))
        botonGrav = Vistas.boton(this, "", TipoBoton.TONAL) {
            filtro.gravMin = (filtro.gravMin + 1) % 3
            firmaRegistro = ""
            pintar()
        }
        f.addView(botonGrav, Vistas.anchoCompleto(this, 6))
        botonVentana = Vistas.boton(this, "", TipoBoton.TONAL) {
            val i = ventanas.indexOf(filtro.ventanaMs).coerceAtLeast(0)
            filtro.ventanaMs = ventanas[(i + 1) % ventanas.size]
            firmaRegistro = ""
            pintar()
        }
        f.addView(botonVentana, Vistas.anchoCompleto(this, 6))
        botonApp = Vistas.boton(this, "", TipoBoton.TONAL) { dialogoApps() }
        f.addView(botonApp, Vistas.anchoCompleto(this, 6))
        botonTexto = Vistas.boton(this, "", TipoBoton.TONAL) { dialogoTexto() }
        f.addView(botonTexto, Vistas.anchoCompleto(this, 0))

        // ---- Registro e informe
        val d = Paneles.plegable(this, raiz, "diag/registro", "Registro e informe", true, true)
        cabeceraRegistro = Vistas.texto(this, "", true)
        d.addView(cabeceraRegistro, Vistas.anchoCompleto(this, 6))
        registro = TextView(this)
        registro.typeface = Typeface.MONOSPACE
        registro.textSize = 11.5f
        registro.movementMethod = LinkMovementMethod.getInstance()
        registro.highlightColor = Color.TRANSPARENT
        registro.setTextColor(Vistas.color(this, R.color.vc_texto))
        d.addView(registro, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Mostrar más líneas", TipoBoton.TONAL) {
            mostrar = minOf(mostrar + 100, 1000)
            firmaRegistro = ""
            pintar()
        }, Vistas.anchoCompleto(this, 8))
        botonLineas = Vistas.boton(this, textoLineas(), TipoBoton.TONAL) {
            lineas = opcionesLineas[(opcionesLineas.indexOf(lineas) + 1) % opcionesLineas.size]
            botonLineas.text = textoLineas()
        }
        d.addView(botonLineas, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Copiar informe", TipoBoton.TONAL) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AntiAds", informe(lineas)))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Compartir informe", TipoBoton.TONAL) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, informe(lineas))
            startActivity(Intent.createChooser(i, "Compartir informe"))
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Guardar informe en archivo", TipoBoton.TONAL) {
            guardar.launch("AntiAds-informe.txt")
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Vaciar todo el registro", TipoBoton.TONAL) {
            Diagnostico.vaciar()
            Conexiones.vaciar()
            Paginas.vaciar()
            Laboratorio.reiniciarConteo()
            EfectividadReglas.reiniciar()
            firmaRegistro = ""
            pintar()
        }, Vistas.anchoCompleto(this, 0))

        // ---- Apps y rastreadores
        val rs = Paneles.plegable(this, raiz, "diag/rastreadores", "Apps y rastreadores", true)
        rs.addView(
            Vistas.texto(
                this,
                "Apps ordenadas por cuántos destinos de tus listas de bloqueo contactan. Toca una para ver su ficha. " +
                    "Funciona mejor con «Todas las conexiones» activada.",
                true
            ), Vistas.anchoCompleto(this, 8)
        )
        rastreadores = TextView(this)
        rastreadores.textSize = 13f
        rastreadores.movementMethod = LinkMovementMethod.getInstance()
        rastreadores.highlightColor = Color.TRANSPARENT
        rastreadores.setTextColor(Vistas.color(this, R.color.vc_texto))
        rs.addView(rastreadores, Vistas.anchoCompleto(this, 0))

        // ---- Efectividad de las reglas
        val ef = Paneles.plegable(this, raiz, "diag/efectividad", "Efectividad de las reglas", true)
        ef.addView(
            Vistas.texto(this, "Cuántas veces ha actuado cada regla de reglas.json desde que arrancó la app. ○ = nunca actuó.", true),
            Vistas.anchoCompleto(this, 8)
        )
        efectividad = TextView(this)
        efectividad.typeface = Typeface.MONOSPACE
        efectividad.textSize = 11.5f
        efectividad.setTextIsSelectable(true)
        efectividad.setTextColor(Vistas.color(this, R.color.vc_texto))
        ef.addView(efectividad, Vistas.anchoCompleto(this, 0))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    // ------------------------------------------------------------------ comprobaciones y sesión

    private fun comprobar() {
        resumenComprobaciones.text = "Comprobando…"
        Comprobaciones.ejecutar(this) { lista ->
            listaComprobaciones.removeAllViews()
            val fallos = lista.count { it.estado == Comprobaciones.FALLO }
            val avisos = lista.count { it.estado == Comprobaciones.AVISO }
            resumenComprobaciones.text = if (fallos == 0 && avisos == 0) "Todo correcto." else fallos.toString() + " problemas y " + avisos + " avisos."
            for (r in lista) {
                val icono = when (r.estado) {
                    Comprobaciones.OK -> "✓"
                    Comprobaciones.AVISO -> "⚠️"
                    else -> "✕"
                }
                val t = Vistas.texto(this, icono + "  " + r.titulo)
                listaComprobaciones.addView(t, Vistas.anchoCompleto(this, 0))
                listaComprobaciones.addView(Vistas.texto(this, r.detalle, true), Vistas.anchoCompleto(this, 4))
                val accion = r.accion
                if (r.boton != null && accion != null) {
                    listaComprobaciones.addView(Vistas.boton(this, r.boton, TipoBoton.TONAL) { accion() }, Vistas.anchoCompleto(this, 8))
                }
            }
        }
    }

    private fun alternarSesion() {
        if (Sesiones.activa()) {
            val r = Sesiones.terminar(this)
            if (r != null) mostrarTexto(r.nombre, Sesiones.texto(r))
        } else {
            Sesiones.iniciar(this)
            Toast.makeText(this, "Prueba iniciada: se activaron las capturas", Toast.LENGTH_LONG).show()
        }
        pintar()
    }

    private fun mostrarTexto(titulo: String, texto: String) {
        Acciones.dialogo(this, titulo, texto, listOf(Acciones.Accion("Copiar") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AntiAds", texto))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }))
    }

    // ------------------------------------------------------------------ filtros

    private fun dialogoCategorias() {
        val cats = Diagnostico.CATEGORIAS
        val cuenta = Diagnostico.contarPorCategoria()
        val nombres = Array<CharSequence>(cats.size) { i -> Presentacion.icono(cats[i].first) + " " + cats[i].second + " (" + (cuenta[cats[i].first] ?: 0) + ")" }
        val actuales = filtro.cats
        val marcados = BooleanArray(cats.size) { i -> actuales?.contains(cats[i].first) ?: true }
        MaterialAlertDialogBuilder(this)
            .setTitle("Categorías")
            .setMultiChoiceItems(nombres, marcados) { _, i, v -> marcados[i] = v }
            .setPositiveButton("Aplicar") { _, _ ->
                val sel = HashSet<String>()
                for (i in cats.indices) if (marcados[i]) sel.add(cats[i].first)
                filtro.cats = if (sel.size == cats.size) null else sel
                firmaRegistro = ""
                pintar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoApps() {
        val apps = listOf("Todas") + Diagnostico.apps()
        val actual = apps.indexOf(filtro.app ?: "Todas").coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("App")
            .setSingleChoiceItems(apps.toTypedArray(), actual) { dialogo, i ->
                filtro.app = if (i == 0) null else apps[i]
                dialogo.dismiss()
                firmaRegistro = ""
                pintar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoTexto() {
        val et = EditText(this)
        et.setText(filtro.texto)
        et.hint = "dominio, app o texto"
        MaterialAlertDialogBuilder(this)
            .setTitle("Buscar")
            .setView(et)
            .setPositiveButton("Buscar") { _, _ ->
                filtro.texto = et.text.toString().trim()
                firmaRegistro = ""
                pintar()
            }
            .setNeutralButton("Quitar") { _, _ ->
                filtro.texto = ""
                firmaRegistro = ""
                pintar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun textoLineas() = "Líneas del informe: " + lineas + (if (lineas >= 2500) " (todo)" else "")

    /** El informe completo (ver [Informe]); respeta los filtros y, en modo privado, recorta las URLs. */
    private fun informe(n: Int): String = Informe.generar(this, n, Laboratorio.activa(this, "privado"), filtro)

    override fun onResume() {
        super.onResume()
        comprobar()
        ui.post(tic)
    }

    override fun onPause() {
        ui.removeCallbacks(tic)
        super.onPause()
    }

    // ------------------------------------------------------------------ pintado

    private fun refrescarYa() {
        firmaRegistro = ""
        ultimoRanking = 0L
        pintar()
    }

    private fun pintar() {
        val restante = Laboratorio.restanteDiag() / 60000L
        val cats = Diagnostico.contarPorCategoria()
        contadores.text = (if (MitmEstado.activo) "Modo Profundo: activo · " else "Modo Profundo: NO activo · ") +
            MitmEstado.descifradas.get() + " conexiones descifradas\n" +
            "Eventos: " + Diagnostico.CATEGORIAS.joinToString(" · ") { it.second + " " + (cats[it.first] ?: 0) } + "\n" +
            "Técnicas: " + listOf("clasico", "juguete", "vast", "stubs", "yt", "skip", "css", "hls").joinToString(" · ") { it + " " + Laboratorio.veces(it) } +
            (if (Laboratorio.diagActivas().isEmpty()) "\nCapturas: apagadas" else "\nCapturas activas: " + Laboratorio.diagActivas().joinToString(", ") + " (se apagan en " + restante + " min)")

        botonSesion.text = if (Sesiones.activa()) "Terminar prueba" else "Iniciar prueba"
        estadoSesion.text = if (Sesiones.activa()) {
            val seg = (System.currentTimeMillis() - Sesiones.inicioMs()) / 1000L
            "Prueba en curso desde las " + hora.format(Date(Sesiones.inicioMs())) + " (" + seg / 60L + " min " + seg % 60L + " s). Las capturas están activadas; al terminar verás un resumen de este rato."
        } else {
            "Sin prueba en curso. «Iniciar prueba» activa las capturas y al terminar te resume solo lo ocurrido en ese rato; " +
                "con dos pruebas puedes compararlas (por ejemplo, con y sin una técnica). Guardadas: " + Sesiones.ultimas().size + "."
        }

        for ((b, r) in botonesRapidos) b.text = (if (r.coincide(filtro)) "✓ " else "") + r.nombre
        val nCats = filtro.cats?.size ?: Diagnostico.CATEGORIAS.size
        botonCats.text = "Categorías: " + (if (filtro.cats == null) "todas" else nCats.toString() + " de " + Diagnostico.CATEGORIAS.size)
        botonGrav.text = "Gravedad: " + when (filtro.gravMin) {
            Diagnostico.AVISO -> "avisos y errores"
            Diagnostico.ERROR -> "solo errores"
            else -> "todas"
        }
        botonVentana.text = "Periodo: " + (if (filtro.ventanaMs == 0L) "todo" else (filtro.ventanaMs / 60000L).toString() + " min")
        botonApp.text = "App: " + (filtro.app ?: "todas")
        botonTexto.text = "Buscar: " + (if (filtro.texto.isEmpty()) "—" else filtro.texto)

        pintarRegistro()
        pintarRastreadores()
        efectividad.text = EfectividadReglas.texto(ReglasEstado.actual)
    }

    /** Solo se reconstruye el texto si algo ha cambiado: así no se gasta batería repintando lo mismo cada 2 s. */
    private fun pintarRegistro() {
        val snaps = Diagnostico.snaps(filtro, mostrar)
        val total = Diagnostico.cuantas(filtro)
        val ultimo = snaps.lastOrNull()
        val firma = snaps.size.toString() + ":" + (ultimo?.ultimo ?: 0L) + ":" + snaps.sumOf { it.n } + ":" + total + ":" + filtro.descripcion()
        if (firma == firmaRegistro) return
        firmaRegistro = firma
        cabeceraRegistro.text = "Mostrando " + snaps.size + " de " + total + (if (filtro.activo()) " (con filtros)" else "")
        if (snaps.isEmpty()) {
            registro.text = "(sin eventos con estos filtros)"
            return
        }
        val rojo = Vistas.color(this, R.color.vc_peligro)
        val ambar = Color.rgb(224, 160, 48)
        val sb = SpannableStringBuilder()
        for (s in snaps) {
            val ini = sb.length
            sb.append(Presentacion.linea(s, hora))
            val fin = sb.length
            sb.append('\n')
            if (s.grav >= Diagnostico.ERROR) sb.setSpan(ForegroundColorSpan(rojo), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (s.grav == Diagnostico.AVISO) sb.setSpan(ForegroundColorSpan(ambar), ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(w: View) {
                    Acciones.mostrarEvento(this@DiagnosticoActivity, s, filtro) { refrescarYa() }
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                }
            }, ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        registro.text = sb
    }

    private fun pintarRastreadores() {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoRanking < 6000L) return
        ultimoRanking = ahora
        val r = Rastreadores.ranking().take(15)
        if (r.isEmpty()) {
            rastreadores.text = "(sin datos todavía: activa «Todas las conexiones» en Capturas y usa algunas apps)"
            return
        }
        val sb = SpannableStringBuilder()
        for (fila in r) {
            val ini = sb.length
            sb.append("📱 ").append(fila.app.split('.').takeLast(2).joinToString(".")).append(" · ").append(fila.destinos.size.toString())
                .append(" destinos · ").append(fila.veces.toString()).append(" veces")
            val fin = sb.length
            sb.append("\n\n")
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(w: View) {
                    Acciones.mostrarFicha(this@DiagnosticoActivity, fila.app, filtro) { refrescarYa() }
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                }
            }, ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        rastreadores.text = sb
    }
}
