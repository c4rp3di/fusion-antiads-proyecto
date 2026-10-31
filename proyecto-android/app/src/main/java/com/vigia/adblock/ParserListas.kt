package com.vigia.adblock

import com.vigia.core.red.UtilRed

/**
 * Lee una línea de una lista de bloqueo en cualquiera de estos formatos y devuelve el dominio, o null si no sirve:
 *  - hosts:     «0.0.0.0 ads.ejemplo.com»  (AdAway, StevenBlack, HaGeZi hosts)
 *  - dominios:  «ads.ejemplo.com»          (OISD domainswild2)
 *  - Adblock:   «||ads.ejemplo.com^»       (EasyList, filtros de AdGuard) y excepciones «@@||ok.ejemplo.com^»
 *
 * A nivel de DNS solo se puede bloquear un dominio entero, así que se descartan las reglas que dependen de la ruta,
 * de comodines, de «$third-party» u otros modificadores, y las reglas cosméticas (##).
 */
object ParserListas {

    class Entrada(val dominio: String, val permitido: Boolean)

    private val IGNORADOS = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
        "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0"
    )

    private val MODIFICADORES_ACEPTADOS = setOf("", "|", "\$important", "\$all")

    fun procesarLinea(linea: String): Entrada? {
        var s = linea.trim()
        if (s.isEmpty()) return null
        val c0 = s[0]
        if (c0 == '#' || c0 == '!' || c0 == '[') return null
        if (s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#\$#")) return null

        if (s.startsWith("||") || s.startsWith("@@||")) {
            val permitido = s.startsWith("@@")
            val resto = s.substring(if (permitido) 4 else 2)
            val fin = resto.indexOf('^')
            if (fin < 0) return null
            if (!MODIFICADORES_ACEPTADOS.contains(resto.substring(fin + 1))) return null
            return crear(resto.substring(0, fin), permitido)
        }

        // Formato hosts o lista de dominios: se corta el comentario final.
        val almohadilla = s.indexOf('#')
        if (almohadilla > 0) s = s.substring(0, almohadilla).trim()
        val partes = s.split(' ', '\t').filter { it.isNotEmpty() }
        if (partes.isEmpty()) return null
        val candidato: String
        if (partes.size == 1) {
            candidato = partes[0]
        } else {
            val primero = partes[0]
            val esIp = UtilRed.parseIpv4(primero) != null || primero.contains(':')
            if (!esIp) return null
            candidato = partes[1]
        }
        return crear(candidato, false)
    }

    private fun crear(crudo: String, permitido: Boolean): Entrada? {
        var d = crudo
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.startsWith(".")) d = d.substring(1)
        // Una regla con ruta, puerto o comodín no se puede aplicar a un dominio entero: se descarta.
        if (d.contains('/') || d.contains('*') || d.contains(':') || d.contains('?') || d.contains('|')) return null
        val limpio = UtilRed.normalizarDominio(d) ?: return null
        if (IGNORADOS.contains(limpio)) return null
        return Entrada(limpio, permitido)
    }
}
