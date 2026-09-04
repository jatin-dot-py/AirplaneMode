package com.airplanemode.doomscroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airplanemode.doomscroll.data.CapturedReelRecord
import com.airplanemode.doomscroll.data.DoomscrollRepository
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** A manual-only fixture used to inspect the release UI without real user credentials. */
@RunWith(AndroidJUnit4::class)
class DoomscrollVisualFixtureTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val testContext = InstrumentationRegistry.getInstrumentation().context
  private val repository = DoomscrollRepository.get(context)

  @Test
  fun seedInstagramStyleSnapshotWhenExplicitlyRequested() {
    assumeTrue(
      InstrumentationRegistry.getArguments().getString("seedVisualFixture") == "true",
    )
    repository.clearAll()
    val snapshotId = repository.createSnapshot("Evening flight", "efficient_hq")
    val reels = listOf(
      reel("9100000000000000001", "wander.frames", "Wander Frames", true,
        "A quiet landing above the clouds ✈️  Save this view for your next window seat.",
        128_400, 932, 4_210),
      reel("9100000000000000002", "studio.north", "Studio North", false,
        "Small details from a slow afternoon. Which frame would you keep?",
        42_800, 317, 865),
      reel("9100000000000000003", "night.market", "Night Market", true,
        "Street food after dark — headphones recommended.",
        806_000, 2_418, 18_300),
    )
    assertEquals(3, repository.saveBatch(snapshotId, 0, reels).persisted)

    val visualAssets = listOf("window-seat", "coastal-train", "night-market")
    reels.forEachIndexed { index, reel ->
      val directory = File(repository.storageRoot(), "reels/${reel.mediaPk}").apply { mkdirs() }
      val cover = File(directory, "cover.png")
      val avatar = File(directory, "avatar.jpg")
      copyPublishAsset("${visualAssets[index]}.png", cover)
      writeArtwork(
        avatar,
        160,
        160,
        listOf(Color.rgb(232, 104, 72), Color.rgb(44, 91, 112), Color.rgb(140, 62, 43))[index],
        listOf(Color.rgb(18, 31, 48), Color.rgb(20, 28, 36), Color.rgb(18, 18, 22))[index],
        listOf(reel.authorUsername.take(1).uppercase()),
      )
      val video = File(directory, "video.mp4")
      copyPublishAsset("${visualAssets[index]}.mp4", video)
      val download = requireNotNull(repository.download(reel.mediaPk))
      repository.updateDownload(
        download.copy(
          state = "ready",
          progress = 1.0,
          videoCandidatesJson = "[]",
          coverCandidatesJson = "[]",
          profilePicRemoteUrl = null,
          videoLocalPath = video.absolutePath,
          coverLocalPath = cover.absolutePath,
          profilePicLocalPath = avatar.absolutePath,
          localBytes = video.length() + cover.length() + avatar.length(),
          errorCode = null,
          errorDetail = null,
          workId = null,
          selectedWidth = 720,
          selectedHeight = 1280,
        ),
      )
    }
    repository.finishSession(snapshotId, "complete")
  }

  @Test
  fun seedThousandReelTailSnapshotWhenExplicitlyRequested() {
    assumeTrue(
      InstrumentationRegistry.getArguments().getString("seedTailStressFixture") == "true",
    )
    repository.clearAll()
    val snapshotId = repository.createSnapshot("1,000 Reel tail stress", "efficient_hq")
    val reels = (0 until 1_000).map { index ->
      val suffix = index.toString().padStart(4, '0')
      reel(
        mediaPk = "920000000000000$suffix",
        username = "tail.test.$suffix",
        fullName = "Tail Test $suffix",
        verified = index % 10 == 0,
        caption = "Virtualized Reel ${index + 1} of 1,000",
        likes = index.toLong(),
        comments = 0,
        shares = 0,
      )
    }
    reels.chunked(25).forEachIndexed { page, batch ->
      assertEquals(batch.size, repository.saveBatch(snapshotId, page, batch).persisted)
    }

    reels.takeLast(2).forEachIndexed { tailIndex, reel ->
      val directory = File(repository.storageRoot(), "reels/${reel.mediaPk}").apply { mkdirs() }
      val cover = File(directory, "cover.jpg")
      writeArtwork(
        cover,
        720,
        1280,
        if (tailIndex == 0) Color.rgb(22, 71, 92) else Color.rgb(89, 27, 50),
        Color.BLACK,
        listOf("TAIL STRESS", if (tailIndex == 0) "999 / 1000" else "1000 / 1000"),
      )
      val video = File(directory, "video.mp4").apply {
        writeBytes(ReelAssetDownloaderTest().validMultiplexedMp4())
      }
      repository.updateDownload(
        requireNotNull(repository.download(reel.mediaPk)).copy(
          state = "ready",
          progress = 1.0,
          videoCandidatesJson = "[]",
          coverCandidatesJson = "[]",
          videoLocalPath = video.absolutePath,
          coverLocalPath = cover.absolutePath,
          localBytes = video.length() + cover.length(),
          errorCode = null,
          errorDetail = null,
          workId = null,
          selectedWidth = 720,
          selectedHeight = 1280,
        ),
      )
    }
    val finalReel = reels.last()
    repository.recordPlayback(
      snapshotId,
      finalReel.mediaPk,
      reels.lastIndex,
      10_000,
      20_000,
      10_000,
    )
    repository.finishSession(snapshotId, "complete")
  }

  private fun reel(
    mediaPk: String,
    username: String,
    fullName: String,
    verified: Boolean,
    caption: String,
    likes: Long,
    comments: Long,
    shares: Long,
  ) = CapturedReelRecord(
    mediaPk = mediaPk,
    mediaId = "${mediaPk}_visual",
    code = "Visual${mediaPk.takeLast(4)}",
    permalink = "https://www.instagram.com/reel/Visual${mediaPk.takeLast(4)}/",
    authorId = "author-${mediaPk.takeLast(4)}",
    authorUsername = username,
    authorFullName = fullName,
    authorIsVerified = verified,
    authorIsPrivate = false,
    authorProfilePicUrl = null,
    caption = caption,
    takenAt = 1_788_300_000,
    mediaType = 2,
    productType = "clips",
    inventorySource = "visual-fixture",
    originalWidth = 720,
    originalHeight = 1280,
    durationMs = 20_000,
    likeCount = likes,
    commentCount = comments,
    repostCount = shares,
    viewCount = null,
    fbLikeCount = null,
    fbCommentCount = null,
    hasLiked = false,
    hasViewerSaved = false,
    canViewerReshare = true,
    hasAudio = true,
    audioAssetId = "audio-${mediaPk.takeLast(4)}",
    audioTitle = "Original audio",
    audioArtistId = "author-${mediaPk.takeLast(4)}",
    audioArtistUsername = username,
    audioIsExplicit = false,
    usertagsJson = "[]",
    coauthorsJson = "[]",
    locationJson = null,
    safeMetadataJson = "{}",
    videoCandidates = emptyList(),
    coverCandidates = emptyList(),
  )

  private fun writeArtwork(
    file: File,
    width: Int,
    height: Int,
    startColor: Int,
    endColor: Int,
    lines: List<String>,
  ) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = LinearGradient(
        0f,
        0f,
        width.toFloat(),
        height.toFloat(),
        startColor,
        endColor,
        Shader.TileMode.CLAMP,
      )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textSize = (width * 0.08f).coerceAtLeast(24f)
    val center = height / 2f - (lines.size - 1) * paint.textSize * 0.62f
    lines.forEachIndexed { index, line ->
      canvas.drawText(line, width / 2f, center + index * paint.textSize * 1.24f, paint)
    }
    FileOutputStream(file).use { output ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
    }
    bitmap.recycle()
  }

  private fun copyPublishAsset(name: String, destination: File) {
    testContext.assets.open("publish/$name").use { input ->
      FileOutputStream(destination).use { output -> input.copyTo(output) }
    }
  }
}
