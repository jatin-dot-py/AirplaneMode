package com.airplanemode.doomscroll

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.airplanemode.doomscroll.data.CaptureSaveResult
import com.airplanemode.doomscroll.data.CapturedReelRecord
import com.airplanemode.doomscroll.data.DoomscrollRepository
import com.airplanemode.doomscroll.data.DoomscrollStatsRecord
import com.airplanemode.doomscroll.data.PlaybackSaveResult
import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import com.airplanemode.doomscroll.data.SnapshotDeleteResult
import com.airplanemode.doomscroll.data.SnapshotSummaryRecord
import com.airplanemode.doomscroll.data.StoredSnapshotReelRecord
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class DoomscrollEngineModule(
  private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
  private val repository = DoomscrollRepository.get(reactContext)
  private val ioExecutor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())
  private var listenerCount = 0
  private val changedRunnable = Runnable {
    emitEvent(CHANGED_EVENT, Arguments.createMap())
  }
  private val downloadReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != DoomscrollDownloadEvents.ACTION) return
      val mediaPk = intent.getStringExtra(DoomscrollDownloadEvents.EXTRA_MEDIA_PK) ?: return
      val state = intent.getStringExtra(DoomscrollDownloadEvents.EXTRA_STATE) ?: return
      emitEvent(DOWNLOAD_EVENT, Arguments.createMap().apply {
        putString("mediaPk", mediaPk)
        putString("state", state)
        putDouble(
          "progress",
          intent.getDoubleExtra(DoomscrollDownloadEvents.EXTRA_PROGRESS, 0.0)
            .coerceIn(0.0, 1.0),
        )
        nullableString("error", intent.getStringExtra(DoomscrollDownloadEvents.EXTRA_ERROR))
      })
      emitChanged()
    }
  }

  init {
    ContextCompat.registerReceiver(
      reactContext,
      downloadReceiver,
      IntentFilter(DoomscrollDownloadEvents.ACTION),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    ioExecutor.execute {
      if (repository.refreshDownloadQueuePolicy() > 0) emitChanged()
    }
  }

  override fun getName(): String = NAME

  @ReactMethod
  fun beginCaptureSession(promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "capture_session_start_failed") {
        repository.beginSession()
      }
    }
  }

  @ReactMethod
  fun createReelSnapshot(name: String, qualityPolicy: String, promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "snapshot_create_failed") {
        repository.createSnapshot(
          name = name.take(MAX_SNAPSHOT_NAME_LENGTH),
          qualityPolicy = qualityPolicy.take(MAX_SMALL_TEXT_LENGTH),
        )
      }
    }
  }

  @ReactMethod
  fun finishCaptureSession(sessionId: String, reason: String, promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "capture_session_finish_failed") {
        repository.finishSession(
          sessionId.take(MAX_ID_LENGTH),
          reason.ifBlank { "stopped" }.take(MAX_REASON_LENGTH),
        )
        emitChanged()
        true
      }
    }
  }

  @ReactMethod
  fun saveCaptureBatch(
    sessionId: String,
    pageIndex: Int,
    reels: ReadableArray,
    promise: Promise,
  ) {
    ioExecutor.execute {
      try {
        val records = (0 until reels.size())
          .take(MAX_BATCH_SIZE)
          .mapNotNull { index -> reels.getMap(index)?.toCapturedReelRecord() }
        val result = repository.saveBatch(
          sessionId = sessionId.take(MAX_ID_LENGTH),
          pageIndex = pageIndex.coerceAtLeast(0),
          records = records,
        )
        emitChanged()
        promise.resolve(result.toWritableMap())
      } catch (error: Exception) {
        promise.reject("capture_batch_save_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun listOfflineReels(promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "offline_reels_query_failed") {
        Arguments.createArray().apply {
          repository.snapshots().firstOrNull()?.let { snapshot ->
            repository.snapshotReels(snapshot.snapshot.id).forEach { pushMap(it.toWritableMap()) }
          }
        }
      }
    }
  }

  @ReactMethod
  fun listReelSnapshots(promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "snapshots_query_failed") {
        Arguments.createArray().apply {
          repository.snapshots().forEach { pushMap(it.toWritableMap()) }
        }
      }
    }
  }

  @ReactMethod
  fun getReelSnapshot(snapshotId: String, promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "snapshot_query_failed") {
        repository.snapshotSummary(snapshotId.take(MAX_ID_LENGTH)).toWritableMap()
      }
    }
  }

  @ReactMethod
  fun listSnapshotReels(snapshotId: String, promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "snapshot_reels_query_failed") {
        Arguments.createArray().apply {
          repository.snapshotReels(snapshotId.take(MAX_ID_LENGTH)).forEach {
            pushMap(it.toWritableMap())
          }
        }
      }
    }
  }

  @ReactMethod
  fun recordSnapshotPlayback(
    snapshotId: String,
    mediaPk: String,
    position: Int,
    playbackPositionMs: Double,
    durationMs: Double,
    activeDeltaMs: Double,
    promise: Promise,
  ) {
    ioExecutor.execute {
      runPromise(promise, "snapshot_progress_save_failed") {
        repository.recordPlayback(
          snapshotId = snapshotId.take(MAX_ID_LENGTH),
          mediaPk = mediaPk.take(MAX_ID_LENGTH),
          position = position.coerceAtLeast(0),
          playbackPositionMs = playbackPositionMs.takeIf(Double::isFinite)?.toLong() ?: 0L,
          durationMs = durationMs.takeIf(Double::isFinite)?.toLong() ?: 0L,
          activeDeltaMs = activeDeltaMs.takeIf(Double::isFinite)?.toLong() ?: 0L,
        ).toWritableMap()
      }
    }
  }

  @ReactMethod
  fun deleteReelSnapshot(snapshotId: String, promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "snapshot_delete_failed") {
        repository.deleteSnapshot(snapshotId.take(MAX_ID_LENGTH)).toWritableMap().also {
          emitChanged()
        }
      }
    }
  }

  @ReactMethod
  fun getDoomscrollStats(promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "doomscroll_stats_query_failed") {
        repository.stats().toWritableMap()
      }
    }
  }

  @ReactMethod
  fun getDoomscrollStorageBreakdown(promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "doomscroll_storage_query_failed") {
        Arguments.createMap().apply {
          putDouble("mediaBytes", directoryBytes(repository.storageRoot()).toDouble())
          putDouble("databaseBytes", databaseBytes(DOOMSCROLL_DATABASE_NAME).toDouble())
          putDouble("websiteDataBytes", websiteDataBytes().toDouble())
        }
      }
    }
  }

  @ReactMethod
  fun clearInstagramWebCache(promise: Promise) {
    mainHandler.post {
      try {
        WebView(reactContext).apply {
          clearCache(true)
          clearHistory()
          destroy()
        }
        promise.resolve(true)
      } catch (error: Exception) {
        promise.reject("instagram_cache_clear_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun clearInstagramWebsiteData(promise: Promise) {
    mainHandler.post {
      try {
        WebStorage.getInstance().deleteAllData()
        WebView(reactContext).apply {
          clearCache(true)
          clearHistory()
          clearFormData()
          destroy()
        }
        CookieManager.getInstance().removeAllCookies {
          CookieManager.getInstance().flush()
          promise.resolve(true)
        }
      } catch (error: Exception) {
        promise.reject("instagram_data_clear_failed", error.message, error)
      }
    }
  }

  @ReactMethod
  fun retryReelDownload(mediaPk: String, promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "reel_download_retry_failed") {
        val queued = repository.retry(mediaPk.take(MAX_ID_LENGTH))
        emitChanged()
        queued
      }
    }
  }

  @ReactMethod
  fun clearOfflineReels(promise: Promise) {
    ioExecutor.execute {
      runPromise(promise, "offline_reels_clear_failed") {
        repository.clearAll()
        emitChanged()
        true
      }
    }
  }

  @ReactMethod
  fun addListener(@Suppress("UNUSED_PARAMETER") eventName: String) {
    listenerCount++
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    listenerCount = (listenerCount - count).coerceAtLeast(0)
  }

  override fun invalidate() {
    mainHandler.removeCallbacks(changedRunnable)
    try {
      reactContext.unregisterReceiver(downloadReceiver)
    } catch (_: Exception) {
      // The bridge can be invalidated after Android has already torn down its receiver table.
    }
    ioExecutor.shutdown()
    super.invalidate()
  }

  private fun emitChanged() {
    mainHandler.removeCallbacks(changedRunnable)
    mainHandler.postDelayed(changedRunnable, CHANGED_DEBOUNCE_MS)
  }

  private fun emitEvent(name: String, payload: WritableMap) {
    if (listenerCount == 0) return
    reactContext.runOnUiQueueThread {
      if (!reactContext.hasActiveReactInstance()) return@runOnUiQueueThread
      reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(name, payload)
    }
  }

  private fun databaseBytes(name: String): Long = listOf(
    reactContext.getDatabasePath(name),
    File(reactContext.getDatabasePath(name).absolutePath + "-wal"),
    File(reactContext.getDatabasePath(name).absolutePath + "-shm"),
  ).filter(File::isFile).sumOf(File::length)

  private fun websiteDataBytes(): Long = listOf(
    File(reactContext.applicationInfo.dataDir, "app_webview"),
    File(reactContext.cacheDir, "WebView"),
  ).distinctBy { it.absolutePath }.sumOf(::directoryBytes)

  private fun directoryBytes(file: File): Long = when {
    file.isFile -> file.length()
    file.isDirectory -> file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    else -> 0L
  }

  private fun <T> runPromise(promise: Promise, code: String, block: () -> T) {
    try {
      promise.resolve(block())
    } catch (error: Exception) {
      promise.reject(code, error.message, error)
    }
  }

  private fun ReadableMap.toCapturedReelRecord(): CapturedReelRecord? {
    val mediaPk = boundedString("mediaPk", MAX_ID_LENGTH)
      ?.takeIf { it.matches(NUMERIC_ID) }
      ?: return null
    val code = boundedString("code", MAX_CODE_LENGTH)
      ?.takeIf { it.matches(SHORTCODE) }
      ?: return null
    val mediaId = boundedString("mediaId", MAX_MEDIA_ID_LENGTH) ?: mediaPk
    val username = boundedString("authorUsername", MAX_USERNAME_LENGTH).orEmpty()
    return CapturedReelRecord(
      mediaPk = mediaPk,
      mediaId = mediaId,
      code = code,
      permalink = "https://www.instagram.com/reel/$code/",
      authorId = boundedString("authorId", MAX_ID_LENGTH).orEmpty(),
      authorUsername = username,
      authorFullName = boundedString("authorFullName", MAX_NAME_LENGTH),
      authorIsVerified = boolean("authorIsVerified"),
      authorIsPrivate = boolean("authorIsPrivate"),
      authorProfilePicUrl = boundedString("authorProfilePicUrl", MAX_URL_LENGTH)
        ?.takeIf(ReelUrlPolicy::isAllowedHttpsUrl),
      caption = boundedString("caption", MAX_CAPTION_LENGTH),
      takenAt = long("takenAt"),
      mediaType = int("mediaType") ?: 0,
      productType = boundedString("productType", MAX_SMALL_TEXT_LENGTH),
      inventorySource = boundedString("inventorySource", MAX_NAME_LENGTH),
      originalWidth = positiveInt("originalWidth"),
      originalHeight = positiveInt("originalHeight"),
      durationMs = long("durationMs")?.coerceIn(0L, MAX_DURATION_MS),
      likeCount = count("likeCount"),
      commentCount = count("commentCount"),
      repostCount = count("repostCount"),
      viewCount = count("viewCount"),
      fbLikeCount = count("fbLikeCount"),
      fbCommentCount = count("fbCommentCount"),
      hasLiked = boolean("hasLiked"),
      hasViewerSaved = boolean("hasViewerSaved"),
      canViewerReshare = boolean("canViewerReshare"),
      hasAudio = boolean("hasAudio"),
      audioAssetId = boundedString("audioAssetId", MAX_ID_LENGTH),
      audioTitle = boundedString("audioTitle", MAX_NAME_LENGTH),
      audioArtistId = boundedString("audioArtistId", MAX_ID_LENGTH),
      audioArtistUsername = boundedString("audioArtistUsername", MAX_NAME_LENGTH),
      audioIsExplicit = boolean("audioIsExplicit"),
      usertagsJson = sanitizedTagsJson(array("usertags")),
      coauthorsJson = sanitizedCoauthorsJson(array("coauthors")),
      locationJson = sanitizedLocationJson(boundedString("locationJson", MAX_JSON_LENGTH)),
      safeMetadataJson = sanitizedMetadataJson(
        boundedString("safeMetadataJson", MAX_JSON_LENGTH),
      ),
      videoCandidates = candidates("videoCandidates", MAX_VIDEO_CANDIDATES),
      coverCandidates = candidates("coverCandidates", MAX_COVER_CANDIDATES),
    )
  }

  private fun ReadableMap.candidates(
    key: String,
    limit: Int,
  ): List<RemoteMediaCandidate> {
    val values = array(key) ?: return emptyList()
    val seen = linkedSetOf<String>()
    val candidates = mutableListOf<RemoteMediaCandidate>()
    for (index in 0 until minOf(values.size(), limit * 3)) {
      val candidate = values.getMap(index) ?: continue
      val url = candidate.boundedString("url", MAX_URL_LENGTH)
        ?.takeIf(ReelUrlPolicy::isAllowedHttpsUrl)
        ?: continue
      if (!seen.add(url)) continue
      candidates += RemoteMediaCandidate(
        url = url,
        width = (candidate.positiveInt("width") ?: 0).coerceAtMost(MAX_DIMENSION),
        height = (candidate.positiveInt("height") ?: 0).coerceAtMost(MAX_DIMENSION),
      )
    }
    return candidates.sortedByDescending { it.width.toLong() * it.height }.take(limit)
  }

  private fun StoredSnapshotReelRecord.toWritableMap(): WritableMap {
    val item = reel
    val job = download
    return Arguments.createMap().apply {
      putString("mediaPk", item.mediaPk)
      putString("mediaId", item.mediaId)
      putString("code", item.code)
      putString("permalink", item.permalink)
      putString("authorId", item.authorId)
      putString("authorUsername", item.authorUsername)
      nullableString("authorFullName", item.authorFullName)
      putBoolean("authorIsVerified", item.authorIsVerified)
      putBoolean("authorIsPrivate", item.authorIsPrivate)
      nullableString("caption", item.caption)
      nullableDouble("takenAt", item.takenAt)
      putInt("mediaType", item.mediaType)
      nullableString("productType", item.productType)
      nullableString("inventorySource", item.inventorySource)
      nullableInt("originalWidth", item.originalWidth)
      nullableInt("originalHeight", item.originalHeight)
      nullableDouble("durationMs", item.durationMs)
      nullableDouble("likeCount", item.likeCount)
      nullableDouble("commentCount", item.commentCount)
      nullableDouble("repostCount", item.repostCount)
      nullableDouble("viewCount", item.viewCount)
      nullableDouble("fbLikeCount", item.fbLikeCount)
      nullableDouble("fbCommentCount", item.fbCommentCount)
      putBoolean("hasLiked", item.hasLiked)
      putBoolean("hasViewerSaved", item.hasViewerSaved)
      putBoolean("canViewerReshare", item.canViewerReshare)
      putBoolean("hasAudio", item.hasAudio)
      nullableString("audioAssetId", item.audioAssetId)
      nullableString("audioTitle", item.audioTitle)
      nullableString("audioArtistId", item.audioArtistId)
      nullableString("audioArtistUsername", item.audioArtistUsername)
      putBoolean("audioIsExplicit", item.audioIsExplicit)
      putArray("usertags", tagsToWritableArray(item.usertagsJson))
      putArray("coauthors", coauthorsToWritableArray(item.coauthorsJson))
      nullableString("locationJson", item.locationJson)
      putString("safeMetadataJson", item.safeMetadataJson)
      nullableString("authorProfilePicLocalPath", job.profilePicLocalPath)
      nullableString("coverLocalPath", job.coverLocalPath)
      nullableString("videoLocalPath", job.videoLocalPath)
      putString("downloadState", job.state)
      putDouble("downloadProgress", job.progress.coerceIn(0.0, 1.0))
      nullableString("downloadError", job.errorDetail ?: job.errorCode)
      putDouble("localBytes", job.localBytes.toDouble())
      putString("qualityPolicy", job.qualityPolicy)
      nullableInt("selectedWidth", job.selectedWidth)
      nullableInt("selectedHeight", job.selectedHeight)
      putDouble("estimatedBytes", job.estimatedBytes.toDouble())
      applySnapshotMetadata(snapshot.snapshotMetadataJson)
      putString("snapshotId", snapshot.snapshotId)
      putInt("snapshotPosition", snapshot.position)
      putDouble("capturedAt", snapshot.capturedAt.toDouble())
      putBoolean("qualifiedWatched", snapshot.qualifiedWatchedAt != null)
      nullableDouble("qualifiedWatchedAt", snapshot.qualifiedWatchedAt)
      putDouble("activePlaybackMs", snapshot.activePlaybackMs.toDouble())
      putDouble("savedPlaybackPositionMs", snapshot.lastPlaybackPositionMs.toDouble())
    }
  }

  private fun WritableMap.applySnapshotMetadata(raw: String) {
    val value = try { JSONObject(raw) } catch (_: Exception) { return }
    if (value.length() == 0) return
    putString("mediaId", value.optString("mediaId", getString("mediaId").orEmpty()))
    putString("code", value.optString("code", getString("code").orEmpty()))
    putString("permalink", value.optString("permalink", getString("permalink").orEmpty()))
    putString("authorId", value.optString("authorId", getString("authorId").orEmpty()))
    putString("authorUsername", value.optString("authorUsername", getString("authorUsername").orEmpty()))
    nullableString("authorFullName", value.nullableString("authorFullName"))
    putBoolean("authorIsVerified", value.optBoolean("authorIsVerified"))
    putBoolean("authorIsPrivate", value.optBoolean("authorIsPrivate"))
    nullableString("caption", value.nullableString("caption"))
    nullableDouble("takenAt", value.nullableLong("takenAt"))
    putInt("mediaType", value.optInt("mediaType"))
    nullableString("productType", value.nullableString("productType"))
    nullableString("inventorySource", value.nullableString("inventorySource"))
    nullableInt("originalWidth", value.nullableInt("originalWidth"))
    nullableInt("originalHeight", value.nullableInt("originalHeight"))
    nullableDouble("durationMs", value.nullableLong("durationMs"))
    nullableDouble("likeCount", value.nullableLong("likeCount"))
    nullableDouble("commentCount", value.nullableLong("commentCount"))
    nullableDouble("repostCount", value.nullableLong("repostCount"))
    nullableDouble("viewCount", value.nullableLong("viewCount"))
    nullableDouble("fbLikeCount", value.nullableLong("fbLikeCount"))
    nullableDouble("fbCommentCount", value.nullableLong("fbCommentCount"))
    putBoolean("hasLiked", value.optBoolean("hasLiked"))
    putBoolean("hasViewerSaved", value.optBoolean("hasViewerSaved"))
    putBoolean("canViewerReshare", value.optBoolean("canViewerReshare"))
    putBoolean("hasAudio", value.optBoolean("hasAudio"))
    nullableString("audioAssetId", value.nullableString("audioAssetId"))
    nullableString("audioTitle", value.nullableString("audioTitle"))
    nullableString("audioArtistId", value.nullableString("audioArtistId"))
    nullableString("audioArtistUsername", value.nullableString("audioArtistUsername"))
    putBoolean("audioIsExplicit", value.optBoolean("audioIsExplicit"))
    putArray("usertags", tagsToWritableArray(value.optString("usertagsJson", "[]")))
    putArray("coauthors", coauthorsToWritableArray(value.optString("coauthorsJson", "[]")))
    nullableString("locationJson", value.nullableString("locationJson"))
    putString("safeMetadataJson", value.optString("safeMetadataJson", "{}"))
  }

  private fun CaptureSaveResult.toWritableMap(): WritableMap = stats.toWritableMap().apply {
    putInt("added", added)
    putInt("updated", updated)
    putInt("persisted", persisted)
    putBoolean("canContinue", canContinue)
    nullableString("stopReason", stopReason)
  }

  private fun DoomscrollStatsRecord.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putInt("capturedCount", capturedCount)
    putInt("readyCount", readyCount)
    putInt("queuedCount", queuedCount)
    putInt("downloadingCount", downloadingCount)
    putInt("failedCount", failedCount)
    putInt("lowStorageCount", lowStorageCount)
    putDouble("downloadedBytes", downloadedBytes.toDouble())
  }

  private fun SnapshotSummaryRecord.toWritableMap(): WritableMap = stats.toWritableMap().apply {
    putString("id", snapshot.id)
    putString("name", snapshot.name)
    putString("state", snapshot.state)
    nullableString("stopReason", snapshot.stopReason)
    putDouble("createdAt", snapshot.startedAt.toDouble())
    nullableDouble("finishedAt", snapshot.finishedAt)
    putDouble("updatedAt", snapshot.updatedAt.toDouble())
    putInt("pagesCaptured", snapshot.pagesCaptured)
    putString("qualityPolicy", snapshot.qualityPolicy)
    putInt("watchedCount", watchedCount)
    putInt("resumePosition", resumePosition)
    putInt("currentPosition", currentPosition)
    putDouble("logicalBytes", logicalBytes.toDouble())
    putDouble("reclaimableBytes", reclaimableBytes.toDouble())
    putDouble("estimatedBytes", estimatedBytes.toDouble())
    putArray("previewCoverPaths", Arguments.createArray().apply {
      previewCoverPaths.forEach(::pushString)
    })
  }

  private fun PlaybackSaveResult.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putBoolean("qualified", qualified)
    putInt("resumePosition", resumePosition)
    putDouble("activePlaybackMs", activePlaybackMs.toDouble())
  }

  private fun SnapshotDeleteResult.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putBoolean("deleted", deleted)
    putDouble("reclaimedBytes", reclaimedBytes.toDouble())
  }

  companion object {
    const val NAME = "DoomscrollEngine"
    private const val DOOMSCROLL_DATABASE_NAME = "airplane-mode-doomscroll.db"
    const val CHANGED_EVENT = "DoomscrollChanged"
    const val DOWNLOAD_EVENT = "DoomscrollDownloadStateChanged"

    private const val CHANGED_DEBOUNCE_MS = 220L
    private const val MAX_BATCH_SIZE = 25
    private const val MAX_SNAPSHOT_NAME_LENGTH = 120
    private const val MAX_ID_LENGTH = 200
    private const val MAX_MEDIA_ID_LENGTH = 300
    private const val MAX_CODE_LENGTH = 100
    private const val MAX_USERNAME_LENGTH = 100
    private const val MAX_NAME_LENGTH = 500
    private const val MAX_SMALL_TEXT_LENGTH = 200
    private const val MAX_REASON_LENGTH = 300
    private const val MAX_CAPTION_LENGTH = 20_000
    private const val MAX_JSON_LENGTH = 65_536
    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_DIMENSION = 16_384
    private const val MAX_DURATION_MS = 3_600_000L
    private const val MAX_COUNT = 10_000_000_000L
    private const val MAX_VIDEO_CANDIDATES = 6
    private const val MAX_COVER_CANDIDATES = 4
    private val NUMERIC_ID = Regex("^[0-9]{1,200}$")
    private val SHORTCODE = Regex("^[A-Za-z0-9_.-]{1,100}$")

    val SAFE_METADATA_KEYS = setOf(
      "aiLabelPresent",
      "aiLabel",
      "clipsAttributionInfo",
      "commentingDisabled",
      "friendshipFollowing",
      "isSharedFromBasel",
      "likeAndViewCountsDisabled",
      "mediaType",
      "showAccountTransparencyDetails",
      "wearableAttributionTitle",
    )
  }
}

