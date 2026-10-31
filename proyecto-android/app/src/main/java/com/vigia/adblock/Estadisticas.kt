package com.vigia.adblock

import android.content.Context
import java.util.TimeZone

/**
 * Contadores de bloqueo. Todo vive en memoria y solo se vuelca a SharedPreferences cada minuto y al parar:
 * contar un bloqueo cuesta una suma y un acceso a un mapa pequeño, nada más.
 */
object Estadisticas {
    private const val PREFS = "adblock_stats"
    private const val DIAS = 7
    private const val MS_DIA = 86_400_000L
    private const val MAX_TOP = 2000

    private var iniciado = false
    private var total = 0L
    private var diaEpoch = 0L
    private var hasta = 0L
    private val historial = LongArray(DIAS)
    private val top = HashMap<String, Int>()
    private var sucio = false

    private fun diaDe(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / MS_DIA

    @Synchronized
    fun iniciar(c: Context) {
        if (iniciado) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        total = p.getLong("total", 0L)
        diaEpoch = p.getLong("dia", diaDe(ahora))
        val partes = (p.getString("hist", "") ?: "").split(',')
        for (i in 0 until DIAS) historial[i] = partes.getOrNull(i)?.toLongOrNull() ?: 0L
        iniciado = true
        rodar(ahora)
    }

    /** Si ha cambiado el día, desplaza el historial y vacía el top. */
    private fun rodar(ahora: Long) {
        val d = diaDe(ahora)
        if (d > diaEpoch) {
            val salto = minOf(d - diaEpoch, DIAS.toLong()).toInt()
            for (i in 0 until DIAS) historial[i] = if (i + salto < DIAS) historial[i + salto] else 0L
            top.clear()
            sucio = true
        }
        diaEpoch = d
        hasta = (d + 1) * MS_DIA - TimeZone.getDefault().getOffset(ahora)
    }

    @Synchronized
    fun registrar(dominio: String) {
        if (!iniciado) return
        val ahora = System.currentTimeMillis()
        if (ahora >= hasta) rodar(ahora)
        historial[DIAS - 1]++
        total++
        sucio = true
        val n = top[dominio]
        if (n != null) top[dominio] = n + 1 else if (top.size < MAX_TOP) top[dominio] = 1
    }

    @Synchronized
    fun hoy(): Long {
        if (iniciado && System.currentTimeMillis() >= hasta) rodar(System.currentTimeMillis())
        return historial[DIAS - 1]
    }

    @Synchronized
    fun total(): Long = total

    /** Últimos 7 días, el más antiguo primero; el último elemento es hoy. */
    @Synchronized
    fun ultimosDias(): LongArray {
        if (iniciado && System.currentTimeMillis() >= hasta) rodar(System.currentTimeMillis())
        return historial.copyOf()
    }

    @Synchronized
    fun masBloqueadosHoy(n: Int): List<Pair<String, Int>> {
        return top.entries.sortedByDescending { it.value }.take(n).map { Pair(it.key, it.value) }
    }

    @Synchronized
    fun guardar(c: Context) {
        if (!iniciado || !sucio) return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("total", total)
            .putLong("dia", diaEpoch)
            .putString("hist", historial.joinToString(","))
            .apply()
        sucio = false
    }

    @Synchronized
    fun reiniciar(c: Context) {
        total = 0L
        for (i in 0 until DIAS) historial[i] = 0L
        top.clear()
        sucio = true
        guardar(c)
    }
}
