package com.airplanemode.doomscroll

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airplanemode.doomscroll.data.DoomscrollRepository
import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ReelAssetDownloaderTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var server: MockWebServer

  @Before
  fun startServer() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun stopServer() {
    server.shutdown()
    File(DoomscrollRepository.get(context).storageRoot(), "reels/instrumented-reel")
      .deleteRecursively()
  }

  @Test
  fun invalidLargestCandidateFallsBackToVerifiedMultiplexedMp4() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("not an mp4"))
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "video/mp4")
        .setBody(okio.Buffer().write(validMultiplexedMp4())),
    )
    val progress = mutableListOf<Double>()
    val downloader = testDownloader(storageAvailable = true)
    val assets = downloader.download(
      mediaPk = "instrumented-reel",
      authorId = "instrumented-author",
      hasAudio = true,
      videoCandidates = listOf(
        candidate("/largest-invalid.mp4", 720, 1280),
        candidate("/fallback-valid.mp4", 360, 640),
      ),
      coverCandidates = emptyList(),
      profileUrl = null,
      existingCoverPath = null,
      existingProfilePath = null,
      onProgress = progress::add,
    )

    val output = File(assets.videoPath)
    assertTrue(output.isFile && output.length() > 0)
    assertTrue(assets.mediaInfo.width == 16 && assets.mediaInfo.height == 16)
    assertTrue((assets.mediaInfo.durationMs ?: 0L) > 0L)
    assertTrue(progress.any { it > 0.9 })
    assertTrue(server.requestCount == 2)
    assertFalse(output.parentFile!!.listFiles().orEmpty().any { it.name.endsWith(".part") })
  }

  @Test
  fun lowStorageLeavesNoFinalMediaFile() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(okio.Buffer().write(validMultiplexedMp4())),
    )
    try {
      testDownloader(storageAvailable = false).download(
        mediaPk = "instrumented-reel",
        authorId = "instrumented-author",
        hasAudio = true,
        videoCandidates = listOf(candidate("/video.mp4", 720, 1280)),
        coverCandidates = emptyList(),
        profileUrl = null,
        existingCoverPath = null,
        existingProfilePath = null,
        onProgress = {},
      )
      fail("Expected the 1 GiB storage reserve to stop the download")
    } catch (_: ReelAssetDownloader.LowStorageException) {
      val output = File(
        DoomscrollRepository.get(context).storageRoot(),
        "reels/instrumented-reel/video.mp4",
      )
      assertFalse(output.exists())
    }
  }

  @Test
  fun blocksRedirectBeforeContactingAHostOutsideTheAllowlist() {
    server.enqueue(
      MockResponse()
        .setResponseCode(302)
        .setHeader("Location", "https://example.test/escaped.mp4"),
    )
    val downloader = ReelAssetDownloader(
      context = context,
      client = OkHttpClient(),
      urlValidator = { value -> !value.isNullOrBlank() && !value.contains("example.test") },
      storageCapacity = { true },
    )

    try {
      downloader.download(
        mediaPk = "instrumented-reel",
        authorId = "instrumented-author",
        hasAudio = true,
        videoCandidates = listOf(candidate("/redirect.mp4", 720, 1280)),
        coverCandidates = emptyList(),
        profileUrl = null,
        existingCoverPath = null,
        existingProfilePath = null,
        onProgress = {},
      )
      fail("Expected the redirect allowlist to reject the destination")
    } catch (error: ReelAssetDownloader.PermanentDownloadException) {
      assertEquals("blocked_redirect", error.code)
      assertEquals(1, server.requestCount)
    }
  }

  @Test
  fun interruptedTransferRemovesItsAtomicPartFile() {
    server.enqueue(
      MockResponse()
        .setBody(okio.Buffer().write(ByteArray(128 * 1024) { 1 }))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
    )

    try {
      testDownloader(storageAvailable = true).download(
        mediaPk = "instrumented-reel",
        authorId = "instrumented-author",
        hasAudio = true,
        videoCandidates = listOf(candidate("/interrupted.mp4", 720, 1280)),
        coverCandidates = emptyList(),
        profileUrl = null,
        existingCoverPath = null,
        existingProfilePath = null,
        onProgress = {},
      )
      fail("Expected the interrupted transfer to fail")
    } catch (_: IOException) {
      val directory = File(
        DoomscrollRepository.get(context).storageRoot(),
        "reels/instrumented-reel",
      )
      assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
      assertFalse(File(directory, "video.mp4").exists())
    }
  }

  private fun testDownloader(storageAvailable: Boolean) = ReelAssetDownloader(
    context = context,
    client = OkHttpClient(),
    urlValidator = { true },
    storageCapacity = { storageAvailable },
  )

  private fun candidate(path: String, width: Int, height: Int) = RemoteMediaCandidate(
    url = server.url(path).toString(),
    width = width,
    height = height,
  )

  internal fun validMultiplexedMp4(): ByteArray = Base64.decode(MULTIPLEXED_MP4_BASE64, Base64.DEFAULT)

  companion object {
    private const val MULTIPLEXED_MP4_BASE64 =
      "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"
  }
}
