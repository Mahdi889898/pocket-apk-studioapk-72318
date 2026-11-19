package com.example.engine

import android.content.Context
import com.android.apksig.ApkSigner
import com.example.data.SigningProfile
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SigningKeyManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_DIR = "keystore"
        private const val KEYSTORE_FILE = "studio_signing.p12"
        private const val ALIAS = "studio_release"
        private val STORE_PASS = "studioapk2026".toCharArray()
        private val BC_PROVIDER = BouncyCastleProvider()
    }

    private fun keystoreFile(): File {
        val dir = File(context.filesDir, KEYSTORE_DIR).apply { mkdirs() }
        return File(dir, KEYSTORE_FILE)
    }

    fun ensureOrLoadProfile(): SigningProfile {
        val file = keystoreFile()
        if (!file.exists()) {
            generateNewKeystore("AI Studio Creator", "StudioAPK Forge", "IR")
        }
        return loadProfile()
    }

    fun generateNewKeystore(
        commonName: String,
        organization: String,
        country: String
    ): SigningProfile {
        val safeCn = commonName.trim().ifEmpty { "AI Studio Creator" }
        val safeOrg = organization.trim().ifEmpty { "StudioAPK Forge" }
        val safeCountry = country.trim().uppercase(Locale.US).take(2).ifEmpty { "IR" }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 25L * 365L * 86_400_000L)
        val serial = BigInteger(64, SecureRandom()).abs()

        val subject = X500Name("CN=$safeCn, O=$safeOrg, C=$safeCountry")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BC_PROVIDER)
            .build(keyPair.private)
        val certHolder = certBuilder.build(contentSigner)
        val certificate = JcaX509CertificateConverter()
            .setProvider(BC_PROVIDER)
            .getCertificate(certHolder)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, keyPair.private, STORE_PASS, arrayOf(certificate))
        FileOutputStream(keystoreFile()).use { fos ->
            ks.store(fos, STORE_PASS)
        }
        return buildProfileFromCert(certificate)
    }

    fun loadProfile(): SigningProfile {
        val file = keystoreFile()
        if (!file.exists()) return SigningProfile(keystoreReady = false)
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { fis -> ks.load(fis, STORE_PASS) }
            val cert = ks.getCertificate(ALIAS) as? X509Certificate
                ?: return SigningProfile(keystoreReady = false)
            buildProfileFromCert(cert)
        } catch (e: Exception) {
            SigningProfile(keystoreReady = false)
        }
    }

    fun loadSignerConfig(): ApkSigner.SignerConfig {
        ensureOrLoadProfile()
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(keystoreFile()).use { fis -> ks.load(fis, STORE_PASS) }
        val privateKey = ks.getKey(ALIAS, STORE_PASS) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return ApkSigner.SignerConfig.Builder(ALIAS, privateKey, listOf(cert)).build()
    }

    private fun buildProfileFromCert(cert: X509Certificate): SigningProfile {
        val dn = cert.subjectX500Principal.name
        val cn = extractDnPart(dn, "CN") ?: "AI Studio Creator"
        val org = extractDnPart(dn, "O") ?: "StudioAPK Forge"
        val country = extractDnPart(dn, "C") ?: "IR"
        val sha256 = digestHex(cert.encoded, "SHA-256")
        val sha1 = digestHex(cert.encoded, "SHA-1")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return SigningProfile(
            commonName = cn,
            organization = org,
            country = country,
            sha256Fingerprint = sha256,
            sha1Fingerprint = sha1,
            validUntil = fmt.format(cert.notAfter),
            keystoreReady = true
        )
    }

    private fun extractDnPart(dn: String, key: String): String? {
        return dn.split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
    }

    private fun digestHex(bytes: ByteArray, algo: String): String {
        val digest = MessageDigest.getInstance(algo).digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
