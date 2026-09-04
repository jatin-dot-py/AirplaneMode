package com.airplanemode.doomscroll

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplanemode.doomscroll.ReelAssetDownloader.Assets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Manual device smoke test for the hardware-backed HEVC export path. */
@RunWith(AndroidJUnit4::class)
class ReelVideoOptimizerInstrumentedTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun exportsVerifiedHevcOrSafelyKeepsTheOriginalWhenExplicitlyRequested() {
    val sourcePath = InstrumentationRegistry.getArguments().getString("optimizerSourcePath")
    assumeTrue(!sourcePath.isNullOrBlank())
    val source = File(requireNotNull(sourcePath))
    assumeTrue(source.isFile && source.length() > 0L)

    val directory = File(context.cacheDir, "reel-optimizer-smoke").apply {
      deleteRecursively()
      mkdirs()
    }
    try {
      val input = File(directory, "video.mp4")
      source.copyTo(input, overwrite = true)
      val originalBytes = input.length()
      val downloader = ReelAssetDownloader(context)
      val initial = Assets(
        coverPath = null,
        localBytes = originalBytes,
        mediaInfo = downloader.inspectLocalVideo(input, audioRequired = true),
        profilePath = null,
        videoPath = input.absolutePath,
      )

      val optimizer = ReelVideoOptimizer(context)
      val hasCompatibleHardwareEncoder = optimizer.hasCompatibleHardwareHevcEncoder(input)
      val optimized = optimizer.optimizeIfUseful(
        assets = initial,
        audioRequired = true,
        onProgress = {},
      )

      assertEquals(input.absolutePath, optimized.videoPath)
      val mimes = trackMimes(input)
      if (hasCompatibleHardwareEncoder) {
        assertTrue("Expected a saving greater than 10%", optimized.localBytes < originalBytes * 0.9)
        assertTrue("Expected HEVC video", mimes.any { mime -> mime == "video/hevc" })
      } else {
        assertEquals("The source must remain untouched", originalBytes, optimized.localBytes)
        assertTrue("Expected the original AVC video", mimes.any { mime -> mime == "video/avc" })
      }
      assertTrue("Expected retained audio", mimes.any { mime -> mime.startsWith("audio/") })
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun trackMimes(file: File): List<String> {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      (0 until extractor.trackCount).mapNotNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
      }
    } finally {
      extractor.release()
    }
  }
}
