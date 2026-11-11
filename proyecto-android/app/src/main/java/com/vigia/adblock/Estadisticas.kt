package com.vigia.adblock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Contadores de bloqueo. Todo vive en memoria y solo se vuelca a SharedPreferences cada minuto y al parar:
 * contar un bloqueo cuesta unas sumas y un acceso a un mapa pequeño, nada más.
 *
 * Qué se guarda: bloqueos por día (30 días), por hora de HOY, por capa (DNS, SNI, proxy), y los dominios y apps más
 * bloqueados de hoy; al cambiar de día, los 100 dominios y 30 apps más bloqueados de ese día pasan al archivo.
 */
object Estadisticas {
    const val CAPA_DNS = 0
    const val CAPA_SNI = 1
    const val CAPA_PROXY = 2
    const val CAPA_OTRA = 3
    val NOMBRES_CAPA = listOf("DNS", "SNI", "Proxy (Modo Profundo)", "Otras")

    private const val PREFS = "adblock_stats"
    private const val DIAS = 30
    private const val MS_DIA = 86_400_000L
    private const val MS_HORA = 3_600_000L
    private const val MAX_DOMINIOS = 2000
    private const val MAX_APPS = 200
    private const val GUARDAR_DOMINIOS_HOY = 200
    private const val GUARDAR_APPS_HOY = 40
    private const val GUARDAR_DOMINIOS_DIA = 100
    private const val GUARDAR_APPS_DIA = 30

    private class Dia(val dia: Long) {
        val horas = LongArray(24)
        val capas = LongArray(4)
        val dominios = HashMap<String, Int>()
        val apps = HashMap<String, Int>()
    }

    /** Resultado de [consultar] para pintar la pantalla de estadísticas. */
    class Consulta(
        val dias: Int,
        val total: Long,
        /** 24 valores (por hora) si [dias] == 1; si no, un valor por día, el último es hoy. */
        val serie: LongArray,
        val capas: LongArray,
        val dominios: List<Pair<String, Int>>,
        val apps: List<Pair<String, Int>>
    )

