package com.photoframe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PhotoEntity::class, SourceEntity::class],
    version = 1,
    // Schema export is deferred to Phase 2, when network sources stabilize the schema and
    // real migrations begin; a personal sideloaded app can fall back to destructive
    // migration in the meantime.
    exportSchema = false,
)
abstract class PhotoFrameDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun sourceDao(): SourceDao

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
