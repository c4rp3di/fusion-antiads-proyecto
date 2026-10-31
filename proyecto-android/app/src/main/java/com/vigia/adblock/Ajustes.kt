package com.vigia.adblock

import android.content.Context
import com.vigia.core.red.UtilRed

class ListaDef(val id: String, val nombre: String, val descripcion: String, val url: String, val propia: Boolean = false)

/** Todas las preferencias de la app (SharedPreferences; no hay base de datos). */
object Ajustes {
    private const val PREFS = "adblock_ajustes"

    val CATALOGO = listOf(
        ListaDef("oisd_small", "OISD small", "Anuncios y rastreadores sin romper webs. Recomendada. ~57 000 dominios.", "https://small.oisd.nl/domainswild2"),
        ListaDef("adaway", "AdAway", "Lista clásica de anuncios móviles. ~6 000 dominios.", "https://adaway.org/hosts.txt"),
        ListaDef("oisd_big", "OISD big", "Más agresiva: añade más rastreadores y telemetría. ~200 000 dominios.", "https://big.oisd.nl/domainswild2"),
        ListaDef("hagezi_pro", "HaGeZi Pro", "Anuncios, rastreadores y malware. ~350 000 dominios.", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt"),
        ListaDef("adguard_dns", "AdGuard DNS filter", "Anuncios y rastreadores pensada para DNS (base de AdGuard). ~100 000 dominios.", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
        ListaDef("onehosts_lite", "1Hosts Lite", "Anuncios, rastreadores y malware, equilibrada y con pocos falsos positivos. ~90 000 dominios.", "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/hosts.txt"),
        ListaDef("stevenblack", "StevenBlack", "Anuncios y malware unificados. ~150 000 dominios.", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        ListaDef("easylist", "EasyList", "Solo se usan sus reglas de dominio entero; la mayoría de EasyList no es aplicable en DNS.", "https://easylist.to/easylist/easylist.txt"),
        ListaDef("easyprivacy", "EasyPrivacy", "Rastreadores. Igual que EasyList: solo reglas de dominio entero.", "https://easylist.to/easylist/easyprivacy.txt")
    )

    private val POR_DEFECTO = setOf("oisd_small", "adaway")

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- estado general
    fun activa(c: Context): Boolean = p(c).getBoolean("activa", false)
    fun setActiva(c: Context, v: Boolean) = p(c).edit().putBoolean("activa", v).apply()

    fun arranqueAuto(c: Context): Boolean = p(c).getBoolean("arranque_auto", true)
    fun setArranqueAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("arranque_auto", v).apply()

    fun soloWifi(c: Context): Boolean = p(c).getBoolean("solo_wifi", true)
    fun setSoloWifi(c: Context, v: Boolean) = p(c).edit().putBoolean("solo_wifi", v).apply()

    fun modoProfundo(c: Context): Boolean = p(c).getBoolean("modo_profundo", false)
    fun setModoProfundo(c: Context, v: Boolean) = p(c).edit().putBoolean("modo_profundo", v).apply()

    // ---- Modo Profundo (MitM) · solo AdBlock Soberano+
    fun mitmActivo(c: Context): Boolean = p(c).getBoolean("mitm_activo", false)
    fun setMitmActivo(c: Context, v: Boolean) = p(c).edit().putBoolean("mitm_activo", v).apply()

    /** Apps (o «ip:x.x.x.x» si Android < 10 no da la app) que NO se interceptan: pinning o sin confianza en CA de usuario. */
    fun mitmExentas(c: Context): Set<String> = HashSet(p(c).getStringSet("mitm_exentas", emptySet()) ?: emptySet())
    fun setMitmExentas(c: Context, v: Set<String>) = p(c).edit().putStringSet("mitm_exentas", HashSet(v)).apply()

    fun bloqueoNxdomain(c: Context): Boolean = p(c).getBoolean("nxdomain", false)
    fun setBloqueoNxdomain(c: Context, v: Boolean) = p(c).edit().putBoolean("nxdomain", v).apply()

    /** «sistema» o la IP de un servidor DNS. */
    fun dnsUpstream(c: Context): String = p(c).getString("dns", "sistema") ?: "sistema"
    fun setDnsUpstream(c: Context, v: String) = p(c).edit().putString("dns", v).apply()

    fun ultimaActualizacion(c: Context): Long = p(c).getLong("ultima_act", 0L)
    fun setUltimaActualizacion(c: Context, v: Long) = p(c).edit().putLong("ultima_act", v).apply()

    // ---- listas
    fun listasActivas(c: Context): Set<String> {
        val guardado = p(c).getStringSet("listas_activas", null) ?: return POR_DEFECTO
        return HashSet(guardado)
    }

    fun setListaActiva(c: Context, id: String, activa: Boolean) {
        val nuevo = HashSet(listasActivas(c))
        if (activa) nuevo.add(id) else nuevo.remove(id)
        p(c).edit().putStringSet("listas_activas", nuevo).apply()
    }

    fun propias(c: Context): List<ListaDef> {
        val texto = p(c).getString("listas_propias", "") ?: ""
        val res = ArrayList<ListaDef>()
        for (linea in texto.split('\n')) {
            val partes = linea.split('\t')
            if (partes.size == 3) res.add(ListaDef(partes[0], partes[1], partes[2], partes[2], true))
        }
        return res
    }

    /** Añade una lista propia (solo https). Devuelve null si la URL no es válida. */
    fun agregarPropia(c: Context, urlCruda: String): ListaDef? {
        val url = urlCruda.trim()
        if (!url.startsWith("https://") || url.length < 12 || url.contains('\t') || url.contains('\n')) return null
        val host = UtilRed.extraerHost(url) ?: return null
        val id = "p" + Integer.toHexString(url.hashCode())
        val lista = ListaDef(id, host, url, url, true)
        val actuales = propias(c).filter { it.id != id } + lista
        guardarPropias(c, actuales)
        setListaActiva(c, id, true)
        return lista
    }

    fun quitarPropia(c: Context, id: String) {
        guardarPropias(c, propias(c).filter { it.id != id })
        setListaActiva(c, id, false)
    }

    private fun guardarPropias(c: Context, lista: List<ListaDef>) {
        val texto = lista.joinToString("\n") { it.id + "\t" + it.nombre + "\t" + it.url }
        p(c).edit().putString("listas_propias", texto).apply()
    }

    fun todasLasListas(c: Context): List<ListaDef> = CATALOGO + propias(c)

    fun listasActivasDef(c: Context): List<ListaDef> {
        val activas = listasActivas(c)
        return todasLasListas(c).filter { activas.contains(it.id) }
    }

    // ---- caché HTTP por lista
    fun etag(c: Context, id: String): String? = p(c).getString("etag_" + id, null)
    fun modificado(c: Context, id: String): String? = p(c).getString("mod_" + id, null)
    fun setCabecerasCache(c: Context, id: String, etag: String?, modificado: String?) {
        p(c).edit().putString("etag_" + id, etag).putString("mod_" + id, modificado).apply()
    }

    // ---- dominios propios
    fun bloqueExtra(c: Context): String = p(c).getString("bloque_extra", "") ?: ""
    fun setBloqueExtra(c: Context, v: String) = p(c).edit().putString("bloque_extra", v).apply()

    fun agregarBloqueo(c: Context, dominio: String) {
        val actual = bloqueExtra(c)
        if (actual.split('\n').any { it.trim() == dominio }) return
        setBloqueExtra(c, if (actual.isBlank()) dominio else actual.trimEnd() + "\n" + dominio)
    }

    // ---- DNS cifrado (DoT/DoH)
    fun bloquearDnsCifrado(c: Context): Boolean = p(c).getBoolean("bloquear_dns_cifrado", false)
    fun setBloquearDnsCifrado(c: Context, v: Boolean) = p(c).edit().putBoolean("bloquear_dns_cifrado", v).apply()

    // ---- Registro DNS por app (solo memoria; esta preferencia solo guarda si está activado)
    fun registroDns(c: Context): Boolean = p(c).getBoolean("registro_dns", false)
    fun setRegistroDns(c: Context, v: Boolean) = p(c).edit().putBoolean("registro_dns", v).apply()

    fun permitidosExtra(c: Context): String = p(c).getString("permitidos_extra", "") ?: ""
    fun setPermitidosExtra(c: Context, v: String) = p(c).edit().putString("permitidos_extra", v).apply()

    fun agregarPermitido(c: Context, dominio: String) {
        val actual = permitidosExtra(c)
        if (actual.split('\n').any { it.trim() == dominio }) return
        setPermitidosExtra(c, if (actual.isBlank()) dominio else actual.trimEnd() + "\n" + dominio)
    }

    // ---- apps excluidas
    fun excluidas(c: Context): Set<String> = HashSet(p(c).getStringSet("excluidas", emptySet()) ?: emptySet())
    fun setExcluidas(c: Context, v: Set<String>) = p(c).edit().putStringSet("excluidas", HashSet(v)).apply()
}
