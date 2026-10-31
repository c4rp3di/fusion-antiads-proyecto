package com.vigia.mitm

import android.content.Context
import android.util.Base64
import com.vigia.core.seguridad.AlmacenClaves
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Autoridad certificadora local (movida de NetLens, con cambios):
 *  - la contraseña del PKCS12 es aleatoria y se guarda cifrada con el Keystore (antes: constante en el código);
 *  - UN solo par de claves RSA para todos los certificados hoja (antes: un RSA-2048 nuevo por dominio, 200-800 ms
 *    en un móvil medio y bloqueaba el primer byte de cada sitio nuevo);
 *  - el emisor se toma del certificado de la CA (antes: se re-parseaba el nombre con un orden de RDN invertido);
 *  - hojas con EKU serverAuth, AuthorityKeyIdentifier y soporte de IP en el SAN;
 *  - no se registra ningún Provider de BouncyCastle: Android ya trae uno interno llamado «BC» y no hace falta.
 */
object CaManager {
    private const val CA_ALIAS = "antiads-ca"
    private const val KS_FILE = "antiads-ca.p12"
    private const val SECRETO = "ca_pkcs12_pass"
    private const val CA_DIAS = 3650L
    private const val HOJA_DIAS = 300L

    private var caKey: PrivateKey? = null
    private var caCert: X509Certificate? = null
    private var claveHoja: KeyPair? = null
    private val hojas = ConcurrentHashMap<String, Pair<PrivateKey, Array<X509Certificate>>>()
    private val azar = SecureRandom()

    @Synchronized
    fun iniciar(contexto: Context) {
        if (caKey != null) return
        val c = contexto.applicationContext
        val almacen = AlmacenClaves.paraApp(c, "antiads_ca")
        val archivo = File(c.filesDir, KS_FILE)
        val guardada = almacen.leerSecreto(SECRETO)
        val ks = KeyStore.getInstance("PKCS12")
        if (archivo.exists() && guardada != null) {
            try {
                archivo.inputStream().use { ks.load(it, guardada.toCharArray()) }
                caKey = ks.getKey(CA_ALIAS, guardada.toCharArray()) as PrivateKey
                caCert = ks.getCertificate(CA_ALIAS) as X509Certificate
            } catch (e: Exception) {
                caKey = null; caCert = null
            }
        }
        if (caKey == null) {
            // Primera vez, o el Keystore ya no puede abrir la CA: se crea una nueva (habrá que reinstalarla).
            val clave = ByteArray(24).also { azar.nextBytes(it) }.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            almacen.guardarSecreto(SECRETO, clave)
            val kp = nuevoRsa()
            val ahora = System.currentTimeMillis()
            val nombre = X500Name("CN=AntiAds CA (local), O=AntiAds")
            val b = JcaX509v3CertificateBuilder(
                nombre, serie(), Date(ahora - TimeUnit.DAYS.toMillis(1)),
                Date(ahora + TimeUnit.DAYS.toMillis(CA_DIAS)), nombre, kp.public
            )
            val ext = JcaX509ExtensionUtils()
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(0)) // CA que no puede delegar
            b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.public))
            val cert = JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)))
            caKey = kp.private; caCert = cert
            ks.load(null, null)
            ks.setKeyEntry(CA_ALIAS, kp.private, clave.toCharArray(), arrayOf(cert))
            archivo.outputStream().use { ks.store(it, clave.toCharArray()) }
            hojas.clear(); claveHoja = null
        }
    }

    private fun nuevoRsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun serie(): BigInteger = BigInteger(64, azar).abs().add(BigInteger.ONE)

    val certificado: X509Certificate get() = caCert ?: throw IllegalStateException("CaManager.iniciar() no se llamó")

    fun huellaSha256(): String =
        MessageDigest.getInstance("SHA-256").digest(certificado.encoded).joinToString(":") { "%02X".format(it) }

    /** DER (lo que Android espera en un .crt). */
    fun exportarDer(): ByteArray = certificado.encoded

    /** PEM, por si el usuario lo quiere en un PC o en Firefox. */
    fun exportarPem(): ByteArray {
        val b64 = Base64.encodeToString(certificado.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return ("-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n").toByteArray(Charsets.US_ASCII)
    }

    /**
     * ¿Está nuestra CA en el almacén de usuario? (NetLens solo comprobaba si existía el archivo, lo que no dice nada.)
     * El AndroidCAStore lista los certificados de usuario con alias «user:…».
     */
    fun estaInstalada(): Boolean {
        val mia = caCert ?: return false
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            ks.aliases().asSequence().filter { it.startsWith("user:") }.any {
                (ks.getCertificate(it) as? X509Certificate)?.encoded?.contentEquals(mia.encoded) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Clave + cadena (hoja, CA) para un nombre de servidor (dominio o IP literal). */
    fun certificadoPara(nombre: String): Pair<PrivateKey, Array<X509Certificate>> =
        hojas.getOrPut(nombre.lowercase()) { generarHoja(nombre.lowercase()) }

    @Synchronized
    private fun parHoja(): KeyPair = claveHoja ?: nuevoRsa().also { claveHoja = it }

    private fun generarHoja(nombre: String): Pair<PrivateKey, Array<X509Certificate>> {
        val ca = certificado
        val kp = parHoja()
        val ahora = System.currentTimeMillis()
        val esIp = nombre.all { it.isDigit() || it == '.' } && nombre.count { it == '.' } == 3
        val san = GeneralNames(GeneralName(if (esIp) GeneralName.iPAddress else GeneralName.dNSName, nombre))
        val b = JcaX509v3CertificateBuilder(
            JcaX509CertificateHolder(ca).subject, serie(),
            Date(ahora - TimeUnit.DAYS.toMillis(1)), Date(ahora + TimeUnit.DAYS.toMillis(HOJA_DIAS)),
            X500Name("CN=" + nombre.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }.take(60).ifEmpty { "host" }), kp.public
        )
        val ext = JcaX509ExtensionUtils()
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        b.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        b.addExtension(Extension.subjectAlternativeName, false, san)
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(ca))
        val cert = JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").build(caKey!!)))
        return Pair(kp.private, arrayOf(cert, ca))
    }
}
