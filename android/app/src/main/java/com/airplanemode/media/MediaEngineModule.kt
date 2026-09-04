package com.airplanemode.media

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.airplanemode.media.data.MediaItemEntity
import com.airplanemode.media.data.MediaRepository
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal fun downloadStateRequiresLibraryRefresh(state: String): Boolean =
  state != "downloading"

@androidx.annotation.OptIn(UnstableApi::class)
class MediaEngineModule(
  private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
  private val repository = MediaRepository.get(reactContext)
  private val ioExecutor = Executors.newSingleThreadExecutor()
  // Playback must never wait behind library scans, artwork repair, or download bookkeeping.
  private val playbackExecutor = Executors.newSingleThreadExecutor()
  private val downloadStateExecutor = Executors.newSingleThreadExecutor()
  private val artworkExecutor = Executors.newFixedThreadPool(ARTWORK_DOWNLOAD_WORKERS)
  private val artworkInFlight = ConcurrentHashMap.newKeySet<String>()
  private val playbackRequestSequence = AtomicLong(0L)
  private var pendingImportPromise: Promise? = null
  private var listenerCount = 0
  private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private val libraryEventRunnable = Runnable {
    emitEvent(LIBRARY_EVENT, Arguments.createMap())
  }
  private val downloadEventReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != DownloadEvents.ACTION) return
      val itemId = intent.getStringExtra(DownloadEvents.EXTRA_ITEM_ID) ?: return
      val state = intent.getStringExtra(DownloadEvents.EXTRA_STATE) ?: return
      val progress = intent.getDoubleExtra(DownloadEvents.EXTRA_PROGRESS, 0.0)
      val error = intent.getStringExtra(DownloadEvents.EXTRA_ERROR)
      emitDownloadState(itemId, state, progress, error)
      // Progress is delivered directly to the library UI. A full Room query for every
      // progress tick used to build a large FIFO backlog in front of playMedia().
      if (downloadStateRequiresLibraryRefresh(state)) emitLibraryChanged()
    }
  }
  private val videoPlayerReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != VideoPlayerActivity.ACTION_CLOSED) return
      emitEvent(VIDEO_PLAYER_CLOSED_EVENT, Arguments.createMap())
    }
  }

  private val downloadManager by lazy { DownloadStore.downloadManager(reactContext) }
  private val downloadListener = object : DownloadManager.Listener {
    override fun onDownloadChanged(
      downloadManager: DownloadManager,
      download: Download,
      finalException: Exception?,
    ) {
      val availability = when (download.state) {
        Download.STATE_COMPLETED -> "ready"
        Download.STATE_DOWNLOADING -> "downloading"
        Download.STATE_FAILED -> "failed"
        else -> "queued"
      }
      val progress = when {
        download.state == Download.STATE_COMPLETED -> 1.0
        download.percentDownloaded >= 0 -> download.percentDownloaded.toDouble() / 100.0
        else -> 0.0
      }
      downloadStateExecutor.execute {
        repository.updateDownloadState(
          download.request.id,
          availability,
          progress.coerceIn(0.0, 1.0),
          finalException?.message,
        )
        val itemId = repository.byDownloadId(download.request.id)?.id
        if (itemId != null) {
          emitDownloadState(itemId, availability, progress, finalException?.message)
        }
        if (downloadStateRequiresLibraryRefresh(availability)) emitLibraryChanged()
      }
    }
  }

  private val controllerFuture = MediaController.Builder(
    reactContext,
    SessionToken(reactContext, android.content.ComponentName(reactContext, PlaybackService::class.java)),
  ).buildAsync()

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) = emitPlaybackState()
    override fun onIsPlayingChanged(isPlaying: Boolean) = emitPlaybackState()
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = emitPlaybackState()
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = emitPlaybackState()
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = emitPlaybackState()
  }

  private val progressRunnable = object : Runnable {
    override fun run() {
      if (listenerCount > 0 && controllerFuture.isDone) emitPlaybackState()
      progressHandler.postDelayed(this, 750L)
    }
  }

  private val activityEventListener: ActivityEventListener = object : BaseActivityEventListener() {
    override fun onActivityResult(
      activity: Activity,
      requestCode: Int,
      resultCode: Int,
      data: Intent?,
    ) {
      if (requestCode != GALLERY_REQUEST_CODE) return
      val promise = pendingImportPromise ?: return
      pendingImportPromise = null

      if (resultCode != Activity.RESULT_OK || data == null) {
        promise.resolve(Arguments.createArray())
        return
      }

      val uris = linkedSetOf<Uri>()
      data.data?.let(uris::add)
      data.clipData?.let { clips ->
        for (index in 0 until clips.itemCount) uris.add(clips.getItemAt(index).uri)
      }

      uris.forEach { uri ->
        try {
          reactContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        } catch (_: SecurityException) {
          // Some providers grant a durable URI without exposing this operation.
        }
      }

      ioExecutor.execute {
        try {
          val imported = Arguments.createArray()
          uris.mapNotNull(::importUri).forEach { imported.pushMap(toWritableMap(it)) }
          emitLibraryChanged()
          promise.resolve(imported)
        } catch (error: Exception) {
          promise.reject("gallery_import_failed", error.message, error)
        }
      }
    }
  }

  init {
    reactContext.addActivityEventListener(activityEventListener)
    controllerFuture.addListener(
      {
        try {
          controllerFuture.get().addListener(playerListener)
        } catch (_: Exception) {}
      },
      ContextCompat.getMainExecutor(reactContext),
    )
    progressHandler.post(progressRunnable)
    downloadManager.addListener(downloadListener)
    ContextCompat.registerReceiver(
      reactContext,
      downloadEventReceiver,
      IntentFilter(DownloadEvents.ACTION),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    ContextCompat.registerReceiver(
      reactContext,
      videoPlayerReceiver,
      IntentFilter(VideoPlayerActivity.ACTION_CLOSED),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    ioExecutor.execute(::resumePendingYtDlpJobs)
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun getStatus(promise: Promise) {
    promise.resolve(Arguments.createMap().apply {
      putString("state", "ready")
      putString("platform", "Android / Kotlin")
      putString("engine", "Room + Media3")
      putString("version", "1.0.0")
    })
  }

  @ReactMethod
  fun listMediaItems(filter: String, promise: Promise) {
    ioExecutor.execute {
      try {
        val validated = repository.all()
          .map(::validateGalleryItem)
          .map(::validateOwnedItem)
          .map(::ensureOfflineArtwork)
        val items = when (filter.lowercase()) {
          "queued" -> validated.filter {
            it.availability in setOf("waiting_for_resolver", "queued", "downloading", "failed")
          }
          "ready" -> validated.filter { it.availability == "ready" }
          "imported" -> validated.filter { it.source == "gallery" }
          else -> validated
        }
        val result = Arguments.createArray()
        items.forEach { result.pushMap(toWritableMap(it)) }
        promise.resolve(result)
      } catch (error: Exception) {
        promise.reject("library_query_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun addDetectedItems(items: ReadableArray, promise: Promise) {
    queueYouTubeItems("youtube-music", items, promise)
  }

  @ReactMethod
  fun queueYouTubeItems(source: String, items: ReadableArray, promise: Promise) {
    if (source != "youtube-music" && source != "youtube") {
      promise.reject("invalid_source", "Only YouTube and YouTube Music can use this resolver.")
      return
    }
    ioExecutor.execute {
      try {
        var added = 0
        var updated = 0
        val queueRequests = mutableListOf<Pair<String, Boolean>>()

        for (index in 0 until items.size()) {
          val detection = items.getMap(index) ?: continue
          val videoId = detection.string("videoId")?.takeIf(::isYouTubeVideoId) ?: continue
          val now = System.currentTimeMillis()
          val existing = repository.bySourceKey(source, videoId)
          val previousJob = existing?.let { repository.downloadJob(it.id) }
          val readyFile = existing?.takeIf(::hasUsableOwnedMedia)
          val activeJob = existing != null && existing.availability in setOf("queued", "downloading") &&
            previousJob?.workId != null
          val title = detection.string("title")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: existing?.title
            ?: if (source == "youtube-music") {
              "YouTube Music item ${videoId.take(6)}"
            } else {
              "YouTube video ${videoId.take(6)}"
            }
          val artist = detection.string("artist")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: existing?.artist
          val artworkCandidates = linkedSetOf<String>()
          detection.stringArray("thumbnailCandidates")
            .mapNotNull(::usableArtworkUrl)
            .forEach(artworkCandidates::add)
          usableArtworkUrl(detection.string("thumbnailUrl"))?.let(artworkCandidates::add)
          usableArtworkUrl(existing?.thumbnailRemoteUrl)?.let(artworkCandidates::add)
          artworkCandidates.add(youtubeMaxResolutionArtworkUrl(videoId))
          artworkCandidates.add(youtubeArtworkUrl(videoId))
          val thumbnailUrl = artworkCandidates.firstOrNull()
          val route = detection.string("route") ?: if (source == "youtube-music") {
            "https://music.youtube.com/"
          } else {
            "https://www.youtube.com/"
          }
          val collectionName = detection.string("collectionName")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: if (source == "youtube-music") "YouTube Music" else "YouTube"
          val sourceUrl = canonicalSourceUrl(source, videoId)

          val existingArtwork = existing?.thumbnailLocalPath?.takeIf {
            isUsableArtworkFile(it, rejectGenericBranding = true)
          }
          val item = existing?.copy(
            mediaType = if (source == "youtube-music") "audio" else "video",
            title = title,
            artist = artist,
            thumbnailRemoteUrl = thumbnailUrl,
            thumbnailLocalPath = existingArtwork,
            playbackKind = readyFile?.playbackKind,
            playbackValue = readyFile?.playbackValue,
            availability = if (readyFile != null) "ready" else if (activeJob) existing.availability else "queued",
            downloadProgress = if (readyFile != null) 1.0 else if (activeJob) existing.downloadProgress else 0.0,
            collectionName = collectionName,
            updatedAt = now,
          ) ?: MediaItemEntity(
            id = MediaRepository.stableId("media", "$source:$videoId"),
            source = source,
            sourceKey = videoId,
            mediaType = if (source == "youtube-music") "audio" else "video",
            title = title,
            artist = artist,
            durationMs = null,
            width = null,
            height = null,
            thumbnailRemoteUrl = thumbnailUrl,
            thumbnailLocalPath = null,
            playbackKind = null,
            playbackValue = null,
            availability = "queued",
            downloadProgress = 0.0,
            collectionName = collectionName,
            createdAt = now,
            updatedAt = now,
          )

          repository.put(item)
          if (readyFile == null && !activeJob) {
            repository.putYtDlpJob(item.id, sourceUrl)
            queueRequests.add(item.id to (existing?.availability in setOf("failed", "missing")))
          }
          val collection = repository.putCollection(source, route, collectionName)
          repository.addToCollection(collection.id, item.id, index)
          if (existing == null) added++ else updated++
          if (item.thumbnailLocalPath == null) {
            requestArtworkDownload(item, artworkCandidates)
          }
        }

        queueRequests.forEach { (mediaItemId, replace) ->
          val workId = YtDlpQueue.enqueue(reactContext, mediaItemId, replace)
          repository.setWorkId(mediaItemId, workId.toString())
          emitDownloadState(mediaItemId, "queued", 0.0, null)
        }
        emitLibraryChanged()
        promise.resolve(Arguments.createMap().apply {
          putInt("added", added)
          putInt("updated", updated)
          putInt("total", added + updated)
        })
      } catch (error: Exception) {
        promise.reject("queue_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun cancelDownload(mediaItemId: String, promise: Promise) {
    ioExecutor.execute {
      try {
        val item = repository.byId(mediaItemId)
        if (item == null || item.source !in setOf("youtube", "youtube-music") ||
          item.availability !in setOf("waiting_for_resolver", "queued", "downloading")
        ) {
          promise.resolve(false)
          return@execute
        }
        val attempt = repository.downloadJob(mediaItemId)?.attemptCount ?: 0
        repository.updateAppDownloadState(
          mediaItemId,
          "cancelled",
          0.0,
          "CANCELLED: Download cancelled",
          attempt,
        )
        repository.setWorkId(mediaItemId, null)
        YtDlpQueue.cancel(reactContext, mediaItemId)
        deleteDownloadArtifacts(mediaItemId)
        emitDownloadState(mediaItemId, "cancelled", 0.0, "CANCELLED: Download cancelled")
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("download_cancel_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun retryDownload(mediaItemId: String, promise: Promise) {
    ioExecutor.execute {
      try {
        val item = repository.byId(mediaItemId)
        if (item == null || item.source !in setOf("youtube", "youtube-music") ||
          item.availability !in setOf("failed", "missing", "cancelled", "waiting_for_resolver")
        ) {
          promise.resolve(false)
          return@execute
        }
        val sourceUrl = repository.downloadJob(mediaItemId)?.sourceUrl
          ?: canonicalSourceUrl(item.source, item.sourceKey)
        repository.putYtDlpJob(mediaItemId, sourceUrl)
        val workId = YtDlpQueue.enqueue(reactContext, mediaItemId, replace = true)
        repository.setWorkId(mediaItemId, workId.toString())
        emitDownloadState(mediaItemId, "queued", 0.0, null)
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("download_retry_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun removeLibraryItem(mediaItemId: String, promise: Promise) {
    ioExecutor.execute {
      try {
        val item = repository.byId(mediaItemId)
        if (item == null) {
          promise.resolve(false)
          return@execute
        }
        removeLibraryItemInternal(item)
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("library_remove_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun removeLibraryItems(mediaItemIds: ReadableArray, promise: Promise) {
    ioExecutor.execute {
      try {
        var removed = 0
        mediaItemIds.stringValues().distinct().forEach { id ->
          repository.byId(id)?.let { item ->
            removeLibraryItemInternal(item)
            removed++
          }
        }
        emitLibraryChanged()
        promise.resolve(removed)
      } catch (error: Exception) {
        promise.reject("library_batch_remove_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun clearMediaLibrary(promise: Promise) {
    ioExecutor.execute {
      try {
        repository.all().forEach(::removeLibraryItemInternal)
        repository.clearAllRows()
        deleteOwnedDirectoryContents("media")
        deleteOwnedDirectoryContents("artwork")
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("library_clear_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun listLocalPlaylists(promise: Promise) {
    ioExecutor.execute {
      try {
        val result = Arguments.createArray()
        repository.localPlaylists().forEach { playlist ->
          val items = repository.localPlaylistItems(playlist.id)
          result.pushMap(Arguments.createMap().apply {
            putString("id", playlist.id)
            putString("name", playlist.name)
            putBoolean("pinned", playlist.pinned)
            putInt("itemCount", playlist.itemCount)
            putDouble("createdAt", playlist.createdAt.toDouble())
            putDouble("updatedAt", playlist.updatedAt.toDouble())
            putArray("mediaItemIds", Arguments.createArray().apply {
              items.forEach { pushString(it.id) }
            })
            putArray("artworkPaths", Arguments.createArray().apply {
              items.mapNotNull { media ->
                media.thumbnailLocalPath?.takeIf { path ->
                  isUsableArtworkFile(
                    path,
                    rejectGenericBranding = media.source in setOf("youtube", "youtube-music"),
                  )
                }
              }
                .distinct()
                .take(4)
                .forEach(::pushString)
            })
          })
        }
        promise.resolve(result)
      } catch (error: Exception) {
        promise.reject("playlist_query_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun createLocalPlaylist(name: String, mediaItemIds: ReadableArray, promise: Promise) {
    ioExecutor.execute {
      try {
        val playlist = repository.createLocalPlaylist(name, mediaItemIds.stringValues())
        emitLibraryChanged()
        promise.resolve(playlist.id)
      } catch (error: Exception) {
        promise.reject("playlist_create_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun addItemsToLocalPlaylist(
    playlistId: String,
    mediaItemIds: ReadableArray,
    promise: Promise,
  ) {
    ioExecutor.execute {
      try {
        repository.addItemsToLocalPlaylist(playlistId, mediaItemIds.stringValues())
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("playlist_add_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun removeItemsFromLocalPlaylist(
    playlistId: String,
    mediaItemIds: ReadableArray,
    promise: Promise,
  ) {
    ioExecutor.execute {
      try {
        repository.removeItemsFromLocalPlaylist(playlistId, mediaItemIds.stringValues())
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("playlist_remove_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun setLocalPlaylistPinned(playlistId: String, pinned: Boolean, promise: Promise) {
    ioExecutor.execute {
      try {
        repository.setLocalPlaylistPinned(playlistId, pinned)
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("playlist_pin_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun deleteLocalPlaylist(playlistId: String, promise: Promise) {
    ioExecutor.execute {
      try {
        repository.deleteLocalPlaylist(playlistId)
        emitLibraryChanged()
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("playlist_delete_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun getStorageStats(promise: Promise) {
    ioExecutor.execute {
      try {
        val allItems = repository.all()
        val appMediaBytes = directoryBytes(File(reactContext.filesDir, "media"))
        val artworkBytes = directoryBytes(File(reactContext.filesDir, "artwork"))
        val galleryBytes = allItems.asSequence()
          .filter { it.source == "gallery" && it.playbackValue != null }
          .mapNotNull { externalDocumentSize(Uri.parse(it.playbackValue)) }
          .sum()
        val unresolvedDownloadCount = allItems.count {
          it.availability in setOf("waiting_for_resolver", "queued", "downloading")
        }
        val downloadingNowCount = YtDlpDownloadWorker.activeDownloadCount()
        promise.resolve(Arguments.createMap().apply {
          putDouble("appMediaBytes", appMediaBytes.toDouble())
          putDouble("artworkBytes", artworkBytes.toDouble())
          putDouble("databaseBytes", databaseBytes(MEDIA_DATABASE_NAME).toDouble())
          putDouble("galleryReferencedBytes", galleryBytes.toDouble())
          putInt("libraryItemCount", allItems.size)
          putInt("readyItemCount", allItems.count { it.availability == "ready" })
          putInt("activeDownloadCount", unresolvedDownloadCount)
          putInt("downloadingItemCount", downloadingNowCount)
          putInt("queuedDownloadCount", (unresolvedDownloadCount - downloadingNowCount).coerceAtLeast(0))
          putInt("downloadedItemCount", allItems.count {
            it.source in setOf("youtube", "youtube-music") &&
              it.availability == "ready" &&
              it.playbackKind == "app-file" &&
              it.playbackValue?.let { path -> File(path).isFile && File(path).length() > 0L } == true
          })
        })
      } catch (error: Exception) {
        promise.reject("storage_query_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun getUiPreference(key: String, promise: Promise) {
    promise.resolve(
      reactContext.getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
        .getString(key, null),
    )
  }

  @ReactMethod
  fun setUiPreference(key: String, value: String, promise: Promise) {
    reactContext.getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .putString(key, value)
      .apply()
    promise.resolve(true)
  }

  @ReactMethod
  fun importGallery(promise: Promise) {
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.reject("no_activity", "Gallery import needs a visible Android activity.")
      return
    }
    if (pendingImportPromise != null) {
      promise.reject("import_in_progress", "A Gallery picker is already open.")
      return
    }

    pendingImportPromise = promise
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "*/*"
      putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    try {
      activity.startActivityForResult(intent, GALLERY_REQUEST_CODE)
    } catch (error: Exception) {
      pendingImportPromise = null
      promise.reject("picker_unavailable", error.message, error)
    }
  }

  @ReactMethod
  fun enqueueResolvedDownload(
    itemId: String,
    authorizedUri: String,
    mimeType: String?,
    promise: Promise,
  ) {
    ioExecutor.execute {
      try {
        val item = repository.byId(itemId)
          ?: throw IllegalArgumentException("Unknown library item: $itemId")
        val uri = Uri.parse(authorizedUri)
        if (uri.scheme.isNullOrBlank()) throw IllegalArgumentException("A resolved media URI is required.")
        val downloadId = "media-${item.id}"
        repository.prepareDownload(item.id, downloadId, authorizedUri)
        val request = DownloadRequest.Builder(downloadId, uri)
          .apply { if (!mimeType.isNullOrBlank()) setMimeType(mimeType) }
          .build()
        DownloadService.sendAddDownload(
          reactContext,
          MediaDownloadService::class.java,
          request,
          false,
        )
        emitLibraryChanged()
        promise.resolve(downloadId)
      } catch (error: Exception) {
        promise.reject("download_enqueue_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun playMedia(id: String, playlistId: String?, promise: Promise) {
    val requestSequence = playbackRequestSequence.incrementAndGet()
    playbackExecutor.execute {
      try {
        val playableItems = repository.playableItems()
        val playlistItems = playlistId
          ?.takeIf { repository.localPlaylistById(it) != null }
          ?.let(repository::localPlaylistItems)
        val queue = PlaybackQueueResolver.resolve(id, playableItems, playlistItems)
        val index = queue.indexOfFirst { it.id == id }
        if (index < 0) {
          promise.reject("not_playable", "This item is not available for offline playback.")
          return@execute
        }
        val mediaItems = queue.map(repository::toMedia3)
        withController(promise) { controller ->
          if (requestSequence != playbackRequestSequence.get()) return@withController false
          controller.setMediaItems(mediaItems, index, 0L)
          controller.prepare()
          controller.play()
          true
        }
      } catch (error: Exception) {
        promise.reject("playback_start_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun togglePlayback(promise: Promise) = withController(promise) { controller ->
    if (controller.isPlaying) controller.pause() else {
      if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
      controller.play()
    }
    true
  }

  @ReactMethod
  fun seekTo(positionMs: Double, promise: Promise) = withController(promise) { controller ->
    controller.seekTo(positionMs.toLong().coerceAtLeast(0L))
    true
  }

  @ReactMethod
  fun setPlaybackSpeed(speed: Double, promise: Promise) = withController(promise) { controller ->
    val safeSpeed = speed.toFloat().coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
    controller.setPlaybackSpeed(safeSpeed)
    safeSpeed.toDouble()
  }

  @ReactMethod
  fun skipNext(promise: Promise) = withController(promise) { controller ->
    if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
    true
  }

  @ReactMethod
  fun skipPrevious(promise: Promise) = withController(promise) { controller ->
    if (controller.currentPosition > 4_000L) controller.seekTo(0L)
    else if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
    true
  }

  @ReactMethod
  fun getPlaybackState(promise: Promise) = withController(promise) { controller ->
    playbackStateMap(controller)
  }

  @ReactMethod
  fun enterPictureInPicture(width: Int, height: Int, promise: Promise) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      promise.resolve(false)
      return
    }
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.resolve(false)
      return
    }
    try {
      activity.startActivity(VideoPlayerActivity.intent(activity, true, width, height))
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("VIDEO_PLAYER_START_FAILED", error)
    }
  }

  @ReactMethod
  fun setVideoFullscreen(enabled: Boolean, width: Int, height: Int, promise: Promise) {
    if (!enabled) {
      promise.resolve(true)
      return
    }
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.resolve(false)
      return
    }
    try {
      activity.startActivity(VideoPlayerActivity.intent(activity, false, width, height))
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("VIDEO_PLAYER_START_FAILED", error)
    }
  }

  @ReactMethod
  fun addListener(eventName: String) {
    listenerCount++
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    listenerCount = (listenerCount - count).coerceAtLeast(0)
  }

  override fun invalidate() {
    progressHandler.removeCallbacks(progressRunnable)
    progressHandler.removeCallbacks(libraryEventRunnable)
    reactContext.removeActivityEventListener(activityEventListener)
    ContextCompat.getMainExecutor(reactContext).execute {
      if (controllerFuture.isDone) {
        try { controllerFuture.get().removeListener(playerListener) } catch (_: Exception) {}
      }
      MediaController.releaseFuture(controllerFuture)
    }
    downloadManager.removeListener(downloadListener)
    try { reactContext.unregisterReceiver(downloadEventReceiver) } catch (_: Exception) {}
    try { reactContext.unregisterReceiver(videoPlayerReceiver) } catch (_: Exception) {}
    ioExecutor.shutdown()
    playbackExecutor.shutdown()
    downloadStateExecutor.shutdown()
    artworkExecutor.shutdown()
    super.invalidate()
  }

  private fun importUri(uri: Uri): MediaItemEntity? {
    val resolver = reactContext.contentResolver
    val mime = resolver.getType(uri) ?: return null
    if (!mime.startsWith("audio/") && !mime.startsWith("video/")) return null

    var displayName: String? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) displayName = cursor.getString(0)
    }

    val retriever = MediaMetadataRetriever()
    var title: String? = null
    var artist: String? = null
    var durationMs: Long? = null
    var width: Int? = null
    var height: Int? = null
    var artwork: Bitmap? = null
    try {
      retriever.setDataSource(reactContext, uri)
      title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
      artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
      durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
      width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
      height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
      artwork = if (mime.startsWith("video/")) {
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
      } else {
        retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
      }
    } catch (_: Exception) {
      // The URI can still be playable even when a provider exposes sparse metadata.
    } finally {
      retriever.release()
    }

    val sourceKey = uri.toString()
    val id = MediaRepository.stableId("media", "gallery:$sourceKey")
    val now = System.currentTimeMillis()
    val existing = repository.bySourceKey("gallery", sourceKey)
    val cleanDisplayName = displayName?.substringBeforeLast('.')
    val artworkPath = artwork?.let { saveArtwork(id, it) } ?: existing?.thumbnailLocalPath
    val item = existing?.copy(
      mediaType = if (mime.startsWith("video/")) "video" else "audio",
      title = title?.takeIf(String::isNotBlank) ?: cleanDisplayName ?: existing.title,
      artist = artist ?: existing.artist,
      durationMs = durationMs ?: existing.durationMs,
      width = width ?: existing.width,
      height = height ?: existing.height,
      thumbnailLocalPath = artworkPath,
      playbackKind = "content-uri",
      playbackValue = sourceKey,
      availability = "ready",
      updatedAt = now,
    ) ?: MediaItemEntity(
      id = id,
      source = "gallery",
      sourceKey = sourceKey,
      mediaType = if (mime.startsWith("video/")) "video" else "audio",
      title = title?.takeIf(String::isNotBlank) ?: cleanDisplayName ?: "Imported media",
      artist = artist,
      durationMs = durationMs,
      width = width,
      height = height,
      thumbnailRemoteUrl = null,
      thumbnailLocalPath = artworkPath,
      playbackKind = "content-uri",
      playbackValue = sourceKey,
      availability = "ready",
      downloadProgress = 1.0,
      collectionName = "On this device",
      createdAt = now,
      updatedAt = now,
    )
    repository.put(item)
    val collection = repository.putCollection("gallery", "saf-imports", "On this device")
    repository.addToCollection(collection.id, item.id, 0)
    return item
  }

  private fun validateGalleryItem(item: MediaItemEntity): MediaItemEntity {
    if (item.source != "gallery" || item.playbackValue == null) return item
    val available = try {
      reactContext.contentResolver.openAssetFileDescriptor(Uri.parse(item.playbackValue), "r")?.use { true } ?: false
    } catch (_: Exception) {
      false
    }
    val nextAvailability = if (available) "ready" else "missing"
    if (item.availability != nextAvailability) {
      repository.updateAvailability(item.id, nextAvailability)
      return item.copy(availability = nextAvailability, updatedAt = System.currentTimeMillis())
    }
    return item
  }

  private fun validateOwnedItem(item: MediaItemEntity): MediaItemEntity {
    if (item.playbackKind != "app-file" || item.playbackValue == null) return item
    val available = hasUsableOwnedMedia(item)
    val nextAvailability = if (available) "ready" else "missing"
    if (item.availability != nextAvailability) {
      repository.updateAvailability(item.id, nextAvailability)
      return item.copy(availability = nextAvailability, updatedAt = System.currentTimeMillis())
    }
    return item
  }

  private fun ensureOfflineArtwork(item: MediaItemEntity): MediaItemEntity {
    if (item.source !in setOf("youtube-music", "youtube")) return item
    // Artwork is fully decoded and validated when it is written. Library reads only need a
    // cheap existence check; decoding every thumbnail on every progress event starved taps.
    if (item.thumbnailLocalPath?.let(::isPresentArtworkFile) == true) {
      return item
    }
    item.thumbnailLocalPath?.let { deleteAppOwnedFile(it, "artwork") }

    if (item.thumbnailRemoteUrl == FALLBACK_ARTWORK_MARKER) {
      return if (item.thumbnailLocalPath != null) {
        item.copy(thumbnailLocalPath = null).also(repository::put)
      } else item
    }

    val remoteUrl = usableArtworkUrl(item.thumbnailRemoteUrl) ?: youtubeArtworkUrl(item.sourceKey)
    val repaired = if (item.thumbnailLocalPath != null || item.thumbnailRemoteUrl != remoteUrl) {
      item.copy(thumbnailRemoteUrl = remoteUrl, thumbnailLocalPath = null)
        .also(repository::put)
    } else item
    requestArtworkDownload(repaired)
    return repaired
  }

  private fun isUsableArtworkFile(
    path: String,
    rejectGenericBranding: Boolean = false,
  ): Boolean {
    val file = File(path)
    if (!file.isFile || file.length() <= 0L) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth < MIN_ARTWORK_EDGE || bounds.outHeight < MIN_ARTWORK_EDGE) return false
    if (!rejectGenericBranding) return true
    val sampled = BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply { inSampleSize = 4 },
    ) ?: return false
    return try {
      !looksLikeGenericYouTubeMusicArtwork(sampled)
    } finally {
      sampled.recycle()
    }
  }

  private fun usableArtworkUrl(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return try {
      URL(value).takeIf { it.protocol == "https" || it.protocol == "http" }?.toString()
    } catch (_: Exception) {
      null
    }
  }

  private fun youtubeArtworkUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

  private fun youtubeMaxResolutionArtworkUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

  private fun isPresentArtworkFile(path: String): Boolean =
    File(path).let { it.isFile && it.length() > 0L }

  private fun requestArtworkDownload(
    item: MediaItemEntity,
    preferredCandidates: Iterable<String> = emptyList(),
  ) {
    if (!artworkInFlight.add(item.id)) return
    val preferred = preferredCandidates.toList()
    try {
      artworkExecutor.execute {
        try {
          val candidates = linkedSetOf<String>()
          preferred.mapNotNull(::usableArtworkUrl).forEach(candidates::add)
          usableArtworkUrl(item.thumbnailRemoteUrl)?.let(candidates::add)
          if (item.source in setOf("youtube-music", "youtube") && isYouTubeVideoId(item.sourceKey)) {
            candidates.add(youtubeMaxResolutionArtworkUrl(item.sourceKey))
            candidates.add(youtubeArtworkUrl(item.sourceKey))
            candidates.add("https://i.ytimg.com/vi/${item.sourceKey}/mqdefault.jpg")
          }
          if (!downloadBestArtwork(item.id, candidates)) {
            Log.w(ARTWORK_LOG_TAG, "All artwork candidates failed for ${item.sourceKey}")
          }
        } finally {
          artworkInFlight.remove(item.id)
        }
      }
    } catch (error: Exception) {
      artworkInFlight.remove(item.id)
      Log.e(ARTWORK_LOG_TAG, "Could not enqueue artwork for ${item.sourceKey}", error)
    }
  }

  private fun saveArtwork(id: String, original: Bitmap): String? {
    var scaled: Bitmap? = null
    return try {
      scaled = if (original.width > 640 || original.height > 640) {
        val ratio = minOf(640f / original.width, 640f / original.height)
        Bitmap.createScaledBitmap(
          original,
          (original.width * ratio).toInt().coerceAtLeast(1),
          (original.height * ratio).toInt().coerceAtLeast(1),
          true,
        )
      } else null
      val output = scaled ?: original
      val directory = File(reactContext.filesDir, "artwork").apply { mkdirs() }
      val file = File(directory, "$id.jpg")
      val temporary = File(directory, "$id.part")
      val compressed = FileOutputStream(temporary).use {
        output.compress(Bitmap.CompressFormat.JPEG, 86, it)
      }
      if (!compressed || temporary.length() <= 0L) {
        temporary.delete()
        return null
      }
      if (file.exists() && !file.delete()) {
        temporary.delete()
        return null
      }
      if (!temporary.renameTo(file)) {
        temporary.delete()
        return null
      }
      file.absolutePath.takeIf(::isUsableArtworkFile)
    } catch (error: Exception) {
      Log.w(ARTWORK_LOG_TAG, "Could not persist artwork $id: ${error.message}")
      null
    } finally {
      scaled?.recycle()
      original.recycle()
    }
  }

  private fun downloadBestArtwork(id: String, candidates: Iterable<String>): Boolean {
    var genericBrandingFound = false
    for (candidate in candidates) {
      when (downloadArtworkCandidate(id, candidate)) {
        ArtworkCandidateResult.SAVED -> return true
        ArtworkCandidateResult.GENERIC_BRANDING -> genericBrandingFound = true
        ArtworkCandidateResult.FAILED -> Unit
      }
    }
    if (genericBrandingFound) {
      repository.useFallbackArtwork(id, FALLBACK_ARTWORK_MARKER)
      emitLibraryChanged()
    }
    return false
  }

  private fun downloadArtworkCandidate(id: String, value: String): ArtworkCandidateResult {
    var connection: HttpURLConnection? = null
    return try {
      val url = URL(value)
      if (url.protocol != "https" && url.protocol != "http") return ArtworkCandidateResult.FAILED
      connection = url.openConnection() as HttpURLConnection
      connection.connectTimeout = 8_000
      connection.readTimeout = 12_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8")
      connection.setRequestProperty("Referer", "https://music.youtube.com/")
      connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
      )
      if (connection.responseCode !in 200..299) return ArtworkCandidateResult.FAILED
      val contentType = connection.contentType?.substringBefore(';')?.lowercase()
      if (contentType == null || !contentType.startsWith("image/")) return ArtworkCandidateResult.FAILED
      if (connection.contentLengthLong > MAX_ARTWORK_BYTES) return ArtworkCandidateResult.FAILED
      connection.inputStream.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return ArtworkCandidateResult.FAILED
        if (bitmap.width < MIN_ARTWORK_EDGE || bitmap.height < MIN_ARTWORK_EDGE) {
          bitmap.recycle()
          return ArtworkCandidateResult.FAILED
        }
        if (looksLikeGenericYouTubeMusicArtwork(bitmap)) {
          bitmap.recycle()
          return ArtworkCandidateResult.GENERIC_BRANDING
        }
        val path = saveArtwork(id, bitmap) ?: return ArtworkCandidateResult.FAILED
        repository.updateArtwork(id, path)
        emitLibraryChanged()
        ArtworkCandidateResult.SAVED
      }
    } catch (error: Exception) {
      Log.w(ARTWORK_LOG_TAG, "Artwork request failed for ${URL(value).host}: ${error.message}")
      ArtworkCandidateResult.FAILED
    } finally {
      connection?.disconnect()
    }
  }

  private fun looksLikeGenericYouTubeMusicArtwork(bitmap: Bitmap): Boolean {
    val stepX = maxOf(1, bitmap.width / 96)
    val stepY = maxOf(1, bitmap.height / 96)
    var total = 0
    var dark = 0
    var brightNeutral = 0
    var youtubeRed = 0
    var otherColor = 0
    var y = 0
    while (y < bitmap.height) {
      var x = 0
      while (x < bitmap.width) {
        val color = bitmap.getPixel(x, y)
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val spread = maximum - minimum
        total++
        if (maximum < 46) dark++
        if (minimum > 170 && spread < 38) brightNeutral++
        val brandedRed = red > 145 && red > green * 2 && red > blue * 3 / 2
        if (brandedRed) youtubeRed++
        else if (maximum > 85 && spread > 52) otherColor++
        x += stepX
      }
      y += stepY
    }
    if (total == 0) return false
    return dark.toDouble() / total > 0.68 &&
      brightNeutral.toDouble() / total > 0.012 &&
      youtubeRed.toDouble() / total > 0.002 &&
      otherColor.toDouble() / total < 0.10
  }

  private fun resumePendingYtDlpJobs() {
    repository.pendingYtDlpJobs().forEach { job ->
      val item = repository.byId(job.mediaItemId) ?: return@forEach
      if (item.source !in setOf("youtube", "youtube-music")) return@forEach
      val sourceUrl = job.sourceUrl ?: canonicalSourceUrl(item.source, item.sourceKey)
      if (job.sourceUrl == null || job.status == "waiting_for_resolver") {
        repository.putYtDlpJob(item.id, sourceUrl)
      }
      val workId = YtDlpQueue.enqueue(reactContext, item.id)
      repository.setWorkId(item.id, workId.toString())
    }
  }

  private fun hasUsableOwnedMedia(item: MediaItemEntity): Boolean {
    if (item.playbackKind != "app-file" || item.playbackValue.isNullOrBlank()) return false
    return try {
      val mediaDirectory = File(reactContext.filesDir, "media").canonicalFile
      val file = File(item.playbackValue).canonicalFile
      file.path.startsWith(mediaDirectory.path + File.separator) && file.isFile && file.length() > 0L
    } catch (_: Exception) {
      false
    }
  }

  private fun canonicalSourceUrl(source: String, videoId: String): String =
    if (source == "youtube-music") {
      "https://music.youtube.com/watch?v=$videoId"
    } else {
      "https://www.youtube.com/watch?v=$videoId"
    }

  private fun deleteAppOwnedFile(path: String?, directoryName: String) {
    if (path.isNullOrBlank()) return
    try {
      val directory = File(reactContext.filesDir, directoryName).canonicalFile
      val file = File(path).canonicalFile
      if (file.path.startsWith(directory.path + File.separator) && file.isFile) file.delete()
    } catch (_: Exception) {}
  }

  private fun deleteOwnedDirectoryContents(directoryName: String) {
    val directory = File(reactContext.filesDir, directoryName)
    try {
      val filesRoot = reactContext.filesDir.canonicalFile
      val target = directory.canonicalFile
      if (target.parentFile == filesRoot) target.listFiles()?.forEach(File::deleteRecursively)
    } catch (_: Exception) {}
  }

  private fun removeLibraryItemInternal(item: MediaItemEntity) {
    repository.updateAppDownloadState(
      item.id,
      "cancelled",
      0.0,
      "REMOVED: Removed from library",
      repository.downloadJob(item.id)?.attemptCount ?: 0,
    )
    YtDlpQueue.cancel(reactContext, item.id)
    if (item.playbackKind == "media3-download" && item.playbackValue != null) {
      DownloadService.sendRemoveDownload(
        reactContext,
        MediaDownloadService::class.java,
        item.playbackValue,
        false,
      )
    }
    deleteAppOwnedFile(item.playbackValue, "media")
    deleteAppOwnedFile(item.thumbnailLocalPath, "artwork")
    deleteDownloadArtifacts(item.id)
    repository.delete(item.id)
  }

  private fun deleteDownloadArtifacts(mediaItemId: String) {
    File(reactContext.filesDir, "media").listFiles()
      ?.filter { it.name.startsWith("$mediaItemId.") }
      ?.forEach(File::delete)
  }

  private fun directoryBytes(directory: File): Long =
    directory.listFiles()?.sumOf { file ->
      when {
        file.isFile -> file.length()
        file.isDirectory -> directoryBytes(file)
        else -> 0L
      }
    } ?: 0L

  private fun databaseBytes(name: String): Long = listOf(
    reactContext.getDatabasePath(name),
    File(reactContext.getDatabasePath(name).absolutePath + "-wal"),
    File(reactContext.getDatabasePath(name).absolutePath + "-shm"),
  ).filter(File::isFile).sumOf(File::length)

  private fun externalDocumentSize(uri: Uri): Long? {
    try {
      reactContext.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0).coerceAtLeast(0L)
      }
    } catch (_: Exception) {}
    return try {
      reactContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
        it.length.takeIf { length -> length >= 0L }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun toWritableMap(item: MediaItemEntity): WritableMap = Arguments.createMap().apply {
    putString("id", item.id)
    putString("source", item.source)
    putString("sourceKey", item.sourceKey)
    putString("mediaType", item.mediaType)
    putString("title", item.title)
    nullableString("artist", item.artist)
    nullableDouble("durationMs", item.durationMs?.toDouble())
    nullableDouble("width", item.width?.toDouble())
    nullableDouble("height", item.height?.toDouble())
    nullableString("thumbnailRemoteUrl", item.thumbnailRemoteUrl)
    nullableString("thumbnailLocalPath", item.thumbnailLocalPath)
    putString("availability", item.availability)
    putDouble("downloadProgress", item.downloadProgress)
    nullableString("collectionName", item.collectionName)
    putDouble("createdAt", item.createdAt.toDouble())
    putDouble("updatedAt", item.updatedAt.toDouble())
    if (item.playbackKind != null && item.playbackValue != null) {
      putMap("playbackLocator", Arguments.createMap().apply {
        putString("kind", item.playbackKind)
        when (item.playbackKind) {
          "content-uri" -> putString("uri", item.playbackValue)
          "app-file" -> putString("path", item.playbackValue)
          else -> putString("downloadId", item.playbackValue)
        }
      })
    } else putNull("playbackLocator")
  }

  private fun WritableMap.nullableString(key: String, value: String?) {
    if (value == null) putNull(key) else putString(key, value)
  }

  private fun WritableMap.nullableDouble(key: String, value: Double?) {
    if (value == null) putNull(key) else putDouble(key, value)
  }

  private fun ReadableMap.string(key: String): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else null

  private fun ReadableMap.stringArray(key: String): List<String> {
    if (!hasKey(key) || isNull(key)) return emptyList()
    val array = getArray(key) ?: return emptyList()
    return buildList {
      for (index in 0 until array.size()) {
        if (!array.isNull(index)) array.getString(index)?.let(::add)
      }
    }
  }

  private fun ReadableArray.stringValues(): List<String> = buildList {
    for (index in 0 until size()) {
      if (!isNull(index)) getString(index)?.let(::add)
    }
  }

  private fun withController(promise: Promise, action: (MediaController) -> Any?) {
    controllerFuture.addListener(
      {
        try {
          promise.resolve(action(controllerFuture.get()))
        } catch (error: Exception) {
          promise.reject("player_unavailable", error.message, error)
        }
      },
      ContextCompat.getMainExecutor(reactContext),
    )
  }

  private fun playbackStateMap(player: Player): WritableMap = Arguments.createMap().apply {
    val metadata = player.mediaMetadata
    val extras = metadata.extras
    nullableString("mediaId", player.currentMediaItem?.mediaId)
    nullableString("title", metadata.title?.toString())
    nullableString("artist", metadata.artist?.toString())
    nullableString("mediaType", extras?.getString("mediaType"))
    nullableString("artworkPath", extras?.getString("artworkPath"))
    nullableDouble("width", extras?.getInt("width")?.takeIf { it > 0 }?.toDouble())
    nullableDouble("height", extras?.getInt("height")?.takeIf { it > 0 }?.toDouble())
    putBoolean("isPlaying", player.isPlaying)
    putString("state", when (player.playbackState) {
      Player.STATE_BUFFERING -> "buffering"
      Player.STATE_READY -> "ready"
      Player.STATE_ENDED -> "ended"
      else -> "idle"
    })
    putDouble("positionMs", player.currentPosition.coerceAtLeast(0L).toDouble())
    putDouble("durationMs", player.duration.takeIf { it > 0 }?.toDouble() ?: 0.0)
    putDouble("playbackSpeed", player.playbackParameters.speed.toDouble())
    putBoolean("hasNext", player.hasNextMediaItem())
    putBoolean("hasPrevious", player.hasPreviousMediaItem())
  }

  private fun emitPlaybackState() {
    if (listenerCount == 0 || !controllerFuture.isDone) return
    try {
      emitEvent(PLAYBACK_EVENT, playbackStateMap(controllerFuture.get()))
    } catch (_: Exception) {}
  }

  private fun emitDownloadState(
    itemId: String,
    state: String,
    progress: Double,
    error: String?,
  ) {
    emitEvent(DOWNLOAD_EVENT, Arguments.createMap().apply {
      putString("itemId", itemId)
      putString("state", state)
      putDouble("progress", progress.coerceIn(0.0, 1.0))
      nullableString("error", error)
    })
  }

  private fun emitLibraryChanged() {
    progressHandler.removeCallbacks(libraryEventRunnable)
    progressHandler.postDelayed(libraryEventRunnable, 180L)
  }

  private fun emitEvent(name: String, payload: WritableMap) {
    reactContext.runOnUiQueueThread {
      if (!reactContext.hasActiveReactInstance()) return@runOnUiQueueThread
      reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(name, payload)
    }
  }

  private enum class ArtworkCandidateResult {
    SAVED,
    GENERIC_BRANDING,
    FAILED,
  }

  companion object {
    const val NAME = "MediaEngine"
    const val LIBRARY_EVENT = "MediaLibraryChanged"
    const val PLAYBACK_EVENT = "PlaybackStateChanged"
    const val DOWNLOAD_EVENT = "DownloadStateChanged"
    const val VIDEO_PLAYER_CLOSED_EVENT = "VideoPlayerClosed"
    private const val UI_PREFERENCES = "airplanemode-ui"
    private const val MEDIA_DATABASE_NAME = "airplane-mode-media.db"
    private const val GALLERY_REQUEST_CODE = 7013
    private const val MAX_ARTWORK_BYTES = 12L * 1024L * 1024L
    private const val MIN_ARTWORK_EDGE = 32
    private const val MIN_PLAYBACK_SPEED = 0.5f
    private const val MAX_PLAYBACK_SPEED = 2.0f
    private const val ARTWORK_DOWNLOAD_WORKERS = 4
    private const val ARTWORK_LOG_TAG = "AirplaneArtwork"
    private const val FALLBACK_ARTWORK_MARKER = "airplanemode://music-note"

    private fun isYouTubeVideoId(value: String) = value.matches(Regex("^[A-Za-z0-9_-]{11}$"))
  }
}
