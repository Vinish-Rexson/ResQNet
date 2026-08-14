package com.resqnet.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@TypeConverters(PersistenceConverters::class)
@Database(
    entities = [
        PacketEntity::class,
        ConversationMessageEntity::class,
        PeerEntity::class,
        PeerDeliveryEntity::class,
        MessageReceiptEntity::class,
        LocalStateEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ResQNetDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao

    companion object {
        fun create(context: Context): ResQNetDatabase = Room.databaseBuilder(
            context.applicationContext, ResQNetDatabase::class.java, "resqnet.db"
        ).fallbackToDestructiveMigration(true).build()
    }
}
