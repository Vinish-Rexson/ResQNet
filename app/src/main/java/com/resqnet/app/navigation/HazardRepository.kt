package com.resqnet.app.navigation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.UUID

@Dao
interface HazardDao {
    @Query("SELECT * FROM hazard_reports WHERE resolvedAt IS NULL AND expiresAt > :now ORDER BY updatedAt DESC")
    fun observeActive(now: Long): Flow<List<HazardReportEntity>>

    @Query("SELECT * FROM hazard_reports WHERE resolvedAt IS NULL AND expiresAt > :now ORDER BY updatedAt DESC")
    suspend fun active(now: Long): List<HazardReportEntity>

    @Query("SELECT * FROM hazard_reports WHERE reportId = :reportId")
    suspend fun find(reportId: String): HazardReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: HazardReportEntity)

    @Query("UPDATE hazard_reports SET resolvedAt = :resolvedAt, updatedAt = :resolvedAt WHERE reportId = :reportId AND resolvedAt IS NULL")
    suspend fun resolve(reportId: String, resolvedAt: Long): Int

    @Query("DELETE FROM hazard_reports WHERE reportId = :reportId")
    suspend fun delete(reportId: String)
}

class HazardRepository(private val dao: HazardDao, private val now: () -> Long = System::currentTimeMillis) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActive(): Flow<List<HazardReport>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(now())
            delay(EXPIRY_REFRESH_MILLIS)
        }
    }.flatMapLatest { instant ->
        dao.observeActive(instant)
    }.map { reports -> reports.map(HazardReportEntity::toDomain) }

    suspend fun activeNow(): List<HazardReport> = dao.active(now()).map(HazardReportEntity::toDomain)

    suspend fun create(type: HazardType, center: GeoPoint, radiusMeters: Int, expiresAt: Long, note: String?): HazardReport {
        center.requireValid()
        require(radiusMeters in AvoidanceArea.MIN_RADIUS_METERS..AvoidanceArea.MAX_RADIUS_METERS) { "Choose a radius between 25 and 250 metres" }
        val timestamp = now()
        require(expiresAt > timestamp) { "Expiry must be in the future" }
        val report = HazardReportEntity(
            reportId = UUID.randomUUID().toString(), type = type, latitude = center.latitude, longitude = center.longitude,
            radiusMeters = radiusMeters, note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(240),
            createdAt = timestamp, updatedAt = timestamp, expiresAt = expiresAt,
        )
        dao.upsert(report)
        return report.toDomain()
    }

    suspend fun update(existing: HazardReport, type: HazardType, radiusMeters: Int, expiresAt: Long, note: String?): HazardReport {
        require(radiusMeters in AvoidanceArea.MIN_RADIUS_METERS..AvoidanceArea.MAX_RADIUS_METERS) { "Choose a radius between 25 and 250 metres" }
        val timestamp = now()
        require(expiresAt > timestamp) { "Expiry must be in the future" }
        val report = HazardReportEntity(existing.id, type, existing.center.latitude, existing.center.longitude, radiusMeters,
            note?.trim()?.takeIf { it.isNotEmpty() }?.take(240), existing.createdAt, timestamp, expiresAt, null)
        dao.upsert(report)
        return report.toDomain()
    }

    /** Applies a verified mesh report. Older copies must not overwrite a newer local edit. */
    suspend fun upsertFromMesh(report: HazardReport): Boolean {
        val existing = dao.find(report.id)
        if (existing != null && existing.updatedAt > report.updatedAt) return false
        dao.upsert(HazardReportEntity(
            reportId = report.id, type = report.type, latitude = report.center.latitude,
            longitude = report.center.longitude, radiusMeters = report.radiusMeters, note = report.note,
            createdAt = report.createdAt, updatedAt = report.updatedAt, expiresAt = report.expiresAt,
            resolvedAt = report.resolvedAt,
        ))
        return true
    }

    suspend fun resolve(id: String) = dao.resolve(id, now())
    suspend fun delete(id: String) = dao.delete(id)

    private companion object {
        const val EXPIRY_REFRESH_MILLIS = 60_000L
    }
}
