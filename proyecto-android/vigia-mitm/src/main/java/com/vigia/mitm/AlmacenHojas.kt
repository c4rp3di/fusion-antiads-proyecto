package com.vigia.mitm

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Elige el certificado según el SNI del ClientHello. NetLens leía handshakeSession.peerHost, que en el lado servidor
 * es la dirección del cliente, no el SNI; el SNI está en ExtendedSSLSession.requestedServerNames (API 24+).
 * Sin SNI se usa la IP original (el proxy la conoce por [Origenes]).
 */
internal class AlmacenHojas : X509ExtendedKeyManager() {
    /** puerto del cliente -> nombre pedido; el proxy lo lee tras el handshake (o cuando falla). */
    private val vistos = ConcurrentHashMap<Int, String>()

    fun nombreVisto(puertoCliente: Int): String? = vistos.remove(puertoCliente)

    private fun alias(nombre: String) = "h:" + nombre

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
        val s = socket as? SSLSocket ?: return null
        var nombre: String? = null
        try {
            val ses = s.handshakeSession as? ExtendedSSLSession
            nombre = ses?.requestedServerNames?.filterIsInstance<SNIHostName>()?.firstOrNull()?.asciiName
        } catch (e: Exception) {
        }
        if (nombre.isNullOrEmpty()) nombre = Origenes.mirar(s.port)?.ip
        if (nombre.isNullOrEmpty()) return null
        vistos[s.port] = nombre
        return alias(nombre)
    }

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? {
        val nombre = try {
            (engine?.handshakeSession as? ExtendedSSLSession)?.requestedServerNames
                ?.filterIsInstance<SNIHostName>()?.firstOrNull()?.asciiName
        } catch (e: Exception) { null }
        return if (nombre.isNullOrEmpty()) null else alias(nombre)
    }

    override fun getPrivateKey(alias: String?): PrivateKey? =
        alias?.removePrefix("h:")?.let { CaManager.certificadoPara(it).first }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        alias?.removePrefix("h:")?.let { CaManager.certificadoPara(it).second }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
