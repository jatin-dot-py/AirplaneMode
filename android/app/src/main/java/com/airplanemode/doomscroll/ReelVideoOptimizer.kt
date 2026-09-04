package com.airplanemode.doomscroll

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.airplanemode.doomscroll.ReelAssetDownloader.Assets
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Converts high-bitrate AVC Reels to HEVC without changing their dimensions.
 * The source wins unless the verified result is meaningfully smaller, so the
 * efficient policy never trades storage for a larger or unusable file.
 */
@OptIn(UnstableApi::class)
class ReelVideoOptimizer(private val context: Context) {
  private val appContext = context.applicationContext

  fun optimizeIfUseful(
    assets: Assets,
    audioRequired: Boolean,
    onProgress: (Double) -> Unit,
  ): Assets {
    val input = File(assets.videoPath)
    if (!input.isFile || input.length() <= 0L) return assets
    val source = inspectSource(input) ?: return assets
    if (
      source.mime == MimeTypes.VIDEO_H265 ||
      source.bitrate < MIN_SOURCE_BITRATE ||
      !hasCompatibleHardwareHevcEncoder(source)
    ) return assets

    val requestedBitrate = targetBitrateFor(source.bitrate)
    val expectedBytes = source.durationMs
      ?.let { requestedBitrate.toLong() * it / 8_000L }
      ?: (input.length() * TARGET_BITRATE_RATIO).toLong()
    if (!DoomscrollRepositoryAccess.hasCapacity(appContext, expectedBytes)) return assets

    val output = File(input.parentFile, "video.efficient.mp4")
    output.delete()
    onProgress(0.05)
    val completed = runTransformer(input, output, requestedBitrate)
    if (!completed || !output.isFile || output.length() <= 0L) {
      output.delete()
      return assets
    }

    val downloader = ReelAssetDownloader(appContext)
    val mediaInfo = runCatching { downloader.inspectLocalVideo(output, audioRequired) }
      .getOrElse {
        output.delete()
        return assets
      }
    onProgress(0.92)
    if (!isMeaningfulSaving(input.length(), output.length())) {
      output.delete()
      return assets
    }

    val originalBytes = input.length()
    val optimizedBytes = output.length()
    val backup = File(input.parentFile, "video.original.pending")
    backup.delete()
    if (!input.renameTo(backup)) {
      output.delete()
      return assets
    }
    if (!output.renameTo(input)) {
      backup.renameTo(input)
      output.delete()
      return assets
    }
    backup.delete()
    onProgress(1.0)
    return assets.copy(
      localBytes = (assets.localBytes - originalBytes + optimizedBytes).coerceAtLeast(optimizedBytes),
      mediaInfo = mediaInfo,
      videoPath = input.absolutePath,
    )
  }

  private fun runTransformer(input: File, output: File, bitrate: Int): Boolean {
    val thread = HandlerThread("AirplaneReelOptimizer").apply { start() }
    val handler = Handler(thread.looper)
    val latch = CountDownLatch(1)
    val succeeded = AtomicReference(false)
    val transformer = AtomicReference<Transformer?>(null)
    handler.post {
      try {
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
          .setEnableFallback(false)
          .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder()
              .setBitrate(bitrate)
              .build(),
          )
          .build()
        val instance = Transformer.Builder(appContext)
          .setLooper(thread.looper)
          .setPortraitEncodingEnabled(true)
          .setVideoMimeType(MimeTypes.VIDEO_H265)
          .setEncoderFactory(encoderFactory)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              succeeded.set(true)
              latch.countDown()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              latch.countDown()
            }
          })
          .build()
        transformer.set(instance)
        instance.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
      } catch (_: Exception) {
        latch.countDown()
      }
    }

    val finished = try {
      latch.await(MAX_TRANSCODE_MINUTES, TimeUnit.MINUTES)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }
    if (!finished) handler.post { transformer.get()?.cancel() }
    thread.quitSafely()
    return finished && succeeded.get()
  }

  internal fun hasCompatibleHardwareHevcEncoder(file: File): Boolean =
    inspectSource(file)?.let(::hasCompatibleHardwareHevcEncoder) ?: false

  private fun hasCompatibleHardwareHevcEncoder(source: SourceVideo): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
      if (!codec.isEncoder || !isHardwareAccelerated(codec.name, codec)) return@any false
      val hevcType = codec.supportedTypes.firstOrNull { type ->
        type.equals(MimeTypes.VIDEO_H265, ignoreCase = true)
      } ?: return@any false
      codec.getCapabilitiesForType(hevcType).videoCapabilities
        ?.isSizeSupported(source.width, source.height) == true
    }
  }.getOrDefault(false)

  private fun isHardwareAccelerated(
    codecName: String,
    codecInfo: android.media.MediaCodecInfo,
  ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    codecInfo.isHardwareAccelerated
  } else {
    !codecName.startsWith("OMX.google.", ignoreCase = true) &&
      !codecName.startsWith("c2.android.", ignoreCase = true)
  }

  private fun inspectSource(file: File): SourceVideo? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      var durationMs: Long? = null
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (!mime.startsWith("video/")) continue
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
          durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L
        }
        val declaredBitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
          format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
        } else {
          0L
        }
        val inferredBitrate = durationMs
          ?.takeIf { it > 0L }
          ?.let { file.length() * 8_000L / it }
          ?: 0L
        return SourceVideo(
          bitrate = maxOf(declaredBitrate, inferredBitrate).coerceAtMost(Int.MAX_VALUE.toLong()),
          durationMs = durationMs,
          height = format.getInteger(MediaFormat.KEY_HEIGHT),
          mime = mime,
          width = format.getInteger(MediaFormat.KEY_WIDTH),
        )
      }
      null
    } catch (_: IOException) {
      null
    } finally {
      extractor.release()
    }
  }

  private data class SourceVideo(
    val bitrate: Long,
    val durationMs: Long?,
    val height: Int,
    val mime: String,
    val width: Int,
  )

  companion object {
    private const val MAX_TRANSCODE_MINUTES = 20L
    private const val MAX_TARGET_BITRATE = 2_200_000
    private const val MIN_SOURCE_BITRATE = 1_100_000L
    private const val MIN_TARGET_BITRATE = 750_000
    private const val MINIMUM_SAVING_RATIO = 0.90
    private const val TARGET_BITRATE_RATIO = 0.68

    internal fun isMeaningfulSaving(sourceBytes: Long, optimizedBytes: Long): Boolean =
      sourceBytes > 0L && optimizedBytes > 0L &&
        optimizedBytes < (sourceBytes * MINIMUM_SAVING_RATIO).toLong()

    internal fun targetBitrateFor(sourceBitrate: Long): Int =
      (sourceBitrate * TARGET_BITRATE_RATIO)
        .toInt()
        .coerceIn(MIN_TARGET_BITRATE, MAX_TARGET_BITRATE)
  }
}

private object DoomscrollRepositoryAccess {
  fun hasCapacity(context: Context, bytes: Long): Boolean =
    com.airplanemode.doomscroll.data.DoomscrollRepository.get(context)
      .hasDownloadCapacity(bytes)
}
