package com.vigia.adblock

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vigia.core.red.ConfigVpn
import com.vigia.core.red.DesvioTcp
import com.vigia.core.red.VpnEstado
import com.vigia.core.red.ModoVpn
import com.vigia.core.red.NucleoVpnService
import com.vigia.core.red.ObservadorTrafico
import com.vigia.core.red.RespuestaBloqueo
import com.vigia.core.ui.NotificacionSesion
import com.vigia.core.util.Hilos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vigia.mitm.CaManager
import com.vigia.mitm.DesvioMitm
import com.vigia.mitm.MitmProxy
import java.text.NumberFormat

/** Solo cuenta bloqueos: el túnel no resuelve la app de origen ni lee respuestas (mínimo coste de batería). */
object ObservadorAdBlock : ObservadorTrafico {
    // Sin registro DNS el túnel no resuelve la app de origen ni lee respuestas (menos batería).
    override val quiereDetalle: Boolean get() = RegistroDns.activo || Laboratorio.activaCache("conexiones")

    override fun alDns(evento: com.vigia.core.red.EventoDns) {
        if (RegistroDns.activo) {
            RegistroDns.registrar(evento)
            Diagnostico.anotar("consulta", (if (evento.bloqueado) "✕ " else "✓ ") + evento.tipo, evento.app, evento.dominio)
        }
        if (Laboratorio.activaCache("conexiones")) NombreIp.aprender(evento.respuesta, evento.dominio)
    }

    override fun alConexion(evento: com.vigia.core.red.EventoRed) {
        if (evento.proto.contains("bloqueada")) {
            Diagnostico.anotar("ip", evento.proto, evento.app, (if (evento.sni.isNotEmpty()) evento.sni else evento.ip) + ":" + evento.puerto)
        } else if (Laboratorio.activaCache("conexiones")) {
            Conexiones.registrar(evento)
        }
    }

    /** Bloqueo por DNS o SNI: cuenta para las estadísticas y queda en el registro (agrupado por app y dominio). */
    override fun alBloqueoDe(dominio: String, capa: String, app: String) {
        Estadisticas.registrar(dominio, capa, app)
        Diagnostico.anotar(capa, "bloqueado", if (app == "desconocida") "" else app, dominio)
    }

    override fun alBloqueo(dominio: String) {
        Estadisticas.registrar(dominio)
    }
}

/**
 * El servicio del AdBlock: toda la mecánica de red está en :vigia-core. Aquí solo se decide qué filtro usar,
 * qué observador (contador en memoria, sin base de datos) y cómo se ve la notificación.
 */
class AdBlockVpnService : NucleoVpnService() {
    companion object {
        /** Botón «Activar/Apagar profundo» de la notificación. */
        const val ACCION_MITM = "com.vigia.antiads.MITM"
    }


    private val ambito = Hilos.nuevoAmbito(Dispatchers.Default)
    private var ticker: Job? = null

    // --- Modo Profundo (MitM). Se crean una sola vez y sobreviven a los reinicios del túnel.
    private var proxy: MitmProxy? = null
    private var politica: PoliticaAdBlock? = null

    override fun onCreate() {
        super.onCreate()
        // Mantiene al día el tile de ajustes rápidos cuando el estado cambia (botón de la app, notificación, fallo...).
        ambito.launch { VpnEstado.activa.collect { AdBlockTileService.pedirActualizacion(applicationContext) } }
    }

    override fun crearConfig(): ConfigVpn {
        val c = applicationContext
        Estadisticas.iniciar(c)
        ListasManager.asegurarCargado(c)
        Ajustes.setActiva(c, true)
        iniciarTicker()
        // Modo Profundo (MitM) apagado -> exactamente el AdBlock 1.0 (DNS, o SNI si se activó el modo experimental).
        // Encendido -> modo CONEXIONES + el TCP 443 se desvía al MitmProxy.
        val desvio: DesvioTcp? = if (Ajustes.mitmActivo(c)) prepararMitm(c) else null
        val mitm = desvio != null
        // Excluidas con bloqueo básico: siguen en el túnel (sin descifrar) en vez de salirse de la VPN. Hace falta saber
        // la app de cada conexión, y eso solo lo da Android 10+.
        val basico = mitm && Ajustes.excluidasBasico(c) && Build.VERSION.SDK_INT >= 29
        politica?.basicas = if (basico) Ajustes.excluidas(c) else emptySet()
        RegistroDns.activo = Ajustes.registroDns(c)
        for (id in Laboratorio.DIAGNOSTICO) Laboratorio.activa(c, id) // lee los valores guardados antes de que haga falta
        AdBlockEstado.filtro.bloquearDnsCifrado = Ajustes.bloquearDnsCifrado(c)
        Diagnostico.anotar(
            "sistema",
            "VPN configurada · Modo Profundo " + (if (mitm) "ACTIVO" else "apagado") + " · IPv6 " + (if (mitm) "oculto" else "visible") +
                " · DNS cifrado " + (if (Ajustes.bloquearDnsCifrado(c)) "bloqueado" else "permitido") +
                (if (basico) " · excluidas con bloqueo básico" else "")
        )
        MitmEstado.reiniciar()
        MitmEstado.activo = mitm
        AdBlockEstado.filtro.ocultarIpv6 = mitm
        val profundo = mitm || Ajustes.modoProfundo(c)
        return ConfigVpn(
            nombreSesion = "AntiAds",
            notificacion = datosNotificacion(),
            modo = if (profundo) ModoVpn.CONEXIONES else ModoVpn.DNS,
            dnsUpstream = Ajustes.dnsUpstream(c),
            bloquearQuic = profundo,
            // En MitM el DNS deja pasar los dominios bloqueados: el proxy contesta 200 vacío en vez de cortar.
            filtro = if (mitm) FiltroProfundo(AdBlockEstado.filtro) { politica?.basicas ?: emptySet() } else AdBlockEstado.filtro,
            respuestaBloqueo = if (Ajustes.bloqueoNxdomain(c)) RespuestaBloqueo.NXDOMAIN else RespuestaBloqueo.CERO,
            observador = ObservadorAdBlock,
            reiniciarSiMuere = true,
            excluirApps = if (basico) emptyList() else Ajustes.excluidas(c).toList(),
            desvioTcp = desvio
        )
    }

