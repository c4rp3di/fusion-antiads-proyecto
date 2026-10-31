package com.vigia.adblock

import android.content.Context
import com.vigia.mitm.EventosMitm
import com.vigia.mitm.FiltroContenido
import com.vigia.mitm.RespuestaFalsa
import com.vigia.mitm.TipoRecurso
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Las técnicas de AntiAds. Cada una se activa por separado en el Laboratorio. */
object Laboratorio {
    class Tecnica(val id: String, val nombre: String, val descripcion: String, val porDefecto: Boolean)

    val TECNICAS = listOf(
        Tecnica("clasico", "Bloqueo clásico (0 bytes)", "Lo que hace el Soberano+: los recursos de la lista se sustituyen por un 200 vacío. Apágalo para ver qué pasa sin él.", true),
        Tecnica("vast", "VAST vacío válido", "Las peticiones de anuncios de vídeo reciben un «no hay anuncio» bien formado en lugar de nada. El reproductor arranca el vídeo.", true),
        Tecnica("juguete", "Anuncio de juguete (vídeo válido)", "Los vídeos de anuncio (.mp4) de dominios bloqueados se sustituyen por un vídeo mínimo y válido de 0,3 s. El reproductor lo da por visto y sigue con el contenido, en vez de quedarse esperando un archivo vacío.", true),
        Tecnica("stubs", "Scripts sustitutos", "Google Publisher Tag, IMA SDK y adsbygoogle se sustituyen por versiones que no hacen nada pero no rompen la página.", true),
        Tecnica("yt", "YouTube: borrar datos de anuncios", "Script que quita adPlacements/playerAds de las respuestas del reproductor antes de que la web los lea.", true),
        Tecnica("skip", "Saltador por JavaScript", "Si un anuncio llega a sonar: lo silencia, lo adelanta al final y pulsa «Saltar». En YouTube y, de forma genérica, en otras webs.", true),
        Tecnica("css", "Ocultar huecos (CSS)", "Oculta los contenedores de anuncios que quedan vacíos.", true),
        Tecnica("peticiones", "Registrar vídeo y audio (diagnóstico)", "No cambia nada: anota en el diagnóstico las peticiones de vídeo, audio, listas de reproducción y archivos grandes. Actívalo, reproduce el anuncio y comparte el informe: así se ve de dónde llega.", false),
        Tecnica("todas", "Registrar TODAS las peticiones (diagnóstico)", "Como el anterior, pero anota cada petición que pasa sin modificar. Úsalo en una prueba corta: pulsa «Vaciar», abre el vídeo, espera al anuncio y comparte.", false),
        Tecnica("conexiones", "Todas las conexiones de todas las apps (diagnóstico)", "Anota destino, puerto, nombre TLS y bytes de lo que conecta CUALQUIER app (nunca el contenido). Resume por app. Solo con Modo Profundo. Se apaga sola a los 30 min.", false),
        Tecnica("pagina", "Telemetría de página (diagnóstico)", "La web, mientras se ve, cuenta sus errores, los eventos del vídeo, bloqueos de seguridad y recursos que vienen de la caché. Se apaga sola a los 30 min.", false),
        Tecnica("privado", "Informe privado (recortar URLs)", "Al copiar o compartir, quita lo que va tras «?», acorta rutas y tapa números largos. Recomendado.", true),
        Tecnica("hls", "Limpiar listas HLS (experimental)", "Quita de las listas .m3u8 los tramos marcados como anuncio (CUE-OUT/CUE-IN). Solo sirve si el sitio los marca.", false)
    )

    private val cache = ConcurrentHashMap<String, Boolean>()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("antiads_lab", Context.MODE_PRIVATE)

    /** Las capturas gastan batería y guardan datos de lo que visitas: se apagan solas pasado este tiempo. */
    val DIAGNOSTICO = setOf("conexiones", "peticiones", "todas", "pagina")
    private const val LIMITE_MS = 30L * 60L * 1000L
    private val desde = ConcurrentHashMap<String, Long>()

    @Volatile
    private var contexto: Context? = null

    /** Quedan minutos de captura si [id] es de diagnóstico y sigue activa; la apaga si ya caducó. */
    private fun vigente(id: String): Boolean {
        if (!DIAGNOSTICO.contains(id)) return true
        val ini = desde.getOrPut(id) { System.currentTimeMillis() }
        if (System.currentTimeMillis() - ini <= LIMITE_MS) return true
        cache[id] = false
        desde.remove(id)
        contexto?.let { prefs(it).edit().putBoolean("t_" + id, false).apply() }
        return false
    }

    fun activa(c: Context, id: String): Boolean {
        contexto = c.applicationContext
        val v = cache.getOrPut(id) { prefs(c).getBoolean("t_" + id, TECNICAS.firstOrNull { it.id == id }?.porDefecto ?: false) }
        return v && vigente(id)
    }

