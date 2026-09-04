package com.airplanemode.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplanemode.media.data.MediaItemEntity
import com.airplanemode.media.data.MediaRepository
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Reproducible, manual-only content used for public screenshots. */
@RunWith(AndroidJUnit4::class)
class PublishVisualFixtureTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testContext = InstrumentationRegistry.getInstrumentation().context
  private val repository = MediaRepository.get(context)

  @Test
  fun seedPublishMediaWhenExplicitlyRequested() {
    assumeTrue(
      InstrumentationRegistry.getArguments().getString("seedPublishMedia") == "true",
    )

    repository.clearAllRows()
    val mediaDirectory = File(context.filesDir, "media").apply { mkdirs() }
    val artworkDirectory = File(context.filesDir, "artwork").apply { mkdirs() }
    val now = System.currentTimeMillis()
    val fixtures = listOf(
      Fixture("window-seat", "Window Seat", "Hazy Atlas", "youtube-music", 262.0),
      Fixture("coastal-train", "Coastline", "Mono Pacific", "youtube", 330.0),
      Fixture("night-market", "Night Market", "Late Checkout", "gallery", 392.0),
    )

    fixtures.forEachIndexed { index, fixture ->
      val media = File(mediaDirectory, "publish-${fixture.asset}.wav")
      val artwork = File(artworkDirectory, "publish-${fixture.asset}.png")
      writeTone(media, fixture.frequency)
      copyPublishAsset("${fixture.asset}.png", artwork)
      repository.put(
        MediaItemEntity(
          id = "publish-${fixture.asset}",
          source = fixture.source,
          sourceKey = "publish-${fixture.asset}",
          mediaType = "audio",
          title = fixture.title,
          artist = fixture.artist,
          durationMs = 18_000L,
          width = null,
          height = null,
          thumbnailRemoteUrl = null,
          thumbnailLocalPath = artwork.absolutePath,
          playbackKind = "app-file",
          playbackValue = media.absolutePath,
          availability = "ready",
          downloadProgress = 1.0,
          collectionName = "Boarding Mix",
          createdAt = now - index * 60_000L,
          updatedAt = now - index * 60_000L,
        ),
      )
    }
    repository.createLocalPlaylist(
      "Boarding Mix",
      fixtures.map { "publish-${it.asset}" },
    )
  }

  private fun copyPublishAsset(name: String, destination: File) {
    testContext.assets.open("publish/$name").use { input ->
      FileOutputStream(destination).use { output -> input.copyTo(output) }
    }
  }

  private fun writeTone(file: File, frequency: Double) {
    val sampleRate = 44_100
    val samples = sampleRate * 18
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
        val fade = when {
          index < sampleRate -> index.toDouble() / sampleRate
          index > samples - sampleRate -> (samples - index).toDouble() / sampleRate
          else -> 1.0
        }
        val sample = (sin(2.0 * PI * frequency * index / sampleRate) * 3_200.0 * fade).toInt()
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

  private data class Fixture(
    val asset: String,
    val title: String,
    val artist: String,
    val source: String,
    val frequency: Double,
  )
}
