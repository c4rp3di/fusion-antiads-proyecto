package com.vigia.adblock

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas

/** Paneles plegables reutilizables (Ajustes › Laboratorio y la pantalla de Diagnóstico). */
object Paneles {
    private val abiertos = HashSet<String>()
    private val vistos = HashSet<String>()

    /**
     * Añade a [raiz] un panel con cabecera tocable (▸ cerrado, ▾ abierto) y devuelve el contenedor donde poner el contenido.
     * [clave] identifica el panel para recordar si está abierto mientras viva el proceso. [tarjeta] = con fondo de tarjeta
     * (nivel superior) o sin él (panel dentro de otro).
     */
    fun plegable(a: Activity, raiz: LinearLayout, clave: String, titulo: String, tarjeta: Boolean, abiertoAlInicio: Boolean = false): LinearLayout {
        if (vistos.add(clave) && abiertoAlInicio) abiertos.add(clave)
        val marco: ViewGroup = if (tarjeta) Vistas.tarjeta(a) else LinearLayout(a).also { it.orientation = LinearLayout.VERTICAL }
        val cab: TextView = if (tarjeta) Vistas.subtitulo(a, titulo) else Vistas.texto(a, titulo, false).also { it.typeface = Typeface.DEFAULT_BOLD }
        cab.setPadding(0, Vistas.dp(a, 6), 0, Vistas.dp(a, 6))
        val contenido = LinearLayout(a)
        contenido.orientation = LinearLayout.VERTICAL
        contenido.setPadding(if (tarjeta) 0 else Vistas.dp(a, 8), Vistas.dp(a, 6), 0, 0)
        fun pintar() {
            val abierto = abiertos.contains(clave)
            cab.text = (if (abierto) "▾  " else "▸  ") + titulo
            contenido.visibility = if (abierto) View.VISIBLE else View.GONE
        }
        cab.setOnClickListener {
            if (!abiertos.add(clave)) abiertos.remove(clave)
            pintar()
        }
        pintar()
        marco.addView(cab, Vistas.anchoCompleto(a, 0))
        marco.addView(contenido, Vistas.anchoCompleto(a, 0))
        raiz.addView(marco, Vistas.anchoCompleto(a, if (tarjeta) 12 else 6))
        return contenido
    }
}

/** El contenido de los paneles del Laboratorio y del Diagnóstico. */
object PanelesLab {

    private fun etiqueta(a: Activity, t: Laboratorio.Tecnica) = (if (Laboratorio.activa(a, t.id)) "✓  " else "✗  ") + t.nombre

    private fun conmutadores(a: Activity, cont: LinearLayout, incluir: (Laboratorio.Tecnica) -> Boolean) {
        for (t in Laboratorio.TECNICAS.filter(incluir)) {
            val boton = Vistas.boton(a, etiqueta(a, t), TipoBoton.TONAL) {}
            boton.setOnClickListener {
                Laboratorio.poner(a, t.id, !Laboratorio.activa(a, t.id))
                boton.text = etiqueta(a, t)
            }
            cont.addView(boton, Vistas.anchoCompleto(a, 2))
            cont.addView(Vistas.texto(a, t.descripcion, true), Vistas.anchoCompleto(a, 10))
        }
    }

    private fun esDeCaptura(t: Laboratorio.Tecnica) = Laboratorio.DIAGNOSTICO.contains(t.id) || t.id == "privado"

    /** Técnicas que CAMBIAN lo que ve la web (Ajustes › Laboratorio). */
    fun tecnicas(a: Activity, cont: LinearLayout) = conmutadores(a, cont) { !esDeCaptura(it) }

    /** Capturas que solo OBSERVAN (Diagnóstico). Se apagan solas a los 30 minutos. */
    fun capturas(a: Activity, cont: LinearLayout) = conmutadores(a, cont) { esDeCaptura(it) }

    fun reglas(a: Activity, cont: LinearLayout, importar: () -> Unit) {
        cont.addView(
            Vistas.texto(a, "Archivo JSON con patrones de peticiones y las técnicas de cada sitio. Puedes importar el tuyo.", true),
            Vistas.anchoCompleto(a, 8)
        )
        cont.addView(Vistas.boton(a, "Importar reglas (JSON)", TipoBoton.TONAL) { importar() }, Vistas.anchoCompleto(a, 8))
        cont.addView(Vistas.boton(a, "Volver a las reglas integradas", TipoBoton.TONAL) {
            Reglas.archivo(a).delete()
            ReglasEstado.recargar(a)
            Toast.makeText(a, "Reglas integradas restauradas", Toast.LENGTH_SHORT).show()
        }, Vistas.anchoCompleto(a, 0))
    }
}
