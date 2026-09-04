package com.airplanemode.doomscroll

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.airplanemode.doomscroll.data.CapturedReelRecord
import com.airplanemode.doomscroll.data.DoomscrollRepository
import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DoomscrollDownloadWorkerIntegrationTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val repository = DoomscrollRepository.get(context)

  @Before
  fun resetStorage() {
    repository.clearAll()
  }

  @After
  fun cleanUp() {
    repository.clearAll()
  }

  @Test
  fun actualForegroundWorkerRunsWithoutCrashingTheAppProcess() {
    val sessionId = repository.beginSession()
    val result = repository.saveBatch(sessionId, 0, listOf(capturedReel()))
    assertEquals(1, result.persisted)

    val scheduledWorkId = repository.download(MEDIA_PK)?.workId
    assertNotNull(scheduledWorkId)

    // The production request correctly waits for CONNECTED. Instrumentation
    // environments can report an unvalidated network (for example, a corporate
    // TLS interception certificate), so execute the same worker without that
    // scheduler constraint and keep the downloader's own network path intact.
    WorkManager.getInstance(context)
      .cancelWorkById(UUID.fromString(scheduledWorkId))
      .result
      .get()
    val request = OneTimeWorkRequestBuilder<DoomscrollDownloadWorker>()
      .setInputData(workDataOf(DoomscrollDownloadQueue.INPUT_MEDIA_PK to MEDIA_PK))
      .build()
    repository.updateDownload(
      requireNotNull(repository.download(MEDIA_PK)).copy(
        state = "queued",
        workId = request.id.toString(),
      ),
    )
    WorkManager.getInstance(context).enqueue(request).result.get()

    val deadline = System.currentTimeMillis() + WORK_TIMEOUT_MS
    var workerReachedDownloadPath = false
    while (System.currentTimeMillis() < deadline) {
      val download = repository.download(MEDIA_PK)
      if (download?.errorCode == "network" || download?.state == "downloading") {
        workerReachedDownloadPath = true
        break
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }

    WorkManager.getInstance(context).cancelWorkById(request.id).result.get()
    assertEquals(true, workerReachedDownloadPath)
  }

  private fun capturedReel() = CapturedReelRecord(
    mediaPk = MEDIA_PK,
    mediaId = MEDIA_PK,
    code = "WorkerForegroundTest",
    permalink = "https://www.instagram.com/reel/WorkerForegroundTest/",
    authorId = "worker-test-author",
    authorUsername = "worker_test",
    authorFullName = "Worker Test",
    authorIsVerified = false,
    authorIsPrivate = false,
    authorProfilePicUrl = null,
    caption = "Foreground worker integration test",
    takenAt = null,
    mediaType = 2,
    productType = "clips",
    inventorySource = null,
    originalWidth = 720,
    originalHeight = 1280,
    durationMs = 1_000,
    likeCount = null,
    commentCount = null,
    repostCount = null,
    viewCount = null,
    fbLikeCount = null,
    fbCommentCount = null,
    hasLiked = false,
    hasViewerSaved = false,
    canViewerReshare = false,
    hasAudio = true,
    audioAssetId = null,
    audioTitle = null,
    audioArtistId = null,
    audioArtistUsername = null,
    audioIsExplicit = false,
    usertagsJson = "[]",
    coauthorsJson = "[]",
    locationJson = null,
    safeMetadataJson = "{}",
    videoCandidates = listOf(
      RemoteMediaCandidate(
        url = "https://cdninstagram.com:1/worker-test.mp4",
        width = 720,
        height = 1280,
      ),
    ),
    coverCandidates = emptyList(),
  )

  companion object {
    private const val MEDIA_PK = "9000000000000000001"
    private const val POLL_INTERVAL_MS = 100L
    private const val WORK_TIMEOUT_MS = 15_000L
  }
}
