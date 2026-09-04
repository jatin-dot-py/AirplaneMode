package com.airplanemode.media

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplanemode.media.data.MediaItemEntity
import com.airplanemode.media.data.MediaRepository
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class MediaPlaybackLatencyTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val repository = MediaRepository.get(context)

  @Test
  fun playCommandBypassesBlockedLibraryExecutor() {
    val id = "playback-latency-fixture"
    val activeDownloadId = "active-download-fixture"
    val retainFixture = InstrumentationRegistry.getArguments()
      .getString("retainMediaFixture") == "true"
    val file = File(context.filesDir, "media/$id.wav")
    file.parentFile?.mkdirs()
    writeTone(file)
    repository.delete(id)
    repository.delete(activeDownloadId)
    repository.put(readyItem(id, file))
    repository.put(activeDownloadItem(activeDownloadId))

    @Suppress("DEPRECATION")
    val reactContext = BridgeReactContext(context)
    val module = MediaEngineModule(reactContext)
    val releaseLibraryWork = CountDownLatch(1)
    var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    try {
      val libraryWorkStarted = CountDownLatch(1)
      val ioField = MediaEngineModule::class.java.getDeclaredField("ioExecutor").apply {
        isAccessible = true
      }
      val ioExecutor = ioField.get(module) as ExecutorService
      ioExecutor.execute {
        libraryWorkStarted.countDown()
        releaseLibraryWork.await(10, TimeUnit.SECONDS)
      }
      assertTrue("Could not block the library executor", libraryWorkStarted.await(5, TimeUnit.SECONDS))

      val completed = CountDownLatch(1)
      val result = AtomicReference<Any?>()
      val rejection = AtomicReference<Any?>()
      val promise = PromiseImpl(
        Callback { values ->
          result.set(values.firstOrNull())
          completed.countDown()
        },
        Callback { values ->
          rejection.set(values.firstOrNull())
          completed.countDown()
        },
      )

      val startedAt = SystemClock.elapsedRealtime()
      module.playMedia(id, null, promise)
      assertTrue(
        "Playback waited behind unrelated library/download work",
        completed.await(2, TimeUnit.SECONDS),
      )
      val elapsed = SystemClock.elapsedRealtime() - startedAt
      assertNull("Playback was rejected: ${rejection.get()}", rejection.get())
      assertEquals(true, result.get())
      assertTrue("Playback command took ${elapsed}ms", elapsed < 2_000L)

      controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java)),
      ).buildAsync()
      val controller = controllerFuture.get(5, TimeUnit.SECONDS)
      val deadline = SystemClock.elapsedRealtime() + 2_000L
      while (onMain { controller.currentMediaItem?.mediaId } != id &&
        SystemClock.elapsedRealtime() < deadline
      ) {
        Thread.sleep(25L)
      }
      assertEquals(id, onMain { controller.currentMediaItem?.mediaId })
    } finally {
      releaseLibraryWork.countDown()
      controllerFuture?.let { future -> onMain { MediaController.releaseFuture(future) } }
      module.invalidate()
      context.stopService(Intent(context, PlaybackService::class.java))
      if (!retainFixture) {
        repository.delete(id)
        repository.delete(activeDownloadId)
        file.delete()
      }
    }
  }

  private fun <T> onMain(action: () -> T): T {
    val value = AtomicReference<T>()
    val error = AtomicReference<Throwable>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      try {
        value.set(action())
      } catch (throwable: Throwable) {
        error.set(throwable)
      }
    }
    error.get()?.let { throw it }
    return value.get()
  }

  private fun readyItem(id: String, file: File): MediaItemEntity {
    val now = System.currentTimeMillis()
    return MediaItemEntity(
      id = id,
      source = "youtube-music",
      sourceKey = id,
      mediaType = "audio",
      title = "Immediate playback fixture",
      artist = "AirplaneMode test",
      durationMs = 3_000L,
      width = null,
      height = null,
      thumbnailRemoteUrl = null,
      thumbnailLocalPath = null,
      playbackKind = "app-file",
      playbackValue = file.absolutePath,
      availability = "ready",
      downloadProgress = 1.0,
      collectionName = null,
      createdAt = now,
      updatedAt = now,
    )
  }

  private fun activeDownloadItem(id: String): MediaItemEntity {
    val now = System.currentTimeMillis()
    return MediaItemEntity(
      id = id,
      source = "youtube",
      sourceKey = "fixture0002",
      mediaType = "video",
      title = "Active download fixture",
      artist = "AirplaneMode test",
      durationMs = null,
      width = 1920,
      height = 1080,
      thumbnailRemoteUrl = null,
      thumbnailLocalPath = null,
      playbackKind = null,
      playbackValue = null,
      availability = "downloading",
      downloadProgress = 0.42,
      collectionName = "YouTube",
      createdAt = now,
      updatedAt = now,
    )
  }

  private fun writeTone(file: File) {
    val sampleRate = 44_100
    val samples = sampleRate * 3
    val dataBytes = samples * 2
    DataOutputStream(FileOutputStream(file)).use { output ->
      output.writeBytes("RIFF")
      output.writeLittleEndianInt(36 + dataBytes)
      output.writeBytes("WAVEfmt ")
      output.writeLittleEndianInt(16)
      output.writeLittleEndianShort(1)
      output.writeLittleEndianShort(1)
      output.writeLittleEndianInt(sampleRate)
      output.writeLittleEndianInt(sampleRate * 2)
      output.writeLittleEndianShort(2)
      output.writeLittleEndianShort(16)
      output.writeBytes("data")
      output.writeLittleEndianInt(dataBytes)
      repeat(samples) { index ->
        val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * 8_000.0).toInt()
        output.writeLittleEndianShort(sample)
      }
    }
  }

  private fun DataOutputStream.writeLittleEndianInt(value: Int) {
    writeByte(value and 0xff)
    writeByte(value ushr 8 and 0xff)
    writeByte(value ushr 16 and 0xff)
    writeByte(value ushr 24 and 0xff)
  }

  private fun DataOutputStream.writeLittleEndianShort(value: Int) {
    writeByte(value and 0xff)
    writeByte(value ushr 8 and 0xff)
  }
}
