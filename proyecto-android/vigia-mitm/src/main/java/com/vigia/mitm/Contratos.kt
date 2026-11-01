package com.vigia.mitm

/** Qué tipo de recurso pide una petición HTTP (según Sec-Fetch-Dest, la extensión o Accept). */
enum class TipoRecurso { SCRIPT, IMAGEN, ESTILO, DOCUMENTO, OTRO }

/** Respuesta inventada por el proxy en lugar de ir al servidor (VAST vacío, script sustituto…). */
class RespuestaFalsa(
    val tecnica: String,
    val tipo: String,
    val cuerpo: ByteArray,
    val codigo: Int = 200,
    val cabeceras: List<Pair<String, String>> = emptyList()
)

/** Lo que el MitM necesita saber del filtro. Lo implementa la app: el módulo no conoce listas ni preferencias. */
interface FiltroContenido {
    /** Verdadero si el recurso debe sustituirse por un 200 OK vacío. [host] llega en minúsculas, sin puerto. */
    fun bloquear(host: String, ruta: String, tipo: TipoRecurso): Boolean

    /** Reglas CSS (sin etiquetas <style>) para ocultar huecos de anuncios en [host], o null/vacío si ninguna. */
    fun cssCosmetico(host: String): String?

    /** Se consulta ANTES que [bloquear]: si devuelve algo, se contesta eso sin llegar al servidor. */
    fun responder(host: String, ruta: String, tipo: TipoRecurso, metodo: String, origen: String?): RespuestaFalsa? = null

    /** JavaScript que se ejecuta al principio de la página (justo tras <head>), antes que los de la propia web. */
    fun scriptInicial(host: String): String? = null

    /** ¿Hay que reescribir esta lista de reproducción HLS (.m3u8)? */
    fun tratarHls(host: String, ruta: String): Boolean = false

    /** Nuevo texto para la respuesta, o null para dejarla igual. */
    fun reescribirTexto(host: String, ruta: String, texto: String): String? = null
}

/** Decisiones de intermediación y lista blanca automática de SSL pinning. Lo implementa la app. */
interface PoliticaMitm {
    /**
     * ¿Se intercepta esta conexión? Se consulta al redirigir, antes de ningún byte TLS, así que solo se conoce la app
     * ([app] == «desconocida» en Android < 10) y la IP de destino. Falso = conexión directa al servidor real.
     */
    fun intermediar(app: String, ip: String): Boolean

    /**
     * El cliente rechazó nuestro certificado (SSL pinning, o la app no confía en CAs de usuario). La app debe
     * recordar que no se vuelva a intermediar y la siguiente conexión (el reintento) irá directa al servidor real.
     */
    fun alFallarHandshake(app: String, ip: String, host: String, motivo: String)
}

/** Eventos para estadísticas / registro. Se llaman desde hilos del proxy: deben ser rápidos. */
interface EventosMitm {
    fun alBloquear(host: String, ruta: String, tipo: TipoRecurso) {}
    fun alInyectarCss(host: String) {}
    /** Empieza a atenderse una conexión descifrada de [app]. Los eventos siguientes de ESTE hilo pertenecen a ella. */
    fun alAbrirConexion(app: String, host: String) {}
    /** Termina la conexión abierta con [alAbrirConexion] en este hilo. */
    fun alCerrarConexion() {}
    /** El cliente aceptó nuestro certificado: la conexión está siendo descifrada. */
    fun alConexionDescifrada(host: String) {}
    /** Resumen de cada respuesta HTTP que pasa por el proxy (para diagnóstico). [longitud] = -1 si no se conoce. */
    fun alPeticion(host: String, ruta: String, codigo: Int, tipo: String, longitud: Long) {}
    /** Datos que un script inyectado en la página ha enviado a la ruta /__antiads/t del propio sitio (JSON). */
    fun alTelemetria(host: String, datos: String) {}
    /** Una técnica del laboratorio actuó sobre esta petición o página. */
    fun alTecnica(tecnica: String, host: String, ruta: String) {}
    fun alFallbackPinning(app: String, host: String, motivo: String) {}
    fun alError(host: String, mensaje: String) {}
}
