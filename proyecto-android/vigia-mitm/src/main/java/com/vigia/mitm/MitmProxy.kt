package com.vigia.mitm

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import android.util.Base64
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Proxy MitM TLS local (movido de NetLens) con tres cambios de fondo:
 *  1. El destino real y la app llegan por [Origenes] (en NetLens se perdían al pasar por loopback).
 *  2. FALLBACK de SSL pinning: si el cliente rechaza el handshake, la app se añade a la lista blanca ([PoliticaMitm]).
 *  3. Tras el handshake se habla HTTP/1.1 de verdad ([HttpCodec]): bloqueo con 200 vacío e inyección de CSS.
 *
 * Solo HTTP/1.1: no se anuncia ALPN, así que los clientes no negocian h2 y todo el tráfico es legible.
 */
class MitmProxy(
    private val protector: (Socket) -> Unit,
    private val politica: PoliticaMitm,
    private val filtro: FiltroContenido,
    private val eventos: EventosMitm
) {
    @Volatile var activo = false
        private set
    @Volatile var puerto = 0
        private set

    private var servidor: ServerSocket? = null
    private var fabrica: SSLSocketFactory? = null
    private val hojas = AlmacenHojas()
    private val azar = SecureRandom()
    private val pool = ThreadPoolExecutor(0, 128, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { r ->
        Thread(r, "mitm").also { it.isDaemon = true }
    }

    @Synchronized
    fun iniciar() {
        if (activo) return
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(hojas), null, null)
        fabrica = ctx.socketFactory
        val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        servidor = ss
        puerto = ss.localPort
        activo = true
        Thread({ aceptar(ss) }, "mitm-accept").also { it.isDaemon = true }.start()
    }

    @Synchronized
    fun detener() {
        activo = false
        try { servidor?.close() } catch (e: IOException) {}
        servidor = null
    }

    private fun aceptar(ss: ServerSocket) {
        while (activo) {
            val c = try { ss.accept() } catch (e: IOException) { break }
            try {
                pool.execute { atender(c) }
            } catch (e: RejectedExecutionException) {
                cerrar(c)
            }
        }
    }

    // ------------------------------------------------------------------ una conexión

    private fun atender(cliente: Socket) {
        val puertoCliente = cliente.port
        val origen = Origenes.tomar(puertoCliente)
        var ssl: Socket? = null
        var remoto: Socket? = null
        try {
            if (origen == null) return // no la desvió nuestro núcleo
            cliente.tcpNoDelay = true
            cliente.soTimeout = 20_000
            if (origen.claro) {
                // HTTP sin cifrar: sin handshake ni certificado. El nombre de host sale de la cabecera Host.
                ssl = cliente
                cliente.soTimeout = 60_000
                remoto = conectarRemotoClaro(origen)
                eventos.alAbrirConexion(origen.app, origen.ip)
                bucle(cliente, remoto, origen.ip, true)
                return
            }
            val tls = fabrica!!.createSocket(cliente, null, puertoCliente, true) as SSLSocket
            ssl = tls
            tls.useClientMode = false
            try {
                tls.startHandshake()
            } catch (e: Exception) {
                // El navegador/app no aceptó nuestro certificado: SSL pinning, o no confía en CAs de usuario.
                val host = hojas.nombreVisto(puertoCliente) ?: origen.ip
                if (esRechazoDelCliente(e)) {
                    val motivo = resumen(e)
                    politica.alFallarHandshake(origen.app, origen.ip, host, motivo)
                    eventos.alFallbackPinning(origen.app, host, motivo)
                }
                return
            }
            val host = (hojas.nombreVisto(puertoCliente) ?: origen.ip).lowercase()
            tls.soTimeout = 60_000
            remoto = conectarRemoto(origen, host)
            eventos.alAbrirConexion(origen.app, host)
            eventos.alConexionDescifrada(host)
            bucle(tls, remoto, host, false)
        } catch (e: Exception) {
            if (origen != null && e !is EOFException && e !is SocketTimeoutException) {
                eventos.alError(origen.ip, resumen(e))
            }
        } finally {
            eventos.alCerrarConexion()
            cerrar(remoto); cerrar(ssl); cerrar(cliente)
        }
    }

    /** Solo cuenta como «pinning» un fallo del lado cliente; un timeout no dice nada. */
    private fun esRechazoDelCliente(e: Exception): Boolean = e !is SocketTimeoutException && (e is SSLException || e is EOFException || e is java.net.SocketException)

    private fun resumen(e: Throwable): String = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160)

    /** Conexión TCP al servidor real para HTTP sin cifrar. Protegida: el propio proxy no debe volver a entrar en la VPN. */
    private fun conectarRemotoClaro(o: Origen): Socket {
        val s = Socket()
        try {
            s.bind(null)
            protector(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getByName(o.ip), o.puerto), 10_000)
            s.soTimeout = 30_000
            return s
        } catch (e: Exception) {
            cerrar(s)
            throw e
        }
    }

    /** ¿Empieza como una petición HTTP/1.x? El puerto 80 lo usan a veces otros protocolos: esos se dejan pasar tal cual. */
    private fun pareceHttp(e: Entrada): Boolean {
        e.mark(16)
        val b = ByteArray(8)
        var n = 0
        try {
            while (n < b.size) {
                val k = e.read(b, n, b.size - n)
                if (k < 0) break
                n += k
            }
        } finally {
            e.reset()
        }
        val s = String(b, 0, n, Charsets.ISO_8859_1)
        return METODOS.any { s.startsWith("$it ") }
    }

    private fun conectarRemoto(o: Origen, host: String): SSLSocket {
        val crudo = Socket()
        try {
            crudo.bind(null)
            protector(crudo) // el propio proxy no debe volver a entrar en la VPN
            crudo.tcpNoDelay = true
            crudo.connect(InetSocketAddress(InetAddress.getByName(o.ip), o.puerto), 10_000)
            val esIp = host == o.ip
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(crudo, if (esIp) null else host, o.puerto, true) as SSLSocket
            s.soTimeout = 30_000
            if (!esIp) {
                // NetLens no verificaba el nombre del servidor real: un atacante con cualquier certificado válido
                // habría pasado. Se pide verificación de nombre en el handshake y, además, se comprueba a mano.
                val p = s.sslParameters
                p.endpointIdentificationAlgorithm = "HTTPS"
                s.sslParameters = p
            }
            s.startHandshake()
            if (!esIp) {
                val hoja = s.session.peerCertificates.firstOrNull() as? X509Certificate
                if (hoja == null || !coincideNombre(hoja, host)) throw SSLPeerUnverifiedException("el certificado no cubre " + host)
            }
            return s
        } catch (e: Exception) {
            cerrar(crudo)
            throw e
        }
    }

    private fun coincideNombre(c: X509Certificate, host: String): Boolean {
        val sans = try { c.subjectAlternativeNames } catch (e: Exception) { null } ?: return false
        for (s in sans) {
            if (s[0] != 2) continue
            val n = (s[1] as? String)?.lowercase() ?: continue
            if (n == host) return true
            if (n.startsWith("*.") && host.substringAfter('.', "") == n.substring(2) && host.indexOf('.') > 0) return true
        }
        return false
    }

    // ------------------------------------------------------------------ bucle HTTP

    private fun bucle(c: Socket, r: Socket, sni: String, claro: Boolean) {
        val cin = Entrada(c.inputStream)
        val cout = BufferedOutputStream(c.outputStream, 16384)
        val rin = Entrada(r.inputStream)
        val rout = BufferedOutputStream(r.outputStream, 16384)

        if (claro && !pareceHttp(cin)) {
            // No es HTTP (otro protocolo en el puerto 80): bytes opacos en ambos sentidos, sin tocar nada.
            tunel(c, r, cin, rin)
            return
        }

        while (true) {
            val req = HttpCodec.leerPeticion(cin) ?: return
            val host = req.cab.get("Host")?.substringBefore(':')?.trim()?.lowercase()?.ifEmpty { null } ?: sni
            val cuerpoReq = HttpCodec.cuerpoPeticion(cin, req)
            val upgrade = req.cab.contiene("Connection", "upgrade") || req.metodo == "CONNECT"
            val cerrarTras = req.cab.contiene("Connection", "close") || req.version == "HTTP/1.0"

            // --- Bloqueo: 200 OK con 0 bytes, la conexión sigue viva y la web no se rompe.
            val ruta = rutaDe(req.destino)
            if (!upgrade && req.metodo == "POST" && ruta.startsWith("/__antiads/")) {
                // Telemetría de nuestro script: no sale a internet; se lee y se contesta 204.
                val acumulado = ByteArrayOutputStream()
                val trozo = ByteArray(4096)
                while (true) {
                    val n = cuerpoReq.leer(trozo)
                    if (n < 0) break
                    if (acumulado.size() < 32768) acumulado.write(trozo, 0, n)
                }
                eventos.alTelemetria(host, String(acumulado.toByteArray(), Charsets.UTF_8))
                cout.write("HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                cout.flush()
                if (cerrarTras) return
                continue
            }
            if (!upgrade) {
                val tipo = clasificar(req)
                val falsa = filtro.responder(host, ruta, tipo, req.metodo, req.cab.get("Origin"))
                if (falsa != null) {
                    cuerpoReq.descartar()
                    respuestaFalsa(cout, falsa, req)
                    eventos.alTecnica(falsa.tecnica, host, ruta)
                    if (cerrarTras) return
                    continue
                }
                if (filtro.bloquear(host, ruta, tipo)) {
                    cuerpoReq.descartar()
                    respuestaVacia(cout, tipo, req)
                    eventos.alBloquear(host, ruta, tipo)
                    if (cerrarTras) return
                    continue
                }
            }

            // --- Reenvío de la petición. Sin compresión para poder leer el HTML; sin h2/h3 (no hay ALPN).
            if (!upgrade) {
                req.cab.poner("Accept-Encoding", "identity")
                req.cab.quitar("Proxy-Connection")
            }
            val espera100 = req.cab.get("Expect")?.contains("100-continue", true) == true
            HttpCodec.escribirPeticion(rout, req)
            rout.flush()

            var resp: CabezaRespuesta? = null
            if (espera100) {
                // El cliente no enviará el cuerpo hasta oír el 100: hay que reenviar la respuesta provisional ya.
                resp = HttpCodec.leerRespuesta(rin) ?: return
                if (resp.codigo == 100) {
                    HttpCodec.escribirRespuesta(cout, resp); cout.flush()
                    resp = null
                }
            }
            if (resp == null || resp.codigo == 100) {
                HttpCodec.copiar(cuerpoReq, rout, cuerpoReq.encuadre == Encuadre.CHUNKED)
                rout.flush()
            }
            while (resp == null || (resp.codigo in 100..199 && resp.codigo != 101)) {
                resp = HttpCodec.leerRespuesta(rin) ?: return
                if (resp.codigo in 100..199 && resp.codigo != 101) {
                    HttpCodec.escribirRespuesta(cout, resp); cout.flush()
                }
            }

            val fin: CabezaRespuesta = resp ?: return
            if (fin.codigo == 101 || req.metodo == "CONNECT") {
                eventos.alPeticion(host, ruta, fin.codigo, "websocket/upgrade", -1)
                // WebSocket u otro upgrade: a partir de aquí son bytes opacos en ambos sentidos.
                HttpCodec.escribirRespuesta(cout, fin); cout.flush()
                tunel(c, r, cin, rin)
                return
            }

            val cuerpoResp = HttpCodec.cuerpoRespuesta(rin, fin, req.metodo)
            val cerrarResp = cuerpoResp.encuadre == Encuadre.HASTA_CIERRE || fin.cab.contiene("Connection", "close")
            val ok = responder(cout, fin, cuerpoResp, host, ruta)
            if (!ok || cerrarResp || cerrarTras) return
        }
    }

    /** @return false si la conexión no puede reutilizarse. */
    private fun responder(cout: OutputStream, resp: CabezaRespuesta, cuerpo: Cuerpo, host: String, ruta: String): Boolean {
        val ct = (resp.cab.get("Content-Type") ?: "").lowercase()
        eventos.alPeticion(host, ruta, resp.codigo, ct, if (cuerpo.encuadre == Encuadre.LONGITUD) cuerpo.longitud else -1L)
        val enc = (resp.cab.get("Content-Encoding") ?: "identity").trim().lowercase()
        val encOk = enc == "identity" || enc == "gzip" || enc == "x-gzip" || enc == "deflate"
        val tamOk = !(cuerpo.encuadre == Encuadre.LONGITUD && cuerpo.longitud > LIMITE_HTML)
        val hayCuerpo = resp.codigo == 200 && cuerpo.encuadre != Encuadre.NINGUNO && encOk && tamOk
        val esHtml = hayCuerpo && ct.startsWith("text/html") && !ct.contains("utf-16") && !ct.contains("utf-32")
        val esM3u8 = hayCuerpo && (ct.contains("mpegurl") || ruta.substringBefore('?').endsWith(".m3u8"))
        val css = if (esHtml) filtro.cssCosmetico(host) else null
        val js = if (esHtml) filtro.scriptInicial(host) else null
        val hls = esM3u8 && filtro.tratarHls(host, ruta)

        if (css.isNullOrEmpty() && js.isNullOrEmpty() && !hls) {
            HttpCodec.escribirRespuesta(cout, resp)
            HttpCodec.copiar(cuerpo, cout, cuerpo.encuadre == Encuadre.CHUNKED)
            cout.flush()
            return true
        }

        // HTML: se lee entero (hasta un límite), se inyecta el CSS y se reenvía con Content-Length.
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(16384)
        var excede = false
        while (true) {
            val n = cuerpo.leer(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
            if (buf.size() > LIMITE_HTML) { excede = true; break }
        }
        if (excede) {
            // Demasiado grande: pasa sin tocar, reencuadrado en chunks (el resto sigue en streaming).
            resp.cab.quitar("Content-Length"); resp.cab.poner("Transfer-Encoding", "chunked")
            HttpCodec.escribirRespuesta(cout, resp)
            val b = buf.toByteArray(); HttpCodec.escribirChunk(cout, b, b.size)
            HttpCodec.copiar(cuerpo, cout, true)
            return cuerpo.encuadre != Encuadre.HASTA_CIERRE
        }

        var html = buf.toByteArray()
        var desc = true
        try {
            html = when (enc) {
                "gzip", "x-gzip" -> leerLimitado(GZIPInputStream(ByteArrayInputStream(html)))
                "deflate" -> leerLimitado(InflaterInputStream(ByteArrayInputStream(html)))
                else -> html
            }
        } catch (e: Exception) { desc = false }

        val nonce = nuevoNonce()
        var nuevo: ByteArray? = null
        if (desc && hls) {
            val t = filtro.reescribirTexto(host, ruta, String(html, Charsets.UTF_8))
            if (t != null) { nuevo = t.toByteArray(Charsets.UTF_8); eventos.alTecnica("hls", host, ruta) }
        } else if (desc) {
            var h: ByteArray = html
            var cambio = false
            if (!js.isNullOrEmpty()) {
                val r = inyectarInicio(h, "<script data-antiads nonce=\"" + nonce + "\">" + js + "</script>")
                if (r != null) { h = r; cambio = true; eventos.alTecnica("scriptlet", host, ruta) }
            }
            if (!css.isNullOrEmpty()) {
                val r = inyectar(h, "<style data-adbp nonce=\"" + nonce + "\">" + css + "</style>")
                if (r != null) { h = r; cambio = true; eventos.alInyectarCss(host) }
            }
            if (cambio) nuevo = h
        }
        val salida = nuevo ?: buf.toByteArray()
        if (nuevo != null) {
            resp.cab.quitar("Content-Encoding")
            if (esHtml) relajarCsp(resp.cab, nonce, !js.isNullOrEmpty())
        }
        resp.cab.quitar("Transfer-Encoding")
        resp.cab.poner("Content-Length", salida.size.toString())
        HttpCodec.escribirRespuesta(cout, resp)
        cout.write(salida)
        cout.flush()
        return cuerpo.encuadre != Encuadre.HASTA_CIERRE
    }

    private fun leerLimitado(i: InputStream): ByteArray {
        val o = ByteArrayOutputStream(); val b = ByteArray(16384)
        while (true) {
            val n = i.read(b); if (n < 0) break
            o.write(b, 0, n)
            if (o.size() > LIMITE_HTML * 8) throw IOException("descompresión excesiva")
        }
        return o.toByteArray()
    }

    /** Inserta [bloque] antes de </head> (o antes de <body). Null si no parece una página completa. Preserva los bytes. */
    internal fun inyectar(html: ByteArray, bloque: String): ByteArray? {
        val s = String(html, Charsets.ISO_8859_1)
        var i = s.indexOf("</head>", ignoreCase = true)
        if (i < 0) i = s.indexOf("<body", ignoreCase = true)
        if (i < 0) return null
        return (s.substring(0, i) + bloque + s.substring(i)).toByteArray(Charsets.ISO_8859_1)
    }

    /** Inserta [bloque] justo después de la etiqueta <head ...> (o de <html ...>). Null si no la encuentra. */
    internal fun inyectarInicio(html: ByteArray, bloque: String): ByteArray? {
        val s = String(html, Charsets.ISO_8859_1)
        var i = s.indexOf("<head", ignoreCase = true)
        if (i < 0 || (s.length > i + 5 && s[i + 5].isLetter())) i = s.indexOf("<html", ignoreCase = true)
        if (i < 0) return null
        val fin = s.indexOf('>', i)
        if (fin < 0) return null
        return (s.substring(0, fin + 1) + bloque + s.substring(fin + 1)).toByteArray(Charsets.ISO_8859_1)
    }

    /** Una CSP sin 'unsafe-inline' bloquearía nuestro <style> y nuestro <script>: se les añade nuestro nonce. */
    private fun relajarCsp(cab: Cabeceras, nonce: String, conScript: Boolean) {
        val nuevas = cab.lista.map { (n, v) ->
            if (!n.equals("Content-Security-Policy", true)) Pair(n, v) else {
                val dirs = v.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                permitirNonce(dirs, listOf("style-src-elem", "style-src"), "style-src-elem", nonce)
                if (conScript) permitirNonce(dirs, listOf("script-src-elem", "script-src"), "script-src-elem", nonce)
                Pair(n, dirs.joinToString("; "))
            }
        }
        cab.lista.clear()
        cab.lista.addAll(nuevas)
    }

    private fun permitirNonce(dirs: MutableList<String>, nombres: List<String>, nuevoNombre: String, nonce: String) {
        fun idx(n: String) = dirs.indexOfFirst { it.substringBefore(' ').equals(n, true) }
        // Con 'unsafe-inline' a secas ya vale; pero 'strict-dynamic', un nonce o un hash lo anulan.
        fun yaPermiteInline(d: String) =
            d.contains("'unsafe-inline'") && !d.contains("'strict-dynamic'") && !d.contains("'nonce-") && !d.contains("'sha")
        val n = "'nonce-" + nonce + "'"
        var hecho = false
        for (nombre in nombres) {
            val i = idx(nombre)
            if (i >= 0) {
                hecho = true
                if (!yaPermiteInline(dirs[i])) dirs[i] = dirs[i] + " " + n
            }
        }
        if (!hecho) {
            val d = idx("default-src")
            if (d >= 0 && !yaPermiteInline(dirs[d])) dirs.add(nuevoNombre + " " + dirs[d].substringAfter(' ', "") + " " + n)
        }
    }

    /** Respuesta inventada (VAST vacío, script sustituto…). Responde también al preflight CORS de las peticiones cruzadas. */
    private fun respuestaFalsa(o: OutputStream, f: RespuestaFalsa, req: CabezaPeticion) {
        val origen = req.cab.get("Origin")
        val preflight = req.metodo == "OPTIONS" && req.cab.get("Access-Control-Request-Method") != null
        var cuerpo = f.cuerpo
        var estado = "HTTP/1.1 " + f.codigo + " OK"
        val extra = StringBuilder()
        if (!preflight && f.tipo.startsWith("video/")) {
            // Un <video> pide «bytes=0-» y espera un 206; un 200 completo lo aceptan algunos reproductores y otros no.
            extra.append("Accept-Ranges: bytes\r\n")
            val m = Regex("bytes=(\\d*)-(\\d*)").find(req.cab.get("Range") ?: "")
            if (m != null) {
                val n = f.cuerpo.size
                val ini = m.groupValues[1]
                val fin = m.groupValues[2]
                val desde = if (ini.isEmpty()) maxOf(0, n - (fin.toIntOrNull() ?: n)) else (ini.toIntOrNull() ?: 0)
                val hasta = if (ini.isEmpty() || fin.isEmpty()) n - 1 else minOf(n - 1, fin.toIntOrNull() ?: (n - 1))
                if (desde >= n || hasta < desde) {
                    estado = "HTTP/1.1 416 Range Not Satisfiable"
                    extra.append("Content-Range: bytes */").append(n).append("\r\n")
                    cuerpo = ByteArray(0)
                } else {
                    estado = "HTTP/1.1 206 Partial Content"
                    extra.append("Content-Range: bytes ").append(desde).append('-').append(hasta).append('/').append(n).append("\r\n")
                    cuerpo = f.cuerpo.copyOfRange(desde, hasta + 1)
                }
            }
        }
        val sb = StringBuilder()
        sb.append(if (preflight) "HTTP/1.1 204 No Content\r\n" else estado + "\r\nContent-Type: " + f.tipo + "\r\n")
        sb.append("Content-Length: ").append(if (preflight) 0 else cuerpo.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append(extra)
        if (origen != null) {
            sb.append("Access-Control-Allow-Origin: ").append(origen).append("\r\n")
            sb.append("Access-Control-Allow-Credentials: true\r\nVary: Origin\r\n")
            if (preflight) {
                sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                sb.append("Access-Control-Allow-Headers: ").append(req.cab.get("Access-Control-Request-Headers") ?: "*").append("\r\n")
                sb.append("Access-Control-Max-Age: 600\r\n")
            }
        }
        for ((n, v) in f.cabeceras) sb.append(n).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!preflight && req.metodo != "HEAD") o.write(cuerpo)
        o.flush()
    }

    private fun nuevoNonce(): String = ByteArray(12).also { azar.nextBytes(it) }.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /** 200 OK sin cuerpo. Lleva CORS y contesta al preflight: sin eso, una petición fetch/XHR a otro dominio parece FALLAR y la web la reintenta en bucle. */
    private fun respuestaVacia(o: OutputStream, tipo: TipoRecurso, req: CabezaPeticion) {
        val ct = when (tipo) {
            TipoRecurso.SCRIPT -> "application/javascript"
            TipoRecurso.IMAGEN -> "image/gif"
            TipoRecurso.ESTILO -> "text/css"
            TipoRecurso.DOCUMENTO -> "text/html; charset=utf-8"
            TipoRecurso.OTRO -> "text/plain"
        }
        respuestaFalsa(o, RespuestaFalsa("clasico", ct, ByteArray(0)), req)
    }

    /** Origin-form (/ruta?q) tal cual; absolute-form (http://h/ruta) se reduce a /ruta. */
    private fun rutaDe(destino: String): String =
        if (destino.startsWith("/") || !destino.contains("://")) destino else "/" + destino.substringAfter("://").substringAfter('/', "")

    private fun clasificar(req: CabezaPeticion): TipoRecurso {
        when (req.cab.get("Sec-Fetch-Dest")?.lowercase()) {
            "script", "worker", "sharedworker", "serviceworker" -> return TipoRecurso.SCRIPT
            "image" -> return TipoRecurso.IMAGEN
            "style" -> return TipoRecurso.ESTILO
            "document", "iframe", "frame", "embed", "object" -> return TipoRecurso.DOCUMENTO
            "empty" -> return TipoRecurso.OTRO
        }
        val p = req.destino.substringBefore('?').substringBefore('#').lowercase()
        when {
            p.endsWith(".js") || p.endsWith(".mjs") -> return TipoRecurso.SCRIPT
            p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".gif") || p.endsWith(".webp") ||
                p.endsWith(".svg") || p.endsWith(".ico") || p.endsWith(".avif") || p.endsWith(".bmp") -> return TipoRecurso.IMAGEN
            p.endsWith(".css") -> return TipoRecurso.ESTILO
            p.endsWith(".html") || p.endsWith(".htm") -> return TipoRecurso.DOCUMENTO
        }
        val a = req.cab.get("Accept")?.lowercase() ?: ""
        return when {
            a.startsWith("image/") -> TipoRecurso.IMAGEN
            a.startsWith("text/css") -> TipoRecurso.ESTILO
            a.contains("text/html") -> TipoRecurso.DOCUMENTO
            else -> TipoRecurso.OTRO
        }
    }

    private fun tunel(c: Socket, r: Socket, cin: InputStream, rin: InputStream) {
        c.soTimeout = 0; r.soTimeout = 0
        val t = Thread({ copiarCrudo(rin, c.getOutputStream()); cerrar(c) }, "mitm-up")
        t.isDaemon = true; t.start()
        copiarCrudo(cin, r.getOutputStream())
        cerrar(r)
    }

    private fun copiarCrudo(i: InputStream, o: OutputStream) {
        try {
            val b = ByteArray(16384)
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); o.flush() }
        } catch (e: IOException) {
        }
    }

    private fun cerrar(s: Socket?) { try { s?.close() } catch (e: IOException) {} }

    companion object {
        private const val LIMITE_HTML = 3 * 1024 * 1024
        private val METODOS = listOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "CONNECT", "TRACE")
    }
}