    /** Valor ya leído (sin Context), para el hilo del proxy; falso si todavía no se ha leído. */
    fun activaCache(id: String): Boolean = (cache[id] ?: false) && vigente(id)

    fun poner(c: Context, id: String, v: Boolean) {
        contexto = c.applicationContext
        cache[id] = v
        if (v) desde[id] = System.currentTimeMillis() else desde.remove(id)
        prefs(c).edit().putBoolean("t_" + id, v).apply()
    }

    fun diagActivas(): List<String> = DIAGNOSTICO.filter { activaCache(it) }

    /** Milisegundos que le quedan a la captura que antes caduque... la que más tiempo tenga. */
    fun restanteDiag(): Long {
        var max = 0L
        for (id in diagActivas()) {
            val ini = desde[id] ?: continue
            max = maxOf(max, LIMITE_MS - (System.currentTimeMillis() - ini))
        }
        return maxOf(0L, max)
    }

    private val conteo = ConcurrentHashMap<String, AtomicInteger>()
    fun contar(t: String) { conteo.getOrPut(t) { AtomicInteger() }.incrementAndGet() }
    fun veces(t: String): Int = conteo[t]?.get() ?: 0
    fun reiniciarConteo() = conteo.clear()
}

/** Registro de lo que han hecho las técnicas. Solo en memoria. Se puede copiar y enviar para diagnosticar. */
object Diagnostico {
    private const val MAX = 2500
    private val lineas = ArrayDeque<String>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val tipos = HashMap<String, Int>()

    @Synchronized
    fun porTipo(): Map<String, Int> = HashMap(tipos)

    @Synchronized
    fun anotar(tipo: String, texto: String) {
        tipos[tipo] = (tipos[tipo] ?: 0) + 1
        if (lineas.size >= MAX) lineas.removeFirst()
        lineas.addLast(hora.format(Date()) + "  " + tipo.padEnd(10) + " " + texto.take(170))
    }

    @Synchronized
    fun texto(ultimas: Int = MAX): String = lineas.toList().takeLast(ultimas).joinToString("\n")

    @Synchronized
    fun vaciar() {
        lineas.clear()
        tipos.clear()
    }
}

/** Reglas editables (JSON): patrones de peticiones y técnicas por sitio. */
class Reglas(
    val vast: List<String>,
    val stubs: List<Pair<String, String>>,
    val sitios: List<Pair<String, Set<String>>>,
    val permitir: List<String> = emptyList(),
    val juguete: List<String> = emptyList()
) {

    /** Técnicas de página para [host]: las de «*» más las del sitio que mejor encaje (sufijo de dominio). */
    fun tecnicasPara(host: String): Set<String> {
        val res = HashSet<String>()
        for ((h, t) in sitios) if (h == "*") res.addAll(t)
        val mejor = sitios.filter { it.first != "*" && (host == it.first || host.endsWith("." + it.first)) }.maxByOrNull { it.first.length }
        if (mejor != null) res.addAll(mejor.second)
        return res
    }

    companion object {
        fun parse(texto: String): Reglas {
            val j = JSONObject(texto)
            val vast = ArrayList<String>()
            j.optJSONArray("vast")?.let { for (i in 0 until it.length()) vast.add(it.getString(i).lowercase()) }
            val stubs = ArrayList<Pair<String, String>>()
            j.optJSONArray("stubs")?.let {
                for (i in 0 until it.length()) {
                    val o = it.getJSONObject(i)
                    stubs.add(Pair(o.getString("patron").lowercase(), o.getString("stub")))
                }
            }
            val sitios = ArrayList<Pair<String, Set<String>>>()
            j.optJSONArray("sitios")?.let {
                for (i in 0 until it.length()) {
                    val o = it.getJSONObject(i)
                    val t = HashSet<String>()
                    val a = o.optJSONArray("tecnicas")
                    if (a != null) for (k in 0 until a.length()) t.add(a.getString(k))
                    sitios.add(Pair(o.getString("host").lowercase(), t))
                }
            }
            val permitir = ArrayList<String>()
            j.optJSONArray("permitir")?.let { for (i in 0 until it.length()) permitir.add(it.getString(i).lowercase()) }
            val juguete = ArrayList<String>()
            j.optJSONArray("juguete")?.let { for (i in 0 until it.length()) juguete.add(it.getString(i).lowercase()) }
            return Reglas(vast, stubs, sitios, permitir, juguete)
        }

        fun archivo(c: Context) = File(c.filesDir, "reglas.json")

        /** Las importadas si existen y son válidas; si no, las integradas. */
        fun cargar(c: Context): Reglas {
            val f = archivo(c)
            if (f.exists()) {
                try { return parse(f.readText()) } catch (e: Exception) { Diagnostico.anotar("reglas", "importadas no válidas, uso las integradas: " + e.message) }
            }
            return parse(c.assets.open("reglas.json").bufferedReader().use { it.readText() })
        }
    }
}

