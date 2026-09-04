package com.airplanemode.doomscroll

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airplanemode.doomscroll.data.CapturedReelRecord
import com.airplanemode.doomscroll.data.DoomscrollRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DoomscrollSnapshotRepositoryTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val repository = DoomscrollRepository.get(context)

  @Before
  fun resetBefore() = repository.clearAll()

  @After
  fun resetAfter() = repository.clearAll()

  @Test
  fun snapshotOrderAndMetadataStayImmutableWhileGlobalMediaDeduplicates() {
    val first = repository.createSnapshot("First", "smart_hq")
    repository.saveBatch(
      first,
      0,
      listOf(reel("100", "first-author", "first caption"), reel("101", "second", "two")),
    )
    repository.saveBatch(
      first,
      1,
      listOf(reel("100", "refreshed-author", "refreshed caption")),
    )
    val second = repository.createSnapshot("Second", "compact")
    repository.saveBatch(
      second,
      0,
      listOf(reel("100", "new-snapshot-author", "new snapshot caption")),
    )

    val firstFeed = repository.snapshotReels(first)
    val secondFeed = repository.snapshotReels(second)
    assertEquals(listOf("100", "101"), firstFeed.map { it.snapshot.mediaPk })
    assertEquals(listOf(0, 1), firstFeed.map { it.snapshot.position })
    assertTrue(firstFeed.first().snapshot.snapshotMetadataJson.contains("first-author"))
    assertFalse(firstFeed.first().snapshot.snapshotMetadataJson.contains("refreshed-author"))
    assertTrue(secondFeed.first().snapshot.snapshotMetadataJson.contains("new-snapshot-author"))
    assertEquals(2, repository.stats().capturedCount)
    assertEquals(2, repository.snapshotSummary(first).stats.capturedCount)
    assertEquals(1, repository.snapshotSummary(second).stats.capturedCount)
  }

  @Test
  fun watchedQualificationAndContinuePointersNeverRewind() {
    val snapshot = repository.createSnapshot("Watch", "smart_hq")
    repository.saveBatch(
      snapshot,
      0,
      listOf(reel("200", "one", "one"), reel("201", "two", "two"), reel("202", "three", "three")),
    )

    val partial = repository.recordPlayback(snapshot, "200", 0, 5_000, 20_000, 5_000)
    assertFalse(partial.qualified)
    val qualified = repository.recordPlayback(snapshot, "200", 0, 10_000, 20_000, 5_000)
    assertTrue(qualified.qualified)
    assertEquals(1, qualified.resumePosition)

    repository.recordPlayback(snapshot, "202", 2, 1_000, 20_000, 0)
    repository.recordPlayback(snapshot, "201", 1, 1_000, 20_000, 0)
    var summary = repository.snapshotSummary(snapshot)
    assertEquals(2, summary.currentPosition)
    assertEquals(1, summary.resumePosition)

    // Native playback reports in small intervals and the repository defensively
    // caps any single interval at five seconds. Two real-sized reports qualify it.
    repository.recordPlayback(snapshot, "202", 2, 5_000, 20_000, 5_000)
    repository.recordPlayback(snapshot, "202", 2, 10_000, 20_000, 5_000)
    repository.recordPlayback(snapshot, "200", 0, 2_000, 20_000, 0)
    summary = repository.snapshotSummary(snapshot)
    assertEquals(2, summary.currentPosition)
    assertEquals(3, summary.resumePosition)
    assertEquals(2, summary.watchedCount)
  }

  @Test
  fun deletingOneSnapshotKeepsSharedMediaUntilItsLastReferenceIsDeleted() {
    val first = repository.createSnapshot("First", "smart_hq")
    val second = repository.createSnapshot("Second", "smart_hq")
    val shared = reel("300", "shared", "shared")
    repository.saveBatch(first, 0, listOf(shared))
    repository.saveBatch(second, 0, listOf(shared))

    val directory = File(repository.storageRoot(), "reels/300").apply { mkdirs() }
    val video = File(directory, "video.mp4").apply { writeBytes(ByteArray(4_096) { 7 }) }
    val download = requireNotNull(repository.download("300"))
    repository.updateDownload(
      download.copy(
        state = "ready",
        progress = 1.0,
        videoLocalPath = video.absolutePath,
        localBytes = video.length(),
      ),
    )

    val firstDelete = repository.deleteSnapshot(first)
    assertTrue(firstDelete.deleted)
    assertEquals(0L, firstDelete.reclaimedBytes)
    assertTrue(video.isFile)
    assertTrue(repository.hasSnapshotReferences("300"))

    val secondDelete = repository.deleteSnapshot(second)
    assertTrue(secondDelete.deleted)
    assertEquals(4_096L, secondDelete.reclaimedBytes)
    assertFalse(video.exists())
    assertFalse(repository.hasSnapshotReferences("300"))
  }

  @Test
  fun oneThousandMetadataItemsKeepStableSequentialPositions() {
    val snapshot = repository.createSnapshot("Thousand", "smart_hq")
    repeat(40) { page ->
      repository.saveBatch(
        snapshot,
        page,
        List(25) { offset ->
          val id = (10_000 + page * 25 + offset).toString()
          reel(id, "creator-$id", "caption-$id")
        },
      )
    }

    val stored = repository.snapshotReels(snapshot)
    assertEquals(1_000, stored.size)
    assertEquals((0 until 1_000).toList(), stored.map { it.snapshot.position })
    assertEquals(40, repository.snapshotSummary(snapshot).snapshot.pagesCaptured)
  }

  private fun reel(
    mediaPk: String,
    username: String,
    caption: String,
  ) = CapturedReelRecord(
    mediaPk = mediaPk,
    mediaId = "${mediaPk}_author",
    code = "Code$mediaPk",
    permalink = "https://www.instagram.com/reel/Code$mediaPk/",
    authorId = "author-$mediaPk",
    authorUsername = username,
    authorFullName = username,
    authorIsVerified = false,
    authorIsPrivate = false,
    authorProfilePicUrl = null,
    caption = caption,
    takenAt = 1_700_000_000,
    mediaType = 2,
    productType = "clips",
    inventorySource = "test",
    originalWidth = 720,
    originalHeight = 1280,
    durationMs = 20_000,
    likeCount = 10,
    commentCount = 2,
    repostCount = 1,
    viewCount = 100,
    fbLikeCount = null,
    fbCommentCount = null,
    hasLiked = false,
    hasViewerSaved = false,
    canViewerReshare = true,
    hasAudio = true,
    audioAssetId = "audio-$mediaPk",
    audioTitle = "Original audio",
    audioArtistId = "author-$mediaPk",
    audioArtistUsername = username,
    audioIsExplicit = false,
    usertagsJson = "[]",
    coauthorsJson = "[]",
    locationJson = null,
    safeMetadataJson = "{}",
    videoCandidates = emptyList(),
    coverCandidates = emptyList(),
  )
}
