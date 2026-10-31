package com.vigia.mitm

import java.util.concurrent.ConcurrentHashMap

/** Destino real y app de una conexión redirigida al proxy. */
class Origen(val ip: String, val puerto: Int, val app: String, val ts: Long = System.currentTimeMillis())

/**
 * El Reenviador del núcleo conecta al proxy por loopback, y por loopback se pierde el destino original. En NetLens el
 * proxy lo suplía con el SNI; aquí, además, se necesita la IP real (para reconectar sin pasar por el DNS, que en modo
 * MitM no bloquea) y la app (para la lista blanca). La clave es el puerto local del socket saliente del Reenviador,
 * que es justo el puerto remoto que ve el proxy en accept().
 */
internal object Origenes {
    private val mapa = ConcurrentHashMap<Int, Origen>()

    fun registrar(puertoLocal: Int, o: Origen) {
        mapa[puertoLocal] = o
        if (mapa.size > 512) {
            val limite = System.currentTimeMillis() - 30_000L
            mapa.entries.removeAll { it.value.ts < limite }
        }
    }

    fun tomar(puertoLocal: Int): Origen? = mapa.remove(puertoLocal)
    fun mirar(puertoLocal: Int): Origen? = mapa[puertoLocal]
}
