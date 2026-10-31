package com.vigia.mitm

/**
 * Parser DPI ligero movido de NetLens (ClientHello TLS y cabecera de petición HTTP). Se han quitado las partes
 * acopladas a la UI/Flow de NetLens (inspectOut, DNS, categorías); NetLens puede depender de :vigia-mitm y
 * volver a añadirlas encima. El contenido HTTP completo (respuestas, cuerpos) lo trata [HttpCodec].
 */
object Dpi {
    class TlsInfo(val sni: String?, val alpn: List<String>, val tls13: Boolean, val ech: Boolean, val complete: Boolean, val legacyVer: Int)
    class HttpInfo(val method: String, val host: String?, val path: String, val queryKeys: List<String>, val userAgent: String?)

    private fun u8(b: ByteArray, p: Int): Int = b[p].toInt() and 0xFF
    private fun u16(b: ByteArray, p: Int): Int = (u8(b, p) shl 8) or u8(b, p + 1)

    fun tls(b: ByteArray): TlsInfo? {
        if (b.size < 6 || u8(b, 0) != 0x16 || u8(b, 1) != 3) return null
        if (u8(b, 5) != 1) return null
        var sni: String? = null
        val alpn = ArrayList<String>()
        var t13 = false; var ech = false; var complete: Boolean; var ver = 0
        try {
            var p = 9
            ver = u16(b, p); p += 2 + 32
            p += 1 + u8(b, p); p += 2 + u16(b, p); p += 1 + u8(b, p)
            val extLen = u16(b, p); p += 2
            val end = p + extLen
            while (p + 4 <= end) {
                val type = u16(b, p); val len = u16(b, p + 2); val q = p + 4
                if (type == 0 && len >= 5) { val nl = u16(b, q + 3); sni = String(b, q + 5, nl, Charsets.US_ASCII) }
                else if (type == 16) { var a = q + 2; val ae = q + 2 + u16(b, q); while (a < ae) { val l = u8(b, a); alpn.add(String(b, a + 1, l, Charsets.US_ASCII)); a += 1 + l } }
                else if (type == 43) { var a = q + 1; val ae = q + 1 + u8(b, q); while (a + 1 < ae) { if (u16(b, a) == 0x0304) t13 = true; a += 2 } }
                else if (type == 0xFE0D) ech = true
                p = q + len
            }
            complete = true
        } catch (e: Exception) { complete = false }
        return TlsInfo(sni, alpn, t13, ech, complete, ver)
    }

    private val METHODS = listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ")
    private val HOST_RE = Regex("(?im)^host:\\s*([^\\r\\n]+)")
    private val UA_RE = Regex("(?im)^user-agent:\\s*([^\\r\\n]+)")

    fun http(b: ByteArray): HttpInfo? {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.ISO_8859_1)
        val m = METHODS.firstOrNull { head.startsWith(it) } ?: return null
        var firstEnd = head.indexOf('\r'); if (firstEnd < 0) firstEnd = head.indexOf('\n')
        val line = if (firstEnd > 0) head.substring(0, firstEnd) else head
        val parts = line.split(' ')
        val target = if (parts.size > 1) parts[1] else ""
        val path = target.substringBefore('?')
        val keys = if (target.contains('?')) target.substringAfter('?').split('&').map { it.substringBefore('=') }.filter { it.isNotEmpty() }.take(12) else emptyList()
        val host = HOST_RE.find(head)?.groupValues?.get(1)?.trim()
        val ua = UA_RE.find(head)?.groupValues?.get(1)?.trim()
        return HttpInfo(m.trim(), host, path.take(80), keys, ua?.take(80))
    }
}
