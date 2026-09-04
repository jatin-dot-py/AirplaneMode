package com.airplanemode.doomscroll.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    CaptureSessionEntity::class,
    ReelEntity::class,
    SnapshotReelEntity::class,
    SnapshotProgressEntity::class,
    ReelDownloadEntity::class,
  ],
  version = 2,
  exportSchema = false,
)
abstract class DoomscrollDatabase : RoomDatabase() {
  abstract fun doomscrollDao(): DoomscrollDao

  companion object {
    @Volatile private var instance: DoomscrollDatabase? = null

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE doomscroll_capture_sessions ADD COLUMN name TEXT NOT NULL DEFAULT ''")
        database.execSQL(
          "ALTER TABLE doomscroll_capture_sessions ADD COLUMN qualityPolicy TEXT NOT NULL DEFAULT 'smart_hq'",
        )
        database.execSQL("ALTER TABLE doomscroll_capture_sessions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        database.execSQL(
          "ALTER TABLE doomscroll_downloads ADD COLUMN qualityPolicy TEXT NOT NULL DEFAULT 'smart_hq'",
        )
        database.execSQL("ALTER TABLE doomscroll_downloads ADD COLUMN selectedWidth INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE doomscroll_downloads ADD COLUMN selectedHeight INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE doomscroll_downloads ADD COLUMN estimatedBytes INTEGER NOT NULL DEFAULT 0")
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS doomscroll_snapshot_reels (
            snapshotId TEXT NOT NULL,
            mediaPk TEXT NOT NULL,
            position INTEGER NOT NULL,
            capturedAt INTEGER NOT NULL,
            snapshotMetadataJson TEXT NOT NULL,
            qualifiedWatchedAt INTEGER,
            activePlaybackMs INTEGER NOT NULL,
            lastPlaybackPositionMs INTEGER NOT NULL,
            firstViewedAt INTEGER,
            lastViewedAt INTEGER,
            PRIMARY KEY(snapshotId, mediaPk)
          )
          """.trimIndent(),
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS index_doomscroll_snapshot_reels_snapshotId_position " +
            "ON doomscroll_snapshot_reels(snapshotId, position)",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS index_doomscroll_snapshot_reels_mediaPk " +
            "ON doomscroll_snapshot_reels(mediaPk)",
        )
        database.execSQL(
          "CREATE INDEX IF NOT EXISTS index_doomscroll_snapshot_reels_snapshotId_qualifiedWatchedAt " +
            "ON doomscroll_snapshot_reels(snapshotId, qualifiedWatchedAt)",
        )
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS doomscroll_snapshot_progress (
            snapshotId TEXT NOT NULL,
            resumePosition INTEGER NOT NULL,
            currentPosition INTEGER NOT NULL,
            currentMediaPk TEXT,
            currentPlaybackMs INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY(snapshotId)
          )
          """.trimIndent(),
        )
        database.execSQL(
          """
          INSERT OR IGNORE INTO doomscroll_snapshot_reels (
            snapshotId, mediaPk, position, capturedAt, snapshotMetadataJson,
            qualifiedWatchedAt, activePlaybackMs, lastPlaybackPositionMs, firstViewedAt, lastViewedAt
          )
          SELECT latestSessionId, mediaPk, latestPosition, lastCapturedAt, '{}',
            NULL, 0, 0, NULL, NULL
          FROM doomscroll_reels
          """.trimIndent(),
        )
        database.execSQL(
          """
          UPDATE doomscroll_capture_sessions
          SET name = CASE WHEN name = '' THEN 'Recovered snapshot' ELSE name END,
              updatedAt = CASE WHEN updatedAt = 0 THEN startedAt ELSE updatedAt END
          """.trimIndent(),
        )
      }
    }

    fun get(context: Context): DoomscrollDatabase = instance ?: synchronized(this) {
      instance ?: Room.databaseBuilder(
        context.applicationContext,
        DoomscrollDatabase::class.java,
        "airplane-mode-doomscroll.db",
      )
        .addMigrations(MIGRATION_1_2)
        .build()
        .also { instance = it }
    }
  }
}
