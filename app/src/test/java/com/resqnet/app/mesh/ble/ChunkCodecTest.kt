package com.resqnet.app.mesh.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkCodecTest {
    @Test fun reassemblesFrameInAnyOrder() {
        val original = ByteArray(1_500) { (it % 251).toByte() }
        val chunks = ChunkCodec.split(original, 64)
        val assembler = ChunkCodec.Assembler()
        chunks.reversed().dropLast(1).forEach { assertNull(assembler.accept(it)) }
        assertArrayEquals(original, assembler.accept(chunks.first()))
    }
}
