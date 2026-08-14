package com.resqnet.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

const val RESQNET_DATABASE_VERSION = 3
private val DESTRUCTIVE_RESET_FROM_VERSIONS = intArrayOf(1, 2)

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
    ],
    version = RESQNET_DATABASE_VERSION,
    exportSchema = false,
)
abstract class ResQNetDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao

    companion object {
        fun create(context: Context): ResQNetDatabase = Room.databaseBuilder(
            context.applicationContext, ResQNetDatabase::class.java, "resqnet.db"
        ).fallbackToDestructiveMigrationFrom(
            dropAllTables = true,
            *DESTRUCTIVE_RESET_FROM_VERSIONS,
        ).build()
    }
}
