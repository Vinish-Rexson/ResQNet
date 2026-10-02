package com.resqnet.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.resqnet.app.circles.*
import com.resqnet.app.navigation.HazardDao
import com.resqnet.app.navigation.HazardReportEntity

const val RESQNET_DATABASE_VERSION = 6
private val DESTRUCTIVE_RESET_FROM_VERSIONS = intArrayOf(1, 2, 3, 4)

fun canDestructivelyResetFrom(version: Int): Boolean = version in DESTRUCTIVE_RESET_FROM_VERSIONS

@TypeConverters(PersistenceConverters::class)
@Database(
    entities = [
        PacketEntity::class,
        ConversationMessageEntity::class,
        PeerEntity::class,
        ContactEntity::class,
        PeerDeliveryEntity::class,
        MessageReceiptEntity::class,
        LocalStateEntity::class,
        CircleEntity::class,
        CircleInvitationEntity::class,
        CircleSnapshotEntity::class,
        CircleMemberEntity::class,
        CircleMessageEntity::class,
        PendingCirclePacketEntity::class,
        CircleMessageReceiptEntity::class,
        CircleStatusEventEntity::class,
        HazardReportEntity::class,
    ],
    version = RESQNET_DATABASE_VERSION,
    exportSchema = false,
)
abstract class ResQNetDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao
    abstract fun hazardDao(): HazardDao

    companion object {
        fun create(context: Context): ResQNetDatabase = Room.databaseBuilder(
            context.applicationContext, ResQNetDatabase::class.java, "resqnet.db"
        ).fallbackToDestructiveMigrationFrom(
            dropAllTables = true,
            *DESTRUCTIVE_RESET_FROM_VERSIONS,
        ).addMigrations(MIGRATION_5_6).build()

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hazard_reports` (
                      `reportId` TEXT NOT NULL,
                      `type` TEXT NOT NULL,
                      `latitude` REAL NOT NULL,
                      `longitude` REAL NOT NULL,
                      `radiusMeters` INTEGER NOT NULL,
                      `note` TEXT,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      `expiresAt` INTEGER NOT NULL,
                      `resolvedAt` INTEGER,
                      PRIMARY KEY(`reportId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_hazard_reports_expiresAt` ON `hazard_reports` (`expiresAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_hazard_reports_resolvedAt` ON `hazard_reports` (`resolvedAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_hazard_reports_updatedAt` ON `hazard_reports` (`updatedAt`)")
            }
        }
    }
}
