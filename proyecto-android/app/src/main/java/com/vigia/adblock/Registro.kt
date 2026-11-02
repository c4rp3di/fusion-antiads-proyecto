package com.vigia.adblock

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Registro ÚNICO de todo lo que ocurre en AntiAds, solo en memoria (nada se escribe en disco salvo «Guardar informe»).
 * Cada evento tiene hora, categoría, subtipo, app, dominio, texto y gravedad. Los que se repiten mucho (bloqueos,
 * conexiones, consultas DNS) se agrupan por app+dominio: una sola línea con «×n», para que no crezca ni sature la pantalla.
 */
object Diagnostico {
    const val INFO = 0
    const val AVISO = 1
    const val ERROR = 2

    class Evento(
        val ts: Long, val cat: String, val sub: String, val app: String, val dominio: String,
        var texto: String, val grav: Int, val clave: String = ""
    ) {
        var n = 1
        var ultimo = ts
    }

    /** Copia inmutable de un evento, para pintarla sin que cambie por debajo. [clave] solo existe en los agrupados. */
    class Snap(
        val ts: Long, val ultimo: Long, val cat: String, val sub: String, val app: String, val dominio: String,
        val texto: String, val grav: Int, val n: Int, val clave: String
    )

    /** Filtros de la pantalla y del informe. */
    class Filtro {
        /** Categorías visibles; null = todas. */
        var cats: Set<String>? = null
        var gravMin = INFO
        var ventanaMs = 0L
        var app: String? = null
        var texto = ""

        fun activo(): Boolean = cats != null || gravMin > INFO || ventanaMs > 0L || app != null || texto.isNotEmpty()

        fun limpiar() {
            cats = null; gravMin = INFO; ventanaMs = 0L; app = null; texto = ""
        }

        fun descripcion(): String {
            val p = ArrayList<String>()
            val cs = cats
            if (cs != null) p.add("categorías: " + CATEGORIAS.filter { cs.contains(it.first) }.joinToString(", ") { it.second })
            if (gravMin == AVISO) p.add("avisos y errores")
            if (gravMin == ERROR) p.add("solo errores")
            if (ventanaMs > 0L) p.add("últimos " + (ventanaMs / 60000L) + " min")
            val a = app
            if (a != null) p.add("app: " + a)
            if (texto.isNotEmpty()) p.add("texto: «" + texto + "»")
            return if (p.isEmpty()) "ninguno" else p.joinToString(" · ")
        }
    }

    val CATEGORIAS = listOf(
        "bloqueo" to "Bloqueos",
        "inventada" to "Respuestas inventadas",
        "pagina" to "Páginas",
        "peticion" to "Peticiones",
        "conexion" to "Conexiones",
        "dns" to "DNS por app",
        "cert" to "Certificado",
        "sistema" to "Sistema",
        "error" to "Errores"
    )