object ReglasEstado {
    @Volatile
    var actual: Reglas? = null

    fun asegurar(c: Context): Reglas = actual ?: Reglas.cargar(c.applicationContext).also { actual = it }
    fun recargar(c: Context) { actual = Reglas.cargar(c.applicationContext) }
}

/**
 * El filtro de contenido de AntiAds: aplica las técnicas activas. Las de red (VAST, scripts sustitutos, bloqueo
 * clásico) actúan sobre cualquier petición según su URL; las de página (scriptlets, saltador, CSS, HLS) según el
 * sitio que se está viendo, que sale de las reglas.
 */
class MotorReglas(contexto: Context, private val base: FiltroAdBlock) : FiltroContenido {
    private val c = contexto.applicationContext
    private val clasico = FiltroContenidoAdBlock(base)

    private fun reglas(): Reglas = ReglasEstado.asegurar(c)

    /** Vídeo mínimo válido (H.264, 16x16, 0,3 s) que va en los assets. */
    private val juguete: ByteArray? by lazy {
        try { c.assets.open("anuncio_vacio.mp4").use { it.readBytes() } } catch (e: Exception) { null }
    }

    override fun responder(host: String, ruta: String, tipo: TipoRecurso, metodo: String, origen: String?): RespuestaFalsa? {
        val url = (host + ruta).lowercase()
        val r = reglas()
        Laboratorio.activa(c, "peticiones") // solo para que el valor esté leído cuando lo mire EventosLab
        Laboratorio.activa(c, "todas")
        // Vídeo de anuncio: o está en un dominio de la lista (y es .mp4), o la URL encaja con un patrón de «juguete»
        // de las reglas (anuncios que se sirven desde el mismo dominio que el contenido, como /pre_videos/ de phncdn.com).
        val esVideoDeLista = ruta.substringBefore('?').lowercase().endsWith(".mp4") && base.dominioBloqueado(host)
        if (tipo != TipoRecurso.DOCUMENTO && Laboratorio.activa(c, "juguete") &&
            (esVideoDeLista || r.juguete.any { url.contains(it) })
        ) {
            val v = juguete
            if (v != null) return RespuestaFalsa("juguete", "video/mp4", v)
        }
        if (Laboratorio.activa(c, "stubs")) {
            for ((patron, stub) in r.stubs) {
                if (url.contains(patron)) {
                    val js = when (stub) {
                        "gpt" -> LaboratorioJs.STUB_GPT
                        "ima" -> LaboratorioJs.STUB_IMA
                        "adsbygoogle" -> LaboratorioJs.STUB_ADSBYGOOGLE
                        else -> null
                    } ?: continue
                    return RespuestaFalsa("stub:" + stub, "application/javascript; charset=utf-8", js.toByteArray(Charsets.UTF_8))
                }
            }
        }
        if (Laboratorio.activa(c, "vast") && r.vast.any { url.contains(it) }) {
            val vmap = url.contains("vmap")
            return RespuestaFalsa(
                if (vmap) "vmap" else "vast", "application/xml; charset=utf-8",
                (if (vmap) LaboratorioJs.VMAP_VACIO else LaboratorioJs.VAST_VACIO).toByteArray(Charsets.UTF_8)
            )
        }
        return null
    }

    override fun bloquear(host: String, ruta: String, tipo: TipoRecurso): Boolean {
        if (!Laboratorio.activa(c, "clasico")) return false
        // Lista «permitir» de las reglas: se deja pasar al servidor real (p. ej. el script que detecta bloqueadores).
        val url = (host + ruta).lowercase()
        if (reglas().permitir.any { url.contains(it) }) {
            Diagnostico.anotar("permitido", host + ruta)
            return false
        }
        return clasico.bloquear(host, ruta, tipo)
    }

    override fun cssCosmetico(host: String): String? {
        if (!Laboratorio.activa(c, "css")) return null
        val t = reglas().tecnicasPara(host)
        if (!t.contains("css")) return null
        return FiltroContenidoAdBlock.CSS + (if (t.contains("yt")) "\n" + LaboratorioJs.CSS_YOUTUBE else "")
    }

