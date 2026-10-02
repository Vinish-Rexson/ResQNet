package com.resqnet.app.navigation.pack

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.resqnet.app.BuildConfig
import com.resqnet.app.navigation.InstalledRegionPack
import com.resqnet.app.navigation.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest

/** Owns app-private region-pack staging, activation, import, and download entry points. */
class OfflinePackManager(
    private val context: Context,
    appVersionCode: Int = BuildConfig.VERSION_CODE,
    private val installer: ZipRegionPackInstaller = ZipRegionPackInstaller(
        File(context.filesDir, "offline-packs"), appVersionCode
    ),
    private val routingEngine: RoutingEngine? = null
) {
    private val packsRoot = File(context.filesDir, "offline-packs")
    private val stagingRoot = File(context.cacheDir, "offline-pack-staging")
    private val preferences = context.getSharedPreferences("offline-pack", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<OfflinePackState>(readActivePack()?.let(OfflinePackState::Ready) ?: OfflinePackState.Absent)
    val state: StateFlow<OfflinePackState> = mutableState.asStateFlow()

    /** Retrieves only HTTPS catalog bytes. Each selected entry is signature-checked before download. */
    suspend fun fetchCatalog(): List<PackCatalogEntry> = withContext(Dispatchers.IO) {
        val configuredUrl = BuildConfig.OFFLINE_PACK_CATALOG_URL
        if (configuredUrl.isBlank()) throw PackFailure.MissingCatalogUrl()
        val connection = (URL(configuredUrl).openConnection() as? HttpsURLConnection)
            ?: throw PackFailure.InvalidArchive("Pack catalog URL must use HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) {
                throw PackFailure.Io("Pack catalog request failed: HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_CATALOG_BYTES) {
                        throw PackFailure.InvalidArchive("Pack catalog is too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            PackManifestCodec.decodeCatalog(bytes.toString(Charsets.UTF_8)).packs
        } catch (failure: PackFailure) {
            throw failure
        } catch (error: Throwable) {
            throw PackFailure.Io("Could not fetch pack catalog", error)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun importFrom(uri: Uri): InstalledRegionPack = withContext(Dispatchers.IO) {
        val expectedBytes = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        stagingRoot.mkdirs()
        expectedBytes?.let(::requireStagingCapacity)
        val staged = File.createTempFile("import-", ".zip", stagingRoot)
        try {
            mutableState.value = OfflinePackState.Importing(0L, expectedBytes)
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (expectedBytes == null) requireStagingCapacity(copied + read)
                        output.write(buffer, 0, read)
                        copied += read
                        mutableState.value = OfflinePackState.Importing(copied, expectedBytes)
                    }
                }
            } ?: throw PackFailure.Io("Could not open selected pack")
            installStagedArchive(staged)
        } catch (failure: PackFailure) {
            mutableState.value = OfflinePackState.Failed(failure)
            throw failure
        } finally {
            staged.delete()
        }
    }

    /** Installs a region pack shipped in this APK through the normal install path. */
    fun hasBundledAsset(assetName: String): Boolean = runCatching {
        context.assets.list("")?.contains(assetName) == true
    }.getOrDefault(false)

    suspend fun installBundledAsset(assetName: String): InstalledRegionPack = withContext(Dispatchers.IO) {
        if (!hasBundledAsset(assetName)) {
            throw PackFailure.Io("The bundled offline map is not available in this app build")
        }
        stagingRoot.mkdirs()
        val staged = File.createTempFile("bundled-", ".zip", stagingRoot)
        try {
            mutableState.value = OfflinePackState.Importing(0L, null)
            var copied = 0L
            context.assets.open(assetName).use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        requireStagingCapacity(copied + read)
                        output.write(buffer, 0, read)
                        copied += read
                        mutableState.value = OfflinePackState.Importing(copied, null)
                    }
                }
            }
            installStagedArchive(staged)
        } catch (failure: PackFailure) {
            mutableState.value = OfflinePackState.Failed(failure)
            throw failure
        } catch (error: Throwable) {
            val failure = PackFailure.Io("Could not install bundled offline map", error)
            mutableState.value = OfflinePackState.Failed(failure)
            throw failure
        } finally {
            staged.delete()
        }
    }

    fun enqueueDownload(entry: PackCatalogEntry): Long {
        validateCatalogEntry(entry)
        val request = DownloadManager.Request(Uri.parse(entry.archiveUrl)).apply {
            setTitle("ResQNet offline map: ${entry.regionId}")
            setDescription("Downloading offline navigation data")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "resqnet-${safe(entry.regionId)}-${safe(entry.version)}.zip"
            )
        }
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        preferences.edit().putString("download_hash_$id", entry.archiveSha256.lowercase()).apply()
        mutableState.value = OfflinePackState.Downloading(id, 0L, entry.archiveSizeBytes)
        return id
    }

    suspend fun installCompletedDownload(downloadId: Long): InstalledRegionPack = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst() || it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                throw PackFailure.Io("Download $downloadId has not completed successfully")
            }
            val uri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let(Uri::parse)
                ?: throw PackFailure.Io("Download $downloadId has no local file")
            try {
                val expectedHash = preferences.getString("download_hash_$downloadId", null)
                    ?: throw PackFailure.InvalidArchive("Download $downloadId has no expected checksum")
                val downloaded = uri.path?.let(::File) ?: throw PackFailure.Io("Download $downloadId is not a file")
                if (!downloaded.isFile || !sha256(downloaded).equals(expectedHash, ignoreCase = true)) {
                    throw PackFailure.IntegrityMismatch("download $downloadId")
                }
                importFrom(uri)
            } finally {
                preferences.edit().remove("download_hash_$downloadId").apply()
                manager.remove(downloadId)
            }
        }
    }

    fun refreshDownload(downloadId: Long) {
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it >= 0L }
            mutableState.value = OfflinePackState.Downloading(downloadId, downloaded, total)
        }
    }

    private suspend fun installStagedArchive(staged: File): InstalledRegionPack {
        val previousPack = readActivePack()
        mutableState.value = OfflinePackState.Verifying
        mutableState.value = OfflinePackState.Installing
        val pack = installer.install(staged)
        try {
            routingEngine?.let { engine ->
                engine.close()
                engine.initialize(pack)
            }
        } catch (error: Throwable) {
            // The active preference is deliberately untouched until the new
            // archive has initialized. Restore the old actor when possible.
            try {
                routingEngine?.let { engine ->
                    engine.close()
                    previousPack?.let { engine.initialize(it) }
                }
            } catch (_: Throwable) {
                // The previous preference remains usable on the next launch.
            }
            throw PackFailure.Io("Installed pack could not initialize", error)
        }
        preferences.edit().putString(KEY_REGION, pack.regionId).putString(KEY_VERSION, pack.version).commit()
        mutableState.value = OfflinePackState.Ready(pack)
        return pack
    }

    private fun validateCatalogEntry(entry: PackCatalogEntry) {
        if (!entry.archiveUrl.startsWith("https://")) throw PackFailure.InvalidArchive("Pack URL must use HTTPS")
        if (entry.archiveSizeBytes <= 0L) throw PackFailure.InvalidArchive("Pack size must be positive")
        if (BuildConfig.OFFLINE_PACK_PUBLIC_KEY_PEM.isBlank()) throw PackFailure.MissingPublicKey()
        val verifier = EcdsaP256PackSignatureVerifier(BuildConfig.OFFLINE_PACK_PUBLIC_KEY_PEM)
        if (!verifier.verify(canonicalCatalogPayload(entry), entry.signatureBase64)) throw PackFailure.InvalidSignature()
    }

    private fun readActivePack(): InstalledRegionPack? {
        val regionId = preferences.getString(KEY_REGION, null) ?: return null
        val version = preferences.getString(KEY_VERSION, null) ?: return null
        val directory = File(packsRoot, "${safe(regionId)}-${safe(version)}")
        return InstalledRegionPack(regionId, version, directory).takeIf { it.directory.isDirectory && it.tileArchive.isFile && it.configFile.isFile }
    }

    private fun canonicalCatalogPayload(entry: PackCatalogEntry): ByteArray = listOf(
        entry.regionId, entry.version, entry.archiveUrl, entry.archiveSha256.lowercase(), entry.archiveSizeBytes.toString()
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

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

    private fun requireStagingCapacity(archiveBytes: Long) {
        val required = ((archiveBytes * 5L) + 1L) / 2L
        // The staged archive itself contributes to usable space only after it is copied.
        val available = stagingRoot.usableSpace
        if (available < required) throw PackFailure.InsufficientStorage(required, available)
    }

    private companion object {
        const val KEY_REGION = "active_region"
        const val KEY_VERSION = "active_version"
        const val MAX_CATALOG_BYTES = 1_048_576
    }
}
