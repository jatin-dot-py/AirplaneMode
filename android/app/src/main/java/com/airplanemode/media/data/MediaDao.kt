package com.airplanemode.media.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MediaDao {
  @Query("SELECT * FROM media_items ORDER BY updatedAt DESC")
  fun allMedia(): List<MediaItemEntity>

  @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
  fun mediaById(id: String): MediaItemEntity?

  @Query("SELECT * FROM media_items WHERE source = :source AND sourceKey = :sourceKey LIMIT 1")
  fun mediaBySourceKey(source: String, sourceKey: String): MediaItemEntity?

  @Query("SELECT * FROM media_items WHERE playbackKind = 'media3-download' AND playbackValue = :downloadId LIMIT 1")
  fun mediaByDownloadId(downloadId: String): MediaItemEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  fun insertMedia(item: MediaItemEntity): Long

  @Update
  fun updateMedia(item: MediaItemEntity)

  @Query("UPDATE media_items SET availability = :availability, updatedAt = :updatedAt WHERE id = :id")
  fun updateAvailability(id: String, availability: String, updatedAt: Long)

  @Query("UPDATE media_items SET thumbnailLocalPath = :path, updatedAt = :updatedAt WHERE id = :id")
  fun updateArtwork(id: String, path: String, updatedAt: Long)

  @Query("UPDATE media_items SET thumbnailRemoteUrl = :marker, thumbnailLocalPath = NULL, updatedAt = :updatedAt WHERE id = :id")
  fun useFallbackArtwork(id: String, marker: String, updatedAt: Long)

  @Query("UPDATE media_items SET playbackKind = NULL, playbackValue = NULL, availability = :availability, downloadProgress = :progress, updatedAt = :updatedAt WHERE id = :id")
  fun prepareAppDownload(
    id: String,
    availability: String,
    progress: Double,
    updatedAt: Long,
  )

  @Query("UPDATE media_items SET availability = :availability, downloadProgress = :progress, updatedAt = :updatedAt WHERE id = :id")
  fun updateAppDownloadState(
    id: String,
    availability: String,
    progress: Double,
    updatedAt: Long,
  )

  @Query("UPDATE media_items SET playbackKind = 'app-file', playbackValue = :path, availability = 'ready', downloadProgress = 1.0, durationMs = COALESCE(:durationMs, durationMs), width = COALESCE(:width, width), height = COALESCE(:height, height), title = CASE WHEN :title IS NULL OR :title = '' THEN title ELSE :title END, artist = COALESCE(:artist, artist), updatedAt = :updatedAt WHERE id = :id")
  fun finishAppDownload(
    id: String,
    path: String,
    durationMs: Long?,
    width: Int?,
    height: Int?,
    title: String?,
    artist: String?,
    updatedAt: Long,
  )

  @Query("UPDATE media_items SET playbackKind = 'media3-download', playbackValue = :downloadId, availability = :availability, downloadProgress = :progress, updatedAt = :updatedAt WHERE id = :id")
  fun prepareDownload(
    id: String,
    downloadId: String,
    availability: String,
    progress: Double,
    updatedAt: Long,
  )

  @Query("UPDATE media_items SET availability = :availability, downloadProgress = :progress, updatedAt = :updatedAt WHERE playbackKind = 'media3-download' AND playbackValue = :downloadId")
  fun updateDownloadState(
    downloadId: String,
    availability: String,
    progress: Double,
    updatedAt: Long,
  )

  @Query("SELECT * FROM collections WHERE source = :source AND sourceKey = :sourceKey LIMIT 1")
  fun collectionBySourceKey(source: String, sourceKey: String): CollectionEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  fun insertCollection(collection: CollectionEntity): Long

  @Update
  fun updateCollection(collection: CollectionEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putMembership(membership: CollectionMembershipEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putDownloadJob(job: DownloadJobEntity)

  @Query("SELECT * FROM download_jobs WHERE mediaItemId = :mediaItemId LIMIT 1")
  fun downloadJobForMedia(mediaItemId: String): DownloadJobEntity?

  @Query("SELECT * FROM download_jobs WHERE resolver = 'yt-dlp' AND status IN ('waiting_for_resolver', 'queued', 'downloading') ORDER BY createdAt")
  fun pendingYtDlpJobs(): List<DownloadJobEntity>

  @Query("UPDATE download_jobs SET status = :status, progress = :progress, error = :error, attemptCount = :attemptCount, updatedAt = :updatedAt WHERE mediaItemId = :mediaItemId")
  fun updateAppDownloadJob(
    mediaItemId: String,
    status: String,
    progress: Double,
    error: String?,
    attemptCount: Int,
    updatedAt: Long,
  )

  @Query("UPDATE download_jobs SET workId = :workId, updatedAt = :updatedAt WHERE mediaItemId = :mediaItemId")
  fun updateWorkId(mediaItemId: String, workId: String?, updatedAt: Long)

  @Query("UPDATE download_jobs SET status = :status, progress = :progress, error = :error, updatedAt = :updatedAt WHERE downloadId = :downloadId")
  fun updateDownloadJob(
    downloadId: String,
    status: String,
    progress: Double,
    error: String?,
    updatedAt: Long,
  )

  @Query("DELETE FROM collection_memberships WHERE mediaItemId = :mediaItemId")
  fun deleteMembershipsForMedia(mediaItemId: String)

  @Query(
    "SELECT p.id, p.name, p.pinned, p.createdAt, p.updatedAt, " +
      "COUNT(i.mediaItemId) AS itemCount FROM local_playlists p " +
      "LEFT JOIN local_playlist_items i ON i.playlistId = p.id " +
      "GROUP BY p.id ORDER BY p.pinned DESC, p.updatedAt DESC",
  )
  fun allLocalPlaylists(): List<LocalPlaylistSummary>

  @Query("SELECT * FROM local_playlists WHERE id = :id LIMIT 1")
  fun localPlaylistById(id: String): LocalPlaylistEntity?

  @Query("SELECT * FROM local_playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
  fun localPlaylistByName(name: String): LocalPlaylistEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  fun insertLocalPlaylist(playlist: LocalPlaylistEntity)

  @Update
  fun updateLocalPlaylist(playlist: LocalPlaylistEntity)

  @Query("UPDATE local_playlists SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
  fun updateLocalPlaylistPin(id: String, pinned: Boolean, updatedAt: Long)

  @Query("UPDATE local_playlists SET updatedAt = :updatedAt WHERE id = :id")
  fun touchLocalPlaylist(id: String, updatedAt: Long)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun putLocalPlaylistItem(item: LocalPlaylistItemEntity)

  @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM local_playlist_items WHERE playlistId = :playlistId")
  fun nextLocalPlaylistPosition(playlistId: String): Int

  @Query(
    "SELECT m.* FROM media_items m INNER JOIN local_playlist_items i " +
      "ON i.mediaItemId = m.id WHERE i.playlistId = :playlistId ORDER BY i.position",
  )
  fun mediaForLocalPlaylist(playlistId: String): List<MediaItemEntity>

  @Query("DELETE FROM local_playlist_items WHERE playlistId = :playlistId AND mediaItemId IN (:mediaItemIds)")
  fun deleteItemsFromLocalPlaylist(playlistId: String, mediaItemIds: List<String>)

  @Query("DELETE FROM local_playlist_items WHERE playlistId = :playlistId")
  fun deleteLocalPlaylistItems(playlistId: String)

  @Query("DELETE FROM local_playlist_items WHERE mediaItemId = :mediaItemId")
  fun deleteLocalPlaylistMembershipsForMedia(mediaItemId: String)

  @Query("DELETE FROM local_playlists WHERE id = :playlistId")
  fun deleteLocalPlaylist(playlistId: String)

  @Query("DELETE FROM download_jobs WHERE mediaItemId = :mediaItemId")
  fun deleteDownloadJobForMedia(mediaItemId: String)

  @Query("DELETE FROM media_items WHERE id = :mediaItemId")
  fun deleteMedia(mediaItemId: String)

  @Query("DELETE FROM local_playlist_items")
  fun clearLocalPlaylistItems()

  @Query("DELETE FROM local_playlists")
  fun clearLocalPlaylists()

  @Query("DELETE FROM collection_memberships")
  fun clearCollectionMemberships()

  @Query("DELETE FROM collections")
  fun clearCollections()

  @Query("DELETE FROM download_jobs")
  fun clearDownloadJobs()

  @Query("DELETE FROM media_items")
  fun clearMedia()
}
