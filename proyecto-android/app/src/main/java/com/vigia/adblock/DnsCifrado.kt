package com.vigia.adblock

/**
 * DNS cifrado (DoT, DoH, DoQ) que una app puede usar para saltarse el filtro. Dos capas:
 *  - DOMINIOS: nombres de los resolvedores públicos más usados. Se añaden al filtro y se bloquean por DNS/SNI, así que
 *    funciona en cualquier modo (en MitM el proxy los vacía con 200 OK).
 *  - IPS y puerto 853: solo en modos que enrutan todo el tráfico (SNI o MitM); en modo DNS no entra en el túnel.
 */
object DnsCifrado {
    val DOMINIOS = listOf(
        "dns.google", "dns.google.com", "dns64.dns.google",
        "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "dns.opendns.com",
        "dns.adguard-dns.com", "dns.adguard.com", "unfiltered.adguard-dns.com", "family.adguard-dns.com",
        "dns.nextdns.io", "freedns.controld.com", "dns.controld.com",
        "doh.dns.sb", "dot.sb", "doh.mullvad.net", "dns.mullvad.net", "adblock.dns.mullvad.net",
        "doh.cleanbrowsing.org", "doh.libredns.gr", "dnsforge.de", "doh.applied-privacy.net",
        "dns.digitale-gesellschaft.ch", "doh.ffmuc.net", "odvr.nic.cz", "public.dns.iij.jp",
        "dns.alidns.com", "doh.pub", "max.rethinkdns.com", "sky.rethinkdns.com",
        // Firefox desactiva su DoH si esta consulta falla; para que lo haga, activa «Responder NXDOMAIN».
        "use-application-dns.net"
    )

    private val IPS_DOH: Set<Int> = listOf(
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        "8.8.8.8", "8.8.4.4",
        "9.9.9.9", "9.9.9.10", "9.9.9.11", "149.112.112.112", "149.112.112.10", "149.112.112.11",
        "208.67.222.222", "208.67.220.220",
        "94.140.14.14", "94.140.15.15", "94.140.14.140", "94.140.14.141",
        "185.222.222.222", "45.11.45.11", "194.242.2.2", "76.76.2.0", "76.76.10.0"
    ).map { ip ->
        val p = ip.split('.').map { it.toInt() }
        (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }.toSet()

    /** DoT/DoQ (puerto 853, cualquier IP) o DoH/DoH3 hacia un resolvedor público conocido (puerto 443). */
    fun bloquea(ip: Int, puerto: Int): Boolean = puerto == 853 || (puerto == 443 && IPS_DOH.contains(ip))
}
