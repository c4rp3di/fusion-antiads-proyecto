package com.vigia.adblock

import android.content.Context
import android.os.Build
import com.vigia.core.red.EventoRed
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun fmtBytes(n: Long): String = when {
    n < 1024L -> n.toString() + " B"
    n < 1024L * 1024L -> (n / 1024L).toString() + " KB"
    else -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
}

/** Nombre de dominio de cada IP, aprendido de las respuestas DNS, para ponerle nombre a conexiones sin SNI. */
object NombreIp {
    private val mapa = ConcurrentHashMap<String, String>()
    private val ipv4 = Regex("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b")

    fun aprender(respuesta: String, dominio: String) {
        if (dominio.isEmpty()) return
        if (mapa.size > 6000) mapa.clear()
        for (m in ipv4.findAll(respuesta)) mapa[m.value] = dominio
    }

    fun de(ip: String): String? = mapa[ip]
}

/**
 * Conexiones de TODAS las apps que pasan por el túnel (metadatos: app, destino, puerto, nombre TLS, bytes; nunca contenido).
 * Se agrupan por app+destino para que la memoria y el trabajo no crezcan con el tráfico. Un registro del núcleo no es
 * exactamente una conexión: una conexión larga puede emitir varios.
 */
object Conexiones {
    class Fila(val app: String, val proto: String, val destino: String) {
        var n = 0
        var bin = 0L
        var bout = 0L
        var primero = 0L
        var ultimo = 0L
    }

    class Reg(val ts: Long, val app: String, val proto: String, val destino: String, val bin: Long, val bout: Long)

    private const val MAX_FILAS = 3000
    private const val MAX_RECIENTES = 600
    private val mapa = ConcurrentHashMap<String, Fila>()
    private val recientes = ArrayDeque<Reg>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun registrar(e: EventoRed) {
        val nombre = if (e.sni.isNotEmpty()) e.sni else (NombreIp.de(e.ip) ?: e.ip)
        val destino = nombre + ":" + e.puerto
        if (mapa.size >= MAX_FILAS) podar()
        val f = mapa.getOrPut(e.app + "|" + e.proto + "|" + destino) { Fila(e.app, e.proto, destino) }
        synchronized(f) {
            f.n++
            f.bin += e.bytesIn
            f.bout += e.bytesOut
            if (f.primero == 0L) f.primero = e.ts
            f.ultimo = e.ts
        }
        Diagnostico.anotar("conexion", e.proto + " ↓" + fmtBytes(f.bin) + " ↑" + fmtBytes(f.bout), e.app, destino)
        synchronized(recientes) {
            if (recientes.size >= MAX_RECIENTES) recientes.removeFirst()
            recientes.addLast(Reg(e.ts, e.app, e.proto, destino, e.bytesIn, e.bytesOut))
        }
    }

    @Synchronized
    private fun podar() {
        val viejas = mapa.entries.sortedBy { it.value.ultimo }.take(MAX_FILAS / 4)
        for (v in viejas) mapa.remove(v.key)
    }

    fun vaciar() {
        mapa.clear()
        synchronized(recientes) { recientes.clear() }
    }

    fun hayDatos(): Boolean = mapa.isNotEmpty()

    fun todas(): List<Fila> = mapa.values.toList()

    fun filasDe(app: String): List<Fila> = mapa.values.filter { it.app == app }

    fun resumen(): String {
        val filas = mapa.values.toList()
        if (filas.isEmpty()) return "Conexiones: sin datos (activa «Todas las conexiones» y usa alguna app; solo con Modo Profundo).\n"
        val sb = StringBuilder("── Conexiones por app (bytes ↓ bajada ↑ subida; «reg» = registros del túnel) ──\n")
        val porApp = filas.groupBy { it.app }
        val apps = porApp.entries.sortedByDescending { en -> en.value.sumOf { it.bin + it.bout } }.take(14)
        for ((app, fs) in apps) {
            sb.append(app).append(" · ").append(fs.sumOf { it.n }).append(" reg · ↓").append(fmtBytes(fs.sumOf { it.bin }))
                .append(" ↑").append(fmtBytes(fs.sumOf { it.bout })).append(" · ").append(fs.size).append(" destinos\n")
            for (f in fs.sortedByDescending { it.bin + it.bout }.take(5)) {
                sb.append("   ").append(f.proto).append(' ').append(f.destino).append(" · ").append(f.n)
                    .append(" · ↓").append(fmtBytes(f.bin)).append(" ↑").append(fmtBytes(f.bout)).append('\n')
            }
        }
        if (porApp.size > apps.size) sb.append("… y ").append(porApp.size - apps.size).append(" apps más\n")
        return sb.toString()
    }

