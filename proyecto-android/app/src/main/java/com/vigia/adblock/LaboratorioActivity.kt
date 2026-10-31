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

/** Laboratorio de AntiAds: activar/desactivar técnicas, ver qué hacen y exportar el diagnóstico. */
class LaboratorioActivity : ActividadBase() {

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

    private val importar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val texto = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            Reglas.parse(texto) // si no es válido, lanza y no se guarda
            Reglas.archivo(this).writeText(texto)
            ReglasEstado.recargar(this)
            Toast.makeText(this, "Reglas importadas", Toast.LENGTH_SHORT).show()
            Diagnostico.anotar("reglas", "importadas desde archivo")
        } catch (e: Exception) {
            Dialogos.informar(this, "Reglas no válidas", e.message ?: e.javaClass.simpleName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Laboratorio"), Vistas.anchoCompleto(this, 4))
        raiz.addView(
            Vistas.texto(
                this,
                "Activa o desactiva cada técnica y prueba una web. Los cambios valen al momento (recarga la página). " +
                    "Necesitas el Modo Profundo con el certificado instalado, y un navegador que lo acepte (Chrome).",
                true
            ), Vistas.anchoCompleto(this, 12)
        )

        val tarjeta = Vistas.tarjeta(this)
        tarjeta.addView(Vistas.subtitulo(this, "Técnicas"), Vistas.anchoCompleto(this, 8))
        for (t in Laboratorio.TECNICAS) {
            val boton = Vistas.boton(this, etiqueta(t), TipoBoton.TONAL) {}
            boton.setOnClickListener {
                Laboratorio.poner(this, t.id, !Laboratorio.activa(this, t.id))
                boton.text = etiqueta(t)
            }
            tarjeta.addView(boton, Vistas.anchoCompleto(this, 2))
            tarjeta.addView(Vistas.texto(this, t.descripcion, true), Vistas.anchoCompleto(this, 10))
        }
        raiz.addView(tarjeta, Vistas.anchoCompleto(this, 12))

        val rc = Vistas.tarjeta(this)
        rc.addView(Vistas.subtitulo(this, "Reglas por sitio"), Vistas.anchoCompleto(this, 4))
        rc.addView(
            Vistas.texto(this, "Archivo JSON con patrones de peticiones y las técnicas de cada sitio. Puedes importar el tuyo.", true),
            Vistas.anchoCompleto(this, 8)
        )
        rc.addView(Vistas.boton(this, "Importar reglas (JSON)", TipoBoton.TONAL) { importar.launch(arrayOf("*/*")) }, Vistas.anchoCompleto(this, 8))
        rc.addView(Vistas.boton(this, "Volver a las reglas integradas", TipoBoton.TONAL) {
            Reglas.archivo(this).delete()
            ReglasEstado.recargar(this)
            Toast.makeText(this, "Reglas integradas restauradas", Toast.LENGTH_SHORT).show()
        }, Vistas.anchoCompleto(this, 0))
        raiz.addView(rc, Vistas.anchoCompleto(this, 12))

        val d = Vistas.tarjeta(this)
        d.addView(Vistas.subtitulo(this, "Qué ha hecho cada técnica"), Vistas.anchoCompleto(this, 8))
        contadores = Vistas.texto(this, "", true)
        d.addView(contadores, Vistas.anchoCompleto(this, 12))
        d.addView(Vistas.subtitulo(this, "Diagnóstico"), Vistas.anchoCompleto(this, 4))
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
        d.addView(Vistas.boton(this, "Copiar diagnóstico", TipoBoton.TONAL) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AntiAds", informe(lineas)))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Compartir diagnóstico", TipoBoton.TONAL) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, informe(lineas))
            startActivity(Intent.createChooser(i, "Compartir diagnóstico"))
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
        raiz.addView(d, Vistas.anchoCompleto(this, 12))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun etiqueta(t: Laboratorio.Tecnica) = (if (Laboratorio.activa(this, t.id)) "✓  " else "✗  ") + t.nombre

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
            (if (Laboratorio.diagActivas().isEmpty()) "\nCapturas de diagnóstico: apagadas" else "\nCapturas activas: " + Laboratorio.diagActivas().joinToString(", ") + " (se apagan en " + restante + " min)")
        registro.text = Diagnostico.texto(40).ifEmpty { "(sin registros todavía)" }
    }
}
