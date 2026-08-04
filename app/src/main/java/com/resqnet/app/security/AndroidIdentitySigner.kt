package com.resqnet.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

class AndroidIdentitySigner : IdentitySigner {
    private val alias = "resqnet_mesh_identity_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyPair by lazy {
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .build())
            }.generateKeyPair()
        } else {
            val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            java.security.KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
    }

    override val publicKey: ByteArray get() = keyPair.public.encoded
    override val fingerprint: String get() = sha256(publicKey).take(16).chunked(4).joinToString("-")
    override val nodeId: String get() = sha256(publicKey).take(32)

    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private); update(payload); sign()
    }

    override fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { String.format(Locale.US, "%02x", it) }
}
