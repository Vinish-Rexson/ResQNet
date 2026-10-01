package com.resqnet.app.navigation.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipRegionPackInstallerTest {
    @Test
    fun `installs a verified archive into a stable pack directory`() {
        val root = Files.createTempDirectory("resqnet-pack-test").toFile()
        try {
            val archive = createArchive(root)
            val pack = ZipRegionPackInstaller(File(root, "packs"), appVersionCode = 1).install(archive)

            assertEquals("mmr", pack.regionId)
            assertEquals("2026.10", pack.version)
            assertTrue(pack.tileArchive.isFile)
            assertTrue(pack.configFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects checksum tampering and leaves no installed pack`() {
        val root = Files.createTempDirectory("resqnet-pack-test").toFile()
        try {
            val archive = createArchive(root, wrongHash = true)
            try {
                ZipRegionPackInstaller(File(root, "packs"), appVersionCode = 1).install(archive)
            } catch (error: PackFailure.IntegrityMismatch) {
                // Expected.
            }
            assertFalse(File(root, "packs/mmr-2026.10").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects zip slip entries`() {
        val root = Files.createTempDirectory("resqnet-pack-test").toFile()
        try {
            val archive = createArchive(root, includeZipSlip = true)
            try {
                ZipRegionPackInstaller(File(root, "packs"), appVersionCode = 1).install(archive)
            } catch (error: PackFailure.InvalidArchive) {
                // Expected.
            }
            assertFalse(File(root.parentFile, "escaped.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createArchive(root: File, wrongHash: Boolean = false, includeZipSlip: Boolean = false): File {
        val files = linkedMapOf(
            "valhalla_tiles.tar" to "tiles".toByteArray(),
            "valhalla.json" to "{\"mjolnir\":{}}".toByteArray()
        )
        val manifestFiles = files.map { (path, bytes) ->
            val hash = if (wrongHash && path == "valhalla_tiles.tar") "00" else sha256(bytes)
            "{\"path\":\"$path\",\"sha256\":\"$hash\",\"sizeBytes\":${bytes.size}}"
        }.joinToString(",")
        val manifest = "{\"regionId\":\"mmr\",\"version\":\"2026.10\",\"minimumAppVersionCode\":1,\"files\":[$manifestFiles]}"
        val archive = File(root, "pack.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("region-pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            if (includeZipSlip) {
                zip.putNextEntry(ZipEntry("../escaped.txt"))
                zip.write("nope".toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
