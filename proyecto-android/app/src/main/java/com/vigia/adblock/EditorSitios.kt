package com.vigia.adblock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Edita la lista de sitios del archivo de reglas desde la propia app. La primera vez parte de las reglas integradas y guarda
 * una copia en el almacenamiento privado (la misma que se usa al importar un JSON); «Volver a las reglas integradas» la borra.
 */
object EditorSitios {
    class Sitio(val host: String, val tecnicas: List<String>)

    /** Técnicas de página que se pueden asignar a un sitio. */
    val TECNICAS = listOf(
        "css" to "Ocultar huecos (CSS)",
        "hls" to "Limpiar listas HLS",
        "yt" to "YouTube: borrar datos de anuncios",
        "skip" to "Saltador de anuncios (JavaScript)"
    )

    private fun jsonActual(c: Context): JSONObject {
        val f = Reglas.archivo(c)
        val texto = if (f.exists()) f.readText() else c.assets.open("reglas.json").bufferedReader().use { it.readText() }
        return JSONObject(texto)
    }

    fun listar(c: Context): List<Sitio> {
        val arr = try {
            jsonActual(c).optJSONArray("sitios")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val res = ArrayList<Sitio>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = ArrayList<String>()
            val ta = o.optJSONArray("tecnicas")
            if (ta != null) for (k in 0 until ta.length()) t.add(ta.getString(k))
            res.add(Sitio(o.getString("host"), t))
        }
        return res.sortedWith(compareBy<Sitio>({ it.host != "*" }, { it.host }))
    }

    /** «https://www.Ejemplo.com/ruta» -> «ejemplo.com». Null si no parece un dominio. «*» se acepta tal cual. */
    fun normalizar(texto: String): String? {
        var h = texto.trim().lowercase()
        h = h.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
        if (h.startsWith("www.")) h = h.removePrefix("www.")
        if (h == "*") return h
        if (!h.contains('.') || h.contains(' ') || h.startsWith('.') || h.endsWith('.')) return null
        return h
    }

    private fun escribir(c: Context, j: JSONObject, nota: String) {
        Reglas.parse(j.toString()) // si no es válido, lanza y no se guarda
        Reglas.archivo(c).writeText(j.toString(2))
        ReglasEstado.recargar(c)
        Diagnostico.anotar("reglas", nota)
    }

    fun guardar(c: Context, host: String, tecnicas: List<String>) {
        val j = jsonActual(c)
        val arr = j.optJSONArray("sitios") ?: JSONArray().also { j.put("sitios", it) }
        var hecho = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("host") == host) {
                o.put("tecnicas", JSONArray(tecnicas))
                hecho = true
            }
        }
        if (!hecho) arr.put(JSONObject().put("host", host).put("tecnicas", JSONArray(tecnicas)))
        escribir(c, j, "sitio " + host + ": " + (if (tecnicas.isEmpty()) "sin técnicas" else tecnicas.joinToString("+")))
    }

    fun borrar(c: Context, host: String) {
        val j = jsonActual(c)
        val arr = j.optJSONArray("sitios") ?: return
        val nuevo = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("host") != host) nuevo.put(o)
        }
        j.put("sitios", nuevo)
        escribir(c, j, "sitio quitado: " + host)
    }
}