    /**
     * Arranca el proxy y devuelve el desvío, o null si no se puede usar el MitM. Sin la CA instalada TODAS las
     * conexiones fallarían el handshake y se irían a la lista blanca una a una, así que mejor no empezar.
     */
    private fun prepararMitm(c: Context): DesvioTcp? {
        return try {
            CaManager.iniciar(c)
            if (!CaManager.estaInstalada()) {
                VpnEstado.mensaje.value = "Modo Profundo (MitM) no activado: falta instalar el certificado CA en Android. " +
                    "Se usa el bloqueo DNS normal."
                return null
            }
            val pol = politica ?: PoliticaAdBlock(c).also { politica = it }
            val p = proxy ?: MitmProxy(
                protector = { socket -> protect(socket) },
                politica = pol,
                filtro = MotorReglas(c, AdBlockEstado.filtro),
                eventos = EventosLab
            ).also { proxy = it }
            p.iniciar()
            DesvioMitm(p, pol, Ajustes.mitmHttp(c))
        } catch (e: Exception) {
            VpnEstado.mensaje.value = "No se pudo iniciar el Modo Profundo (MitM): " + (e.message ?: e.javaClass.simpleName)
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Botón «profundo» de la notificación: cambia el ajuste y vuelve a abrir el túnel, sin parar el servicio.
        if (intent != null && ACCION_MITM == intent.action) {
            val c = applicationContext
            val nuevo = !Ajustes.mitmActivo(c)
            Ajustes.setMitmActivo(c, nuevo)
            Diagnostico.anotar("sistema", "Modo Profundo " + (if (nuevo) "activado" else "apagado") + " desde la notificación")
            reconfigurar()
            return START_STICKY
        }
        // «Desactivar» en la notificación es una orden del usuario: no se reactivará solo al arrancar el móvil.
        if (intent != null && NotificacionSesion.ACCION_DETENER == intent.action) {
            Ajustes.setActiva(applicationContext, false)
            Diagnostico.anotar("sistema", "VPN detenida desde la notificación")
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onRevoke() {
        // El usuario apagó la VPN desde los ajustes de Android.
        Ajustes.setActiva(applicationContext, false)
        Estadisticas.guardar(applicationContext)
        super.onRevoke()
    }

    override fun onDestroy() {
        Diagnostico.anotar("sistema", "VPN detenida")
        ticker?.cancel()
        ambito.cancel()
        proxy?.detener()
        proxy = null
        MitmEstado.activo = false
        Estadisticas.guardar(applicationContext)
        super.onDestroy()
        // super.onDestroy() deja VpnEstado.activa en false; el observador ya está cancelado, así que se avisa al tile aquí.
        AdBlockTileService.pedirActualizacion(applicationContext)
    }

    private fun iniciarTicker() {
        if (ticker != null) return
        ticker = ambito.launch {
            var ultimo = -1L
            while (true) {
                delay(60_000L)
                Estadisticas.guardar(applicationContext)
                val hoy = Estadisticas.hoy()
                if (hoy != ultimo) {
                    ultimo = hoy
                    actualizarNotificacion(datosNotificacion())
                }
            }
        }
    }

    private fun datosNotificacion(): NotificacionSesion.Datos {
        val hoy = Estadisticas.hoy()
        val deseado = Ajustes.mitmActivo(applicationContext)
        val sufijo = when {
            MitmEstado.activo -> " · Modo profundo"
            deseado -> " · Profundo: falta el certificado"
            else -> ""
        }
        val texto = (if (hoy == 0L) "Protección activa" else NumberFormat.getIntegerInstance().format(hoy) + " anuncios bloqueados hoy") + sufijo
        return NotificacionSesion.Datos(
            canalId = "adblock_proteccion",
            canalNombre = "Protección AdBlock",
            id = 101,
            titulo = "AntiAds",
            texto = texto,
            actividad = MainActivity::class.java,
            textoDetener = "Desactivar",
            textoExtra = if (deseado) "Apagar profundo" else "Activar profundo",
            accionExtra = ACCION_MITM
        )
    }
}
