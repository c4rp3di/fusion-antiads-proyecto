package com.vigia.adblock

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vigia.core.R
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import com.vigia.core.util.Portapapeles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Estadísticas de bloqueo: periodo (hoy, 7 o 30 días), gráfico por horas o por días, desglose por capa y lista de
 * dominios o apps con buscador. Tocar un dominio permite dejar de bloquearlo.
 */
class EstadisticasActivity : ActividadBase() {

    private val numeros: NumberFormat = NumberFormat.getIntegerInstance()

    private var dias = 1
    private var verApps = false
    private var busqueda = ""
    private var firma = ""
    private var ultima: Estadisticas.Consulta? = null

    private lateinit var filaPeriodos: LinearLayout
    private lateinit var tarjetaResumen: LinearLayout
    private lateinit var tarjetaCapas: LinearLayout
    private lateinit var filaVista: LinearLayout
    private lateinit var campoBusqueda: EditText
    private lateinit var listaFilas: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Estadisticas.iniciar(this)

        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Estadísticas"), Vistas.anchoCompleto(this, 12))

        filaPeriodos = Vistas.fila(this)
        raiz.addView(filaPeriodos, Vistas.anchoCompleto(this, 12))

        tarjetaResumen = Vistas.tarjeta(this)
        raiz.addView(tarjetaResumen, Vistas.anchoCompleto(this, 12))

        tarjetaCapas = Vistas.tarjeta(this)
        raiz.addView(tarjetaCapas, Vistas.anchoCompleto(this, 12))

