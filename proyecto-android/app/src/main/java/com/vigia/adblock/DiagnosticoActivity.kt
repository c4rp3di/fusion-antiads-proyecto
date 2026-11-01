package com.vigia.adblock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.vigia.core.R
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas

/** Diagnóstico: qué hace la app, qué conectan las apps y el informe para compartir. Las técnicas están en Ajustes › Laboratorio. */
class DiagnosticoActivity : ActividadBase() {

    private lateinit var contadores: TextView
    private lateinit var registro: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            pintar()
            ui.postDelayed(this, 2000L)
        }
    }

    private val opcionesLineas = intArrayOf(200, 450, 1000, 2500)
    private var lineas = 450
    private lateinit var botonLineas: com.google.android.material.button.MaterialButton

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
                "Aquí se observa; lo que cambia el comportamiento está en Ajustes › Laboratorio. Para ver conexiones, peticiones y " +
                    "páginas hace falta el Modo Profundo con el certificado instalado.",
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
                "Lo que se anota. Cuestan algo de batería, así que vienen apagadas y se apagan solas a los 30 minutos.",
                true
            ), Vistas.anchoCompleto(this, 8)
        )
        PanelesLab.capturas(this, capturas)

        // ---- Registro e informe
        val d = Paneles.plegable(this, raiz, "diag/registro", "Registro e informe", true, true)
        registro = TextView(this)
        registro.typeface = Typeface.MONOSPACE
        registro.textSize = 11f
        registro.setTextIsSelectable(true)
        registro.setTextColor(Vistas.color(this, R.color.vc_texto))
        d.addView(registro, Vistas.anchoCompleto(this, 8))
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
        d.addView(Vistas.boton(this, "Vaciar", TipoBoton.TONAL) {
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

    private fun textoLineas() = "Líneas del informe: " + lineas + (if (lineas >= 2500) " (todo)" else "")

    /** El informe completo (ver [Informe]); en modo privado se recortan las URLs. */
    private fun informe(n: Int): String = Informe.generar(this, n, Laboratorio.activa(this, "privado"))

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
        contadores.text = (if (MitmEstado.activo) "Modo Profundo: activo · " else "Modo Profundo: NO activo · ") +
            MitmEstado.descifradas.get() + " conexiones descifradas\n" +
            listOf("clasico", "juguete", "vast", "stubs", "yt", "skip", "css", "hls").joinToString(" · ") { it + " " + Laboratorio.veces(it) } +
            (if (Laboratorio.diagActivas().isEmpty()) "\nCapturas: apagadas" else "\nCapturas activas: " + Laboratorio.diagActivas().joinToString(", ") + " (se apagan en " + restante + " min)")
        registro.text = Diagnostico.texto(40).ifEmpty { "(sin registros todavía)" }
    }
}
