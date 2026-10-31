package com.vigia.adblock

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import java.text.NumberFormat

/**
 * Qué dominios consulta cada app y si se bloquearon. Sirve para cazar los anuncios de una app concreta: se abre la
 * app, se mira qué dominios nuevos aparecen y se bloquean desde aquí con un toque.
 */
class RegistroDnsActivity : ActividadBase() {

    private lateinit var estado: android.widget.TextView
    private lateinit var lista: LinearLayout
    private val expandidas = HashSet<String>()
    private var firma = ""
    private val numeros = NumberFormat.getIntegerInstance()
    private val ui = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            pintar()
            ui.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Registro DNS por app"), Vistas.anchoCompleto(this, 4))
        raiz.addView(
            Vistas.texto(
                this,
                "Abre la app que te enseña anuncios, vuelve aquí y mira qué dominios ha consultado. Toca un dominio para " +
                    "bloquearlo (✓ = resuelto, ✕ = bloqueado). Solo vive en memoria: no se guarda nada en el móvil.",
                true
            ), Vistas.anchoCompleto(this, 12)
        )
        estado = Vistas.texto(this, "", true)
        raiz.addView(estado, Vistas.anchoCompleto(this, 8))
        raiz.addView(Vistas.boton(this, "Vaciar registro", TipoBoton.TONAL) {
            RegistroDns.vaciar()
            firma = ""
            pintar()
        }, Vistas.anchoCompleto(this, 12))
        lista = LinearLayout(this)
        lista.orientation = LinearLayout.VERTICAL
        raiz.addView(lista, Vistas.anchoCompleto(this, 0))
        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tic)
    }

    override fun onPause() {
        ui.removeCallbacks(tic)
        super.onPause()
    }

    private fun etiqueta(app: String): String = when {
        app == "desconocida" -> "App desconocida (Android anterior a 10)"
        app.startsWith("uid:") -> "UID " + app.removePrefix("uid:")
        else -> try {
            packageManager.getApplicationInfo(app, 0).loadLabel(packageManager).toString() + "  (" + app + ")"
        } catch (e: Exception) {
            app
        }
    }

    private fun pintar() {
        if (!Ajustes.registroDns(this)) {
            estado.text = "El registro está DESACTIVADO. Actívalo en Ajustes › Registro DNS por app."
            if (firma != "off") { firma = "off"; lista.removeAllViews() }
            return
        }
        val datos = RegistroDns.resumen()
        val nueva = datos.joinToString("|") { it.app + ":" + it.consultas + ":" + it.bloqueadas } + "#" + expandidas.joinToString(",")
        estado.text = if (datos.isEmpty()) {
            "Activo, sin consultas todavía. Comprueba que la protección está en marcha y usa alguna app."
        } else {
            "Activo · " + numeros.format(RegistroDns.total()) + " pares app-dominio"
        }
        if (nueva == firma) return
        firma = nueva
        lista.removeAllViews()
        for (r in datos) {
            val abierta = expandidas.contains(r.app)
            val cab = Vistas.tarjeta(this)
            val titulo = Vistas.subtitulo(this, (if (abierta) "▾ " else "▸ ") + etiqueta(r.app))
            cab.addView(titulo, Vistas.anchoCompleto(this, 2))
            cab.addView(
                Vistas.texto(this, numeros.format(r.consultas) + " consultas · " + numeros.format(r.bloqueadas) + " bloqueadas", true),
                Vistas.anchoCompleto(this, if (abierta) 8 else 0)
            )
            cab.setOnClickListener {
                if (!expandidas.add(r.app)) expandidas.remove(r.app)
                pintar()
            }
            if (abierta) {
                for (f in r.filas.take(60)) {
                    val bloqueado = f.bloqueadas > 0
                    val fila = Vistas.texto(this, (if (bloqueado) "✕ " else "✓ ") + f.dominio + "  ·  " + numeros.format(f.consultas.toLong()))
                    fila.setPadding(0, Vistas.dp(this, 6), 0, Vistas.dp(this, 6))
                    fila.setOnClickListener { accion(f.dominio, bloqueado) }
                    cab.addView(fila)
                }
                if (r.filas.size > 60) cab.addView(Vistas.texto(this, "… y " + (r.filas.size - 60) + " más", true))
            }
            lista.addView(cab, Vistas.anchoCompleto(this, 12))
        }
    }

    private fun accion(dominio: String, bloqueado: Boolean) {
        if (bloqueado) {
            Dialogos.confirmar(
                this, "Permitir dominio",
                "¿Dejar de bloquear " + dominio + " y sus subdominios? Podrás revertirlo en Ajustes › Dominios permitidos.", "Permitir"
            ) {
                Ajustes.agregarPermitido(this, dominio)
                ListasManager.reconstruirAsync(this)
            }
        } else {
            Dialogos.confirmar(
                this, "Bloquear dominio",
                "¿Bloquear " + dominio + " y sus subdominios? Si una app deja de funcionar, quítalo en Ajustes › Bloquear dominios extra.", "Bloquear"
            ) {
                Ajustes.agregarBloqueo(this, dominio)
                ListasManager.reconstruirAsync(this)
            }
        }
    }
}
