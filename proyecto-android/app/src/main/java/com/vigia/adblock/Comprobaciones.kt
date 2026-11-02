package com.vigia.adblock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.vigia.core.red.VpnEstado
import com.vigia.mitm.CaManager
import java.util.concurrent.TimeUnit

/** Autodiagnóstico: lo que suele impedir que todo funcione, con un botón para ir a arreglarlo. */
object Comprobaciones {
    const val OK = 0
    const val AVISO = 1
    const val FALLO = 2

    class Resultado(val estado: Int, val titulo: String, val detalle: String, val boton: String? = null, val accion: (() -> Unit)? = null)

    /** Las comprobaciones se hacen fuera del hilo principal (la del certificado puede tardar la primera vez). */
    fun ejecutar(a: Activity, alTerminar: (List<Resultado>) -> Unit) {
        Thread {
            val lista = try {
                calcular(a)
            } catch (e: Exception) {
                listOf(Resultado(AVISO, "No se pudieron hacer las comprobaciones", e.message ?: e.javaClass.simpleName))
            }
            a.runOnUiThread { if (!a.isFinishing) alTerminar(lista) }
        }.start()
    }

    private fun abrir(a: Activity, intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(a, "No se pudo abrir esa pantalla de Android", Toast.LENGTH_LONG).show()
        }
    }

    private fun calcular(a: Activity): List<Resultado> {
        val r = ArrayList<Resultado>()
        val vpnActiva = VpnEstado.activa.value
        val profundoPedido = Ajustes.mitmActivo(a)

        r.add(
            if (vpnActiva) Resultado(OK, "Protección activa", "La VPN local está funcionando.")
            else Resultado(FALLO, "Protección parada", "Pulsa el botón grande de la pantalla principal para activarla.")
        )

        r.add(
            when {
                !profundoPedido -> Resultado(OK, "Modo Profundo apagado", "Solo se usa el filtro DNS (sin conexiones ni páginas).")
                !vpnActiva -> Resultado(AVISO, "Modo Profundo pedido, protección parada", "Se aplicará al activar la protección.")
                MitmEstado.activo -> Resultado(OK, "Modo Profundo activo", MitmEstado.descifradas.get().toString() + " conexiones descifradas hasta ahora.")
                else -> Resultado(FALLO, "Modo Profundo pedido pero no activo", "Normalmente falta instalar el certificado, o ha fallado al arrancar el proxy.")
            }
        )

        val instalada = try {
            CaManager.iniciar(a)
            CaManager.estaInstalada()
        } catch (e: Exception) {
            false
        }
        r.add(
            when {
                instalada -> Resultado(OK, "Certificado instalado", "AntiAds CA está en el almacén de usuario de Android.")
                profundoPedido -> Resultado(
                    FALLO, "Certificado NO instalado",
                    "Sin él el Modo Profundo no puede arrancar. Exporta el certificado en Ajustes › Modo Profundo e instálalo desde Seguridad › Cifrado y credenciales.",
                    "Abrir ajustes de seguridad"
                ) { abrir(a, Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                else -> Resultado(OK, "Certificado no instalado", "Solo hace falta para el Modo Profundo.")
            }
        )

        val dnsPrivado = try {
            Settings.Global.getString(a.contentResolver, "private_dns_mode")
        } catch (e: Exception) {
            null
        }
        r.add(
            when (dnsPrivado) {
                "hostname" -> Resultado(
                    AVISO, "DNS privado en modo estricto",
                    "Android envía las consultas cifradas por su cuenta y se saltan el filtro DNS. Ponlo en «Automático» o «Desactivado» (y con «Bloquear DNS cifrado» te quedarías sin DNS).",
                    "Abrir ajustes de red"
                ) { abrir(a, Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                null -> Resultado(AVISO, "DNS privado: no se pudo comprobar", "Revisa en Ajustes de Android › Red › DNS privado que no esté en modo estricto.")
                else -> Resultado(OK, "DNS privado correcto", "Modo: " + dnsPrivado + ".")
            }
        )

        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        r.add(
            if (pm.isIgnoringBatteryOptimizations(a.packageName)) Resultado(OK, "Sin restricciones de batería", "Android no debería cerrar la VPN en segundo plano.")
            else Resultado(
                AVISO, "Optimización de batería activa",
                "Android puede cerrar la VPN en segundo plano. En Xiaomi permite también «Inicio automático» y «Sin restricciones» en Ahorro de batería.",
                "Abrir ajustes de batería"
            ) { abrir(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        )

        r.add(
            if (NotificationManagerCompat.from(a).areNotificationsEnabled()) Resultado(OK, "Notificaciones permitidas", "Se ve el aviso con los botones Desactivar y Profundo.")
            else Resultado(
                AVISO, "Notificaciones desactivadas",
                "Sin la notificación Android puede cerrar el servicio y no tendrás los botones rápidos.",
                "Abrir ajustes de notificaciones"
            ) { abrir(a, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) }
        )

        val dominios = AdBlockEstado.filtro.totalDominios
        val ultima = Ajustes.ultimaActualizacion(a)
        val dias = if (ultima > 0L) TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ultima) else -1L
        r.add(
            when {
                dominios == 0 -> Resultado(FALLO, "Sin listas cargadas", "No hay nada que bloquear.", "Actualizar listas ahora") { ListasManager.actualizarAsync(a) }
                dias > 7L -> Resultado(AVISO, "Listas con " + dias + " días", dominios.toString() + " dominios.", "Actualizar listas ahora") { ListasManager.actualizarAsync(a) }
                else -> Resultado(OK, "Listas cargadas", dominios.toString() + " dominios" + (if (dias >= 0L) " · actualizadas hace " + dias + " d" else "") + ".")
            }
        )

        val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val otraVpn = try {
            cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } && !vpnActiva
        } catch (e: Exception) {
            false
        }
        if (otraVpn) r.add(Resultado(AVISO, "Hay otra VPN activa", "Solo puede haber una a la vez: desactívala para usar AntiAds."))

        val exentas = Ajustes.mitmExentas(a).size
        r.add(Resultado(OK, exentas.toString() + " apps sin descifrar", "Son las que rechazan el certificado. Es normal que haya varias (WhatsApp, Play Store…)."))
        return r
    }
}
