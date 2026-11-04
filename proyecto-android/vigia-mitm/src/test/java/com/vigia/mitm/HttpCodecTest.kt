package com.vigia.mitm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/** Pruebas del códec HTTP/1.1 del proxy: es código puro (sin Android) y donde más fácil es cometer errores. */
class HttpCodecTest {

    private fun entrada(s: String) = Entrada(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)))

    /** Lee todo el cuerpo con un búfer pequeño a propósito, para cruzar los límites de chunk. */
    private fun leerTodo(c: Cuerpo): String {
        val o = ByteArrayOutputStream()
        val b = ByteArray(3)
        while (true) {
            val n = c.leer(b)
            if (n < 0) break
            o.write(b, 0, n)
        }
        return o.toString("ISO-8859-1")
    }

    @Test
    fun peticionSencilla() {
        val e = entrada("GET /a?b=1 HTTP/1.1\r\nHost: ejemplo.com\r\nAccept: */*\r\n\r\n")
        val r = HttpCodec.leerPeticion(e)!!
        assertEquals("GET", r.metodo)
        assertEquals("/a?b=1", r.destino)
        assertEquals("HTTP/1.1", r.version)
        assertEquals("ejemplo.com", r.cab.get("host")) // sin distinguir mayúsculas
        assertEquals("*/*", r.cab.get("ACCEPT"))
    }

    @Test
    fun finLimpioDevuelveNull() {
        assertNull(HttpCodec.leerPeticion(entrada("")))
    }

    @Test
    fun lineasVaciasAntesDeLaPeticionSeIgnoran() {
        val r = HttpCodec.leerPeticion(entrada("\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n"))!!
        assertEquals("/", r.destino)
    }

    @Test
    fun noEsHttpLanzaExcepcion() {
        try {
            HttpCodec.leerPeticion(entrada("\u0016\u0003\u0001basura\r\n\r\n"))
            fail("debía fallar")
        } catch (e: IOException) {
            // correcto
        }
    }

    @Test
    fun cabeceraSinDosPuntosLanzaExcepcion() {
        try {
            HttpCodec.leerPeticion(entrada("GET / HTTP/1.1\r\nmal formada\r\n\r\n"))
            fail("debía fallar")
        } catch (e: IOException) {
            // correcto
        }
    }

    @Test
    fun cabeceraConTokensSeparadosPorComas() {
        val r = HttpCodec.leerPeticion(entrada("GET / HTTP/1.1\r\nConnection: keep-alive, Upgrade\r\n\r\n"))!!
        assertTrue(r.cab.contiene("Connection", "upgrade"))
        assertFalse(r.cab.contiene("Connection", "close"))
    }

    @Test
    fun respuestaConLongitud() {
        val e = entrada("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloSIGUIENTE")
        val r = HttpCodec.leerRespuesta(e)!!
        assertEquals(200, r.codigo)
        assertEquals("OK", r.razon)
        val c = HttpCodec.cuerpoRespuesta(e, r, "GET")
        assertEquals(Encuadre.LONGITUD, c.encuadre)
        assertEquals("hello", leerTodo(c))
        assertTrue(c.terminado)
        assertEquals("SIGUIENTE", leerTodo(Cuerpo(e, Encuadre.HASTA_CIERRE, -1))) // lo que sigue no se consume
    }

    @Test
    fun respuestaSinRazon() {
        val r = HttpCodec.leerRespuesta(entrada("HTTP/1.1 204\r\n\r\n"))!!
        assertEquals(204, r.codigo)
        assertEquals("", r.razon)
    }

    @Test
    fun chunkedConExtensionesYTrailers() {
        val e = entrada("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\nX-T: 1\r\n\r\nSIGUE")
        val r = HttpCodec.leerRespuesta(e)!!
        val c = HttpCodec.cuerpoRespuesta(e, r, "GET")
        assertEquals(Encuadre.CHUNKED, c.encuadre)
        assertEquals("hello world", leerTodo(c))
        assertEquals("SIGUE", leerTodo(Cuerpo(e, Encuadre.HASTA_CIERRE, -1)))
    }

    @Test
    fun chunkedConTamanoHexadecimalEnMayusculas() {
        val e = entrada("A\r\n0123456789\r\n0\r\n\r\n")
        assertEquals("0123456789", leerTodo(Cuerpo(e, Encuadre.CHUNKED, -1)))
    }

    @Test
    fun chunkedCortadoLanzaEOF() {
        val e = entrada("5\r\nhel")
        try {
            leerTodo(Cuerpo(e, Encuadre.CHUNKED, -1))
            fail("debía fallar")
        } catch (x: EOFException) {
            // correcto
        }
    }

    @Test
    fun cuerpoConLongitudCortadoLanzaEOF() {
        try {
            leerTodo(Cuerpo(entrada("abc"), Encuadre.LONGITUD, 10))
            fail("debía fallar")
        } catch (x: EOFException) {
            // correcto
        }
    }

    @Test
    fun respuestaSinLongitudSeLeeHastaElCierre() {
        val e = entrada("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>fin")
        val r = HttpCodec.leerRespuesta(e)!!
        val c = HttpCodec.cuerpoRespuesta(e, r, "GET")
        assertEquals(Encuadre.HASTA_CIERRE, c.encuadre)
        assertEquals("<html>fin", leerTodo(c))
    }

    @Test
    fun headYRespuestasSinCuerpoNoTienenCuerpo() {
        val r200 = HttpCodec.leerRespuesta(entrada("HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\n"))!!
        assertEquals(Encuadre.NINGUNO, HttpCodec.cuerpoRespuesta(entrada(""), r200, "HEAD").encuadre)
        val r304 = HttpCodec.leerRespuesta(entrada("HTTP/1.1 304 Not Modified\r\n\r\n"))!!
        assertEquals(Encuadre.NINGUNO, HttpCodec.cuerpoRespuesta(entrada(""), r304, "GET").encuadre)
        val r204 = HttpCodec.leerRespuesta(entrada("HTTP/1.1 204 No Content\r\n\r\n"))!!
        assertEquals(Encuadre.NINGUNO, HttpCodec.cuerpoRespuesta(entrada(""), r204, "GET").encuadre)
        val r100 = HttpCodec.leerRespuesta(entrada("HTTP/1.1 100 Continue\r\n\r\n"))!!
        assertEquals(Encuadre.NINGUNO, HttpCodec.cuerpoRespuesta(entrada(""), r100, "POST").encuadre)
    }

    @Test
    fun cuerpoDePeticionConLongitudYChunked() {
        val p = HttpCodec.leerPeticion(entrada("POST /x HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc"))!!
        assertEquals(Encuadre.LONGITUD, HttpCodec.cuerpoPeticion(entrada("abc"), p).encuadre)
        val q = HttpCodec.leerPeticion(entrada("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))!!
        assertEquals(Encuadre.CHUNKED, HttpCodec.cuerpoPeticion(entrada(""), q).encuadre)
        val g = HttpCodec.leerPeticion(entrada("GET /x HTTP/1.1\r\nHost: a\r\n\r\n"))!!
        assertEquals(Encuadre.NINGUNO, HttpCodec.cuerpoPeticion(entrada(""), g).encuadre)
    }

    @Test
    fun copiarReencuadraEnChunks() {
        val o = ByteArrayOutputStream()
        HttpCodec.copiar(Cuerpo(entrada("hello"), Encuadre.LONGITUD, 5), o, true)
        assertEquals("5\r\nhello\r\n0\r\n\r\n", o.toString("ISO-8859-1"))
    }

    @Test
    fun copiarSinReencuadrarCopiaTalCual() {
        val o = ByteArrayOutputStream()
        HttpCodec.copiar(Cuerpo(entrada("5\r\nhello\r\n0\r\n\r\n"), Encuadre.CHUNKED, -1), o, false)
        assertEquals("hello", o.toString("ISO-8859-1"))
    }

    @Test
    fun escribirYVolverALeerUnaPeticion() {
        val original = HttpCodec.leerPeticion(entrada("GET /ruta?q=1 HTTP/1.1\r\nHost: a.com\r\nAccept-Encoding: gzip\r\n\r\n"))!!
        original.cab.poner("Accept-Encoding", "identity")
        val o = ByteArrayOutputStream()
        HttpCodec.escribirPeticion(o, original)
        val otra = HttpCodec.leerPeticion(Entrada(ByteArrayInputStream(o.toByteArray())))!!
        assertEquals("/ruta?q=1", otra.destino)
        assertEquals("identity", otra.cab.get("Accept-Encoding"))
        assertEquals("a.com", otra.cab.get("Host"))
    }

    @Test
    fun escribirYVolverALeerUnaRespuesta() {
        val r = HttpCodec.leerRespuesta(entrada("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"))!!
        val o = ByteArrayOutputStream()
        HttpCodec.escribirRespuesta(o, r)
        val otra = HttpCodec.leerRespuesta(Entrada(ByteArrayInputStream(o.toByteArray())))!!
        assertEquals(404, otra.codigo)
        assertEquals("Not Found", otra.razon)
    }
}
