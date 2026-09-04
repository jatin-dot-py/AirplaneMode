package com.airplanemode.media.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.security.MessageDigest

class MediaRepository private constructor(context: Context) {
  private val appContext = context.applicationContext
  private val database = MediaDatabase.get(appContext)
  private val dao = database.mediaDao()

  fun all(): List<MediaItemEntity> = dao.allMedia()

  fun byId(id: String): MediaItemEntity? = dao.mediaById(id)

  fun bySourceKey(source: String, sourceKey: String): MediaItemEntity? =
    dao.mediaBySourceKey(source, sourceKey)

  fun byDownloadId(downloadId: String): MediaItemEntity? =
    dao.mediaByDownloadId(downloadId)

  fun put(item: MediaItemEntity) {
    if (dao.insertMedia(item) == -1L) dao.updateMedia(item)
  }

  fun updateAvailability(id: String, availability: String) =
    dao.updateAvailability(id, availability, System.currentTimeMillis())

  fun updateArtwork(id: String, path: String) =
    dao.updateArtwork(id, path, System.currentTimeMillis())

  fun useFallbackArtwork(id: String, marker: String) =
    dao.useFallbackArtwork(id, marker, System.currentTimeMillis())

  fun putCollection(source: String, sourceKey: String, name: String): CollectionEntity {
    val now = System.currentTimeMillis()
    val existing = dao.collectionBySourceKey(source, sourceKey)
    val collection = existing?.copy(name = name, updatedAt = now) ?: CollectionEntity(
      id = stableId("collection", "$source:$sourceKey"),
      source = source,
      sourceKey = sourceKey,
      name = name,
      createdAt = now,
      updatedAt = now,
    )
    if (dao.insertCollection(collection) == -1L) dao.updateCollection(collection)
    return collection
  }

  fun addToCollection(collectionId: String, mediaId: String, position: Int) =
    dao.putMembership(CollectionMembershipEntity(collectionId, mediaId, position))

  fun putWaitingJob(mediaId: String) {
    val item = byId(mediaId) ?: return
    val sourceUrl = if (item.source == "youtube-music") {
      "https://music.youtube.com/watch?v=${item.sourceKey}"
    } else {
      "https://www.youtube.com/watch?v=${item.sourceKey}"
    }
    putYtDlpJob(mediaId, sourceUrl, "queued")
  }

  fun putYtDlpJob(mediaId: String, sourceUrl: String, status: String = "queued") {
    val now = System.currentTimeMillis()
    val existing = dao.downloadJobForMedia(mediaId)
    dao.putDownloadJob(
      DownloadJobEntity(
        id = existing?.id ?: stableId("job", mediaId),
        mediaItemId = mediaId,
        resolver = "yt-dlp",
        sourceUrl = sourceUrl,
        status = status,
        progress = if (status == "ready") 1.0 else 0.0,
        resolvedUri = null,
        downloadId = null,
        workId = null,
        attemptCount = existing?.attemptCount ?: 0,
        outputOwnership = "app-owned",
        error = null,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      ),
    )
    if (status != "ready") dao.prepareAppDownload(mediaId, status, 0.0, now)
  }

  fun downloadJob(mediaId: String): DownloadJobEntity? = dao.downloadJobForMedia(mediaId)

  fun pendingYtDlpJobs(): List<DownloadJobEntity> = dao.pendingYtDlpJobs()

  fun setWorkId(mediaId: String, workId: String?) =
    dao.updateWorkId(mediaId, workId, System.currentTimeMillis())

  fun localPlaylists(): List<LocalPlaylistSummary> = dao.allLocalPlaylists()

  fun localPlaylistById(id: String): LocalPlaylistEntity? = dao.localPlaylistById(id)

  fun createLocalPlaylist(name: String, mediaIds: List<String>): LocalPlaylistEntity {
    val cleanName = name.trim().take(80)
    require(cleanName.isNotEmpty()) { "Playlist name cannot be empty." }
    require(dao.localPlaylistByName(cleanName) == null) { "A playlist with this name already exists." }
    val now = System.currentTimeMillis()
    val playlist = LocalPlaylistEntity(
      id = stableId("local-playlist", "$now:$cleanName"),
      name = cleanName,
      pinned = true,
      createdAt = now,
      updatedAt = now,
    )
    dao.insertLocalPlaylist(playlist)
    addItemsToLocalPlaylist(playlist.id, mediaIds)
    return playlist
  }

  fun addItemsToLocalPlaylist(playlistId: String, mediaIds: List<String>) {
    require(dao.localPlaylistById(playlistId) != null) { "Playlist does not exist." }
    var position = dao.nextLocalPlaylistPosition(playlistId)
    val now = System.currentTimeMillis()
    mediaIds.distinct().filter { dao.mediaById(it) != null }.forEach { mediaId ->
      dao.putLocalPlaylistItem(LocalPlaylistItemEntity(playlistId, mediaId, position++, now))
    }
    dao.touchLocalPlaylist(playlistId, now)
  }

