package com.vigia.adblock

import android.content.Context
import com.vigia.mitm.PoliticaMitm
import java.util.concurrent.ConcurrentHashMap

/**
 * Lista blanca automática del MitM. Se consulta en cada conexión 443 (hilo del túnel), así que vive en memoria y
 * solo se escribe en disco cuando cambia.
 *
 * Los NAVEGADORES nunca se excluyen: un navegador abre conexiones «de cortesía» (preconexiones) y las cierra sin usarlas,
 * y eso se ve igual que un rechazo por SSL pinning. Si se excluyera el navegador entero tras uno de esos cierres, dejaría
 * de pasar por el proxy y ninguna técnica actuaría. Chrome y similares sí aceptan certificados instalados por el usuario.
 */
class PoliticaAdBlock(contexto: Context) : PoliticaMitm {
    private val c = contexto.applicationContext
    private val exentas: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().also { set ->
        set.addAll(Ajustes.mitmExentas(c))
        // Limpieza de versiones anteriores, que sí podían excluir navegadores.
        if (set.removeAll(NAVEGADORES)) Ajustes.setMitmExentas(c, HashSet(set))
    }

    private fun clave(app: String, ip: String) = if (app == "desconocida" || app.isEmpty()) "ip:" + ip else app

    override fun intermediar(app: String, ip: String): Boolean = !exentas.contains(clave(app, ip))

    override fun alFallarHandshake(app: String, ip: String, host: String, motivo: String) {
        if (NAVEGADORES.contains(app)) {
            Diagnostico.anotar("navegador", app + " cerró " + host + " (no se excluye)")
            return
        }
        // El cliente cerró esta conexión (no se puede continuar un TLS ya rechazado); la siguiente, el reintento de la
        // app, irá directa al servidor real porque intermediar() ya devuelve falso.
        if (exentas.add(clave(app, ip))) {
            Ajustes.setMitmExentas(c, HashSet(exentas))
            Diagnostico.anotar("exento", clave(app, ip))
        }
    }

    fun lista(): Set<String> = HashSet(exentas)

    fun limpiar() {
        exentas.clear()
        Ajustes.setMitmExentas(c, emptySet())
    }

    fun quitar(clave: String) {
        if (exentas.remove(clave)) Ajustes.setMitmExentas(c, HashSet(exentas))
    }

    companion object {
        val NAVEGADORES = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
            "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
            "com.sec.android.app.sbrowser", "com.mi.globalbrowser", "com.android.browser",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser"
        )
    }
}
