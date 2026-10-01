package com.resqnet.app.navigation.pack

import com.resqnet.app.navigation.InstalledRegionPack

data class PackFile(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

/** Stored inside every imported/downloaded region ZIP as region-pack.json. */
data class RegionPackManifest(
    val regionId: String,
    val version: String,
    val minimumAppVersionCode: Int,
    val files: List<PackFile>
)

/** Fetched from the configured HTTPS catalog before a download begins. */
data class PackCatalogEntry(
    val regionId: String,
    val version: String,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    val signatureBase64: String
)

data class PackCatalog(val packs: List<PackCatalogEntry>)

sealed class OfflinePackState {
    data object Absent : OfflinePackState()
    data class Downloading(val downloadId: Long, val bytesDownloaded: Long, val totalBytes: Long?) : OfflinePackState()
    data class Importing(val bytesCopied: Long, val expectedBytes: Long?) : OfflinePackState()
    data object Verifying : OfflinePackState()
    data object Installing : OfflinePackState()
    data class Ready(val pack: InstalledRegionPack) : OfflinePackState()
    data class UpdateAvailable(val installed: InstalledRegionPack, val available: PackCatalogEntry) : OfflinePackState()
    data class Failed(val reason: PackFailure) : OfflinePackState()
}

sealed class PackFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class MissingCatalogUrl : PackFailure("Offline pack catalog URL is not configured")
    class MissingPublicKey : PackFailure("Offline pack signing key is not configured")
    class InsufficientStorage(requiredBytes: Long, availableBytes: Long) : PackFailure(
        "Insufficient storage: need $requiredBytes bytes, have $availableBytes bytes"
    )
    class InvalidArchive(message: String, cause: Throwable? = null) : PackFailure(message, cause)
    class InvalidManifest(message: String, cause: Throwable? = null) : PackFailure(message, cause)
    class IntegrityMismatch(path: String) : PackFailure("SHA-256 mismatch for $path")
    class InvalidSignature(cause: Throwable? = null) : PackFailure("Offline pack catalog signature is invalid", cause)
    class IncompatiblePack(message: String) : PackFailure(message)
    class Io(message: String, cause: Throwable? = null) : PackFailure(message, cause)
}