    private const val MAX_RING = 2500
    private const val MAX_AGRUPADOS = 1500
    private val ring = ArrayDeque<Evento>()
    private val agrupados = object : LinkedHashMap<String, Evento>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Evento>?): Boolean = size > MAX_AGRUPADOS
    }
    private val tipos = HashMap<String, Int>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US) // no es seguro entre hilos: solo se usa dentro de métodos @Synchronized

    private class Clase(val cat: String, val sub: String, val grav: Int)

    private fun clasificar(tipo: String): Clase = when {
        tipo == "clasico" -> Clase("bloqueo", "proxy", INFO)
        tipo == "dns" || tipo == "sni" || tipo == "ip" -> Clase("bloqueo", tipo, INFO)
        tipo == "juguete" || tipo == "vast" || tipo == "vmap" || tipo == "hls" -> Clase("inventada", tipo, INFO)
        tipo.startsWith("stub") -> Clase("inventada", "stub", INFO)
        tipo == "scriptlet" || tipo == "css" -> Clase("pagina", tipo, INFO)
        tipo.startsWith("pág:") ->
            Clase("pagina", tipo.removePrefix("pág:"), if (tipo == "pág:js" || tipo == "pág:csp" || tipo == "pág:recurso") AVISO else INFO)
        tipo == "req" -> Clase("peticion", "http", INFO)
        tipo == "conexion" -> Clase("conexion", "red", INFO)
        tipo == "consulta" -> Clase("dns", "consulta", INFO)
        tipo == "rechazo" || tipo == "exento" -> Clase("cert", tipo, AVISO)
        tipo == "navegador" || tipo == "permitido" -> Clase("cert", tipo, INFO)
        tipo == "error" -> Clase("error", "proxy", ERROR)
        else -> Clase("sistema", tipo, INFO)
    }

    /**
     * Anota un evento. [tipo] es el nombre corto de siempre («clasico», «juguete», «rechazo»…) y de él salen categoría,
     * subtipo y gravedad; [grav] solo hace falta para forzar otra.
     */
    @Synchronized
    fun anotar(tipo: String, texto: String, app: String = "", dominio: String = "", grav: Int = -1) {
        tipos[tipo] = (tipos[tipo] ?: 0) + 1
        val c = clasificar(tipo)
        val g = if (grav >= 0) grav else c.grav
        val ahora = System.currentTimeMillis()
        val t = texto.take(160)
        if (c.cat == "bloqueo" || c.cat == "conexion" || c.cat == "dns") {
            val clave = c.cat + "|" + c.sub + "|" + app + "|" + dominio
            val e = agrupados[clave]
            if (e != null) {
                e.n++
                e.ultimo = ahora
                e.texto = t
            } else {
                agrupados[clave] = Evento(ahora, c.cat, c.sub, app, dominio, t, g, clave)
            }
        } else {
            if (ring.size >= MAX_RING) ring.removeFirst()
            ring.addLast(Evento(ahora, c.cat, c.sub, app, dominio, t, g))
        }
    }

    @Synchronized
    fun porTipo(): Map<String, Int> = HashMap(tipos)

    @Synchronized
    fun vaciar() {
        ring.clear()
        agrupados.clear()
        tipos.clear()
    }

    @Synchronized
    private fun filtrados(f: Filtro): List<Evento> {
        val desde = if (f.ventanaMs > 0L) System.currentTimeMillis() - f.ventanaMs else 0L
        val q = f.texto.lowercase()
        val cs = f.cats
        val ap = f.app
        val todos = ArrayList<Evento>(ring.size + agrupados.size)
        todos.addAll(ring)
        todos.addAll(agrupados.values)
        return todos.filter { e ->
            (cs == null || cs.contains(e.cat)) && e.grav >= f.gravMin && e.ultimo >= desde && (ap == null || e.app == ap) &&
                (q.isEmpty() || (e.texto + " " + e.dominio + " " + e.app + " " + e.sub).lowercase().contains(q))
        }.sortedBy { it.ultimo }
    }

    private fun corto(app: String): String = app.split('.').takeLast(2).joinToString(".")

    private fun formatear(e: Evento, abreviar: Boolean): String {
        val sb = StringBuilder()
        sb.append(hora.format(Date(e.ultimo))).append("  ").append((e.cat + "/" + e.sub).padEnd(16)).append(' ')
        if (e.app.isNotEmpty()) sb.append(if (abreviar) corto(e.app) else e.app).append("  ")
        if (e.dominio.isNotEmpty()) sb.append(e.dominio)
        if (e.texto.isNotEmpty() && e.texto != "bloqueado") sb.append(if (e.dominio.isNotEmpty()) "  " else "").append(e.texto)
        if (e.n > 1) sb.append("  ×").append(e.n)
        return sb.toString().take(220)
    }

    /** Las últimas [max] líneas que pasan el filtro, de más antigua a más reciente. */
    @Synchronized
    fun lineas(f: Filtro, max: Int, abreviarApp: Boolean): List<String> =
        filtrados(f).takeLast(max).map { formatear(it, abreviarApp) }

    @Synchronized
    fun cuantas(f: Filtro): Int = filtrados(f).size

    private fun copia(e: Evento) = Snap(e.ts, e.ultimo, e.cat, e.sub, e.app, e.dominio, e.texto, e.grav, e.n, e.clave)

    /** Las últimas [max] instantáneas que pasan el filtro, de más antigua a más reciente. */
    @Synchronized
    fun snaps(f: Filtro, max: Int): List<Snap> = filtrados(f).takeLast(max).map { copia(it) }

    /** Veces que ha ocurrido cada «categoría/subtipo». */
    @Synchronized
    fun contarPorSub(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (e in ring) m[e.cat + "/" + e.sub] = (m[e.cat + "/" + e.sub] ?: 0) + 1
        for (e in agrupados.values) m[e.cat + "/" + e.sub] = (m[e.cat + "/" + e.sub] ?: 0) + e.n
        return m
    }

    /** Cuenta actual de cada evento agrupado (para medir lo ocurrido desde un momento dado). */
    @Synchronized
    fun nPorClave(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (e in agrupados.values) m[e.clave] = e.n
        return m
    }

    /** Bloqueos con app conocida: (app, dominio, veces). */
    @Synchronized
    fun bloqueosPorApp(): List<Triple<String, String, Int>> =
        agrupados.values.filter { it.cat == "bloqueo" && it.app.isNotEmpty() }.map { Triple(it.app, it.dominio, it.n) }

    /** Compatibilidad: las últimas [ultimas] líneas sin filtrar. */
    @Synchronized
    fun texto(ultimas: Int): String = lineas(Filtro(), ultimas, false).joinToString("\n")

    /** Eventos por categoría (un agrupado cuenta tantos como veces se repitió). */
    @Synchronized
    fun contarPorCategoria(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (e in ring) m[e.cat] = (m[e.cat] ?: 0) + 1
        for (e in agrupados.values) m[e.cat] = (m[e.cat] ?: 0) + e.n
        return m
    }

    @Synchronized
    fun apps(): List<String> {
        val s = HashSet<String>()
        for (e in ring) if (e.app.isNotEmpty()) s.add(e.app)
        for (e in agrupados.values) if (e.app.isNotEmpty()) s.add(e.app)
        return s.sorted()
    }

    /** Dominios más bloqueados: (dominio, capa, veces). */
    @Synchronized
    fun topBloqueos(n: Int): List<Triple<String, String, Int>> {
        val m = HashMap<String, Int>()
        for (e in agrupados.values) if (e.cat == "bloqueo") m[e.dominio + "|" + e.sub] = (m[e.dominio + "|" + e.sub] ?: 0) + e.n
        return m.entries.sortedByDescending { it.value }.take(n).map { Triple(it.key.substringBefore('|'), it.key.substringAfter('|'), it.value) }
    }
}
