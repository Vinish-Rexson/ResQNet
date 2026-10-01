package com.resqnet.app.navigation

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class Shelter(
    val id: String,
    val name: String,
    val longitude: Double,
    val latitude: Double,
    val address: String?,
    val notes: String?,
    val source: String?,
    val verified: Boolean,
    val lastVerified: String?
)

/** Reads only the curated shelter catalog packaged with an installed region. */
class ShelterRepository {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ShelterFeatureCollection::class.java)

    fun load(pack: InstalledRegionPack): List<Shelter> {
        val catalog = File(pack.directory, "shelters.geojson")
        if (!catalog.isFile) return emptyList()
        val collection = adapter.fromJson(catalog.readText()) ?: return emptyList()
        return collection.features.mapNotNull { feature ->
            val coordinates = feature.geometry.coordinates
            val properties = feature.properties
            if (feature.geometry.type != "Point" || coordinates.size < 2 || properties.id.isBlank() || properties.name.isBlank()) null
            else Shelter(
                id = properties.id,
                name = properties.name,
                longitude = coordinates[0],
                latitude = coordinates[1],
                address = properties.address,
                notes = properties.notes,
                source = properties.source,
                verified = properties.verificationStatus.equals("verified", ignoreCase = true),
                lastVerified = properties.lastVerified
            )
        }
    }
}

private data class ShelterFeatureCollection(val features: List<ShelterFeature> = emptyList())
private data class ShelterFeature(val properties: ShelterProperties, val geometry: ShelterGeometry)
private data class ShelterProperties(
    val id: String = "", val name: String = "", val address: String? = null,
    val notes: String? = null, val source: String? = null,
    val verificationStatus: String? = null, val lastVerified: String? = null
)
private data class ShelterGeometry(val type: String = "", val coordinates: List<Double> = emptyList())