private fun ReadableMap.boundedString(key: String, maxLength: Int): String? {
  if (!hasKey(key) || isNull(key) || getType(key) != ReadableType.String) return null
  return getString(key)?.trim()?.take(maxLength)?.takeIf { it.isNotEmpty() }
}

private fun ReadableMap.boolean(key: String): Boolean =
  hasKey(key) && !isNull(key) && getType(key) == ReadableType.Boolean && getBoolean(key)

private fun ReadableMap.int(key: String): Int? {
  if (!hasKey(key) || isNull(key) || getType(key) != ReadableType.Number) return null
  return getDouble(key).takeIf(Double::isFinite)?.toInt()
}

private fun ReadableMap.positiveInt(key: String): Int? = int(key)?.takeIf { it > 0 }

private fun ReadableMap.long(key: String): Long? {
  if (!hasKey(key) || isNull(key) || getType(key) != ReadableType.Number) return null
  return getDouble(key).takeIf(Double::isFinite)?.toLong()
}

private fun ReadableMap.count(key: String): Long? = long(key)?.coerceIn(0L, 10_000_000_000L)

private fun ReadableMap.array(key: String): ReadableArray? =
  if (hasKey(key) && !isNull(key) && getType(key) == ReadableType.Array) getArray(key) else null

private fun sanitizedTagsJson(values: ReadableArray?): String {
  val output = JSONArray()
  if (values == null) return output.toString()
  for (index in 0 until minOf(values.size(), 30)) {
    val tag = values.getMap(index) ?: continue
    val id = tag.boundedString("id", 200) ?: continue
    val username = tag.boundedString("username", 100) ?: continue
    val position = tag.array("position")
    output.put(JSONObject().apply {
      put("id", id)
      put("username", username)
      put("fullName", tag.boundedString("fullName", 500) ?: JSONObject.NULL)
      put("isVerified", tag.boolean("isVerified"))
      put("position", if (position != null && position.size() >= 2) {
        JSONArray().apply {
          put(position.getDouble(0).takeIf(Double::isFinite) ?: 0.0)
          put(position.getDouble(1).takeIf(Double::isFinite) ?: 0.0)
        }
      } else {
        JSONObject.NULL
      })
    })
  }
  return output.toString()
}

