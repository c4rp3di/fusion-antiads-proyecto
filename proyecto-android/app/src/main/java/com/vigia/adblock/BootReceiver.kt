package com.vigia.adblock

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vigia.core.ui.NotificacionSesion

/** Enciende la protección al arrancar el móvil (o tras actualizar la app) si el usuario la tenía activa. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intent: Intent) {
        val accion = intent.action
        if (accion != Intent.ACTION_BOOT_COMPLETED && accion != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Programador.programar(contexto, false)
        if (!Ajustes.arranqueAuto(contexto) || !Ajustes.activa(contexto)) return
        if (VpnService.prepare(contexto) != null) {
            avisar(contexto, "Android pide confirmar de nuevo la VPN. Toca para reactivar la protección.")
            return
        }
        try {
            ContextCompat.startForegroundService(contexto, Intent(contexto, AdBlockVpnService::class.java))
        } catch (e: Exception) {
            avisar(contexto, "No se pudo activar la protección al arrancar. Toca para activarla.")
        }
    }

    private fun avisar(c: Context, texto: String) {
        val datos = NotificacionSesion.Datos("adblock_aviso", "Avisos de AdBlock", 102, "AntiAds", texto, textoDetener = null)
        NotificacionSesion.crearCanal(c, datos)
        val abrir = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, datos.canalId)
            .setSmallIcon(datos.iconoRes)
            .setContentTitle(datos.titulo)
            .setContentText(texto)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(datos.id, n)
    }
}