    fun ultimas(n: Int): String {
        val regs = synchronized(recientes) { recientes.toList() }.takeLast(n)
        if (regs.isEmpty()) return ""
        val sb = StringBuilder("── Últimas conexiones ──\n")
        for (r in regs) {
            sb.append(hora.format(Date(r.ts))).append("  ").append(r.app).append("  ").append(r.proto).append(' ').append(r.destino)
                .append("  ↓").append(fmtBytes(r.bin)).append(" ↑").append(fmtBytes(r.bout)).append('\n')
        }
        return sb.toString()
    }
}

/**
 * ¿Qué apps hablan con más destinos de las listas de bloqueo? Se calcula con los bloqueos que tienen app conocida y con las
 * conexiones registradas. Para que salga bien conviene tener activada la captura «Todas las conexiones».
 */
object Rastreadores {
    class Fila(val app: String, val destinos: Set<String>, val veces: Int)

    fun ranking(): List<Fila> {
        val destinos = HashMap<String, HashSet<String>>()
        val veces = HashMap<String, Int>()
        for ((app, dom, n) in Diagnostico.bloqueosPorApp()) {
            destinos.getOrPut(app) { HashSet() }.add(dom)
            veces[app] = (veces[app] ?: 0) + n
        }
        for (f in Conexiones.todas()) {
            val host = f.destino.substringBeforeLast(':')
            if (AdBlockEstado.filtro.dominioBloqueado(host)) {
                destinos.getOrPut(f.app) { HashSet() }.add(host)
                veces[f.app] = (veces[f.app] ?: 0) + f.n
            }
        }
        return destinos.entries.map { Fila(it.key, it.value, veces[it.key] ?: 0) }
            .sortedWith(compareByDescending<Fila> { it.destinos.size }.thenByDescending { it.veces })
    }

    fun texto(max: Int): String {
        val r = ranking().take(max)
        if (r.isEmpty()) return "(sin datos: activa «Todas las conexiones» en Capturas y usa algunas apps)"
        return r.joinToString("\n") { it.app + " · " + it.destinos.size + " destinos de seguimiento · " + it.veces + " veces" }
    }
}

/** Lo que las propias páginas cuentan por telemetría (errores, vídeo, CSP, recursos servidos desde caché). */
object Paginas {
    class Res(val host: String) {
        var cargas = 0
        var js = 0
        var recurso = 0
        var promesa = 0
        var csp = 0
        var cache = 0
        var medios = 0
        var mediaError = 0
        val ejemplos = ArrayList<String>()
    }

    @Volatile
    var navegador = ""
    private val mapa = ConcurrentHashMap<String, Res>()

    fun vaciar() = mapa.clear()

