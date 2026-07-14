package com.photoframe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PhotoEntity::class, SourceEntity::class, SyncLogEntity::class],
    version = 2,
    // Schema export is deferred until the network sources stabilize the schema and real
    // migrations begin; a personal sideloaded app can fall back to destructive migration in
    // the meantime (the cache re-syncs from the source on first run after an upgrade).
    exportSchema = false,
)
abstract class PhotoFrameDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun sourceDao(): SourceDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile private var instance: PhotoFrameDatabase? = null

        fun get(context: Context): PhotoFrameDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoFrameDatabase::class.java,
                    "photoframe.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
