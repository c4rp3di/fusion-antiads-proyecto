package com.vigia.adblock

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vigia.core.red.VpnController
import com.vigia.core.red.VpnEstado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lógica compartida para encender/apagar la protección fuera de MainActivity (tile de ajustes rápidos y su actividad
 * de permiso). Hace lo mismo que el botón de la app: marca el ajuste «activa» y arranca o para el servicio.
 */
object ProteccionControl {

    fun controlador(c: Context): VpnController = VpnController.crear(c, AdBlockVpnService::class.java)

    fun encender(c: Context) {
        val app = c.applicationContext
        Ajustes.setActiva(app, true)
        if (!AdBlockEstado.filtro.cargado && !ListasManager.hayListasDescargadas(app)) {
            ListasManager.actualizarAsync(app)
        }
        controlador(app).start()
    }

    fun apagar(c: Context) {
        val app = c.applicationContext
        Ajustes.setActiva(app, false)
        controlador(app).stop()
    }
}

/**
 * Interruptor de la protección en el panel de ajustes rápidos.
 *
 * - Toque con la VPN apagada: si Android ya concedió el permiso, arranca el servicio; si no, abre [TileVpnActivity]
 *   (transparente) para mostrar el diálogo de VPN y arrancar al aceptar.
 * - Toque con la VPN encendida: la detiene.
 * - El estado visual sale de [VpnEstado.activa]. Mientras el panel está abierto se observa en vivo; con el panel
 *   cerrado, [AdBlockVpnService] llama a [pedirActualizacion] cuando cambia el estado.
 */
class AdBlockTileService : TileService() {

    private var ambito: CoroutineScope? = null
    private var observador: Job? = null

    private val vpn: VpnController by lazy { ProteccionControl.controlador(this) }

    override fun onStartListening() {
        super.onStartListening()
        refrescar()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        ambito = s
        observador = s.launch { VpnEstado.activa.collect { refrescar() } }
    }

    override fun onStopListening() {
        observador?.cancel()
        observador = null
        ambito?.cancel()
        ambito = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Con el móvil bloqueado pide desbloquear antes de cambiar nada.
        unlockAndRun { alternar() }
    }

    private fun alternar() {
        if (vpn.activa.value) {
            ProteccionControl.apagar(this)
            return
        }
        if (vpn.permisoPendiente() != null) {
            // Primera vez (o permiso revocado): hace falta el diálogo de Android.
            abrirActividadPermiso()
            return
        }
        try {
            ProteccionControl.encender(this)
        } catch (e: Exception) {
            // Android 12+ puede negar arrancar un servicio en primer plano desde aquí: se hace desde una actividad visible.
            abrirActividadPermiso()
        }
    }

    private fun abrirActividadPermiso() {
        val intent = Intent(this, TileVpnActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refrescar() {
        val tile = qsTile ?: return
        val activa = VpnEstado.activa.value
        tile.state = if (activa) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(
            this, if (activa) R.drawable.ic_tile_escudo else R.drawable.ic_tile_escudo_off
        )
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (activa) R.string.tile_activo else R.string.tile_desactivado)
        }
        tile.updateTile()
    }

    companion object {
        /** Pide a Android que enlace el tile y llame a onStartListening() para que se repinte. No hace nada si no está añadido. */
        fun pedirActualizacion(c: Context) {
            try {
                requestListeningState(c, ComponentName(c, AdBlockTileService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