    /** Procesa el JSON de un aviso y devuelve las líneas que merece la pena poner en la cronología. */
    fun procesar(hostAviso: String, datos: String): List<String> {
        val lineas = ArrayList<String>()
        try {
            val j = JSONObject(datos)
            val host = j.optString("h", hostAviso)
            val r = mapa.getOrPut(host) { Res(host) }
            val arr = j.optJSONArray("e") ?: return lineas
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val k = o.optString("k")
                var linea: String? = null
                synchronized(r) {
                    when (k) {
                        "pagina" -> { r.cargas++; if (navegador.isEmpty()) navegador = o.optString("ua"); linea = "carga " + j.optString("p") + " (" + o.optString("red") + ")" }
                        "js" -> { r.js++; linea = "JS: " + o.optString("m") + " @" + o.optString("u") }
                        "recurso" -> { r.recurso++; linea = "falló " + o.optString("t") + " " + o.optString("u") }
                        "promesa" -> { r.promesa++; linea = "promesa rechazada: " + o.optString("m") }
                        "csp" -> { r.csp++; linea = "CSP bloqueó " + o.optString("d") + " " + o.optString("u") }
                        "cache" -> { r.cache++; linea = "desde CACHÉ " + o.optString("u") + " " + (o.optLong("b") / 1024L) + "KB" }
                        "media" -> {
                            r.medios++
                            val ev = o.optString("ev")
                            if (ev == "error") r.mediaError++
                            if (ev == "loadedmetadata" || ev == "play" || ev == "playing" || ev == "ended" || ev == "error") {
                                linea = "vídeo " + ev + " dur=" + o.optString("d") + " t=" + o.optString("t") + " err=" + o.optString("c") + " " + o.optString("u")
                            }
                        }
                    }
                    val l = linea
                    if (l != null && k != "media" && k != "pagina" && r.ejemplos.size < 10) r.ejemplos.add(k + ": " + l)
                }
                val l2 = linea
                if (l2 != null) lineas.add(host + " " + l2)
            }
        } catch (e: Exception) {
            lineas.add("telemetría ilegible: " + e.javaClass.simpleName)
        }
        return lineas
    }

    fun resumen(): String {
        val filas = mapa.values.toList().sortedByDescending { it.cargas + it.js + it.recurso + it.csp + it.cache }
        if (filas.isEmpty()) return "Páginas: sin telemetría (activa «Telemetría de página» y recarga la web).\n"
        val sb = StringBuilder("── Páginas (telemetría) ──\n")
        if (navegador.isNotEmpty()) sb.append("Navegador: ").append(navegador).append('\n')
        for (r in filas.take(12)) {
            sb.append(r.host).append(" · cargas ").append(r.cargas).append(" · errores JS ").append(r.js).append(" · recursos fallidos ")
                .append(r.recurso).append(" · CSP ").append(r.csp).append(" · desde caché ").append(r.cache).append(" · vídeo ")
                .append(r.medios).append(" (err ").append(r.mediaError).append(")\n")
            for (e in r.ejemplos.take(4)) sb.append("   ").append(e.take(150)).append('\n')
        }
        return sb.toString()
    }
}

/** Genera el informe completo. Con [privado] se recortan los datos que identifican lo que visitaste. */
object Informe {
    private val consulta = Regex("\\?\\S*")
    private val largos = Regex("\\d{6,}")
    private val ruta = Regex("(/[^/\\s?]+/[^/\\s?]+)(/[^\\s?]+)+")

    fun mascara(texto: String): String =
        texto.replace(consulta, "?…").replace(ruta) { it.groupValues[1] + "/…" }.replace(largos, "#")

