package com.airplanemode.doomscroll

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.airplanemode.R
import com.airplanemode.doomscroll.ReelAssetDownloader.LowStorageException
import com.airplanemode.doomscroll.ReelAssetDownloader.PermanentDownloadException
import com.airplanemode.doomscroll.data.DoomscrollRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore

class DoomscrollDownloadWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {
  private val repository = DoomscrollRepository.get(context)

  override fun doWork(): Result {
    val mediaPk = inputData.getString(DoomscrollDownloadQueue.INPUT_MEDIA_PK)
      ?: return Result.failure()
    if (!repository.hasSnapshotReferences(mediaPk)) return Result.failure()
    val reel = repository.reel(mediaPk) ?: return Result.failure()
    val initial = repository.download(mediaPk) ?: return Result.failure()
    setForegroundAsync(DoomscrollForegroundInfo.create(applicationContext, mediaPk, reel.authorUsername))
    downloadSlots.acquireUninterruptibly()
    try {
      update(mediaPk, "downloading", initial.progress, null, null)
      val videoCandidates = ReelUrlPolicy.fromJson(initial.videoCandidatesJson)
      val coverCandidates = ReelUrlPolicy.fromJson(initial.coverCandidatesJson)
      var lastProgressUpdate = 0L
      val efficient = initial.qualityPolicy == "efficient_hq"
      var assets = ReelAssetDownloader(applicationContext).download(
        mediaPk = mediaPk,
        authorId = reel.authorId,
        hasAudio = reel.hasAudio,
        videoCandidates = videoCandidates,
        coverCandidates = coverCandidates,
        profileUrl = initial.profilePicRemoteUrl,
        existingCoverPath = initial.coverLocalPath,
        existingProfilePath = initial.profilePicLocalPath,
      ) { progress ->
        val displayedProgress = if (efficient) progress * DOWNLOAD_PROGRESS_WEIGHT else progress
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate >= PROGRESS_INTERVAL_MS || displayedProgress >= 1.0) {
          lastProgressUpdate = now
          update(mediaPk, "downloading", displayedProgress, null, null)
        }
      }
      if (efficient) {
        assets = ReelVideoOptimizer(applicationContext).optimizeIfUseful(
          assets = assets,
          audioRequired = reel.hasAudio,
        ) { progress ->
          update(
            mediaPk,
            "downloading",
            DOWNLOAD_PROGRESS_WEIGHT + progress * OPTIMIZE_PROGRESS_WEIGHT,
            null,
            null,
          )
        }
      }
      val completedAt = System.currentTimeMillis()
      val current = repository.download(mediaPk)
      if (current == null || current.workId != id.toString()) {
        if (!repository.hasSnapshotReferences(mediaPk)) {
          File(assets.videoPath).parentFile?.deleteRecursively()
        }
        return Result.failure()
      }
      if (!repository.hasSnapshotReferences(mediaPk)) {
        File(assets.videoPath).parentFile?.deleteRecursively()
        return Result.failure()
      }
      repository.updateDownload(
        current.copy(
          state = "ready",
          progress = 1.0,
          videoCandidatesJson = "[]",
          coverCandidatesJson = "[]",
          profilePicRemoteUrl = null,
          videoLocalPath = assets.videoPath,
          coverLocalPath = assets.coverPath,
          profilePicLocalPath = assets.profilePath,
          localBytes = assets.localBytes,
          errorCode = null,
          errorDetail = null,
          attemptCount = runAttemptCount,
          selectedWidth = assets.mediaInfo.width ?: current.selectedWidth,
          selectedHeight = assets.mediaInfo.height ?: current.selectedHeight,
          estimatedBytes = assets.localBytes,
          updatedAt = completedAt,
        ),
      )
      DoomscrollDownloadEvents.send(applicationContext, mediaPk, "ready", 1.0)
      return Result.success()
    } catch (error: LowStorageException) {
      update(mediaPk, "paused_low_storage", 0.0, "low_storage", error.message)
      return Result.failure()
    } catch (error: PermanentDownloadException) {
      update(mediaPk, "failed", 0.0, error.code, error.message)
      return Result.failure()
    } catch (error: IOException) {
      return if (runAttemptCount < MAX_NETWORK_ATTEMPTS) {
        update(mediaPk, "queued", 0.0, "network", "Waiting for a stable connection.")
        Result.retry()
      } else {
        update(mediaPk, "failed", 0.0, "network", error.message ?: "The download failed.")
        Result.failure()
      }
    } catch (error: Exception) {
      update(mediaPk, "failed", 0.0, "unexpected", error.message ?: "The download failed.")
      return Result.failure()
    } finally {
      downloadSlots.release()
    }
  }

  private fun update(
    mediaPk: String,
    state: String,
    progress: Double,
    errorCode: String?,
    errorDetail: String?,
  ) {
    val current = repository.download(mediaPk) ?: return
    if (current.workId != id.toString()) return
    repository.updateDownload(
      current.copy(
        state = state,
        progress = progress.coerceIn(0.0, 1.0),
        errorCode = errorCode,
        errorDetail = errorDetail,
        attemptCount = runAttemptCount,
        updatedAt = System.currentTimeMillis(),
      ),
    )
    DoomscrollDownloadEvents.send(applicationContext, mediaPk, state, progress, errorDetail)
  }

  companion object {
    private const val MAX_NETWORK_ATTEMPTS = 4
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val DOWNLOAD_PROGRESS_WEIGHT = 0.84
    private const val OPTIMIZE_PROGRESS_WEIGHT = 0.15
    private val downloadSlots = Semaphore(2, true)
  }
}

internal object DoomscrollForegroundInfo {
  private const val CHANNEL_ID = "airplanemode-doomscroll-downloads"
  private const val NOTIFICATION_ID_BASE = 8300

  fun create(context: Context, mediaPk: String, username: String): ForegroundInfo {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          "Offline Reels",
          NotificationManager.IMPORTANCE_LOW,
        ),
      )
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_download)
      .setContentTitle("Saving Reels for offline")
      .setContentText(if (username.isBlank()) "Downloading a Reel" else "Downloading @$username")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setProgress(0, 0, true)
      .build()
    return ForegroundInfo(
      NOTIFICATION_ID_BASE + (mediaPk.hashCode() and 0x0fff),
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}
