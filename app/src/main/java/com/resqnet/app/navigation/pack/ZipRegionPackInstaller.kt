package com.resqnet.app.navigation.pack

import com.resqnet.app.navigation.InstalledRegionPack
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Verifies and atomically stages a region ZIP. Activation is a separate registry update. */
class ZipRegionPackInstaller(
    private val packsRoot: File,
    private val appVersionCode: Int
) {
    fun install(archive: File): InstalledRegionPack {
        if (!archive.isFile) throw PackFailure.InvalidArchive("Archive is missing: ${archive.absolutePath}")
        packsRoot.mkdirs()
        requireCapacity(archive.length())
        val staging = Files.createTempDirectory(packsRoot.toPath(), "staging-").toFile()
        try {
            extractSafely(archive, staging)
            val manifestFile = File(staging, MANIFEST_FILE)
            if (!manifestFile.isFile) throw PackFailure.InvalidManifest("Archive does not contain $MANIFEST_FILE")
            val manifest = PackManifestCodec.decode(manifestFile.readText())
            validateManifest(manifest, staging)
            val target = File(packsRoot, "${safe(manifest.regionId)}-${safe(manifest.version)}")
            // Pack versions are immutable.  Never replace an existing directory here:
            // it might be the pack currently used by the routing actor.
            if (target.exists()) {
                if (!target.isDirectory) throw PackFailure.InvalidArchive("Pack target is not a directory")
                staging.deleteRecursively()
                return InstalledRegionPack(manifest.regionId, manifest.version, target)
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return InstalledRegionPack(
                regionId = manifest.regionId,
                version = manifest.version,
                directory = target
            )
        } catch (failure: PackFailure) {
            staging.deleteRecursively()
            throw failure
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw PackFailure.Io("Could not install region pack", error)
        }
    }

    private fun requireCapacity(archiveBytes: Long) {
        val required = ((archiveBytes * 5L) + 1L) / 2L
        // The archive is already on this volume when this method runs, so add
        // its occupied bytes back to compare against pre-install free space.
        val available = packsRoot.usableSpace + archiveBytes
        if (available < required) throw PackFailure.InsufficientStorage(required, available)
    }

    private fun extractSafely(archive: File, destination: File) {
        ZipFile(archive).use { zip ->
            if (zip.size() > MAX_ENTRY_COUNT) throw PackFailure.InvalidArchive("Archive has too many entries")
            var extractedBytes = 0L
            val root = destination.canonicalFile
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (entry.size < 0 || entry.size > MAX_ENTRY_BYTES) throw PackFailure.InvalidArchive("Invalid archive entry ${entry.name}")
                extractedBytes += entry.size
                if (extractedBytes > archive.length() * MAX_EXPANSION_FACTOR || extractedBytes > MAX_TOTAL_BYTES) {
                    throw PackFailure.InvalidArchive("Archive expansion limit exceeded")
                }
                val output = File(destination, entry.name).canonicalFile
                if (!output.path.startsWith(root.path + File.separator)) {
                    throw PackFailure.InvalidArchive("Archive entry escapes its destination: ${entry.name}")
                }
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun validateManifest(manifest: RegionPackManifest, directory: File) {
        if (manifest.regionId.isBlank() || manifest.version.isBlank()) throw PackFailure.InvalidManifest("Region ID and version are required")
        if (manifest.minimumAppVersionCode > appVersionCode) {
            throw PackFailure.IncompatiblePack("Pack requires app version ${manifest.minimumAppVersionCode}")
        }
        if (manifest.files.isEmpty()) throw PackFailure.InvalidManifest("Pack has no declared files")
        val declared = manifest.files.map { it.path }.toSet()
        if (declared.size != manifest.files.size || MANIFEST_FILE in declared) throw PackFailure.InvalidManifest("Invalid declared file list")
        if ("valhalla_tiles.tar" !in declared || "valhalla.json" !in declared) {
            throw PackFailure.InvalidManifest("Pack must contain valhalla_tiles.tar and valhalla.json")
        }
        manifest.files.forEach { file ->
            if (file.path.isBlank() || file.path.contains("..") || file.sizeBytes < 0L) {
                throw PackFailure.InvalidManifest("Invalid path in manifest")
            }
            val actual = File(directory, file.path).canonicalFile
            if (!actual.path.startsWith(directory.canonicalPath + File.separator) || !actual.isFile || actual.length() != file.sizeBytes) {
                throw PackFailure.InvalidManifest("Missing or invalid file ${file.path}")
            }
            if (!sha256(actual).equals(file.sha256, ignoreCase = true)) throw PackFailure.IntegrityMismatch(file.path)
        }
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val MANIFEST_FILE = "region-pack.json"
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_ENTRY_BYTES = 1_000_000_000L
        const val MAX_TOTAL_BYTES = 2_000_000_000L
        const val MAX_EXPANSION_FACTOR = 25L
    }
}
