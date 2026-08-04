package com.resqnet.app.mesh.ble

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object ChunkCodec {
    private const val MAGIC: Short = 0x5251
    private const val HEADER = 10
    private val ids = AtomicInteger(1)

    fun split(frame: ByteArray, mtu: Int): List<ByteArray> {
        val payloadSize = (mtu - 3 - HEADER).coerceAtLeast(8)
        val count = ((frame.size + payloadSize - 1) / payloadSize).coerceAtLeast(1)
        require(count <= 0xffff) { "Frame too large" }
        val transfer = ids.getAndIncrement()
        return List(count) { index ->
            val start = index * payloadSize
            val end = minOf(frame.size, start + payloadSize)
            ByteBuffer.allocate(HEADER + end - start).apply {
                putShort(MAGIC); putInt(transfer); putShort(index.toShort()); putShort(count.toShort())
                put(frame, start, end - start)
            }.array()
        }
    }

    class Assembler {
        private data class Transfer(val chunks: Array<ByteArray?>, var received: Int = 0)
        private val transfers = ConcurrentHashMap<Int, Transfer>()

        fun accept(chunk: ByteArray): ByteArray? {
            require(chunk.size >= HEADER) { "Short BLE chunk" }
            val input = ByteBuffer.wrap(chunk)
            require(input.short == MAGIC) { "Bad BLE chunk magic" }
            val id = input.int
            val index = input.short.toInt() and 0xffff
            val count = input.short.toInt() and 0xffff
            require(count in 1..4096 && index < count) { "Invalid BLE chunk indexes" }
            val payload = ByteArray(input.remaining()).also(input::get)
            val transfer = transfers.computeIfAbsent(id) { Transfer(arrayOfNulls(count)) }
            require(transfer.chunks.size == count) { "Chunk count changed" }
            if (transfer.chunks[index] == null) { transfer.chunks[index] = payload; transfer.received++ }
            if (transfer.received != count) return null
            transfers.remove(id)
            val size = transfer.chunks.sumOf { it!!.size }
            return ByteArray(size).also { result ->
                var offset = 0
                transfer.chunks.forEach { part -> part!!; part.copyInto(result, offset); offset += part.size }
            }
        }
    }
}
