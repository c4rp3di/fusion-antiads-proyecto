package com.vigia.mitm

import com.vigia.core.red.DesvioTcp
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Enrutamiento del TCP 443 (y del 80, sin TLS, si [incluirHttp]): lo que el núcleo llama antes de conectar cada socket saliente.
 * Si la app está en la lista blanca (o el proxy no corre) devuelve null y el núcleo conecta directo al servidor real.
 */
class DesvioMitm(
    private val proxy: MitmProxy,
    private val politica: PoliticaMitm,
    /** También desvía el TCP 80 (HTTP sin cifrar): el proxy lo atiende sin TLS. */
    private val incluirHttp: Boolean = true
) : DesvioTcp {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    override fun desviar(uid: Int, app: String, ip: String, puerto: Int, puertoLocal: Int): InetSocketAddress? {
        val esperado = puerto == 443 || (incluirHttp && puerto == 80)
        if (!esperado || !proxy.activo || puertoLocal <= 0) return null
        if (!politica.intermediar(app, ip)) return null
        Origenes.registrar(puertoLocal, Origen(ip, puerto, app))
        return InetSocketAddress(loopback, proxy.puerto)
    }
}
