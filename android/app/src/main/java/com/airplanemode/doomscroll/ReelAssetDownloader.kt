package com.airplanemode.doomscroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.airplanemode.doomscroll.data.DoomscrollRepository
import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ReelAssetDownloader(
  context: Context,
  private val client: OkHttpClient = defaultClient(),
  private val urlValidator: (String?) -> Boolean = ReelUrlPolicy::isAllowedHttpsUrl,
  private val storageCapacity: (Long) -> Boolean = {
    DoomscrollRepository.get(context).hasDownloadCapacity(it)
  },
) {
  private val appContext = context.applicationContext
  private val repository = DoomscrollRepository.get(appContext)
  private val downloadClient = client.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  data class MediaInfo(
    val durationMs: Long?,
    val height: Int?,
    val width: Int?,
  )

  data class Assets(
    val coverPath: String?,
    val localBytes: Long,
    val mediaInfo: MediaInfo,
    val profilePath: String?,
    val videoPath: String,
  )

  class LowStorageException : IOException("At least 1 GiB of free app storage is reserved.")
  class PermanentDownloadException(val code: String, message: String) : IOException(message)

  fun download(
    mediaPk: String,
    authorId: String,
    hasAudio: Boolean,
    videoCandidates: List<RemoteMediaCandidate>,
    coverCandidates: List<RemoteMediaCandidate>,
    profileUrl: String?,
    existingCoverPath: String?,
    existingProfilePath: String?,
    onProgress: (Double) -> Unit,
  ): Assets {
    val safePk = safeName(mediaPk)
    val reelDirectory = File(repository.storageRoot(), "reels/$safePk").apply { mkdirs() }
    val profileDirectory = File(repository.storageRoot(), "profiles").apply { mkdirs() }

    val profilePath = existingProfilePath?.takeIf { File(it).isFile } ?: profileUrl
      ?.takeIf(urlValidator)
      ?.let { url ->
        val target = File(profileDirectory, "${safeName(authorId.ifBlank { mediaPk })}.webp")
        runCatching { downloadArtwork(url, target, PROFILE_MAX_DIMENSION, PROFILE_WEBP_QUALITY) }
          .getOrNull()?.absolutePath
      }
    onProgress(0.03)

    val coverPath = existingCoverPath?.takeIf { File(it).isFile } ?: orderedCandidates(coverCandidates)
      .firstNotNullOfOrNull { candidate ->
        runCatching {
          downloadArtwork(
            candidate.url,
            File(reelDirectory, "cover.webp"),
            COVER_MAX_DIMENSION,
            COVER_WEBP_QUALITY,
          )
        }
          .getOrNull()?.absolutePath
      }
    onProgress(0.06)

    if (videoCandidates.isEmpty()) {
      throw PermanentDownloadException(
        "no_progressive_video",
        "Instagram did not provide a progressive MP4 for this Reel.",
      )
    }

    var lastPermanentFailure: PermanentDownloadException? = null
    for (candidate in orderedCandidates(videoCandidates)) {
      val target = File(reelDirectory, "video.mp4")
      try {
        downloadFile(candidate.url, target, isVideo = true) { downloaded, total ->
          val fraction = if (total > 0) downloaded.toDouble() / total.toDouble() else 0.0
          onProgress(0.06 + fraction.coerceIn(0.0, 1.0) * 0.94)
        }
        val inspected = inspectLocalVideo(target, hasAudio)
        val totalBytes = listOfNotNull(
          target.takeIf(File::isFile),
          coverPath?.let(::File)?.takeIf(File::isFile),
        ).sumOf(File::length)
        return Assets(
          coverPath = coverPath,
          localBytes = totalBytes,
          mediaInfo = inspected,
          profilePath = profilePath,
          videoPath = target.absolutePath,
        )
      } catch (error: PermanentDownloadException) {
        target.delete()
        lastPermanentFailure = error
      }
    }
    throw lastPermanentFailure ?: PermanentDownloadException(
      "url_expired",
      "Instagram’s temporary video links expired. Open Reels and capture this item again.",
    )
  }

  private fun downloadArtwork(
    url: String,
    target: File,
    maxDimension: Int,
    quality: Int,
  ): File {
    val lock = targetLocks.computeIfAbsent(target.absolutePath) { Any() }
    return synchronized(lock) {
      if (target.isFile && target.length() > 0L) return@synchronized target
      val source = File(target.parentFile, "${target.name}.source")
      val temporary = File(target.parentFile, "${target.name}.part")
      source.delete()
      temporary.delete()
      try {
        downloadFile(url, source, isVideo = false) { _, _ -> }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
          throw PermanentDownloadException("invalid_image", "Instagram returned invalid artwork.")
        }
        val sample = imageSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val decoded = BitmapFactory.decodeFile(
          source.absolutePath,
          BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw PermanentDownloadException("invalid_image", "Instagram returned invalid artwork.")
        val scaled = scaleInside(decoded, maxDimension)
        if (!storageCapacity(maxOf(source.length(), 256_000L))) throw LowStorageException()
        FileOutputStream(temporary).use { output ->
          val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
          } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
          }
          if (!scaled.compress(format, quality, output)) {
            throw IOException("Offline artwork could not be encoded.")
          }
          output.fd.sync()
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!temporary.isFile || temporary.length() <= 0L) {
          throw IOException("Offline artwork was empty.")
        }
        target.delete()
        if (!temporary.renameTo(target)) {
          throw IOException("Offline artwork could not be committed atomically.")
        }
        target
      } finally {
        source.delete()
        temporary.delete()
      }
    }
  }

  private fun imageSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (maxOf(width / sample, height / sample) > maxDimension * 2) sample *= 2
    return sample
  }

  private fun scaleInside(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxDimension) return bitmap
    val ratio = maxDimension.toDouble() / largest.toDouble()
    return Bitmap.createScaledBitmap(
      bitmap,
      (bitmap.width * ratio).toInt().coerceAtLeast(1),
      (bitmap.height * ratio).toInt().coerceAtLeast(1),
      true,
    )
  }

  private fun downloadFile(
    url: String,
    target: File,
    isVideo: Boolean,
    onBytes: (Long, Long) -> Unit,
  ) {
    if (!urlValidator(url)) {
      throw PermanentDownloadException("blocked_url", "The media URL is not a valid HTTPS address.")
    }
    target.parentFile?.mkdirs()
    val temporary = File(target.parentFile, "${target.name}.part")
    temporary.delete()
    var committed = false
    try {
      var requestUrl = url
      var redirectCount = 0
      while (true) {
        if (!urlValidator(requestUrl)) {
          throw PermanentDownloadException(
            "blocked_redirect",
            "The media download redirected to an invalid address.",
          )
        }
        val request = Request.Builder()
          .url(requestUrl)
          .header(
            "Accept",
            if (isVideo) "video/mp4,*/*;q=0.8" else "image/avif,image/webp,image/*,*/*;q=0.8",
          )
          .header("Referer", "https://www.instagram.com/")
          .header("User-Agent", USER_AGENT)
          .build()
        val response = downloadClient.newCall(request).execute()
        if (response.isRedirect) {
          val nextUrl = response.header("Location")
            ?.let(response.request.url::resolve)
            ?.toString()
          response.close()
          if (nextUrl == null || !urlValidator(nextUrl)) {
            throw PermanentDownloadException(
              "blocked_redirect",
              "The media download redirected to an invalid address.",
            )
          }
          if (redirectCount >= MAX_REDIRECTS) {
            throw PermanentDownloadException("redirect_loop", "The media URL redirected too many times.")
          }
          redirectCount++
          requestUrl = nextUrl
          continue
        }
        response.use {
          if (response.code == 429 || response.code >= 500) {
            throw IOException("Instagram CDN returned HTTP ${response.code}.")
          }
          if (!response.isSuccessful) {
            throw PermanentDownloadException(
              if (response.code in setOf(401, 403, 404, 410)) "url_expired" else "http_${response.code}",
              "Instagram CDN returned HTTP ${response.code}.",
            )
          }
          val body = response.body ?: throw IOException("Instagram returned an empty media response.")
          val expected = body.contentLength().coerceAtLeast(0L)
          if (!storageCapacity(expected)) throw LowStorageException()
          body.byteStream().use { input ->
            FileOutputStream(temporary).use { output ->
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              var downloaded = 0L
              var nextStorageCheck = STORAGE_CHECK_INTERVAL
              while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                downloaded += read
                onBytes(downloaded, expected)
                if (downloaded >= nextStorageCheck) {
                  if (!storageCapacity(0L)) throw LowStorageException()
                  nextStorageCheck += STORAGE_CHECK_INTERVAL
                }
              }
              output.fd.sync()
            }
          }
        }
        break
      }
      if (!temporary.isFile || temporary.length() <= 0L) {
        throw IOException("Instagram returned an empty media file.")
      }
      target.delete()
      if (!temporary.renameTo(target)) {
        throw IOException("The offline media file could not be committed atomically.")
      }
      committed = true
    } finally {
      if (!committed) temporary.delete()
    }
  }

  internal fun inspectLocalVideo(file: File, audioRequired: Boolean): MediaInfo {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      var hasVideo = false
      var hasAudio = false
      var width: Int? = null
      var height: Int? = null
      var durationMs: Long? = null
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("video/")) {
          hasVideo = true
          if (format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
          if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        if (mime.startsWith("audio/")) hasAudio = true
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
          val trackDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000L
          durationMs = maxOf(durationMs ?: 0L, trackDuration)
        }
      }
      if (!hasVideo) {
        throw PermanentDownloadException("invalid_video", "The downloaded file has no video track.")
      }
      if (audioRequired && !hasAudio) {
        throw PermanentDownloadException("unmuxed_video", "The downloaded file has no audio track.")
      }
      return MediaInfo(durationMs, height, width)
    } catch (error: PermanentDownloadException) {
      throw error
    } catch (error: Exception) {
      throw PermanentDownloadException("invalid_video", error.message ?: "The video could not be verified.")
    } finally {
      extractor.release()
    }
  }

  private fun safeName(value: String): String = value
    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    .take(160)
    .ifBlank { "unknown" }

  private fun orderedCandidates(candidates: List<RemoteMediaCandidate>): List<RemoteMediaCandidate> =
    candidates
      .asSequence()
      .filter { urlValidator(it.url) }
      .distinctBy(RemoteMediaCandidate::url)
      .take(ReelUrlPolicy.MAX_CANDIDATES)
      .toList()

  companion object {
    private const val MAX_REDIRECTS = 5
    private const val COVER_MAX_DIMENSION = 720
    private const val COVER_WEBP_QUALITY = 84
    private const val PROFILE_MAX_DIMENSION = 192
    private const val PROFILE_WEBP_QUALITY = 82
    private const val STORAGE_CHECK_INTERVAL = 2L * 1024L * 1024L
    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    private val targetLocks = ConcurrentHashMap<String, Any>()

    private fun defaultClient() = OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(45, TimeUnit.SECONDS)
      .writeTimeout(45, TimeUnit.SECONDS)
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
  }
}
