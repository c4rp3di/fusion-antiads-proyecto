package com.vigia.adblock

import java.text.SimpleDateFormat
import java.util.Date

/** Cómo se enseña un evento al usuario: iconos, nombres claros y una explicación breve de cada tipo. */
object Presentacion {

    fun icono(cat: String): String = when (cat) {
        "bloqueo" -> "🚫"
        "inventada" -> "🎭"
        "pagina" -> "📄"
        "peticion" -> "🌐"
        "conexion" -> "🔌"
        "dns" -> "🔎"
        "cert" -> "🔒"
        "sistema" -> "⚙️"
        "error" -> "⛔"
        else -> "•"
    }

    fun nombre(cat: String, sub: String): String = when (cat + "/" + sub) {
        "bloqueo/proxy" -> "bloqueado (proxy)"
        "bloqueo/dns" -> "bloqueado (DNS)"
        "bloqueo/sni" -> "bloqueado (SNI)"
        "bloqueo/ip" -> "bloqueado (IP/puerto)"
        "inventada/juguete" -> "anuncio de juguete"
        "inventada/vast" -> "VAST vacío"
        "inventada/vmap" -> "VMAP vacío"
        "inventada/stub" -> "script sustituto"
        "inventada/hls" -> "lista HLS limpiada"
        "pagina/scriptlet" -> "script inyectado"
        "pagina/css" -> "CSS inyectado"
        "pagina/js" -> "error de JavaScript"
        "pagina/recurso" -> "recurso fallido"
        "pagina/csp" -> "bloqueo de seguridad (CSP)"
        "pagina/cache" -> "servido desde caché"
        "pagina/video" -> "vídeo"
        "peticion/http" -> "petición"
        "conexion/red" -> "conexión"
        "dns/consulta" -> "consulta DNS"
        "cert/rechazo" -> "certificado rechazado"
        "cert/exento" -> "app sin descifrar"
        "cert/navegador" -> "navegador (se mantiene)"
        "cert/permitido" -> "permitido (no se bloquea)"
        "error/proxy" -> "error"
        "sistema/listas" -> "listas"
        "sistema/reglas" -> "reglas"
        else -> sub
    }

    fun explicacion(cat: String, sub: String): String = when (cat + "/" + sub) {
        "bloqueo/proxy" -> "El proxy sustituyó esta petición por una respuesta vacía porque el dominio está en tus listas. La web no se rompe."
        "bloqueo/dns" -> "El filtro DNS no resolvió este dominio porque está en tus listas: la app no llegó a conectar."
        "bloqueo/sni" -> "Se cortó una conexión TLS cuyo nombre (SNI) está en tus listas. Pasa con apps que no se descifran."
        "bloqueo/ip" -> "Se cortó una conexión por su IP o puerto, por ejemplo DNS cifrado (puerto 853) con «Bloquear DNS cifrado»."
        "inventada/juguete" -> "En lugar del vídeo del anuncio se entregó un vídeo mínimo de 0,3 s para que el reproductor lo dé por visto."
        "inventada/vast" -> "A una petición de anuncio de vídeo se le contestó «no hay anuncio» en formato VAST."
        "inventada/vmap" -> "A una petición de anuncios de vídeo se le contestó una lista VMAP vacía."
        "inventada/stub" -> "Un script de publicidad (GPT, IMA o adsbygoogle) se sustituyó por uno que no hace nada pero no rompe la página."
        "inventada/hls" -> "Se quitaron de una lista de reproducción los tramos marcados como anuncio."
        "pagina/scriptlet" -> "Se insertó un bloque de JavaScript de AntiAds al principio de la página. No dice que se ejecutara."
        "pagina/css" -> "Se insertó CSS para ocultar huecos de anuncios en la página."
        "pagina/js" -> "La propia página notificó un error de JavaScript. Si sale justo tras inyectar algo nuestro, puede ser relevante."
        "pagina/recurso" -> "Un elemento de la página (imagen, script, vídeo…) no se pudo cargar, por ejemplo por haberse vaciado."
        "pagina/csp" -> "La política de seguridad de la web bloqueó algo. Si es nuestro script o CSS, esa técnica no pudo actuar."
        "pagina/cache" -> "El navegador usó una copia guardada: el proxy no vio esa petición, así que no pudo actuar sobre ella."
        "pagina/video" -> "Eventos del reproductor de la página (carga, reproducción, fin, error)."
        "peticion/http" -> "Una petición HTTP que pasó por el proxy sin modificar. Solo se anotan con la captura de peticiones."
        "conexion/red" -> "Una conexión de red de la app (destino, puerto, bytes). Solo metadatos, nunca contenido."
        "dns/consulta" -> "Una consulta DNS de la app. ✕ = bloqueada, ✓ = resuelta."
        "cert/rechazo" -> "La app no aceptó el certificado de AntiAds (certificado fijo o no confía en CAs de usuario): no se puede descifrar."
        "cert/exento" -> "La app quedó en la lista de apps sin descifrar y sus conexiones nuevas irán directas."
        "cert/navegador" -> "Un navegador cerró una conexión sin avisar (suele ser una preconexión). Los navegadores nunca se excluyen."
        "cert/permitido" -> "La regla «permitir» dejó pasar esta petición aunque estuviera en las listas."
        "error/proxy" -> "Un fallo del proxy con esta conexión (servidor que la cierra, tiempo agotado…). Muchos son inocuos."
        "sistema/listas" -> "Resultado de la última actualización de listas."
        "sistema/reglas" -> "Cambios en las reglas por sitio."
        else -> "Evento de AntiAds."
    }

    private fun corto(app: String): String = app.split('.').takeLast(2).joinToString(".")

    /** Una línea para la pantalla. */
    fun linea(s: Diagnostico.Snap, fmt: SimpleDateFormat): String {
        val sb = StringBuilder()
        sb.append(fmt.format(Date(s.ultimo))).append(' ').append(icono(s.cat)).append(' ').append(nombre(s.cat, s.sub))
        if (s.app.isNotEmpty()) sb.append(" · ").append(corto(s.app))
        if (s.dominio.isNotEmpty()) sb.append(" · ").append(s.dominio)
        if (s.texto.isNotEmpty() && s.texto != "bloqueado") sb.append(" · ").append(s.texto.take(80))
        if (s.n > 1) sb.append("  ×").append(s.n)
        return sb.toString().take(240)
    }
}