private fun sanitizedCoauthorsJson(values: ReadableArray?): String {
  val output = JSONArray()
  if (values == null) return output.toString()
  for (index in 0 until minOf(values.size(), 20)) {
    val author = values.getMap(index) ?: continue
    val id = author.boundedString("id", 200) ?: continue
    val username = author.boundedString("username", 100).orEmpty()
    output.put(JSONObject().apply {
      put("id", id)
      put("username", username)
      put("fullName", author.boundedString("fullName", 500) ?: JSONObject.NULL)
      put("isVerified", author.boolean("isVerified"))
    })
  }
  return output.toString()
}

private fun sanitizedLocationJson(raw: String?): String? {
  if (raw == null) return null
  return try {
    val input = JSONObject(raw)
    JSONObject().apply {
      putNullable("id", input.optString("id").take(200).takeIf(String::isNotBlank))
      putNullable("name", input.optString("name").take(500).takeIf(String::isNotBlank))
      putNullable("address", input.optString("address").take(500).takeIf(String::isNotBlank))
      putNullable("city", input.optString("city").take(300).takeIf(String::isNotBlank))
      if (input.has("latitude") && !input.isNull("latitude")) put("latitude", input.optDouble("latitude"))
      if (input.has("longitude") && !input.isNull("longitude")) put("longitude", input.optDouble("longitude"))
    }.toString()
  } catch (_: Exception) {
    null
  }
}

