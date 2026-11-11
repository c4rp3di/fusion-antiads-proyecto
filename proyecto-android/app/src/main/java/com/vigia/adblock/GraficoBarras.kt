package com.vigia.adblock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.vigia.core.R
import com.vigia.core.ui.Vistas

/** Gráfico de barras sencillo (sin librerías). Al tocar una barra la resalta y avisa de cuál es. */
class GraficoBarras(contexto: Context) : View(contexto) {

    private var valores = LongArray(0)
    private var etiquetas: List<String> = emptyList()
    private var alSeleccionar: ((Int) -> Unit)? = null
    private var seleccion = -1

    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSeleccion = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBase = Paint()
    private val rect = RectF()

    init {
        pBarra.color = Vistas.color(contexto, R.color.vc_acento)
        pBarra.alpha = 170
        pSeleccion.color = Vistas.color(contexto, R.color.vc_acento)
        pTexto.color = Vistas.color(contexto, R.color.vc_texto_sec)
        pTexto.textSize = Vistas.dp(contexto, 10).toFloat()
        pTexto.textAlign = Paint.Align.CENTER
        pBase.color = Vistas.color(contexto, R.color.vc_superficie_alta)
        pBase.strokeWidth = Vistas.dp(contexto, 1).toFloat()
    }

    fun poner(valores: LongArray, etiquetas: List<String>, alSeleccionar: (Int) -> Unit) {
        this.valores = valores
        this.etiquetas = etiquetas
        this.alSeleccionar = alSeleccionar
        seleccion = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = valores.size
        if (n == 0) return
        val ancho = width.toFloat()
        val abajo = height - Vistas.dp(context, 18).toFloat()
        val arriba = Vistas.dp(context, 4).toFloat()
        var max = 1L
        for (v in valores) if (v > max) max = v
        val hueco = ancho / n
        val grosor = hueco * 0.7f
        val radio = Vistas.dp(context, 3).toFloat()
        val minimo = Vistas.dp(context, 2).toFloat()
        val cada = when {
            n <= 8 -> 1
            n <= 12 -> 2
            n <= 24 -> 6
            else -> 5
        }
        canvas.drawLine(0f, abajo, ancho, abajo, pBase)
        for (i in 0 until n) {
            val izq = i * hueco + (hueco - grosor) / 2f
            var alto = (valores[i].toFloat() / max.toFloat()) * (abajo - arriba)
            if (valores[i] > 0L && alto < minimo) alto = minimo
            rect.set(izq, abajo - alto, izq + grosor, abajo)
            canvas.drawRoundRect(rect, radio, radio, if (i == seleccion) pSeleccion else pBarra)
            if (i % cada == 0 && i < etiquetas.size) {
                canvas.drawText(etiquetas[i], i * hueco + hueco / 2f, height - Vistas.dp(context, 4).toFloat(), pTexto)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (valores.isEmpty()) return false
        if (e.action == MotionEvent.ACTION_DOWN) {
            val i = (e.x / (width.toFloat() / valores.size)).toInt().coerceIn(0, valores.size - 1)
            seleccion = i
            invalidate()
            alSeleccionar?.invoke(i)
            return true
        }
        return super.onTouchEvent(e)
    }
}
