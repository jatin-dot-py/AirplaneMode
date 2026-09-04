package com.airplanemode.doomscroll

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airplanemode.doomscroll.data.CaptureSessionEntity
import com.airplanemode.doomscroll.data.DoomscrollDatabase
import com.airplanemode.doomscroll.data.ReelDownloadEntity
import com.airplanemode.doomscroll.data.ReelEntity
import com.airplanemode.doomscroll.data.SnapshotProgressEntity
import com.airplanemode.doomscroll.data.SnapshotReelEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoomscrollDaoTest {
  private lateinit var database: DoomscrollDatabase

  @Before
  fun openDatabase() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      DoomscrollDatabase::class.java,
    ).allowMainThreadQueries().build()
  }

  @After
  fun closeDatabase() = database.close()

  @Test
  fun globalMediaKeyDeduplicatesAndRefreshesMetadata() {
    val dao = database.doomscrollDao()
    dao.putReel(reel(mediaPk = "100", username = "original", sessionStartedAt = 10, position = 0))
    dao.putReel(reel(mediaPk = "100", username = "refreshed", sessionStartedAt = 20, position = 4))

    assertEquals(1, dao.reelCount())
    assertEquals("refreshed", dao.reelByPk("100")?.authorUsername)
    assertEquals(4, dao.reelByPk("100")?.latestPosition)
  }

  @Test
  fun latestSessionSortsFirstWhileSessionPositionsStayAscending() {
    val dao = database.doomscrollDao()
    dao.putReel(reel(mediaPk = "old", username = "old", sessionStartedAt = 10, position = 0))
    dao.putReel(reel(mediaPk = "new-two", username = "new", sessionStartedAt = 20, position = 2))
    dao.putReel(reel(mediaPk = "new-one", username = "new", sessionStartedAt = 20, position = 1))

    assertEquals(
      listOf("new-one", "new-two", "old"),
      dao.allReels().map(ReelEntity::mediaPk),
    )
  }

  @Test
  fun snapshotMembershipUsesCompositeUniquenessAndIndependentOrdering() {
    val dao = database.doomscrollDao()
    dao.putSnapshotReel(snapshotReel("first", "100", 0, "first metadata"))
    dao.putSnapshotReel(snapshotReel("first", "101", 1, "second metadata"))
    dao.putSnapshotReel(snapshotReel("second", "100", 4, "new metadata"))

    assertEquals(setOf("100", "101"), dao.snapshotMediaPks("first").toSet())
    assertEquals(listOf(0, 1), dao.snapshotReels("first").map { it.position })
    assertEquals(2, dao.snapshotReferenceCount("100"))
    assertEquals("first metadata", dao.snapshotReel("first", "100")?.snapshotMetadataJson)
    assertEquals("new metadata", dao.snapshotReel("second", "100")?.snapshotMetadataJson)
  }

  @Test
  fun qualifiedWatchAndProgressAreScopedToOneSnapshot() {
    val dao = database.doomscrollDao()
    dao.putSnapshotReel(snapshotReel("first", "100", 0, "{}").copy(qualifiedWatchedAt = 50))
    dao.putSnapshotReel(snapshotReel("second", "100", 0, "{}"))
    dao.putSnapshotProgress(
      SnapshotProgressEntity("first", 1, 0, "100", 8_000, 100),
    )

    assertEquals(1, dao.watchedCount("first"))
    assertEquals(0, dao.watchedCount("second"))
    assertEquals(1, dao.snapshotProgress("first")?.resumePosition)
    assertNull(dao.snapshotProgress("second"))
  }

  @Test
  fun stateCountsBytesAndClearAllTablesAreIndependentFromMediaLibrary() {
    val dao = database.doomscrollDao()
    dao.putSession(
      CaptureSessionEntity(
        id = "session",
        startedAt = 1,
        finishedAt = null,
        state = "capturing",
        stopReason = null,
        pagesCaptured = 1,
        uniqueReels = 2,
      ),
    )
    dao.putReel(reel(mediaPk = "ready", username = "one", sessionStartedAt = 1, position = 0))
    dao.putReel(reel(mediaPk = "failed", username = "two", sessionStartedAt = 1, position = 1))
    dao.putDownload(download(mediaPk = "ready", state = "ready", bytes = 4096))
    dao.putDownload(download(mediaPk = "failed", state = "failed", bytes = 0))

    assertEquals(1, dao.downloadCount("ready"))
    assertEquals(1, dao.downloadCount("failed"))
    assertEquals(4096L, dao.downloadedBytes())

    dao.clearDownloads()
    dao.clearSnapshotProgress()
    dao.clearSnapshotReels()
    dao.clearReels()
    dao.clearSessions()
    assertEquals(0, dao.reelCount())
    assertEquals(0, dao.downloadCount("ready"))
    assertNull(dao.sessionById("session"))
  }

  private fun reel(
    mediaPk: String,
    username: String,
    sessionStartedAt: Long,
    position: Int,
  ) = ReelEntity(
    mediaPk = mediaPk,
    mediaId = "${mediaPk}_author",
    code = "code-$mediaPk",
    permalink = "https://www.instagram.com/reel/code-$mediaPk/",
    authorId = "author",
    authorUsername = username,
    authorFullName = null,
    authorIsVerified = false,
    authorIsPrivate = false,
    caption = null,
    takenAt = null,
    mediaType = 2,
    productType = "clips",
    inventorySource = null,
    originalWidth = 720,
    originalHeight = 1280,
    durationMs = 10_000,
    likeCount = null,
    commentCount = null,
    repostCount = null,
    viewCount = null,
    fbLikeCount = null,
    fbCommentCount = null,
    hasLiked = false,
    hasViewerSaved = false,
    canViewerReshare = true,
    hasAudio = true,
    audioAssetId = null,
    audioTitle = "Original audio",
    audioArtistId = null,
    audioArtistUsername = username,
    audioIsExplicit = false,
    usertagsJson = "[]",
    coauthorsJson = "[]",
    locationJson = null,
    safeMetadataJson = "{}",
    latestSessionId = "session-$sessionStartedAt",
    latestSessionStartedAt = sessionStartedAt,
    latestPosition = position,
    firstCapturedAt = 1,
    lastCapturedAt = 2,
  )

  private fun download(mediaPk: String, state: String, bytes: Long) = ReelDownloadEntity(
    mediaPk = mediaPk,
    state = state,
    progress = if (state == "ready") 1.0 else 0.0,
    videoCandidatesJson = "[]",
    coverCandidatesJson = "[]",
    profilePicRemoteUrl = null,
    videoLocalPath = null,
    coverLocalPath = null,
    profilePicLocalPath = null,
    localBytes = bytes,
    errorCode = null,
    errorDetail = null,
    attemptCount = 0,
    workId = null,
    createdAt = 1,
    updatedAt = 2,
  )

  private fun snapshotReel(
    snapshotId: String,
    mediaPk: String,
    position: Int,
    metadata: String,
  ) = SnapshotReelEntity(
    snapshotId = snapshotId,
    mediaPk = mediaPk,
    position = position,
    capturedAt = 1,
    snapshotMetadataJson = metadata,
    qualifiedWatchedAt = null,
    activePlaybackMs = 0,
    lastPlaybackPositionMs = 0,
    firstViewedAt = null,
    lastViewedAt = null,
  )
}
