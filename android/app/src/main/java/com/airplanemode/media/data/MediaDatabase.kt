package com.airplanemode.media.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    MediaItemEntity::class,
    CollectionEntity::class,
    CollectionMembershipEntity::class,
    DownloadJobEntity::class,
    LocalPlaylistEntity::class,
    LocalPlaylistItemEntity::class,
  ],
  version = 3,
  exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {
  abstract fun mediaDao(): MediaDao

  companion object {
    @Volatile private var instance: MediaDatabase? = null

    fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
      instance ?: Room.databaseBuilder(
        context.applicationContext,
        MediaDatabase::class.java,
        "airplane-mode-media.db",
      ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
        .also { instance = it }
    }

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE download_jobs ADD COLUMN resolver TEXT NOT NULL DEFAULT 'media3'")
        database.execSQL("ALTER TABLE download_jobs ADD COLUMN sourceUrl TEXT")
        database.execSQL("ALTER TABLE download_jobs ADD COLUMN workId TEXT")
        database.execSQL("ALTER TABLE download_jobs ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE download_jobs ADD COLUMN outputOwnership TEXT")
        database.execSQL(
          "UPDATE download_jobs SET resolver = 'yt-dlp', status = 'queued', outputOwnership = 'app-owned' " +
            "WHERE mediaItemId IN (SELECT id FROM media_items WHERE source IN ('youtube', 'youtube-music'))",
        )
        database.execSQL(
          "UPDATE media_items SET availability = 'queued' " +
            "WHERE source IN ('youtube', 'youtube-music') AND availability = 'waiting_for_resolver'",
        )
      }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          "CREATE TABLE IF NOT EXISTS local_playlists (" +
            "id TEXT NOT NULL, name TEXT NOT NULL, pinned INTEGER NOT NULL, " +
            "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS index_local_playlists_name ON local_playlists(name)",
        )
        database.execSQL(
          "CREATE TABLE IF NOT EXISTS local_playlist_items (" +
            "playlistId TEXT NOT NULL, mediaItemId TEXT NOT NULL, position INTEGER NOT NULL, " +
            "addedAt INTEGER NOT NULL, PRIMARY KEY(playlistId, mediaItemId))",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS index_local_playlist_items_mediaItemId " +
            "ON local_playlist_items(mediaItemId)",
        )
      }
    }
  }
}
