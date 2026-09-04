package com.airplanemode.doomscroll.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DoomscrollDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putSession(session: CaptureSessionEntity)

  @Query("SELECT * FROM doomscroll_capture_sessions WHERE id = :id LIMIT 1")
  fun sessionById(id: String): CaptureSessionEntity?

  @Query("SELECT * FROM doomscroll_capture_sessions ORDER BY startedAt DESC, id DESC")
  fun allSessions(): List<CaptureSessionEntity>

  @Query("DELETE FROM doomscroll_capture_sessions WHERE id = :id")
  fun deleteSession(id: String)

  @Query("SELECT * FROM doomscroll_reels WHERE mediaPk = :mediaPk LIMIT 1")
  fun reelByPk(mediaPk: String): ReelEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putReel(reel: ReelEntity)

  @Query("SELECT * FROM doomscroll_reels ORDER BY latestSessionStartedAt DESC, latestPosition ASC")
  fun allReels(): List<ReelEntity>

  @Query("DELETE FROM doomscroll_reels WHERE mediaPk = :mediaPk")
  fun deleteReel(mediaPk: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putSnapshotReel(item: SnapshotReelEntity)

  @Query(
    "SELECT * FROM doomscroll_snapshot_reels " +
      "WHERE snapshotId = :snapshotId ORDER BY position ASC, mediaPk ASC",
  )
  fun snapshotReels(snapshotId: String): List<SnapshotReelEntity>

  @Query(
    "SELECT * FROM doomscroll_snapshot_reels " +
      "WHERE snapshotId = :snapshotId AND mediaPk = :mediaPk LIMIT 1",
  )
  fun snapshotReel(snapshotId: String, mediaPk: String): SnapshotReelEntity?

  @Query("SELECT COUNT(*) FROM doomscroll_snapshot_reels WHERE snapshotId = :snapshotId")
  fun snapshotReelCount(snapshotId: String): Int

  @Query(
    "SELECT COUNT(*) FROM doomscroll_snapshot_reels " +
      "WHERE snapshotId = :snapshotId AND qualifiedWatchedAt IS NOT NULL",
  )
  fun watchedCount(snapshotId: String): Int

  @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM doomscroll_snapshot_reels WHERE snapshotId = :snapshotId")
  fun nextSnapshotPosition(snapshotId: String): Int

  @Query("SELECT mediaPk FROM doomscroll_snapshot_reels WHERE snapshotId = :snapshotId")
  fun snapshotMediaPks(snapshotId: String): List<String>

  @Query("SELECT COUNT(*) FROM doomscroll_snapshot_reels WHERE mediaPk = :mediaPk")
  fun snapshotReferenceCount(mediaPk: String): Int

  @Query("DELETE FROM doomscroll_snapshot_reels WHERE snapshotId = :snapshotId")
  fun deleteSnapshotReels(snapshotId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putSnapshotProgress(progress: SnapshotProgressEntity)

  @Query("SELECT * FROM doomscroll_snapshot_progress WHERE snapshotId = :snapshotId LIMIT 1")
  fun snapshotProgress(snapshotId: String): SnapshotProgressEntity?

  @Query("DELETE FROM doomscroll_snapshot_progress WHERE snapshotId = :snapshotId")
  fun deleteSnapshotProgress(snapshotId: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putDownload(download: ReelDownloadEntity)

  @Query("SELECT * FROM doomscroll_downloads WHERE mediaPk = :mediaPk LIMIT 1")
  fun downloadByPk(mediaPk: String): ReelDownloadEntity?

  @Query("SELECT * FROM doomscroll_downloads")
  fun allDownloads(): List<ReelDownloadEntity>

  @Query(
    "SELECT d.* FROM doomscroll_downloads d " +
      "INNER JOIN doomscroll_snapshot_reels s ON s.mediaPk = d.mediaPk " +
      "WHERE s.snapshotId = :snapshotId ORDER BY s.position ASC",
  )
  fun downloadsForSnapshot(snapshotId: String): List<ReelDownloadEntity>

  @Query("SELECT COUNT(*) FROM doomscroll_downloads WHERE profilePicLocalPath = :path")
  fun profilePathReferenceCount(path: String): Int

  @Query("DELETE FROM doomscroll_downloads WHERE mediaPk = :mediaPk")
  fun deleteDownload(mediaPk: String)

  @Query("SELECT COUNT(*) FROM doomscroll_reels")
  fun reelCount(): Int

  @Query("SELECT COUNT(*) FROM doomscroll_downloads WHERE state = :state")
  fun downloadCount(state: String): Int

  @Query("SELECT COALESCE(SUM(localBytes), 0) FROM doomscroll_downloads")
  fun downloadedBytes(): Long

  @Query(
    "SELECT COALESCE(SUM(CASE WHEN estimatedBytes > localBytes " +
      "THEN estimatedBytes - localBytes ELSE 0 END), 0) " +
      "FROM doomscroll_downloads WHERE state IN ('queued', 'downloading')",
  )
  fun pendingDownloadBytes(): Long

  @Query(
    "UPDATE doomscroll_capture_sessions SET state = 'stopped', finishedAt = :finishedAt, " +
      "stopReason = 'app-restarted', updatedAt = :finishedAt WHERE state = 'capturing'",
  )
  fun recoverInterruptedSessions(finishedAt: Long)

  @Query("DELETE FROM doomscroll_snapshot_progress")
  fun clearSnapshotProgress()

  @Query("DELETE FROM doomscroll_snapshot_reels")
  fun clearSnapshotReels()

  @Query("DELETE FROM doomscroll_downloads")
  fun clearDownloads()

  @Query("DELETE FROM doomscroll_reels")
  fun clearReels()

  @Query("DELETE FROM doomscroll_capture_sessions")
  fun clearSessions()
}