    fun generar(c: Context, ultimas: Int, privado: Boolean, filtro: Diagnostico.Filtro = Diagnostico.Filtro()): String {
        val sb = StringBuilder()
        val ahora = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("AntiAds · informe de diagnóstico · ").append(ahora).append(if (privado) " · PRIVADO (URLs recortadas)" else "").append('\n')

        // ---- Entorno
        var version = "?"
        try { version = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Exception) { }
        sb.append("Versión ").append(version).append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
            .append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        val profundo = MitmEstado.activo || Ajustes.modoProfundo(c)
        sb.append("Modo Profundo (MitM): ").append(if (MitmEstado.activo) "ACTIVO" else "NO activo (solo filtro DNS)")
            .append(" · IPv6 oculto: ").append(if (MitmEstado.activo) "sí" else "no")
            .append(" · QUIC bloqueado: ").append(if (profundo) "sí" else "no")
            .append(" · DNS cifrado bloqueado: ").append(if (Ajustes.bloquearDnsCifrado(c)) "sí" else "no").append('\n')
        val r = ReglasEstado.actual
        sb.append("Reglas: ").append(if (Reglas.archivo(c).exists()) "personalizadas (importadas o editadas)" else "integradas")
        if (r != null) sb.append(" (sitios ").append(r.sitios.size).append(", vast ").append(r.vast.size).append(", stubs ").append(r.stubs.size)
            .append(", permitir ").append(r.permitir.size).append(", juguete ").append(r.juguete.size).append(')')
        sb.append('\n')
        val activas = Laboratorio.diagActivas()
        sb.append("Capturas activas: ").append(if (activas.isEmpty()) "ninguna" else activas.joinToString(", "))
        if (activas.isNotEmpty()) sb.append(" · quedan ").append(Laboratorio.restanteDiag() / 60000L).append(" min (se apagan solas)")
        sb.append('\n')
        sb.append("Técnicas: ").append(Laboratorio.TECNICAS.joinToString(", ") { it.id + "=" + (if (Laboratorio.activa(c, it.id)) "sí" else "no") }).append("\n\n")

        // ---- Avisos
        sb.append("── Avisos ──\n")
        val avisos = ArrayList<String>()
        if (!MitmEstado.activo) avisos.add("El Modo Profundo no está activo: solo se ve el DNS, no las conexiones ni las páginas.")
        avisos.add("MitM: " + MitmEstado.descifradas.get() + " conexiones descifradas, " + MitmEstado.rechazos.get() + " rechazos del certificado.")
        val exentas = Ajustes.mitmExentas(c)
        if (exentas.isNotEmpty()) avisos.add(exentas.size.toString() + " apps sin descifrar: " + exentas.sorted().joinToString(", "))
        val tipos = Diagnostico.porTipo()
        val errores = tipos["error"] ?: 0
        if (errores > 0) avisos.add(errores.toString() + " errores del proxy (ver línea de tiempo).")
        val cache = tipos["pág:cache"] ?: 0
        if (cache > 0) avisos.add("La página reporta " + cache + " recursos servidos desde la CACHÉ del navegador (el proxy no los ve).")
        val csp = tipos["pág:csp"] ?: 0
        if (csp > 0) avisos.add("La política de seguridad de la página bloqueó " + csp + " recursos (posible efecto de nuestro script/CSS).")
        val sinActuar = listOf("vast", "stubs", "juguete", "yt", "skip", "hls").filter { Laboratorio.activa(c, it) && Laboratorio.veces(it) == 0 }
        if (sinActuar.isNotEmpty()) avisos.add("Activadas pero sin actuar todavía: " + sinActuar.joinToString(", ") + ".")
        for (a in avisos) sb.append("• ").append(a).append('\n')
        sb.append('\n')

        // ---- Conteo
        sb.append("── Veces que actuó cada técnica ──\n")
        sb.append(listOf("clasico", "juguete", "vast", "stubs", "yt", "skip", "css", "hls").joinToString(", ") { it + "=" + Laboratorio.veces(it) }).append("\n\n")

        // ---- Categorías y bloqueos
        val cats = Diagnostico.contarPorCategoria()
        sb.append("── Eventos por categoría ──\n")
        sb.append(Diagnostico.CATEGORIAS.joinToString(" · ") { it.second + " " + (cats[it.first] ?: 0) }).append("\n\n")
        sb.append("── Bloqueados por dominio (top 15; capa: dns, sni, proxy o ip) ──\n")
        val top = Diagnostico.topBloqueos(15)
        if (top.isEmpty()) sb.append("(ninguno todavía)\n")
        for ((dom, capa, n) in top) sb.append(dom).append("  ×").append(n).append("  [").append(capa).append("]\n")
        sb.append('\n')

        // ---- Rastreadores, reglas y pruebas
        sb.append("── Apps y rastreadores (destinos de tus listas contactados) ──\n")
        sb.append(Rastreadores.texto(10)).append("\n\n")
        sb.append("── Efectividad de las reglas ──\n")
        sb.append(EfectividadReglas.texto(ReglasEstado.actual)).append('\n')
        val pruebas = Sesiones.ultimas()
        if (pruebas.isNotEmpty()) {
            sb.append("── Pruebas guardadas (").append(pruebas.size).append(") ──\n")
            sb.append(Sesiones.texto(pruebas.last())).append('\n')
            if (pruebas.size >= 2) sb.append(Sesiones.comparar(pruebas[pruebas.size - 2], pruebas.last())).append('\n')
        }

        // ---- Conexiones y páginas
        sb.append(Conexiones.resumen()).append('\n')
        sb.append(Paginas.resumen()).append('\n')

        // ---- Registro (respeta los filtros de la pantalla)
        val lineasLog = Diagnostico.lineas(filtro, ultimas, false)
        sb.append("── Registro (últimas ").append(lineasLog.size).append(" de ").append(Diagnostico.cuantas(filtro))
            .append(") · filtros: ").append(filtro.descripcion()).append(" ──\n")
        val cron = lineasLog.joinToString("\n")
        sb.append(if (privado) mascara(cron) else cron).append('\n')
        return sb.toString()
    }
}
