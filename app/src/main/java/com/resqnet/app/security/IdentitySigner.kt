package com.resqnet.app.security

interface IdentitySigner {
    val nodeId: String
    val publicKey: ByteArray
    val fingerprint: String
    fun sign(payload: ByteArray): ByteArray
    fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}