    override fun scriptInicial(host: String): String? {
        val t = reglas().tecnicasPara(host)
        val sb = StringBuilder()
        if (Laboratorio.activa(c, "pagina")) sb.append(LaboratorioJs.TELEMETRIA).append('\n')
        if (t.contains("yt") && Laboratorio.activa(c, "yt")) {
            sb.append(LaboratorioJs.YT_PRUNE).append('\n')
            Laboratorio.contar("yt")
        }
        if (t.contains("skip") && Laboratorio.activa(c, "skip")) {
            val yt = host == "youtube.com" || host.endsWith(".youtube.com") || host.endsWith("youtube-nocookie.com")
            sb.append(if (yt) LaboratorioJs.SKIP_YT else LaboratorioJs.SKIP_GENERICO).append('\n')
            Laboratorio.contar("skip")
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    override fun tratarHls(host: String, ruta: String): Boolean =
        Laboratorio.activa(c, "hls") && reglas().tecnicasPara(host).contains("hls")

    /** Quita de una lista HLS los tramos entre #EXT-X-CUE-OUT y #EXT-X-CUE-IN y las marcas de intersticiales. */
    override fun reescribirTexto(host: String, ruta: String, texto: String): String? {
        if (!texto.startsWith("#EXTM3U")) return null
        val salida = ArrayList<String>()
        var enAnuncio = false
        var cambio = false
        for (l in texto.split('\n')) {
            val t = l.trim()
            when {
                t.startsWith("#EXT-X-CUE-OUT") -> { enAnuncio = true; cambio = true }
                t.startsWith("#EXT-X-CUE-IN") -> { enAnuncio = false; cambio = true }
                t.startsWith("#EXT-X-DATERANGE") && (t.contains("SCTE35-OUT") || t.contains("com.apple.hls.interstitial")) -> cambio = true
                enAnuncio && !t.startsWith("#EXT-X-ENDLIST") -> cambio = true
                else -> salida.add(l)
            }
        }
        return if (cambio) salida.joinToString("\n") else null
    }
}

/** Contadores y diagnóstico de lo que ocurre en el proxy. */
object EventosLab : EventosMitm {
    override fun alBloquear(host: String, ruta: String, tipo: TipoRecurso) {
        MitmEstado.vaciados.incrementAndGet()
        Laboratorio.contar("clasico")
        Estadisticas.registrar(host)
        Diagnostico.anotar("clasico", host + ruta)
    }

    override fun alInyectarCss(host: String) {
        MitmEstado.paginasLimpias.incrementAndGet()
        Laboratorio.contar("css")
        Diagnostico.anotar("css", host)
    }

    override fun alConexionDescifrada(host: String) { MitmEstado.descifradas.incrementAndGet() }

    override fun alFallbackPinning(app: String, host: String, motivo: String) {
        MitmEstado.rechazos.incrementAndGet()
        Diagnostico.anotar("rechazo", app + " → " + host + " (" + motivo.take(50) + ")")
    }

    override fun alTecnica(tecnica: String, host: String, ruta: String) {
        val base = tecnica.substringBefore(':')
        Laboratorio.contar(if (base == "stub") "stubs" else base)
        Diagnostico.anotar(tecnica, host + ruta)
    }

    override fun alTelemetria(host: String, datos: String) {
        if (!Laboratorio.activaCache("pagina")) return
        for (l in Paginas.procesar(host, datos)) {
            val tipo = l.substringAfter(' ').substringBefore(' ')
            Diagnostico.anotar("pág:" + clave(tipo), l)
        }
    }

    /** Tipo corto para contar: «desde CACHÉ…» -> cache, «CSP bloqueó…» -> csp. */
    private fun clave(primera: String): String = when {
        primera.startsWith("CACH") || primera == "desde" -> "cache"
        primera.startsWith("CSP") -> "csp"
        primera.startsWith("JS") -> "js"
        primera.startsWith("falló") -> "recurso"
        primera.startsWith("vídeo") -> "video"
        else -> "otro"
    }

    override fun alPeticion(host: String, ruta: String, codigo: Int, tipo: String, longitud: Long) {
        val todas = Laboratorio.activaCache("todas")
        if (!todas && !Laboratorio.activaCache("peticiones")) return
        val r = ruta.substringBefore('?').lowercase()
        val multimedia = codigo == 101 || tipo.startsWith("video/") || tipo.startsWith("audio/") || tipo.contains("mpegurl") ||
            tipo.contains("dash") || tipo.contains("octet-stream") || longitud >= 300_000L ||
            r.endsWith(".mp4") || r.endsWith(".m4s") || r.endsWith(".ts") || r.endsWith(".m3u8") || r.endsWith(".webm") || r.endsWith(".mpd")
        if (!multimedia && !todas) return
        Diagnostico.anotar("req", codigo.toString() + " " + tipo.take(22) + " " + (if (longitud >= 0) (longitud / 1024).toString() + "KB" else "?KB") + "  " + host + ruta)
    }

    override fun alError(host: String, mensaje: String) { Diagnostico.anotar("error", host + " " + mensaje) }
}
