package com.resqnet.app.contacts

import java.nio.ByteBuffer
import java.security.MessageDigest

fun directConversationId(firstNodeId: String, secondNodeId: String): String {
    require(firstNodeId.isNotBlank() && secondNodeId.isNotBlank()) { "Node IDs cannot be blank" }
    require(firstNodeId != secondNodeId) { "A direct conversation needs two different nodes" }
    val (first, second) = listOf(firstNodeId, secondNodeId).sorted()
    val firstBytes = first.toByteArray(Charsets.UTF_8)
    val secondBytes = second.toByteArray(Charsets.UTF_8)
    val canonical = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + firstBytes.size + secondBytes.size)
        .putInt(firstBytes.size).put(firstBytes)
        .putInt(secondBytes.size).put(secondBytes)
        .array()
    return MessageDigest.getInstance("SHA-256").digest(canonical)
        .joinToString("") { "%02x".format(it) }
}