  fun removeItemsFromLocalPlaylist(playlistId: String, mediaIds: List<String>) {
    if (mediaIds.isEmpty()) return
    dao.deleteItemsFromLocalPlaylist(playlistId, mediaIds.distinct())
    dao.touchLocalPlaylist(playlistId, System.currentTimeMillis())
  }

  fun localPlaylistItems(playlistId: String): List<MediaItemEntity> =
    dao.mediaForLocalPlaylist(playlistId)

  fun setLocalPlaylistPinned(playlistId: String, pinned: Boolean) =
    dao.updateLocalPlaylistPin(playlistId, pinned, System.currentTimeMillis())

  fun deleteLocalPlaylist(playlistId: String) {
    dao.deleteLocalPlaylistItems(playlistId)
    dao.deleteLocalPlaylist(playlistId)
  }

  fun updateAppDownloadState(
    mediaId: String,
    availability: String,
    progress: Double,
    error: String?,
    attemptCount: Int,
  ) {
    val now = System.currentTimeMillis()
    val safeProgress = progress.coerceIn(0.0, 1.0)
    dao.updateAppDownloadState(mediaId, availability, safeProgress, now)
    dao.updateAppDownloadJob(mediaId, availability, safeProgress, error, attemptCount, now)
  }

  fun finishAppDownload(
    mediaId: String,
    path: String,
    durationMs: Long?,
    width: Int?,
    height: Int?,
    title: String?,
    artist: String?,
    attemptCount: Int,
  ) {
    val now = System.currentTimeMillis()
    dao.finishAppDownload(mediaId, path, durationMs, width, height, title, artist, now)
    dao.updateAppDownloadJob(mediaId, "ready", 1.0, null, attemptCount, now)
  }

  fun delete(mediaId: String) {
    dao.deleteMembershipsForMedia(mediaId)
    dao.deleteLocalPlaylistMembershipsForMedia(mediaId)
    dao.deleteDownloadJobForMedia(mediaId)
    dao.deleteMedia(mediaId)
  }

  fun clearAllRows() {
    database.runInTransaction {
      dao.clearLocalPlaylistItems()
      dao.clearLocalPlaylists()
      dao.clearCollectionMemberships()
      dao.clearCollections()
      dao.clearDownloadJobs()
      dao.clearMedia()
    }
    runCatching { database.openHelper.writableDatabase.execSQL("VACUUM") }
  }

  fun prepareDownload(mediaId: String, downloadId: String, resolvedUri: String) {
    val now = System.currentTimeMillis()
    val existing = dao.downloadJobForMedia(mediaId)
    dao.putDownloadJob(
      DownloadJobEntity(
        id = existing?.id ?: stableId("job", mediaId),
        mediaItemId = mediaId,
        resolver = "media3",
        sourceUrl = resolvedUri,
        status = "queued",
        progress = 0.0,
        resolvedUri = resolvedUri,
        downloadId = downloadId,
        workId = null,
        attemptCount = existing?.attemptCount ?: 0,
        outputOwnership = "media3-cache",
        error = null,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      ),
    )
    dao.prepareDownload(mediaId, downloadId, "queued", 0.0, now)
  }

  fun updateDownloadState(
    downloadId: String,
    availability: String,
    progress: Double,
    error: String?,
  ) {
    val now = System.currentTimeMillis()
    dao.updateDownloadState(downloadId, availability, progress, now)
    dao.updateDownloadJob(downloadId, availability, progress, error, now)
  }

  fun playableItems(): List<MediaItemEntity> = all().filter {
    it.availability == "ready" && it.playbackKind != null && it.playbackValue != null
  }

  fun toMedia3(item: MediaItemEntity): MediaItem {
    val extras = Bundle().apply {
      putString("source", item.source)
      putString("mediaType", item.mediaType)
      putString("artworkPath", item.thumbnailLocalPath)
      item.width?.let { putInt("width", it) }
      item.height?.let { putInt("height", it) }
    }
    val metadata = MediaMetadata.Builder()
      .setTitle(item.title)
      .setArtist(item.artist)
      .setIsPlayable(true)
      .setExtras(extras)
      .apply {
        item.thumbnailLocalPath?.let { setArtworkUri(Uri.fromFile(File(it))) }
      }
      .build()

    val playbackUri = when (item.playbackKind) {
      "media3-download" -> dao.downloadJobForMedia(item.id)?.resolvedUri ?: item.playbackValue!!
      "app-file" -> Uri.fromFile(File(item.playbackValue!!)).toString()
      else -> item.playbackValue!!
    }

    return MediaItem.Builder()
      .setMediaId(item.id)
      .setUri(playbackUri)
      .setMediaMetadata(metadata)
      .build()
  }

  companion object {
    @Volatile private var instance: MediaRepository? = null

    fun get(context: Context): MediaRepository = instance ?: synchronized(this) {
      instance ?: MediaRepository(context).also { instance = it }
    }

    fun stableId(namespace: String, value: String): String {
      val digest = MessageDigest.getInstance("SHA-256")
        .digest("$namespace:$value".toByteArray())
      return digest.joinToString("") { "%02x".format(it) }
    }
  }
}