private fun sanitizedMetadataJson(raw: String?): String {
  if (raw == null) return "{}"
  return try {
    val input = JSONObject(raw)
    JSONObject().apply {
      DoomscrollEngineModule.SAFE_METADATA_KEYS.forEach { key ->
        if (!input.has(key) || input.isNull(key)) return@forEach
        when (val value = input.get(key)) {
          is Boolean, is Number -> put(key, value)
          is String -> put(key, value.take(1_000))
        }
      }
    }.toString()
  } catch (_: Exception) {
    "{}"
  }
}

private fun tagsToWritableArray(raw: String): WritableArray {
  val result = Arguments.createArray()
  val source = try {
    JSONArray(raw)
  } catch (_: Exception) {
    return result
  }
  for (index in 0 until source.length()) {
    val tag = source.optJSONObject(index) ?: continue
    result.pushMap(Arguments.createMap().apply {
      putString("id", tag.optString("id"))
      putString("username", tag.optString("username"))
      nullableString("fullName", tag.optString("fullName").takeIf(String::isNotBlank))
      putBoolean("isVerified", tag.optBoolean("isVerified"))
      val position = tag.optJSONArray("position")
      if (position == null) {
        putNull("position")
      } else {
        putArray("position", Arguments.createArray().apply {
          pushDouble(position.optDouble(0, 0.0))
          pushDouble(position.optDouble(1, 0.0))
        })
      }
    })
  }
  return result
}

private fun coauthorsToWritableArray(raw: String): WritableArray {
  val result = Arguments.createArray()
  val source = try {
    JSONArray(raw)
  } catch (_: Exception) {
    return result
  }
  for (index in 0 until source.length()) {
    val author = source.optJSONObject(index) ?: continue
    result.pushMap(Arguments.createMap().apply {
      putString("id", author.optString("id"))
      putString("username", author.optString("username"))
      nullableString("fullName", author.optString("fullName").takeIf(String::isNotBlank))
      putBoolean("isVerified", author.optBoolean("isVerified"))
    })
  }
  return result
}

private fun JSONObject.putNullable(key: String, value: String?) {
  put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.nullableString(key: String): String? =
  if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.nullableLong(key: String): Long? =
  if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.nullableInt(key: String): Int? =
  if (!has(key) || isNull(key)) null else optInt(key)

private fun WritableMap.nullableString(key: String, value: String?) {
  if (value == null) putNull(key) else putString(key, value)
}

private fun WritableMap.nullableDouble(key: String, value: Long?) {
  if (value == null) putNull(key) else putDouble(key, value.toDouble())
}

private fun WritableMap.nullableInt(key: String, value: Int?) {
  if (value == null) putNull(key) else putInt(key, value)
}
