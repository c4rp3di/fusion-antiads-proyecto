package com.vigia.mitm

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Códec HTTP/1.x mínimo y por streaming. OJO: el parser HTTP de NetLens ([Dpi.http]) solo lee la línea de petición
 * y unas cabeceras de la PRIMERA petición; no entiende respuestas, longitudes ni chunked, que es justo lo que hace
 * falta para filtrar contenido. Esto es lo nuevo.
 */
class Entrada(entrada: InputStream) : BufferedInputStream(entrada, 16384) {
    /** Lee una línea terminada en LF (quita el CR). Null si el flujo termina antes de leer nada. */
    fun leerLinea(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > 65536) throw IOException("línea HTTP demasiado larga")
        }
    }
}

class Cabeceras {
    val lista = ArrayList<Pair<String, String>>()
    fun get(n: String): String? = lista.firstOrNull { it.first.equals(n, true) }?.second
    fun quitar(n: String) { lista.removeAll { it.first.equals(n, true) } }
    fun poner(n: String, v: String) { quitar(n); lista.add(Pair(n, v)) }
    fun contiene(n: String, token: String): Boolean =
        get(n)?.split(',')?.any { it.trim().equals(token, true) } == true
}

class CabezaPeticion(val metodo: String, val destino: String, val version: String, val cab: Cabeceras)
class CabezaRespuesta(val version: String, val codigo: Int, val razon: String, val cab: Cabeceras)

/** Cómo viaja el cuerpo por el cable. */
enum class Encuadre { NINGUNO, LONGITUD, CHUNKED, HASTA_CIERRE }

/** Cuerpo HTTP: [leer] entrega siempre los bytes ya DESENCUADRADOS (sin tamaños de chunk). */
class Cuerpo(private val e: Entrada, val encuadre: Encuadre, val longitud: Long) {
    private var restante = longitud
    private var enChunk = 0L
    var terminado = encuadre == Encuadre.NINGUNO || (encuadre == Encuadre.LONGITUD && longitud == 0L)
        private set

    fun leer(b: ByteArray): Int {
        if (terminado) return -1
        when (encuadre) {
            Encuadre.LONGITUD -> {
                val n = e.read(b, 0, minOf(b.size.toLong(), restante).toInt())
                if (n < 0) throw EOFException("cuerpo cortado")
                restante -= n
                if (restante == 0L) terminado = true
                return n
            }
            Encuadre.HASTA_CIERRE -> {
                val n = e.read(b, 0, b.size)
                if (n < 0) { terminado = true; return -1 }
                return n
            }
            Encuadre.CHUNKED -> {
                if (enChunk == 0L) {
                    val linea = e.leerLinea() ?: throw EOFException("chunk cortado")
                    val tam = linea.substringBefore(';').trim().toLong(16)
                    if (tam < 0) throw IOException("chunk negativo")
                    if (tam == 0L) {
                        while (true) { val l = e.leerLinea() ?: break; if (l.isEmpty()) break } // trailers
                        terminado = true
                        return -1
                    }
                    enChunk = tam
                }
                val n = e.read(b, 0, minOf(b.size.toLong(), enChunk).toInt())
                if (n < 0) throw EOFException("chunk cortado")
                enChunk -= n
                if (enChunk == 0L) e.leerLinea()
                return n
            }
            Encuadre.NINGUNO -> return -1
        }
    }

    fun descartar() {
        val b = ByteArray(8192)
        while (leer(b) >= 0) { /* nada */ }
    }
}

object HttpCodec {
    private fun leerCabeceras(e: Entrada): Cabeceras {
        val cab = Cabeceras()
        var total = 0
        while (true) {
            val l = e.leerLinea() ?: throw EOFException("cabeceras cortadas")
            if (l.isEmpty()) return cab
            total += l.length
            if (total > 65536 || cab.lista.size > 200) throw IOException("cabeceras demasiado grandes")
            val i = l.indexOf(':')
            if (i <= 0) throw IOException("cabecera mal formada")
            cab.lista.add(Pair(l.substring(0, i).trim(), l.substring(i + 1).trim()))
        }
    }

    /** Null si el cliente cerró limpiamente entre peticiones. */
    fun leerPeticion(e: Entrada): CabezaPeticion? {
        var linea = e.leerLinea() ?: return null
        while (linea.isEmpty()) linea = e.leerLinea() ?: return null
        val p = linea.split(' ')
        if (p.size != 3 || !p[2].startsWith("HTTP/")) throw IOException("no es HTTP/1.x")
        return CabezaPeticion(p[0], p[1], p[2], leerCabeceras(e))
    }

    fun leerRespuesta(e: Entrada): CabezaRespuesta? {
        val linea = e.leerLinea() ?: return null
        val p = linea.split(' ', limit = 3)
        if (p.size < 2 || !p[0].startsWith("HTTP/")) throw IOException("respuesta no HTTP")
        return CabezaRespuesta(p[0], p[1].toInt(), if (p.size > 2) p[2] else "", leerCabeceras(e))
    }

    fun cuerpoPeticion(e: Entrada, r: CabezaPeticion): Cuerpo {
        if (r.cab.contiene("Transfer-Encoding", "chunked")) return Cuerpo(e, Encuadre.CHUNKED, -1)
        val l = r.cab.get("Content-Length")?.trim()?.toLongOrNull()
        return if (l != null && l > 0) Cuerpo(e, Encuadre.LONGITUD, l) else Cuerpo(e, Encuadre.NINGUNO, 0)
    }

    fun cuerpoRespuesta(e: Entrada, r: CabezaRespuesta, metodo: String): Cuerpo {
        if (metodo == "HEAD" || r.codigo < 200 || r.codigo == 204 || r.codigo == 304) return Cuerpo(e, Encuadre.NINGUNO, 0)
        if (r.cab.contiene("Transfer-Encoding", "chunked")) return Cuerpo(e, Encuadre.CHUNKED, -1)
        val l = r.cab.get("Content-Length")?.trim()?.toLongOrNull()
        return if (l != null && l >= 0) Cuerpo(e, Encuadre.LONGITUD, l) else Cuerpo(e, Encuadre.HASTA_CIERRE, -1)
    }

    private fun escribirCab(o: OutputStream, primera: String, cab: Cabeceras) {
        val sb = StringBuilder(primera).append("\r\n")
        for ((n, v) in cab.lista) sb.append(n).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun escribirPeticion(o: OutputStream, r: CabezaPeticion) = escribirCab(o, r.metodo + " " + r.destino + " " + r.version, r.cab)

    fun escribirRespuesta(o: OutputStream, r: CabezaRespuesta) =
        escribirCab(o, r.version + " " + r.codigo + (if (r.razon.isEmpty()) "" else " " + r.razon), r.cab)

    /** Copia [c] a [o]. Con [comoChunked] lo vuelve a encuadrar en chunks (y escribe el chunk final). */
    fun copiar(c: Cuerpo, o: OutputStream, comoChunked: Boolean) {
        val b = ByteArray(16384)
        while (true) {
            val n = c.leer(b)
            if (n < 0) break
            if (n == 0) continue
            if (comoChunked) escribirChunk(o, b, n) else o.write(b, 0, n)
            o.flush()
        }
        if (comoChunked) { o.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); o.flush() }
    }

    fun escribirChunk(o: OutputStream, b: ByteArray, n: Int) {
        o.write((Integer.toHexString(n) + "\r\n").toByteArray(Charsets.ISO_8859_1))
        o.write(b, 0, n)
        o.write("\r\n".toByteArray(Charsets.ISO_8859_1))
    }
}
