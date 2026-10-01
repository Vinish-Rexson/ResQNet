package com.resqnet.app.navigation

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Production adapter for a prebuilt, fully offline Valhalla tile archive. */
class ValhallaRoutingEngine(private val context: Context) : RoutingEngine {
    private val lock = Any()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private var actor: Valhalla? = null
    private var activePack: InstalledRegionPack? = null

    override suspend fun initialize(pack: InstalledRegionPack): Unit = withContext(Dispatchers.IO) {
        validatePack(pack)
        val config = parseRuntimeConfig(pack)
        val manager = ValhallaConfigManager(
            context,
            ValhallaFile(context, runtimeConfigName(pack), context.filesDir),
            moshi
        )
        val nextActor = try {
            Valhalla(context.applicationContext, config, manager, moshi)
        } catch (error: Throwable) {
            throw RoutingException.NativeFailure("Could not initialize Valhalla for ${pack.regionId}", error)
        }
        synchronized(lock) {
            actor?.close()
            actor = nextActor
            activePack = pack
        }
    }

    override suspend fun calculateRoute(request: NavigationRouteRequest): RoutePlan = withContext(Dispatchers.IO) {
        try {
            request.origin.requireValid()
            request.destination.requireValid()
        } catch (error: IllegalArgumentException) {
            throw RoutingException.InvalidCoordinate(error.message ?: "Invalid route coordinate")
        }
        val pack = synchronized(lock) { activePack } ?: throw RoutingException.PackMissing("No Valhalla region pack is initialized")
        val response = try {
            synchronized(lock) {
                val currentActor = actor ?: throw RoutingException.PackMissing("No Valhalla actor is available")
                currentActor.route(
                    RouteRequest(
                        locations = listOf(
                            RoutingWaypoint(request.origin.latitude, request.origin.longitude),
                            RoutingWaypoint(request.destination.latitude, request.destination.longitude)
                        ),
                        costing = CostingModel.pedestrian
                    )
                )
            }
        } catch (error: Throwable) {
            throw RoutingException.NativeFailure("Valhalla route request failed", error)
        }
        val json = response as? ValhallaResponse.Json
            ?: throw RoutingException.NativeFailure("Valhalla returned an unsupported route format")
        if (json.jsonResponse.trip.status != 0) {
            throw RoutingException.NoRoute(json.jsonResponse.trip.statusMessage.ifBlank { "Valhalla could not find a pedestrian route" })
        }
        try {
            ValhallaRouteMapper.map(json.jsonResponse, pack.version)
        } catch (error: RoutingException) {
            throw error
        } catch (error: Throwable) {
            throw RoutingException.NoRoute("Valhalla could not produce a pedestrian route: ${error.message}")
        }
    }

    override fun isInitialized(): Boolean = synchronized(lock) { actor != null }

    override fun close() {
        synchronized(lock) {
            actor?.close()
            actor = null
            activePack = null
        }
    }

    private fun validatePack(pack: InstalledRegionPack) {
        if (!pack.directory.isDirectory) throw RoutingException.PackMissing("Region directory is missing: ${pack.directory}")
        if (!pack.configFile.isFile) throw RoutingException.PackMissing("Valhalla config is missing: ${pack.configFile}")
        if (!pack.tileArchive.isFile) throw RoutingException.PackMissing("Valhalla tile archive is missing: ${pack.tileArchive}")
    }

    private fun parseRuntimeConfig(pack: InstalledRegionPack): ValhallaConfig {
        val adjusted = try {
            JSONObject(pack.configFile.readText()).apply {
                val mjolnir = optJSONObject("mjolnir") ?: JSONObject()
                mjolnir.put("tile_dir", pack.directory.absolutePath)
                mjolnir.put("tile_extract", pack.tileArchive.absolutePath)
                put("mjolnir", mjolnir)
            }
        } catch (error: Throwable) {
            throw RoutingException.PackCorrupt("Could not read Valhalla config", error)
        }
        return try {
            moshi.adapter(ValhallaConfig::class.java).fromJson(adjusted.toString())
                ?: throw RoutingException.PackCorrupt("Valhalla config is empty")
        } catch (error: RoutingException) {
            throw error
        } catch (error: Throwable) {
            throw RoutingException.PackCorrupt("Could not parse Valhalla config", error)
        }
    }

    private fun runtimeConfigName(pack: InstalledRegionPack): String {
        val safeRegion = pack.regionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = pack.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "valhalla-$safeRegion-$safeVersion.json"
    }
}
