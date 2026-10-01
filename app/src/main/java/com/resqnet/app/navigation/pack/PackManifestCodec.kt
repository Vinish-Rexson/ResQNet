package com.resqnet.app.navigation.pack

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

internal object PackManifestCodec {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(RegionPackManifest::class.java)
    private val catalogAdapter = moshi.adapter(PackCatalog::class.java)

    fun decode(json: String): RegionPackManifest = try {
        adapter.fromJson(json) ?: throw PackFailure.InvalidManifest("region-pack.json is empty")
    } catch (failure: PackFailure) {
        throw failure
    } catch (error: Throwable) {
        throw PackFailure.InvalidManifest("Could not parse region-pack.json", error)
    }

    fun decodeCatalog(json: String): PackCatalog = try {
        catalogAdapter.fromJson(json) ?: throw PackFailure.InvalidArchive("Pack catalog is empty")
    } catch (failure: PackFailure) {
        throw failure
    } catch (error: Throwable) {
        throw PackFailure.InvalidArchive("Could not parse pack catalog", error)
    }
}