    private var iniciado = false
    private var total = 0L
    private var diaEpoch = 0L
    private var hasta = 0L
    private val historial = LongArray(DIAS)
    private var hoyDia = Dia(0L)
    private val pasados = ArrayList<Dia>() // el más antiguo primero
    private var sucio = false
    private var pasadosSucios = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun diaDe(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / MS_DIA

    @Synchronized
    fun iniciar(c: Context) {
        if (iniciado) return
        val p = prefs(c)
        val ahora = System.currentTimeMillis()
        total = p.getLong("total", 0L)
        diaEpoch = p.getLong("dia", diaDe(ahora))
        val partes = (p.getString("hist", "") ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }
        // Las versiones anteriores guardaban 7 días: se colocan al final (el último es hoy).
        val k = minOf(partes.size, DIAS)
        for (i in 0 until k) historial[DIAS - k + i] = partes[partes.size - k + i]
        val guardadoHoy = leerDia(p.getString("hoy", null))
        hoyDia = if (guardadoHoy != null && guardadoHoy.dia == diaEpoch) guardadoHoy else Dia(diaEpoch)
        pasados.clear()
        pasados.addAll(leerPasados(p.getString("pasados", null)))
        iniciado = true
        rodar(ahora)
    }

    /** Si ha cambiado el día, archiva el de ayer y desplaza el historial. */
    private fun rodar(ahora: Long) {
        val d = diaDe(ahora)
        if (d > diaEpoch) {
            if (historial[DIAS - 1] > 0L || hoyDia.dominios.isNotEmpty()) archivar(hoyDia)
            val salto = minOf(d - diaEpoch, DIAS.toLong()).toInt()
            for (i in 0 until DIAS) historial[i] = if (i + salto < DIAS) historial[i + salto] else 0L
            hoyDia = Dia(d)
            sucio = true
        }
        diaEpoch = d
        hasta = (d + 1) * MS_DIA - TimeZone.getDefault().getOffset(ahora)
    }

    private fun archivar(d: Dia) {
        pasados.add(recortar(d, GUARDAR_DOMINIOS_DIA, GUARDAR_APPS_DIA))
        while (pasados.size > DIAS - 1) pasados.removeAt(0)
        pasadosSucios = true
    }

    private fun recortar(d: Dia, dominios: Int, apps: Int): Dia {
        val r = Dia(d.dia)
        System.arraycopy(d.horas, 0, r.horas, 0, 24)
        System.arraycopy(d.capas, 0, r.capas, 0, 4)
        for (e in d.dominios.entries.sortedByDescending { it.value }.take(dominios)) r.dominios[e.key] = e.value
        for (e in d.apps.entries.sortedByDescending { it.value }.take(apps)) r.apps[e.key] = e.value
        return r
    }

    private fun indiceCapa(capa: String): Int = when (capa) {
        "dns" -> CAPA_DNS
        "sni" -> CAPA_SNI
        "proxy" -> CAPA_PROXY
        else -> CAPA_OTRA
    }

    private fun sumar(m: HashMap<String, Int>, clave: String, maximo: Int) {
        val n = m[clave]
        if (n != null) m[clave] = n + 1 else if (m.size < maximo) m[clave] = 1
    }

    /** [capa]: «dns», «sni» o «proxy». [app] vacío o «desconocida» si no se sabe. */
    @Synchronized
    fun registrar(dominio: String, capa: String = "dns", app: String = "") {
        if (!iniciado) return
        val ahora = System.currentTimeMillis()
        if (ahora >= hasta) rodar(ahora)
        historial[DIAS - 1]++
        total++
        sucio = true
        val hora = (((ahora + TimeZone.getDefault().getOffset(ahora)) % MS_DIA) / MS_HORA).toInt().coerceIn(0, 23)
        hoyDia.horas[hora]++
        hoyDia.capas[indiceCapa(capa)]++
        sumar(hoyDia.dominios, dominio, MAX_DOMINIOS)
        if (app.isNotEmpty() && app != "desconocida") sumar(hoyDia.apps, app, MAX_APPS)
    }

    @Synchronized
    fun hoy(): Long {
        if (iniciado && System.currentTimeMillis() >= hasta) rodar(System.currentTimeMillis())
        return historial[DIAS - 1]
    }

    @Synchronized
    fun total(): Long = total

    /** Últimos 7 días, el más antiguo primero; el último elemento es hoy. */
    fun ultimosDias(): LongArray = ultimosDias(7)

    @Synchronized
    fun ultimosDias(n: Int): LongArray {
        if (iniciado && System.currentTimeMillis() >= hasta) rodar(System.currentTimeMillis())
        return historial.copyOfRange(DIAS - n.coerceIn(1, DIAS), DIAS)
    }

    @Synchronized
    fun masBloqueadosHoy(n: Int): List<Pair<String, Int>> {
        return hoyDia.dominios.entries.sortedByDescending { it.value }.take(n).map { Pair(it.key, it.value) }
    }

    /** Datos de los últimos [dias] días (1 = hoy, 7, 30) para la pantalla de estadísticas. */
    @Synchronized
    fun consultar(dias: Int): Consulta {
        val n = dias.coerceIn(1, DIAS)
        if (iniciado && System.currentTimeMillis() >= hasta) rodar(System.currentTimeMillis())
        val serie = if (n == 1) hoyDia.horas.copyOf() else historial.copyOfRange(DIAS - n, DIAS)
        val capas = hoyDia.capas.copyOf()
        val dominios = HashMap<String, Int>(hoyDia.dominios)
        val apps = HashMap<String, Int>(hoyDia.apps)
        if (n > 1) {
            val desde = diaEpoch - n + 1
            for (d in pasados) {
                if (d.dia < desde) continue
                for (i in 0 until 4) capas[i] += d.capas[i]
                for ((k, v) in d.dominios) dominios[k] = (dominios[k] ?: 0) + v
                for ((k, v) in d.apps) apps[k] = (apps[k] ?: 0) + v
            }
        }
        val suma = if (n == 1) historial[DIAS - 1] else serie.sum()
        return Consulta(n, suma, serie, capas, ordenar(dominios), ordenar(apps))
    }

    private fun ordenar(m: Map<String, Int>): List<Pair<String, Int>> =
        m.entries.sortedByDescending { it.value }.map { Pair(it.key, it.value) }

    @Synchronized
    fun guardar(c: Context) {
        if (!iniciado || (!sucio && !pasadosSucios)) return
        val e = prefs(c).edit()
            .putLong("total", total)
            .putLong("dia", diaEpoch)
            .putString("hist", historial.joinToString(","))
            .putString("hoy", escribirDia(recortar(hoyDia, GUARDAR_DOMINIOS_HOY, GUARDAR_APPS_HOY), true).toString())
        if (pasadosSucios) {
            val a = JSONArray()
            for (d in pasados) a.put(escribirDia(d, false))
            e.putString("pasados", a.toString())
        }
        e.apply()
        sucio = false
        pasadosSucios = false
    }

    @Synchronized
    fun reiniciar(c: Context) {
        total = 0L
        for (i in 0 until DIAS) historial[i] = 0L
        hoyDia = Dia(diaEpoch)
        pasados.clear()
        sucio = true
        pasadosSucios = true
        guardar(c)
    }

    // ------------------------------------------------------------------ JSON

    private fun escribirDia(d: Dia, conHoras: Boolean): JSONObject {
        val o = JSONObject()
        o.put("d", d.dia)
        if (conHoras) {
            val h = JSONArray()
            for (v in d.horas) h.put(v)
            o.put("h", h)
        }
        val c = JSONArray()
        for (v in d.capas) c.put(v)
        o.put("c", c)
        val t = JSONObject()
        for ((k, v) in d.dominios) t.put(k, v)
        o.put("t", t)
        val a = JSONObject()
        for ((k, v) in d.apps) a.put(k, v)
        o.put("a", a)
        return o
    }

    private fun desdeJson(o: JSONObject): Dia {
        val d = Dia(o.optLong("d", 0L))
        val h = o.optJSONArray("h")
        if (h != null) for (i in 0 until minOf(24, h.length())) d.horas[i] = h.optLong(i)
        val c = o.optJSONArray("c")
        if (c != null) for (i in 0 until minOf(4, c.length())) d.capas[i] = c.optLong(i)
        val t = o.optJSONObject("t")
        if (t != null) {
            val claves = t.keys()
            while (claves.hasNext()) {
                val k = claves.next()
                d.dominios[k] = t.optInt(k)
            }
        }
        val a = o.optJSONObject("a")
        if (a != null) {
            val claves = a.keys()
            while (claves.hasNext()) {
                val k = claves.next()
                d.apps[k] = a.optInt(k)
            }
        }
        return d
    }

    private fun leerDia(texto: String?): Dia? {
        if (texto.isNullOrEmpty()) return null
        return try {
            desdeJson(JSONObject(texto))
        } catch (e: Exception) {
            null
        }
    }

    private fun leerPasados(texto: String?): List<Dia> {
        if (texto.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(texto)
            val res = ArrayList<Dia>()
            for (i in 0 until a.length()) res.add(desdeJson(a.getJSONObject(i)))
            res
        } catch (e: Exception) {
            emptyList()
        }
    }
}
