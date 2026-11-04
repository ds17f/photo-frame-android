package com.photoframe.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoEntity::class, SourceEntity::class, SyncLogEntity::class, ShownHistoryEntity::class],
    version = 6,
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

        /**
         * v2 → v3 (streaming-cache model): relax `photos.localPath` to nullable so a row can be
         * INDEXED (metadata only). SQLite can't drop a NOT NULL constraint in place, so the table is
         * rebuilt. Existing rows are all cached files → they carry over as `READY` with their path
         * intact (warm start: nothing re-downloads, show history preserved).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photos_new` (" +
                        "`sourceId` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `localPath` TEXT, " +
                        "`takenAt` INTEGER, `width` INTEGER, `height` INTEGER, `locationLabel` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, `lastShownAt` INTEGER, `sizeBytes` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `contentHash` TEXT, " +
                        "PRIMARY KEY(`sourceId`, `remoteId`))",
                )
                db.execSQL(
                    "INSERT INTO `photos_new` (sourceId, remoteId, localPath, takenAt, width, height, " +
                        "locationLabel, addedAt, lastShownAt, sizeBytes, status, contentHash) " +
                        "SELECT sourceId, remoteId, localPath, takenAt, width, height, locationLabel, " +
                        "addedAt, lastShownAt, sizeBytes, status, contentHash FROM `photos`",
                )
                db.execSQL("DROP TABLE `photos`")
                db.execSQL("ALTER TABLE `photos_new` RENAME TO `photos`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_status` ON `photos` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_addedAt` ON `photos` (`addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_lastShownAt` ON `photos` (`lastShownAt`)")
            }
        }

        /**
         * v3 → v4: add `photos.isFavorite` (favorites bias). Additive column, so an in-place
         * ALTER preserves the cache and show history; existing rows default to not-favorite and
         * pick up their real status on the next metadata sync.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `photos` ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5: add `photos.missStreak` (debounced mirror deletion). Additive column; existing
         * rows default to 0 (a fresh streak) and accrue misses only if the listing stops covering
         * them. In-place ALTER preserves the cache and show history.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `photos` ADD COLUMN `missStreak` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: add `shown_history`, the show-history table that sync never deletes from.
         * Backfilled from the current `photos.lastShownAt`, so nothing already shown is forgotten.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shown_history` (`sourceId` TEXT NOT NULL, " +
                        "`remoteId` TEXT NOT NULL, `lastShownAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `remoteId`))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `shown_history` (sourceId, remoteId, lastShownAt) " +
                        "SELECT sourceId, remoteId, lastShownAt FROM `photos` WHERE lastShownAt IS NOT NULL",
                )
            }
        }

        fun get(context: Context): PhotoFrameDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoFrameDatabase::class.java,
                    "photoframe.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration() // backstop for any other version gap
                    .build()
                    .also { instance = it }
            }
    }
}
