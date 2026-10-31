package com.vigia.core.red

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.vigia.core.ui.NotificacionSesion
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo único que la interfaz de una app necesita saber del túnel. Cada app decide qué hacer con el tráfico
 * (su [ConfigVpn]); la pantalla solo arranca, para y observa.
 *
 *     val vpn = VpnController.crear(this, MiVpnService::class.java)
 *     vpn.permisoPendiente()   // null si ya hay permiso; si no, un Intent para pedirlo
 *     vpn.start(); vpn.stop()
 */
interface VpnController {
    val activa: StateFlow<Boolean>

    val mensaje: StateFlow<String?>

    /** Intent del diálogo de permiso de VPN de Android, o null si la app ya tiene el permiso. */
    fun permisoPendiente(): Intent?

    /** Arranca el túnel como servicio en primer plano. Requiere que el permiso ya esté concedido. */
    fun start()

    fun stop()

    companion object {
        fun crear(contexto: Context, servicio: Class<out NucleoVpnService>): VpnController {
            return ControladorVpn(contexto.applicationContext, servicio)
        }
    }
}

internal class ControladorVpn(
    private val contexto: Context,
    private val servicio: Class<out NucleoVpnService>
) : VpnController {

    override val activa: StateFlow<Boolean> get() = VpnEstado.activa

    override val mensaje: StateFlow<String?> get() = VpnEstado.mensaje

    override fun permisoPendiente(): Intent? = VpnService.prepare(contexto)

    override fun start() {
        ContextCompat.startForegroundService(contexto, Intent(contexto, servicio))
    }

    /**
     * OJO: stopService() NO basta. Mientras el túnel está abierto, Android mantiene enlazado el servicio de la VPN, y un
     * servicio enlazado no se destruye con stopService(): el túnel seguía vivo y la pantalla seguía en «activa».
     * Se le pide al propio servicio que cierre el túnel y se pare (es lo que ya hacía el botón de la notificación).
     * Se usa startService (no startForegroundService): esta orden no publica ninguna notificación.
     */
    override fun stop() {
        try {
            contexto.startService(Intent(contexto, servicio).setAction(NotificacionSesion.ACCION_DETENER))
        } catch (e: Exception) {
            // App en segundo plano sin permiso para arrancar servicios: lo mejor que queda.
            contexto.stopService(Intent(contexto, servicio))
        }
    }
}
