package com.airplanemode.media

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplanemode.media.data.MediaItemEntity
import com.airplanemode.media.data.MediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class YtDlpDownloadInstrumentedTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val repository = MediaRepository.get(context)

  @Test
  fun publicYouTubeVideoDownloadsAsSinglePlayableFile() {
    val output = download(
      source = "youtube",
      sourceKey = "2PuFyjAs7JA",
      sourceUrl = "https://www.youtube.com/watch?v=2PuFyjAs7JA",
      mediaType = "video",
      title = "Public 1080p video test",
    )

    try {
      val tracks = trackTypes(output)
      assertTrue("Downloaded video has no video track", tracks.video)
      assertTrue("Downloaded video has no audio track", tracks.audio)
      assertTrue(
        "Downloaded video quality is below 720p: ${tracks.width}x${tracks.height}",
        tracks.height >= 720,
      )
      assertPlaybackAdvances(output)
    } finally {
      remove("youtube", "2PuFyjAs7JA")
    }
  }

  @Test
  fun publicYouTubeMusicDownloadsOriginalAudioOnly() {
    val output = download(
      source = "youtube-music",
      sourceKey = "dQw4w9WgXcQ",
      sourceUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
      mediaType = "audio",
      title = "Public music test",
    )

    try {
      val tracks = trackTypes(output)
      assertTrue("Downloaded music has no audio track", tracks.audio)
      assertFalse("Audio-only download unexpectedly contains video", tracks.video)
      assertPlaybackAdvances(output)
    } finally {
      remove("youtube-music", "dQw4w9WgXcQ")
    }
  }

  private fun download(
    source: String,
    sourceKey: String,
    sourceUrl: String,
    mediaType: String,
    title: String,
  ): File {
    val id = MediaRepository.stableId("media", "$source:$sourceKey")
    remove(source, sourceKey)
    val now = System.currentTimeMillis()
    repository.put(
      MediaItemEntity(
        id = id,
        source = source,
        sourceKey = sourceKey,
        mediaType = mediaType,
        title = title,
        artist = null,
        durationMs = null,
        width = null,
        height = null,
        thumbnailRemoteUrl = "https://i.ytimg.com/vi/$sourceKey/hqdefault.jpg",
        thumbnailLocalPath = null,
        playbackKind = null,
        playbackValue = null,
        availability = "queued",
        downloadProgress = 0.0,
        collectionName = if (source == "youtube") "YouTube" else "YouTube Music",
        createdAt = now,
        updatedAt = now,
      ),
    )
    repository.putYtDlpJob(id, sourceUrl)
    repository.setWorkId(id, YtDlpQueue.enqueue(context, id, replace = true).toString())

    val deadline = System.currentTimeMillis() + 120_000L
    var item = repository.byId(id)
    while (System.currentTimeMillis() < deadline &&
      item?.availability !in setOf("ready", "failed")
    ) {
      Thread.sleep(500L)
      item = repository.byId(id)
    }

    val error = repository.downloadJob(id)?.error
    assertNotNull("The test item disappeared", item)
    assertEquals("yt-dlp failed: $error", "ready", item!!.availability)
    assertEquals("app-file", item.playbackKind)
    val output = File(item.playbackValue!!)
    assertTrue("Downloaded output is empty", output.isFile && output.length() > 0L)
    assertTrue("Downloaded media has no duration", (item.durationMs ?: 0L) > 0L)
    return output
  }

  private fun trackTypes(file: File): TrackTypes {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      val mimeTypes = (0 until extractor.trackCount).mapNotNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
      }
      val videoFormats = (0 until extractor.trackCount)
        .map(extractor::getTrackFormat)
        .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
      TrackTypes(
        audio = mimeTypes.any { it.startsWith("audio/") },
        video = mimeTypes.any { it.startsWith("video/") },
        width = videoFormats.maxOfOrNull {
          if (it.containsKey(MediaFormat.KEY_WIDTH)) it.getInteger(MediaFormat.KEY_WIDTH) else 0
        } ?: 0,
        height = videoFormats.maxOfOrNull {
          if (it.containsKey(MediaFormat.KEY_HEIGHT)) it.getInteger(MediaFormat.KEY_HEIGHT) else 0
        } ?: 0,
      )
    } finally {
      extractor.release()
    }
  }

  private fun assertPlaybackAdvances(file: File) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val ready = CountDownLatch(1)
    lateinit var player: ExoPlayer
    instrumentation.runOnMainSync {
      player = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        addListener(object : Player.Listener {
          override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) ready.countDown()
          }
        })
        setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        prepare()
        play()
      }
    }
    try {
      assertTrue("Media3 did not prepare the downloaded file", ready.await(20, TimeUnit.SECONDS))
      Thread.sleep(1_500L)
      val position = AtomicLong()
      instrumentation.runOnMainSync { position.set(player.currentPosition) }
      assertTrue("Media3 playback position did not advance", position.get() > 0L)
    } finally {
      instrumentation.runOnMainSync { player.release() }
    }
  }

  private fun remove(source: String, sourceKey: String) {
    val id = MediaRepository.stableId("media", "$source:$sourceKey")
    repository.byId(id)?.playbackValue?.let { File(it).delete() }
    repository.delete(id)
  }

  private data class TrackTypes(
    val audio: Boolean,
    val video: Boolean,
    val width: Int,
    val height: Int,
  )
}
