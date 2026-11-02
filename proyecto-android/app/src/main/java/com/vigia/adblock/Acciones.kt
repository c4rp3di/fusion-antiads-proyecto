package com.vigia.adblock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigia.core.R
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Detalle de un evento y de una app, con acciones directas (bloquear, permitir, dejar sin descifrar, filtrar…). */
object Acciones {

    /** Un botón del diálogo: su texto y lo que hace. */
    class Accion(val etiqueta: String, val ejecutar: () -> Unit)

    /** Diálogo con un texto seleccionable y una lista de botones de acción. */
    fun dialogo(a: Activity, titulo: String, texto: String, acciones: List<Accion>) {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Vistas.dp(a, 20), Vistas.dp(a, 8), Vistas.dp(a, 20), Vistas.dp(a, 8))
        val t = TextView(a)
        t.text = texto
        t.setTextIsSelectable(true)
        t.textSize = 13f
        t.setTextColor(Vistas.color(a, R.color.vc_texto))
        t.setPadding(0, 0, 0, Vistas.dp(a, 12))
        col.addView(t)
        val scroll = ScrollView(a)
        scroll.addView(col)
        val dlg = MaterialAlertDialogBuilder(a).setTitle(titulo).setView(scroll).setNegativeButton("Cerrar", null).create()
        for (ac in acciones) {
            col.addView(Vistas.boton(a, ac.etiqueta, TipoBoton.TONAL) {
                dlg.dismiss()
                ac.ejecutar()
            }, Vistas.anchoCompleto(a, 6))
        }
        dlg.show()
    }

    private fun etiquetaApp(a: Activity, app: String): String = try {
        a.packageManager.getApplicationInfo(app, 0).loadLabel(a.packageManager).toString() + " (" + app + ")"
    } catch (e: Exception) {
        app
    }

    private fun appValida(app: String) = app.isNotEmpty() && app != "desconocida"

    /** «dominio:443» -> «dominio»; null si no es un nombre de dominio (por ejemplo una IP). */
    private fun dominioBase(d: String): String? {
        val b = d.substringBefore(':').lowercase()
        if (b.isEmpty() || !b.contains('.')) return null
        if (b.all { it.isDigit() || it == '.' }) return null
        return b
    }

    private fun copiar(a: Activity, texto: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AntiAds", texto))
        Toast.makeText(a, "Copiado", Toast.LENGTH_SHORT).show()
    }

    fun estaExenta(a: Context, app: String): Boolean = Ajustes.mitmExentas(a).contains(app)

    private fun ponerExenta(a: Activity, app: String, exenta: Boolean) {
        val p = PoliticaAdBlock.actual
        if (p != null) {
            if (exenta) p.agregar(app) else p.quitar(app)
        } else {
            val s = HashSet(Ajustes.mitmExentas(a))
            if (exenta) s.add(app) else s.remove(app)
            Ajustes.setMitmExentas(a, s)
        }
        Toast.makeText(a, if (exenta) "Se dejará sin descifrar (conexiones nuevas)" else "Se volverá a descifrar (conexiones nuevas)", Toast.LENGTH_LONG).show()
    }

    /** Pantalla de detalle de una línea del registro. [refrescar] repinta la pantalla tras una acción. */
    fun mostrarEvento(a: Activity, s: Diagnostico.Snap, filtro: Diagnostico.Filtro, refrescar: () -> Unit) {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append(Presentacion.icono(s.cat)).append(' ').append(Presentacion.nombre(s.cat, s.sub)).append('\n')
        sb.append("Hora: ").append(f.format(Date(s.ts)))
        if (s.n > 1) sb.append(" – ").append(f.format(Date(s.ultimo))).append(" (").append(s.n).append(" veces)")
        sb.append('\n')
        if (s.app.isNotEmpty()) sb.append("App: ").append(etiquetaApp(a, s.app)).append('\n')
        if (s.dominio.isNotEmpty()) sb.append("Dominio: ").append(s.dominio).append('\n')
        if (s.texto.isNotEmpty()) sb.append("Detalle: ").append(s.texto).append('\n')
        sb.append("\n").append(Presentacion.explicacion(s.cat, s.sub))

        val acc = ArrayList<Accion>()
        val dom = dominioBase(s.dominio)
        if (dom != null) {
            acc.add(Accion("Bloquear «" + dom + "»") {
                Ajustes.agregarBloqueo(a, dom)
                ListasManager.reconstruirAsync(a)
                Toast.makeText(a, "Bloqueado: " + dom, Toast.LENGTH_SHORT).show()
            })
            acc.add(Accion("Permitir «" + dom + "»") {
                Ajustes.agregarPermitido(a, dom)
                ListasManager.reconstruirAsync(a)
                Toast.makeText(a, "Permitido: " + dom, Toast.LENGTH_SHORT).show()
            })
            acc.add(Accion("Filtrar el registro por este dominio") { filtro.texto = dom; refrescar() })
        }
        if (appValida(s.app)) {
            acc.add(Accion("Ver ficha de la app") { mostrarFicha(a, s.app, filtro, refrescar) })
            acc.add(Accion("Filtrar el registro por esta app") { filtro.app = s.app; refrescar() })
            if (!PoliticaAdBlock.NAVEGADORES.contains(s.app)) {
                if (estaExenta(a, s.app)) acc.add(Accion("Volver a descifrar esta app") { ponerExenta(a, s.app, false) })
                else acc.add(Accion("Dejar esta app sin descifrar") { ponerExenta(a, s.app, true) })
            }
        }
        acc.add(Accion("Copiar esta línea") { copiar(a, Presentacion.linea(s, f)) })
        dialogo(a, "Detalle", sb.toString(), acc)
    }

    private fun bytes(n: Long): String = when {
        n < 1024L -> n.toString() + " B"
        n < 1024L * 1024L -> (n / 1024L).toString() + " KB"
        else -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    }

    /** Todo lo que se sabe de una app: estado del MitM, conexiones, bloqueos, DNS, rechazos y errores. */
    fun textoFicha(a: Activity, app: String): String {
        val sb = StringBuilder()
        sb.append(etiquetaApp(a, app)).append("\n\n")
        sb.append("Descifrado: ").append(
            when {
                !MitmEstado.activo -> "el Modo Profundo no está activo"
                PoliticaAdBlock.NAVEGADORES.contains(app) -> "navegador: siempre se intenta descifrar"
                estaExenta(a, app) -> "SIN descifrar (rechaza el certificado o la dejaste fuera)"
                else -> "se intenta descifrar; si rechaza el certificado quedará sin descifrar"
            }
        ).append("\n\n")

        val filas = Conexiones.filasDe(app).sortedByDescending { it.bin + it.bout }
        sb.append("Conexiones: ")
        if (filas.isEmpty()) sb.append("sin datos (activa «Todas las conexiones»)\n")
        else {
            sb.append(filas.sumOf { it.n }).append(" registros · ↓").append(bytes(filas.sumOf { it.bin })).append(" ↑").append(bytes(filas.sumOf { it.bout }))
                .append(" · ").append(filas.size).append(" destinos\n")
            for (f in filas.take(8)) sb.append("  ").append(f.proto).append(' ').append(f.destino).append(" · ↓").append(bytes(f.bin)).append(" ↑").append(bytes(f.bout)).append('\n')
        }

        val propios = Diagnostico.snaps(Diagnostico.Filtro().also { it.app = app }, 5000)
        val bloqueos = propios.filter { it.cat == "bloqueo" }.sortedByDescending { it.n }
        sb.append("\nBloqueos: ").append(bloqueos.sumOf { it.n }).append(" en ").append(bloqueos.size).append(" dominios\n")
        for (b in bloqueos.take(10)) sb.append("  ").append(b.dominio).append("  ×").append(b.n).append("  [").append(b.sub).append("]\n")

        val dns = propios.filter { it.cat == "dns" }.sortedByDescending { it.n }
        if (dns.isNotEmpty()) {
            sb.append("\nConsultas DNS (registro DNS por app): ").append(dns.size).append(" dominios\n")
            for (d in dns.take(8)) sb.append("  ").append(d.dominio).append("  ×").append(d.n).append("  ").append(d.texto).append('\n')
        }
        val rastro = Rastreadores.ranking().firstOrNull { it.app == app }
        if (rastro != null) sb.append("\nRastreadores: ").append(rastro.destinos.size).append(" destinos de tus listas contactados (").append(rastro.veces).append(" veces)\n")

        val problemas = propios.filter { it.cat == "cert" || it.cat == "error" }.takeLast(8)
        if (problemas.isNotEmpty()) {
            sb.append("\nAvisos y errores recientes:\n")
            val f = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (p in problemas) sb.append("  ").append(Presentacion.linea(p, f)).append('\n')
        }
        return sb.toString()
    }

    fun mostrarFicha(a: Activity, app: String, filtro: Diagnostico.Filtro, refrescar: () -> Unit) {
        val acc = ArrayList<Accion>()
        acc.add(Accion("Filtrar el registro por esta app") { filtro.app = app; refrescar() })
        if (!PoliticaAdBlock.NAVEGADORES.contains(app) && appValida(app)) {
            if (estaExenta(a, app)) acc.add(Accion("Volver a descifrar esta app") { ponerExenta(a, app, false) })
            else acc.add(Accion("Dejar esta app sin descifrar") { ponerExenta(a, app, true) })
        }
        acc.add(Accion("Copiar la ficha") { copiar(a, textoFicha(a, app)) })
        dialogo(a, "Ficha de la app", textoFicha(a, app), acc)
    }
}
