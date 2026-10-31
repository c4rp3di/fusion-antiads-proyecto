package com.vigia.adblock

import com.vigia.core.red.FiltroTrafico
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Hash de 64 bits de un dominio, calculado de DERECHA a IZQUIERDA para poder comprobar todos sus sufijos en una sola pasada. */
object HashDominio {
    const val SEMILLA = 1125899906842597L
    const val PRIMO = 1099511628211L

    fun completo(dominio: String): Long {
        var h = SEMILLA
        var i = dominio.length - 1
        while (i >= 0) {
            h = (h xor dominio[i].code.toLong()) * PRIMO
            i--
        }
        return h
    }
}

/**
 * Conjunto de hashes de 64 bits en una tabla abierta de longs: ~16 bytes por dominio, sin objetos por entrada.
 * 600 000 dominios ocupan unos 16 MB y una consulta tarda menos de un microsegundo. Una colisión falsa tiene
 * probabilidad ~ n / 2^64 (despreciable). El 0 marca «hueco», así que el hash 0 se guarda como 1.
 */
class ConjuntoHash64 private constructor(capacidad: Int) {
    private var tabla = LongArray(capacidad)

    var tamano = 0
        private set

    constructor() : this(1024)

    fun agregar(valor: Long): Boolean {
        val h = if (valor == 0L) 1L else valor
        if ((tamano + 1) * 10 > tabla.size * 6) crecer()
        if (insertar(tabla, h)) {
            tamano++
            return true
        }
        return false
    }

    fun contiene(valor: Long): Boolean {
        val h = if (valor == 0L) 1L else valor
        val t = tabla
        val mascara = t.size - 1
        var i = ((h xor (h ushr 32)).toInt()) and mascara
        while (true) {
            val v = t[i]
            if (v == 0L) return false
            if (v == h) return true
            i = (i + 1) and mascara
        }
    }

    private fun insertar(t: LongArray, h: Long): Boolean {
        val mascara = t.size - 1
        var i = ((h xor (h ushr 32)).toInt()) and mascara
        while (true) {
            val v = t[i]
            if (v == 0L) {
                t[i] = h
                return true
            }
            if (v == h) return false
            i = (i + 1) and mascara
        }
    }

    private fun crecer() {
        val nueva = LongArray(tabla.size * 2)
        for (v in tabla) {
            if (v != 0L) insertar(nueva, v)
        }
        tabla = nueva
    }

    fun escribir(salida: OutputStream) {
        val cab = ByteBuffer.allocate(8)
        cab.putInt(tamano)
        cab.putInt(tabla.size)
        salida.write(cab.array())
        val bloque = ByteBuffer.allocate(BLOQUE * 8)
        var i = 0
        while (i < tabla.size) {
            val n = minOf(BLOQUE, tabla.size - i)
            bloque.clear()
            for (j in 0 until n) bloque.putLong(tabla[i + j])
            salida.write(bloque.array(), 0, n * 8)
            i += n
        }
    }

    companion object {
        private const val BLOQUE = 8192

        fun leer(entrada: InputStream): ConjuntoHash64 {
            val din = DataInputStream(entrada)
            val tam = din.readInt()
            val cap = din.readInt()
            if (cap < 16 || cap > (1 shl 26) || (cap and (cap - 1)) != 0 || tam < 0 || tam > cap) {
                throw IllegalStateException("caché de filtro no válida")
            }
            val c = ConjuntoHash64(cap)
            val bytes = ByteArray(BLOQUE * 8)
            var i = 0
            while (i < cap) {
                val n = minOf(BLOQUE, cap - i)
                din.readFully(bytes, 0, n * 8)
                val bb = ByteBuffer.wrap(bytes, 0, n * 8)
                for (j in 0 until n) c.tabla[i + j] = bb.getLong()
                i += n
            }
            c.tamano = tam
            return c
        }
    }
}

/**
 * El filtro de AdBlock Soberano: dominios bloqueados y excepciones, cada uno en su tabla. Un dominio bloqueado
 * incluye todos sus subdominios; una excepción (permitido) gana siempre sobre el bloqueo.
 */
class FiltroAdBlock : FiltroTrafico {

    class Tablas(val bloqueo: ConjuntoHash64, val permitido: ConjuntoHash64)

    @Volatile
    private var tablas: Tablas? = null

    val cargado: Boolean get() = tablas != null

    val totalDominios: Int get() = tablas?.bloqueo?.tamano ?: 0

    fun reemplazar(nuevas: Tablas) {
        tablas = nuevas
    }

    override val hayDominios: Boolean get() = totalDominios > 0

    override fun ipBloqueada(ip: Int): Boolean = false

    /** Bloqueo de DoT/DoH por IP y puerto (ver [DnsCifrado]). Lo fija el servicio desde los ajustes. */
    @Volatile
    var bloquearDnsCifrado = false

    override fun destinoBloqueado(ip: Int, puerto: Int): Boolean = bloquearDnsCifrado && DnsCifrado.bloquea(ip, puerto)

    /** En modo MitM se ocultan las direcciones IPv6 para que todo el tráfico entre por el túnel (IPv4). */
    @Volatile
    var ocultarIpv6 = false

    override fun filtrarAaaa(): Boolean = ocultarIpv6

    override fun dominioBloqueado(nombre: String): Boolean {
        val t = tablas ?: return false
        return evaluar(t, nombre) == BLOQUEADO
    }

    /** 0 = no está en las listas · 1 = bloqueado · 2 = permitido por una excepción. */
    fun consultar(nombre: String): Int {
        val t = tablas ?: return NO_LISTADO
        return evaluar(t, nombre.lowercase().trim('.'))
    }

    private fun evaluar(t: Tablas, nombre: String): Int {
        var h = HashDominio.SEMILLA
        var puntos = 0
        var bloq = false
        var perm = false
        var i = nombre.length - 1
        while (i >= 0) {
            val c = nombre[i]
            h = (h xor c.code.toLong()) * HashDominio.PRIMO
            if (c == '.') puntos++
            // [i..fin] es un sufijo completo (empieza en una etiqueta). Los sufijos sin punto (TLD) no se miran.
            if ((i == 0 || nombre[i - 1] == '.') && puntos > 0) {
                if (t.permitido.contiene(h)) perm = true
                if (t.bloqueo.contiene(h)) bloq = true
            }
            i--
        }
        if (perm) return PERMITIDO
        return if (bloq) BLOQUEADO else NO_LISTADO
    }

    fun guardarCache(archivo: File, t: Tablas) {
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.outputStream().buffered().use { out ->
            out.write(MAGIA)
            t.bloqueo.escribir(out)
            t.permitido.escribir(out)
        }
        if (!tmp.renameTo(archivo)) {
            archivo.delete()
            tmp.renameTo(archivo)
        }
    }

    /** Lee la caché compilada (mucho más rápido que volver a procesar las listas). Devuelve null si no existe o está dañada. */
    fun leerCache(archivo: File): Tablas? {
        if (!archivo.exists()) return null
        return try {
            archivo.inputStream().buffered().use { entrada ->
                val magia = ByteArray(MAGIA.size)
                DataInputStream(entrada).readFully(magia)
                if (!magia.contentEquals(MAGIA)) {
                    null
                } else {
                    val bloqueo = ConjuntoHash64.leer(entrada)
                    val permitido = ConjuntoHash64.leer(entrada)
                    Tablas(bloqueo, permitido)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val NO_LISTADO = 0
        const val BLOQUEADO = 1
        const val PERMITIDO = 2
        private val MAGIA = "ADB1".toByteArray(Charsets.US_ASCII)
    }
}
