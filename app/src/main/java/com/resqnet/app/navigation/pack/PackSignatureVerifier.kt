package com.resqnet.app.navigation.pack

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

fun interface PackSignatureVerifier {
    fun verify(payload: ByteArray, signatureBase64: String): Boolean
}

class EcdsaP256PackSignatureVerifier(pemPublicKey: String) : PackSignatureVerifier {
    private val publicKey = try {
        val encoded = pemPublicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        require(encoded.isNotBlank()) { "Public key is empty" }
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(encoded, Base64.DEFAULT)))
    } catch (error: Throwable) {
        throw PackFailure.MissingPublicKey()
    }

    override fun verify(payload: ByteArray, signatureBase64: String): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }
    } catch (_: Throwable) {
        false
    }
}
