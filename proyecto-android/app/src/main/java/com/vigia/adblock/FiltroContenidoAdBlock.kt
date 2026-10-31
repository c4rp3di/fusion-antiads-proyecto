package com.vigia.adblock

import com.vigia.core.red.FiltroTrafico
import com.vigia.mitm.EventosMitm
import com.vigia.mitm.FiltroContenido
import com.vigia.mitm.TipoRecurso

/**
 * Filtro en modo MitM. El DNS NO bloquea (si bloqueara, la petición no llegaría nunca al proxy y no habría 200 vacío);
 * el bloqueo lo hace el proxy por host + ruta. El resto del comportamiento (SNI de apps exentas) es el del 1.0.
 */
class FiltroProfundo(private val base: FiltroTrafico) : FiltroTrafico by base {
    override fun dominioBloqueadoEnDns(nombre: String): Boolean = false
}

class FiltroContenidoAdBlock(private val base: FiltroAdBlock) : FiltroContenido {

    override fun bloquear(host: String, ruta: String, tipo: TipoRecurso): Boolean {
        // Una navegación directa a un dominio de anuncios no se vacía: el usuario la pidió.
        if (tipo != TipoRecurso.DOCUMENTO && base.dominioBloqueado(host)) return true
        val r = ruta.substringBefore('?').lowercase()
        return RUTAS.any { r.contains(it) }
    }

    override fun cssCosmetico(host: String): String? = CSS

    companion object {
        /** Rutas típicas de anuncios en dominios que mezclan contenido y publicidad. Conservadora a propósito. */
        private val RUTAS = listOf("/pagead/", "/adserver/", "/ads/banner", "/doubleclick/", "/adsbygoogle.js", "/prebid")

        private val SELECTORES = listOf(
            "ins.adsbygoogle", "[id^=\"google_ads_\"]", "[id^=\"div-gpt-ad\"]", "[class*=\"adsbygoogle\"]",
            "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication.com\"]", "iframe[src*=\"adservice.\"]",
            "[data-ad-slot]", "[data-ad-unit]", "[aria-label=\"Advertisement\"]", "[aria-label=\"Publicidad\"]",
            ".ad-slot", ".ad-container", ".ad-banner", ".advert", ".advertisement", ".publicidad", ".sponsored-ad",
            "#ad-banner", "#ads", "#adContainer"
        )

        // Sin comas fuera de los selectores y sin '<' para que sea seguro dentro de <style>.
        val CSS: String = SELECTORES.joinToString(",") + "{display:none!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important}"
    }
}

/** Contadores del Modo Profundo desde que arrancó la protección; la pantalla principal los muestra. */
object MitmEstado {
    @Volatile var activo = false
    val descifradas = java.util.concurrent.atomic.AtomicInteger()
    val vaciados = java.util.concurrent.atomic.AtomicInteger()
    val paginasLimpias = java.util.concurrent.atomic.AtomicInteger()
    val rechazos = java.util.concurrent.atomic.AtomicInteger()

    fun reiniciar() {
        descifradas.set(0); vaciados.set(0); paginasLimpias.set(0); rechazos.set(0)
    }
}

/** Cuenta los bloqueos del MitM en las mismas estadísticas que el DNS. */
object EventosAdBlock : EventosMitm {
    override fun alBloquear(host: String, ruta: String, tipo: TipoRecurso) {
        MitmEstado.vaciados.incrementAndGet()
        Estadisticas.registrar(host)
    }

    override fun alInyectarCss(host: String) { MitmEstado.paginasLimpias.incrementAndGet() }
    override fun alConexionDescifrada(host: String) { MitmEstado.descifradas.incrementAndGet() }
    override fun alFallbackPinning(app: String, host: String, motivo: String) { MitmEstado.rechazos.incrementAndGet() }
}
