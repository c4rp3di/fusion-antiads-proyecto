package com.vigia.adblock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
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

/**
 * Diagnóstico: el registro ÚNICO de todo lo que hace AntiAds, con filtros por categoría, gravedad, periodo, app y texto.
 * Lo que cambia el comportamiento (técnicas y reglas) está en Ajustes › Laboratorio.
 */
class DiagnosticoActivity : ActividadBase() {

    private val filtro = Diagnostico.Filtro()
    private var mostrar = 60

    private lateinit var contadores: TextView
    private lateinit var registro: TextView
    private lateinit var cabeceraRegistro: TextView
    private lateinit var botonCats: MaterialButton
    private lateinit var botonGrav: MaterialButton
    private lateinit var botonVentana: MaterialButton
    private lateinit var botonApp: MaterialButton
    private lateinit var botonTexto: MaterialButton
    private lateinit var botonLineas: MaterialButton

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
                "Todo lo que registra AntiAds, en un solo sitio y con filtros. Está solo en memoria. Lo que cambia el comportamiento " +
                    "(técnicas y reglas) está en Ajustes › Laboratorio. Conexiones, peticiones y páginas necesitan el Modo Profundo.",
                true
            ), Vistas.anchoCompleto(this, 12)
        )

        // ---- Estado
        val estado = Paneles.plegable(this, raiz, "diag/estado", "Estado", true, true)
        contadores = Vistas.texto(this, "", true)
        estado.addView(contadores, Vistas.anchoCompleto(this, 0))

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
        botonCats = Vistas.boton(this, "", TipoBoton.TONAL) { dialogoCategorias() }
        f.addView(botonCats, Vistas.anchoCompleto(this, 6))
        botonGrav = Vistas.boton(this, "", TipoBoton.TONAL) {
            filtro.gravMin = (filtro.gravMin + 1) % 3
            pintar()
        }
        f.addView(botonGrav, Vistas.anchoCompleto(this, 6))
        botonVentana = Vistas.boton(this, "", TipoBoton.TONAL) {
            val i = ventanas.indexOf(filtro.ventanaMs).coerceAtLeast(0)
            filtro.ventanaMs = ventanas[(i + 1) % ventanas.size]
            pintar()
        }
        f.addView(botonVentana, Vistas.anchoCompleto(this, 6))
        botonApp = Vistas.boton(this, "", TipoBoton.TONAL) { dialogoApps() }
        f.addView(botonApp, Vistas.anchoCompleto(this, 6))
        botonTexto = Vistas.boton(this, "", TipoBoton.TONAL) { dialogoTexto() }
        f.addView(botonTexto, Vistas.anchoCompleto(this, 6))
        f.addView(Vistas.boton(this, "Quitar todos los filtros", TipoBoton.TONAL) {
            filtro.limpiar()
            pintar()
        }, Vistas.anchoCompleto(this, 0))

        // ---- Registro e informe
        val d = Paneles.plegable(this, raiz, "diag/registro", "Registro e informe", true, true)
        cabeceraRegistro = Vistas.texto(this, "", true)
        d.addView(cabeceraRegistro, Vistas.anchoCompleto(this, 6))
        registro = TextView(this)
        registro.typeface = Typeface.MONOSPACE
        registro.textSize = 11f
        registro.setTextIsSelectable(true)
        registro.setTextColor(Vistas.color(this, R.color.vc_texto))
        d.addView(registro, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Mostrar más líneas", TipoBoton.TONAL) {
            mostrar = minOf(mostrar + 100, 1000)
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
            pintar()
        }, Vistas.anchoCompleto(this, 0))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    // ------------------------------------------------------------------ filtros

    private fun dialogoCategorias() {
        val cats = Diagnostico.CATEGORIAS
        val cuenta = Diagnostico.contarPorCategoria()
        val nombres = Array<CharSequence>(cats.size) { i -> cats[i].second + " (" + (cuenta[cats[i].first] ?: 0) + ")" }
        val actuales = filtro.cats
        val marcados = BooleanArray(cats.size) { i -> actuales?.contains(cats[i].first) ?: true }
        MaterialAlertDialogBuilder(this)
            .setTitle("Categorías")
            .setMultiChoiceItems(nombres, marcados) { _, i, v -> marcados[i] = v }
            .setPositiveButton("Aplicar") { _, _ ->
                val sel = HashSet<String>()
                for (i in cats.indices) if (marcados[i]) sel.add(cats[i].first)
                filtro.cats = if (sel.size == cats.size) null else sel
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
                pintar()
            }
            .setNeutralButton("Quitar") { _, _ ->
                filtro.texto = ""
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
        ui.post(tic)
    }

    override fun onPause() {
        ui.removeCallbacks(tic)
        super.onPause()
    }

    private fun pintar() {
        val restante = Laboratorio.restanteDiag() / 60000L
        val cats = Diagnostico.contarPorCategoria()
        contadores.text = (if (MitmEstado.activo) "Modo Profundo: activo · " else "Modo Profundo: NO activo · ") +
            MitmEstado.descifradas.get() + " conexiones descifradas\n" +
            "Eventos: " + Diagnostico.CATEGORIAS.joinToString(" · ") { it.second + " " + (cats[it.first] ?: 0) } + "\n" +
            "Técnicas: " + listOf("clasico", "juguete", "vast", "stubs", "yt", "skip", "css", "hls").joinToString(" · ") { it + " " + Laboratorio.veces(it) } +
            (if (Laboratorio.diagActivas().isEmpty()) "\nCapturas: apagadas" else "\nCapturas activas: " + Laboratorio.diagActivas().joinToString(", ") + " (se apagan en " + restante + " min)")

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

        val l = Diagnostico.lineas(filtro, mostrar, true)
        cabeceraRegistro.text = "Mostrando " + l.size + " de " + Diagnostico.cuantas(filtro) + (if (filtro.activo()) " (con filtros)" else "")
        registro.text = if (l.isEmpty()) "(sin eventos con estos filtros)" else l.joinToString("\n")
    }
}
