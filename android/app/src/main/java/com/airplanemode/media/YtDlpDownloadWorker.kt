package com.airplanemode.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.airplanemode.R
import com.airplanemode.MainActivity
import com.airplanemode.media.data.MediaItemEntity
import com.airplanemode.media.data.MediaRepository
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap

class YtDlpDownloadWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  private val repository = MediaRepository.get(appContext)
  private val notificationManager =
    appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  override suspend fun doWork(): Result = queueMutex.withLock {
    val mediaItemId = inputData.getString(YtDlpQueue.INPUT_MEDIA_ITEM_ID)
      ?: return@withLock Result.failure()
    val item = repository.byId(mediaItemId) ?: return@withLock Result.success()
    val job = repository.downloadJob(mediaItemId) ?: return@withLock Result.success()
    // A queued cancellation can race WorkManager starting this worker. Never let
    // that race resurrect an item by transitioning it back to downloading.
    if (job.status in setOf("ready", "cancelled") || item.availability == "cancelled") {
      deleteDownloadArtifacts(mediaItemId)
      return@withLock Result.success()
    }

    activeMediaIds.add(mediaItemId)
    try {
      val sourceUrl = job.sourceUrl ?: sourceUrlFor(item)
      val attempt = job.attemptCount + 1
      updateState(item.id, "downloading", job.progress, null, attempt)
      setForeground(foregroundInfo(item, job.progress))

      val nativeLibraryDir = File(applicationContext.applicationInfo.nativeLibraryDir)
      val quickJs = File(nativeLibraryDir, QUICKJS_LIBRARY_NAME)
      if (!quickJs.isFile) {
        fail(item.id, "RUNTIME_MISSING", "QuickJS runtime is not installed", attempt)
        return@withLock Result.success()
      }

      val mediaDirectory = File(applicationContext.filesDir, "media").apply { mkdirs() }
      val outputTemplate = File(mediaDirectory, "${item.id}.%(ext)s").absolutePath
      val runtimeDirectory = File(applicationContext.cacheDir, "yt-runtime").apply { mkdirs() }
      val androidCa = exportAndroidCertificates(File(runtimeDirectory, "android-ca.pem"))
      val mergedCa = File(runtimeDirectory, "merged-ca.pem")
      val callback = ProgressCallback(item, attempt)
      var cookieFile: File? = null

      try {
        ensurePython()
        val module = Python.getInstance().getModule("android_ytdlp")
        var response = callDownloader(
          module = module,
          item = item,
          sourceUrl = sourceUrl,
          outputTemplate = outputTemplate,
          quickJs = quickJs,
          androidCa = androidCa,
          mergedCa = mergedCa,
          cookieFile = null,
          callback = callback,
        )

        if (!response.optBoolean("ok") && response.optString("code") == "AUTH_REQUIRED") {
          cookieFile = YouTubeCookieSnapshot.create(applicationContext, item.id)
          if (cookieFile != null) {
            response = callDownloader(
              module = module,
              item = item,
              sourceUrl = sourceUrl,
              outputTemplate = outputTemplate,
              quickJs = quickJs,
              androidCa = androidCa,
              mergedCa = mergedCa,
              cookieFile = cookieFile,
              callback = callback,
            )
          }
        }

        if (!response.optBoolean("ok")) {
          val code = response.optString("code", "EXTRACTOR_FAILED")
          val message = response.optString("message", "Download failed")
          if (code == "CANCELLED" || isStopped || repository.downloadJob(item.id)?.status == "cancelled") {
            cancelState(item.id, attempt)
            deleteDownloadArtifacts(item.id)
            return@withLock Result.success()
          }
          if (code == "NETWORK" && runAttemptCount < MAX_NETWORK_RETRIES && !isStopped) {
            updateState(item.id, "queued", 0.0, "$code: $message", attempt)
            return@withLock Result.retry()
          }
          fail(item.id, code, message, attempt)
          return@withLock Result.success()
        }

        val output = if (item.source == "youtube" &&
          response.optString("videoPath").isNotBlank() && response.optString("audioPath").isNotBlank()
        ) {
          val video = File(response.optString("videoPath"))
          val audio = File(response.optString("audioPath"))
          val merged = File(mediaDirectory, "${item.id}.mp4")
          validateOwnedComponent(video, mediaDirectory)
          validateOwnedComponent(audio, mediaDirectory)
          muxVideoAndAudio(video, audio, merged, callback)
          video.delete()
          audio.delete()
          merged
        } else {
          File(response.optString("path"))
        }
        val canonicalMediaDirectory = mediaDirectory.canonicalFile
        if (!output.isFile || output.length() <= 0L ||
          !output.canonicalFile.path.startsWith(canonicalMediaDirectory.path + File.separator)
        ) {
          fail(item.id, "INVALID_OUTPUT", "The downloaded file is missing or invalid", attempt)
          return@withLock Result.success()
        }

        if (callback.isCancelled()) {
          output.delete()
          cancelState(item.id, attempt)
          deleteDownloadArtifacts(item.id)
          return@withLock Result.success()
        }

        val metadata = inspectMedia(output)
        repository.finishAppDownload(
          mediaId = item.id,
          path = output.absolutePath,
          durationMs = metadata.durationMs ?: response.optionalLong("durationMs"),
          width = metadata.width ?: response.optionalInt("width"),
          height = metadata.height ?: response.optionalInt("height"),
          title = response.optionalString("title"),
          artist = response.optionalString("artist"),
          attemptCount = attempt,
        )
        notifyState(item.id, "ready", 1.0, null)
        Result.success(workDataOf("mediaItemId" to item.id, "path" to output.absolutePath))
      } catch (error: Exception) {
        if (isStopped || error.message == "DOWNLOAD_CANCELLED" ||
          repository.downloadJob(item.id)?.status == "cancelled"
        ) {
          cancelState(item.id, attempt)
          deleteDownloadArtifacts(item.id)
          Result.success()
        } else if (runAttemptCount < MAX_NETWORK_RETRIES) {
          updateState(item.id, "queued", 0.0, "RUNTIME_ERROR: ${safeMessage(error)}", attempt)
          Result.retry()
        } else {
          fail(item.id, "RUNTIME_ERROR", safeMessage(error), attempt)
          Result.success()
        }
      } finally {
        cookieFile?.delete()
        mergedCa.delete()
      }
    } finally {
      activeMediaIds.remove(mediaItemId)
    }
  }

  private fun callDownloader(
    module: com.chaquo.python.PyObject,
    item: MediaItemEntity,
    sourceUrl: String,
    outputTemplate: String,
    quickJs: File,
    androidCa: File,
    mergedCa: File,
    cookieFile: File?,
    callback: DownloadProgressCallback,
  ): JSONObject = JSONObject(
    module.callAttr(
      "download",
      sourceUrl,
      item.source,
      outputTemplate,
      quickJs.absolutePath,
      androidCa.absolutePath,
      mergedCa.absolutePath,
      cookieFile?.absolutePath,
      callback,
    ).toString(),
  )

  private fun updateState(
    itemId: String,
    state: String,
    progress: Double,
    error: String?,
    attempt: Int,
  ) {
    repository.updateAppDownloadState(itemId, state, progress, error, attempt)
    notifyState(itemId, state, progress, error)
  }

  private fun fail(itemId: String, code: String, message: String, attempt: Int) {
    val error = "$code: ${message.take(320)}"
    updateState(itemId, "failed", 0.0, error, attempt)
  }

  private fun cancelState(itemId: String, attempt: Int) {
    if (repository.byId(itemId) == null) return
    repository.updateAppDownloadState(
      itemId,
      "cancelled",
      0.0,
      "CANCELLED: Download cancelled",
      attempt,
    )
    repository.setWorkId(itemId, null)
    notifyState(itemId, "cancelled", 0.0, "CANCELLED: Download cancelled")
  }

  private fun notifyState(itemId: String, state: String, progress: Double, error: String?) {
    DownloadEvents.send(applicationContext, itemId, state, progress, error)
  }

  private fun foregroundInfo(item: MediaItemEntity, progress: Double): ForegroundInfo {
    createNotificationChannel()
    val percent = (progress.coerceIn(0.0, 1.0) * 100).toInt()
    val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
      .setSmallIcon(R.drawable.ic_download)
      .setContentTitle(item.title)
      .setContentText(if (percent > 0) "$percent%" else "Preparing download")
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setContentIntent(
        PendingIntent.getActivity(
          applicationContext,
          1,
          Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
          },
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
      .setProgress(100, percent, percent == 0)
      .addAction(
        0,
        "Cancel",
        WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
      )
      .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(
        NOTIFICATION_ID_BASE + item.id.take(6).hashCode().and(0x0fff),
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      ForegroundInfo(NOTIFICATION_ID_BASE + item.id.take(6).hashCode().and(0x0fff), notification)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL) == null
    ) {
      notificationManager.createNotificationChannel(
        NotificationChannel(
          NOTIFICATION_CHANNEL,
          "Offline media downloads",
          NotificationManager.IMPORTANCE_LOW,
        ),
      )
    }
  }

  private fun exportAndroidCertificates(target: File): File {
    val temporary = File(target.parentFile, "${target.name}.part")
    val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    temporary.bufferedWriter().use { output ->
      val aliases = store.aliases()
      while (aliases.hasMoreElements()) {
        val certificate = store.getCertificate(aliases.nextElement()) ?: continue
        val encoded = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
        output.appendLine("-----BEGIN CERTIFICATE-----")
        encoded.chunked(64).forEach(output::appendLine)
        output.appendLine("-----END CERTIFICATE-----")
      }
    }
    if (target.exists()) target.delete()
    if (!temporary.renameTo(target)) throw IllegalStateException("Could not prepare Android CA bundle")
    return target
  }

  private fun inspectMedia(file: File): LocalMetadata {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      LocalMetadata(
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
      )
    } catch (_: Exception) {
      LocalMetadata(null, null, null)
    } finally {
      retriever.release()
    }
  }

  private fun validateOwnedComponent(file: File, mediaDirectory: File) {
    val canonicalDirectory = mediaDirectory.canonicalFile
    val canonicalFile = file.canonicalFile
    require(
      canonicalFile.isFile && canonicalFile.length() > 0L &&
        canonicalFile.path.startsWith(canonicalDirectory.path + File.separator),
    ) { "Downloaded track is missing or outside app storage" }
  }

  private fun muxVideoAndAudio(
    videoFile: File,
    audioFile: File,
    outputFile: File,
    callback: DownloadProgressCallback,
  ): File {
    val temporary = File(outputFile.parentFile, "${outputFile.name}.mux.part")
    temporary.delete()
    val videoExtractor = MediaExtractor()
    val audioExtractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
      videoExtractor.setDataSource(videoFile.absolutePath)
      audioExtractor.setDataSource(audioFile.absolutePath)
      val (videoTrack, videoFormat) = findTrack(videoExtractor, "video/")
      val (audioTrack, audioFormat) = findTrack(audioExtractor, "audio/")
      videoExtractor.selectTrack(videoTrack)
      audioExtractor.selectTrack(audioTrack)

      muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val muxedVideoTrack = muxer.addTrack(videoFormat)
      val muxedAudioTrack = muxer.addTrack(audioFormat)
      videoRotation(videoFile)?.takeIf { it != 0 }?.let(muxer::setOrientationHint)
      muxer.start()
      started = true
      copyTrack(videoExtractor, videoFormat, muxer, muxedVideoTrack, callback)
      callback.onProgress(0.99)
      copyTrack(audioExtractor, audioFormat, muxer, muxedAudioTrack, callback)
      muxer.stop()
      started = false
      muxer.release()
      muxer = null

      if (outputFile.exists() && !outputFile.delete()) {
        throw IllegalStateException("Could not replace previous video output")
      }
      if (!temporary.renameTo(outputFile)) {
        throw IllegalStateException("Could not finalize high-quality video")
      }
      callback.onProgress(1.0)
      return outputFile
    } finally {
      if (started) try { muxer?.stop() } catch (_: Exception) {}
      try { muxer?.release() } catch (_: Exception) {}
      videoExtractor.release()
      audioExtractor.release()
      if (temporary.exists()) temporary.delete()
    }
  }

  private fun findTrack(extractor: MediaExtractor, prefix: String): Pair<Int, MediaFormat> {
    for (index in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(index)
      if (format.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return index to format
    }
    throw IllegalStateException("Downloaded ${prefix.removeSuffix("/")} track is missing")
  }

  private fun copyTrack(
    extractor: MediaExtractor,
    format: MediaFormat,
    muxer: MediaMuxer,
    muxerTrack: Int,
    callback: DownloadProgressCallback,
  ) {
    val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
      format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_MUX_BUFFER_BYTES)
    } else {
      DEFAULT_MUX_BUFFER_BYTES
    }
    val buffer = ByteBuffer.allocateDirect(capacity.coerceAtMost(MAX_MUX_BUFFER_BYTES))
    val info = MediaCodec.BufferInfo()
    while (true) {
      if (callback.isCancelled()) throw IllegalStateException("DOWNLOAD_CANCELLED")
      buffer.clear()
      val size = extractor.readSampleData(buffer, 0)
      if (size < 0) break
      info.set(
        0,
        size,
        extractor.sampleTime.coerceAtLeast(0L),
        extractor.sampleFlags,
      )
      muxer.writeSampleData(muxerTrack, buffer, info)
      extractor.advance()
    }
  }

  private fun videoRotation(file: File): Int? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
    } catch (_: Exception) {
      null
    } finally {
      retriever.release()
    }
  }

  private fun deleteDownloadArtifacts(mediaItemId: String) {
    File(applicationContext.filesDir, "media").listFiles()
      ?.filter { it.name.startsWith("$mediaItemId.") }
      ?.forEach(File::delete)
  }

  private fun sourceUrlFor(item: MediaItemEntity): String = if (item.source == "youtube-music") {
    "https://music.youtube.com/watch?v=${item.sourceKey}"
  } else {
    "https://www.youtube.com/watch?v=${item.sourceKey}"
  }

  private fun safeMessage(error: Exception): String =
    (error.message ?: error.javaClass.simpleName).take(320)

  private fun ensurePython() {
    if (Python.isStarted()) return
    synchronized(pythonLock) {
      if (!Python.isStarted()) {
        Python.start(AndroidPlatform(applicationContext))
      }
    }
  }

  private inner class ProgressCallback(
    private val item: MediaItemEntity,
    private val attempt: Int,
  ) : DownloadProgressCallback {
    private var lastUpdate = 0L
    private var lastProgress = -1.0
    private var lastCancellationCheck = 0L
    private var cancellationCached = false

    override fun onProgress(progress: Double) {
      val safeProgress = progress.coerceIn(0.0, 1.0)
      val now = SystemClock.elapsedRealtime()
      if (safeProgress < 1.0 && now - lastUpdate < 800L && safeProgress - lastProgress < 0.02) return
      lastUpdate = now
      lastProgress = safeProgress
      repository.updateAppDownloadState(item.id, "downloading", safeProgress, null, attempt)
      setProgressAsync(workDataOf("progress" to safeProgress))
      notificationManager.notify(
        NOTIFICATION_ID_BASE + item.id.take(6).hashCode().and(0x0fff),
        foregroundInfo(item, safeProgress).notification,
      )
      notifyState(item.id, "downloading", safeProgress, null)
    }

    override fun isCancelled(): Boolean {
      if (isStopped || cancellationCached) return true
      val now = SystemClock.elapsedRealtime()
      if (now - lastCancellationCheck >= 400L) {
        lastCancellationCheck = now
        val currentJob = repository.downloadJob(item.id)
        // A missing job means the library item was removed while yt-dlp was active.
        cancellationCached = currentJob == null || currentJob.status == "cancelled"
      }
      return cancellationCached
    }
  }

  private data class LocalMetadata(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
  )

  companion object {
    private val queueMutex = Mutex()
    private val activeMediaIds = ConcurrentHashMap.newKeySet<String>()
    private val pythonLock = Any()
    private const val QUICKJS_LIBRARY_NAME = "libqjs.so"
    private const val NOTIFICATION_CHANNEL = "airplanemode-offline-media"
    private const val NOTIFICATION_ID_BASE = 7400
    private const val MAX_NETWORK_RETRIES = 2
    private const val DEFAULT_MUX_BUFFER_BYTES = 2 * 1024 * 1024
    private const val MAX_MUX_BUFFER_BYTES = 16 * 1024 * 1024

    fun activeDownloadCount(): Int = activeMediaIds.size
  }
}

interface DownloadProgressCallback {
  fun onProgress(progress: Double)
  fun isCancelled(): Boolean
}

private fun JSONObject.optionalString(key: String): String? =
  optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optionalLong(key: String): Long? =
  if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optionalInt(key: String): Int? =
  if (has(key) && !isNull(key)) optInt(key) else null
