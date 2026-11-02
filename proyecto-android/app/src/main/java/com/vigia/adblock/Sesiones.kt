package com.vigia.adblock

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sesiones de prueba: «Iniciar prueba» activa las capturas, «Terminar» las deja como estaban y resume qué ocurrió SOLO en ese
 * rato. Se guardan en memoria las últimas para poder compararlas (por ejemplo, con y sin una técnica).
 */
object Sesiones {
    private val TECNICAS = listOf("clasico", "juguete", "vast", "stubs", "yt", "skip", "css", "hls")
    private val CAPTURAS = listOf("conexiones", "peticiones", "pagina")

    private class Base(
        val ts: Long,
        val tecnicas: Map<String, Int>,
        val subs: Map<String, Int>,
        val claves: Map<String, Int>,
        val descifradas: Int,
        val rechazos: Int,
        val capturasPrevias: Map<String, Boolean>
    )

    class Resumen(
        val nombre: String,
        val inicio: Long,
        val fin: Long,
        val tecnicas: Map<String, Int>,
        val subs: Map<String, Int>,
        val descifradas: Int,
        val rechazos: Int,
        val avisos: List<String>,
        val bloqueos: List<Pair<String, Int>>
    )

    @Volatile
    private var base: Base? = null
    private val historial = ArrayList<Resumen>()
    private var contador = 0

    fun activa(): Boolean = base != null
    fun inicioMs(): Long = base?.ts ?: 0L

    @Synchronized
    fun ultimas(): List<Resumen> = ArrayList(historial)

    /** Empieza una prueba: activa las capturas y apunta cómo estaban los contadores. */
    fun iniciar(c: Context) {
        val previas = HashMap<String, Boolean>()
        for (id in CAPTURAS) {
            previas[id] = Laboratorio.activa(c, id)
            Laboratorio.poner(c, id, true)
        }
        val tec = HashMap<String, Int>()
        for (t in TECNICAS) tec[t] = Laboratorio.veces(t)
        base = Base(System.currentTimeMillis(), tec, Diagnostico.contarPorSub(), Diagnostico.nPorClave(), MitmEstado.descifradas.get(), MitmEstado.rechazos.get(), previas)
        Diagnostico.anotar("sistema", "Prueba iniciada (capturas activadas)")
    }

    /** Termina la prueba, restaura las capturas y devuelve el resumen (null si no había ninguna). */
    @Synchronized
    fun terminar(c: Context): Resumen? {
        val b = base ?: return null
        base = null
        for ((id, v) in b.capturasPrevias) Laboratorio.poner(c, id, v)
        val ahora = System.currentTimeMillis()

        val tec = HashMap<String, Int>()
        for (t in TECNICAS) tec[t] = Laboratorio.veces(t) - (b.tecnicas[t] ?: 0)
        val subs = HashMap<String, Int>()
        for ((k, v) in Diagnostico.contarPorSub()) {
            val d = v - (b.subs[k] ?: 0)
            if (d > 0) subs[k] = d
        }
        // Bloqueos de la prueba: los agrupados acumulan desde que existen, así que se resta lo que ya había al empezar.
        val porDominio = HashMap<String, Int>()
        for (s in Diagnostico.snaps(Diagnostico.Filtro().also { it.cats = setOf("bloqueo") }, 5000)) {
            val d = s.n - (b.claves[s.clave] ?: 0)
            if (d > 0) porDominio[s.dominio] = (porDominio[s.dominio] ?: 0) + d
        }
        val bloqueos = porDominio.entries.sortedByDescending { it.value }.take(10).map { Pair(it.key, it.value) }
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        val avisos = Diagnostico.snaps(
            Diagnostico.Filtro().also { it.gravMin = Diagnostico.AVISO; it.ventanaMs = maxOf(1L, ahora - b.ts) }, 200
        ).takeLast(10).map { Presentacion.linea(it, f) }

        contador++
        val r = Resumen("Prueba " + contador, b.ts, ahora, tec, subs, MitmEstado.descifradas.get() - b.descifradas, MitmEstado.rechazos.get() - b.rechazos, avisos, bloqueos)
        historial.add(r)
        while (historial.size > 6) historial.removeAt(0)
        Diagnostico.anotar("sistema", r.nombre + " terminada")
        return r
    }

    fun texto(r: Resumen): String {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        val seg = (r.fin - r.inicio) / 1000L
        val sb = StringBuilder()
        sb.append(r.nombre).append(" · ").append(f.format(Date(r.inicio))).append(" → ").append(f.format(Date(r.fin))).append(" (").append(seg / 60L).append(" min ").append(seg % 60L).append(" s)\n\n")
        sb.append("Conexiones descifradas: ").append(r.descifradas).append(" · rechazos del certificado: ").append(r.rechazos).append("\n\n")
        sb.append("Técnicas (veces que actuaron):\n")
        sb.append("  ").append(TECNICAS.joinToString(" · ") { it + " " + (r.tecnicas[it] ?: 0) }).append("\n\n")
        sb.append("Eventos por tipo:\n")
        if (r.subs.isEmpty()) sb.append("  (ninguno)\n")
        for ((k, v) in r.subs.entries.sortedByDescending { it.value }.take(14)) sb.append("  ").append(k).append("  ×").append(v).append('\n')
        sb.append("\nDominios más bloqueados:\n")
        if (r.bloqueos.isEmpty()) sb.append("  (ninguno)\n")
        for ((d, n) in r.bloqueos) sb.append("  ").append(d).append("  ×").append(n).append('\n')
        sb.append("\nAvisos y errores:\n")
        if (r.avisos.isEmpty()) sb.append("  (ninguno)\n")
        for (a in r.avisos) sb.append("  ").append(a).append('\n')
        return sb.toString()
    }

    /** Compara dos pruebas lado a lado: «antes → después (diferencia)». */
    fun comparar(a: Resumen, b: Resumen): String {
        val sb = StringBuilder()
        sb.append(a.nombre).append("  →  ").append(b.nombre).append("\n\n")
        fun fila(nombre: String, x: Int, y: Int) {
            if (x == 0 && y == 0) return
            sb.append(nombre.padEnd(24)).append(x.toString().padStart(6)).append("  →").append(y.toString().padStart(6)).append("   (")
                .append(if (y - x > 0) "+" else "").append(y - x).append(")\n")
        }
        fila("descifradas", a.descifradas, b.descifradas)
        fila("rechazos certificado", a.rechazos, b.rechazos)
        sb.append("\nTécnicas:\n")
        for (t in TECNICAS) fila(t, a.tecnicas[t] ?: 0, b.tecnicas[t] ?: 0)
        sb.append("\nEventos:\n")
        for (k in (a.subs.keys + b.subs.keys).toSortedSet()) fila(k, a.subs[k] ?: 0, b.subs[k] ?: 0)
        sb.append("\nAvisos: ").append(a.avisos.size).append("  →  ").append(b.avisos.size).append('\n')
        return sb.toString()
    }
}
