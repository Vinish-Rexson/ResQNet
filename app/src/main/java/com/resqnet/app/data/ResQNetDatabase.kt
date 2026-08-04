package com.resqnet.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, PeerEntity::class, PeerDeliveryEntity::class, LocalStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ResQNetDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao

    companion object {
        fun create(context: Context): ResQNetDatabase = Room.databaseBuilder(
            context.applicationContext, ResQNetDatabase::class.java, "resqnet.db"
        ).build()
    }
}
