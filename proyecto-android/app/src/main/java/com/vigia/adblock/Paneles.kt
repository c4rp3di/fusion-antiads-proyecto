package com.vigia.adblock

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            a.recreate()
        }, Vistas.anchoCompleto(a, 0))
    }

    // ------------------------------------------------------------------ editor de sitios

    /** Páginas con técnicas propias (YouTube, saltador, CSS, HLS…). Se guardan en el archivo de reglas. */
    fun sitios(a: Activity, cont: LinearLayout) {
        cont.addView(
            Vistas.texto(
                a,
                "Webs a las que se aplican técnicas de página. «*» vale para todas. Toca un sitio para editarlo o quitarlo. " +
                    "Las de red (VAST, scripts, vídeo de juguete) actúan por URL y no dependen de esta lista.",
                true
            ), Vistas.anchoCompleto(a, 8)
        )
        val lista = LinearLayout(a)
        lista.orientation = LinearLayout.VERTICAL
        cont.addView(lista, Vistas.anchoCompleto(a, 6))
        pintarSitios(a, lista)
        cont.addView(Vistas.boton(a, "Añadir sitio", TipoBoton.TONAL) { dialogoNuevoSitio(a, lista) }, Vistas.anchoCompleto(a, 0))
    }

    private fun pintarSitios(a: Activity, lista: LinearLayout) {
        lista.removeAllViews()
        val sitios = EditorSitios.listar(a)
        if (sitios.isEmpty()) lista.addView(Vistas.texto(a, "(ningún sitio)", true), Vistas.anchoCompleto(a, 6))
        for (s in sitios) {
            val resumen = if (s.tecnicas.isEmpty()) "sin técnicas" else s.tecnicas.joinToString(", ")
            lista.addView(Vistas.boton(a, s.host + " · " + resumen, TipoBoton.TONAL) { menuSitio(a, lista, s) }, Vistas.anchoCompleto(a, 4))
        }
    }

    private fun menuSitio(a: Activity, lista: LinearLayout, s: EditorSitios.Sitio) {
        val acc = ArrayList<Acciones.Accion>()
        acc.add(Acciones.Accion("Editar técnicas") { dialogoTecnicas(a, lista, s.host, s.tecnicas) })
        if (s.host != "*") {
            acc.add(Acciones.Accion("Quitar este sitio") {
                try {
                    EditorSitios.borrar(a, s.host)
                } catch (e: Exception) {
                    Toast.makeText(a, "No se pudo guardar: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                pintarSitios(a, lista)
            })
        }
        Acciones.dialogo(a, s.host, "Técnicas activas: " + (if (s.tecnicas.isEmpty()) "ninguna" else s.tecnicas.joinToString(", ")), acc)
    }

    private fun dialogoNuevoSitio(a: Activity, lista: LinearLayout) {
        val et = EditText(a)
        et.hint = "dominio, por ejemplo ejemplo.com"
        MaterialAlertDialogBuilder(a)
            .setTitle("Añadir sitio")
            .setView(et)
            .setPositiveButton("Siguiente") { _, _ ->
                val host = EditorSitios.normalizar(et.text.toString())
                if (host == null) Toast.makeText(a, "Escribe un dominio válido, por ejemplo ejemplo.com", Toast.LENGTH_LONG).show()
                else dialogoTecnicas(a, lista, host, listOf("css"))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dialogoTecnicas(a: Activity, lista: LinearLayout, host: String, actuales: List<String>) {
        val ts = EditorSitios.TECNICAS
        val nombres = Array<CharSequence>(ts.size) { i -> ts[i].second }
        val marcados = BooleanArray(ts.size) { i -> actuales.contains(ts[i].first) }
        MaterialAlertDialogBuilder(a)
            .setTitle("Técnicas en " + host)
            .setMultiChoiceItems(nombres, marcados) { _, i, v -> marcados[i] = v }
            .setPositiveButton("Guardar") { _, _ ->
                val sel = ArrayList<String>()
                for (i in ts.indices) if (marcados[i]) sel.add(ts[i].first)
                try {
                    EditorSitios.guardar(a, host, sel)
                } catch (e: Exception) {
                    Toast.makeText(a, "No se pudo guardar: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                pintarSitios(a, lista)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
