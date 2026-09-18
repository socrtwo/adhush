package io.adhush.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * The phone's own certificate (ADR 0026): Android TV's remote protocol
 * authenticates the *client* with a self-signed certificate, and the
 * pairing secret is a hash over both sides' RSA keys. Made once, kept in
 * the encrypted settings as a PKCS#12 blob, and presented on every
 * connection thereafter.
 */
class ClientIdentity(val keyStore: KeyStore, private val password: CharArray) {
    val certificate: X509Certificate get() = keyStore.getCertificate(ALIAS) as X509Certificate
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    /** TLS that presents this identity and trusts the set's own certificate (on the LAN only). */
    fun sslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAllManager()), SecureRandom()) }
    }

    fun serialize(): String = ByteArrayOutputStream().also { keyStore.store(it, password) }.toByteArray().let { Base64.getEncoder().encodeToString(it) }

    companion object {
        const val ALIAS = "adhush"
        private val PASSWORD = "adhush".toCharArray()
        private val provider = BouncyCastleProvider()

        fun trustAllManager(): javax.net.ssl.X509TrustManager = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        fun generate(name: String = "AdHush"): ClientIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=$name")
            val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), subject, pair.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, pair.private, PASSWORD, arrayOf(cert)) }
            return ClientIdentity(ks, PASSWORD)
        }

        fun deserialize(blob: String): ClientIdentity {
            val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(Base64.getDecoder().decode(blob)), PASSWORD) }
            return ClientIdentity(ks, PASSWORD)
        }

        /** Big-endian magnitude bytes, no sign byte: what the pairing hash wants. */
        fun magnitude(n: BigInteger): ByteArray { val b = n.toByteArray(); return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b }
    }
}