        val tarjetaLista = Vistas.tarjeta(this)
        tarjetaLista.addView(Vistas.subtitulo(this, "Más bloqueados"), Vistas.anchoCompleto(this, 8))
        filaVista = Vistas.fila(this)
        tarjetaLista.addView(filaVista, Vistas.anchoCompleto(this, 8))
        campoBusqueda = EditText(this)
        campoBusqueda.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        campoBusqueda.setSingleLine(true)
        campoBusqueda.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                busqueda = (s?.toString() ?: "").trim()
                firma = ""
                refrescar()
            }
        })
        tarjetaLista.addView(campoBusqueda, Vistas.anchoCompleto(this, 8))
        listaFilas = LinearLayout(this)
        listaFilas.orientation = LinearLayout.VERTICAL
        tarjetaLista.addView(listaFilas, Vistas.anchoCompleto(this, 0))
        raiz.addView(tarjetaLista, Vistas.anchoCompleto(this, 12))

        raiz.addView(Vistas.boton(this, "Copiar lista (CSV)", TipoBoton.TONAL) { copiarCsv() }, Vistas.anchoCompleto(this, 0))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)

        pintarPeriodos()
        pintarVista()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refrescar()
                    delay(3000L)
                }
            }
        }
    }

    // ------------------------------------------------------------------ controles

    private fun pintarPeriodos() {
        filaPeriodos.removeAllViews()
        for ((n, nombre) in listOf(1 to "Hoy", 7 to "7 días", 30 to "30 días")) {
            val b = Vistas.boton(this, nombre, if (n == dias) TipoBoton.PRIMARIO else TipoBoton.TONAL) {
                dias = n
                firma = ""
                pintarPeriodos()
                refrescar()
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = Vistas.dp(this, 8)
            filaPeriodos.addView(b, lp)
        }
    }

    private fun pintarVista() {
        filaVista.removeAllViews()
        for ((esApps, nombre) in listOf(false to "Dominios", true to "Apps")) {
            val b = Vistas.boton(this, nombre, if (esApps == verApps) TipoBoton.PRIMARIO else TipoBoton.TONAL) {
                verApps = esApps
                firma = ""
                pintarVista()
                refrescar()
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = Vistas.dp(this, 8)
            filaVista.addView(b, lp)
        }
        campoBusqueda.hint = if (verApps) "Buscar app" else "Buscar dominio"
    }

    // ------------------------------------------------------------------ pintado

    private fun refrescar() {
        val c = Estadisticas.consultar(dias)
        ultima = c
        val nueva = c.dias.toString() + ":" + c.total + ":" + verApps + ":" + busqueda
        if (nueva == firma) return
        firma = nueva
        pintarResumen(c)
        pintarCapas(c)
        pintarLista(c)
    }

    private fun pintarResumen(c: Estadisticas.Consulta) {
        tarjetaResumen.removeAllViews()
        val grande = Vistas.titulo(this, numeros.format(c.total))
        grande.textSize = 40f
        tarjetaResumen.addView(grande, Vistas.anchoCompleto(this, 0))
        var sub = if (c.dias == 1) "anuncios bloqueados hoy" else "anuncios bloqueados en los últimos " + c.dias + " días"
        if (c.dias > 1) sub += " · media de " + numeros.format(c.total / c.dias) + " al día"
        tarjetaResumen.addView(Vistas.texto(this, sub, true), Vistas.anchoCompleto(this, 12))

        val detalle = Vistas.texto(this, textoPico(c), true)
        val grafico = GraficoBarras(this)
        grafico.poner(c.serie, etiquetas(c)) { i -> detalle.text = descripcion(c, i) }
        tarjetaResumen.addView(grafico, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Vistas.dp(this, 130)))
        val lp = Vistas.anchoCompleto(this, 0)
        lp.topMargin = Vistas.dp(this, 8)
        tarjetaResumen.addView(detalle, lp)
    }

    private fun pintarCapas(c: Estadisticas.Consulta) {
        tarjetaCapas.removeAllViews()
        tarjetaCapas.addView(Vistas.subtitulo(this, "Por capa de bloqueo"), Vistas.anchoCompleto(this, 8))
        var suma = 0L
        for (v in c.capas) suma += v
        if (suma == 0L) {
            tarjetaCapas.addView(Vistas.texto(this, "Todavía no hay desglose en este periodo.", true))
            return
        }
        for (i in c.capas.indices) {
            if (c.capas[i] == 0L) continue
            val pct = (c.capas[i] * 100 / suma).toInt()
            tarjetaCapas.addView(filaBarra(Estadisticas.NOMBRES_CAPA[i], c.capas[i], pct), Vistas.anchoCompleto(this, 8))
        }
        if (suma < c.total) {
            tarjetaCapas.addView(Vistas.texto(this, "No incluye los bloqueos anteriores a esta versión.", true))
        }
    }

    private fun filaBarra(nombre: String, valor: Long, pct: Int): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Vistas.texto(this, nombre + " · " + numeros.format(valor) + " (" + pct + "%)"), Vistas.anchoCompleto(this, 4))
        val barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        val lleno = View(this)
        lleno.setBackgroundColor(Vistas.color(this, R.color.vc_acento))
        barra.addView(lleno, LinearLayout.LayoutParams(0, Vistas.dp(this, 6), pct.toFloat()))
        val vacio = View(this)
        vacio.setBackgroundColor(Vistas.color(this, R.color.vc_superficie_alta))
        barra.addView(vacio, LinearLayout.LayoutParams(0, Vistas.dp(this, 6), (100 - pct).toFloat()))
        col.addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Vistas.dp(this, 6)))
        return col
    }

    private fun pintarLista(c: Estadisticas.Consulta) {
        listaFilas.removeAllViews()
        val todos = filtrar(c)
        if (todos.isEmpty()) {
            val vacio = if (busqueda.isNotEmpty()) "Nada coincide con la búsqueda." else if (verApps) {
                "Sin datos de apps. La app de origen solo se conoce con el Modo Profundo o la captura de detalle."
            } else {
                "Todavía no se ha bloqueado nada en este periodo."
            }
            listaFilas.addView(Vistas.texto(this, vacio, true))
            return
        }
        for (e in todos.take(MAX_FILAS)) {
            val pct = if (c.total > 0L) (e.second.toLong() * 100 / c.total).toInt() else 0
            val porcentaje = if (pct == 0) "<1%" else "$pct%"
            val nombre = if (verApps) nombreApp(e.first) else e.first
            val fila = Vistas.texto(this, nombre + "  ·  " + numeros.format(e.second.toLong()) + "  (" + porcentaje + ")")
            fila.setPadding(0, Vistas.dp(this, 6), 0, Vistas.dp(this, 6))
            if (!verApps) fila.setOnClickListener { permitir(e.first) }
            listaFilas.addView(fila)
        }
        if (todos.size > MAX_FILAS) {
            val nota = "Mostrando " + MAX_FILAS + " de " + numeros.format(todos.size.toLong()) + ". Usa el buscador para acotar."
            listaFilas.addView(Vistas.texto(this, nota, true))
        }
        if (!verApps && c.dias > 1) {
            listaFilas.addView(Vistas.texto(this, "Los días pasados guardan sus 100 dominios más bloqueados.", true))
        } else if (!verApps) {
            listaFilas.addView(Vistas.texto(this, "Toca un dominio si una app deja de funcionar para permitirlo.", true))
        }
    }

    private fun filtrar(c: Estadisticas.Consulta): List<Pair<String, Int>> {
        val base = if (verApps) c.apps else c.dominios
        if (busqueda.isEmpty()) return base
        return base.filter {
            it.first.contains(busqueda, ignoreCase = true) || (verApps && nombreApp(it.first).contains(busqueda, ignoreCase = true))
        }
    }

    private fun nombreApp(paquete: String): String = try {
        packageManager.getApplicationInfo(paquete, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        paquete
    }

    // ------------------------------------------------------------------ gráfico: textos

    private fun etiquetas(c: Estadisticas.Consulta): List<String> {
        if (c.dias == 1) return (0 until 24).map { it.toString() }
        val formato = SimpleDateFormat(if (c.dias <= 7) "EEE" else "d", Locale.getDefault())
        return (0 until c.dias).map { i ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -(c.dias - 1 - i))
            formato.format(cal.time)
        }
    }

    private fun descripcion(c: Estadisticas.Consulta, i: Int): String {
        val n = numeros.format(c.serie[i])
        if (c.dias == 1) return String.format(Locale.getDefault(), "%02d:00–%02d:59 · %s bloqueos", i, i, n)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(c.dias - 1 - i))
        return SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(cal.time) + " · " + n + " bloqueos"
    }

    private fun textoPico(c: Estadisticas.Consulta): String {
        var mejor = 0
        for (i in c.serie.indices) if (c.serie[i] > c.serie[mejor]) mejor = i
        if (c.serie[mejor] == 0L) return "Sin bloqueos en este periodo."
        val que = if (c.dias == 1) "Hora" else "Día"
        return que + " con más bloqueos: " + descripcion(c, mejor) + ". Toca una barra para ver el detalle."
    }

    // ------------------------------------------------------------------ acciones

    private fun permitir(dominio: String) {
        Dialogos.confirmar(
            this, "Permitir dominio",
            "¿Dejar de bloquear " + dominio + " y sus subdominios? Podrás revertirlo en Ajustes › Dominios permitidos.", "Permitir"
        ) {
            Ajustes.agregarPermitido(this, dominio)
            ListasManager.reconstruirAsync(this)
            Toast.makeText(this, dominio + " permitido.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copiarCsv() {
        val c = ultima ?: return
        val sb = StringBuilder(if (verApps) "app,bloqueos\n" else "dominio,bloqueos\n")
        for (e in filtrar(c)) sb.append(e.first).append(',').append(e.second).append('\n')
        Portapapeles.copiar(this, sb.toString(), "AntiAds estadísticas")
        Toast.makeText(this, "Lista copiada", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_FILAS = 50
    }
}
