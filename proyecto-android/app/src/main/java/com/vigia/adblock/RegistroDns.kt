package com.vigia.adblock

import com.vigia.core.red.EventoDns
import java.util.concurrent.ConcurrentHashMap

/**
 * Registro de consultas DNS por app, SOLO EN MEMORIA (se pierde al cerrarse el proceso o al vaciarlo; no se guarda
 * nada en disco). Una fila por pareja app+dominio con contadores, para que no crezca sin límite.
 */
object RegistroDns {
    /** Se lee en cada consulta desde el túnel: por eso es un booleano volátil y no una preferencia. */
    @Volatile
    var activo = false

    class Fila(val app: String, val dominio: String) {
        var consultas = 0
        var bloqueadas = 0
        var ultimo = 0L
    }

    class ResumenApp(val app: String, val consultas: Int, val bloqueadas: Int, val filas: List<Fila>)

    private const val MAX_FILAS = 4000
    private val mapa = ConcurrentHashMap<String, Fila>()

    fun registrar(e: EventoDns) {
        val d = e.dominio.lowercase().trimEnd('.')
        if (d.isEmpty()) return
        if (mapa.size >= MAX_FILAS) podar()
        val f = mapa.getOrPut(e.app + "\u0000" + d) { Fila(e.app, d) }
        synchronized(f) {
            f.consultas++
            if (e.bloqueado) f.bloqueadas++
            f.ultimo = e.ts
        }
    }

    /** Quita el 25 % de filas más antiguas. */
    @Synchronized
    private fun podar() {
        val viejas = mapa.entries.sortedBy { it.value.ultimo }.take(MAX_FILAS / 4)
        for (v in viejas) mapa.remove(v.key)
    }

    fun vaciar() = mapa.clear()

    fun total(): Int = mapa.size

    /** Apps de más a menos consultas; dentro de cada una, dominios de más a menos consultas. */
    fun resumen(): List<ResumenApp> {
        val porApp = HashMap<String, ArrayList<Fila>>()
        for (f in mapa.values) porApp.getOrPut(f.app) { ArrayList() }.add(f)
        return porApp.map { (app, filas) ->
            var c = 0
            var b = 0
            for (f in filas) synchronized(f) { c += f.consultas; b += f.bloqueadas }
            ResumenApp(app, c, b, filas.sortedByDescending { it.consultas })
        }.sortedByDescending { it.consultas }
    }
}
