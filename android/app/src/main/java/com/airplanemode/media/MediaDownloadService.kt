package com.airplanemode.media

import android.app.Notification
import androidx.media3.exoplayer.offline.Download
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.airplanemode.R

@androidx.annotation.OptIn(UnstableApi::class)
class MediaDownloadService : DownloadService(
  FOREGROUND_NOTIFICATION_ID,
  DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
  CHANNEL_ID,
  R.string.download_channel_name,
  0,
) {
  private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

  override fun getDownloadManager(): DownloadManager = DownloadStore.downloadManager(this)

  override fun getScheduler(): Scheduler? = null

  override fun getForegroundNotification(
    downloads: MutableList<Download>,
    notMetRequirements: Int,
  ): Notification = notificationHelper.buildProgressNotification(
    this,
    R.drawable.ic_download,
    null,
    null,
    downloads,
    notMetRequirements,
  )

  companion object {
    private const val FOREGROUND_NOTIFICATION_ID = 7102
    private const val CHANNEL_ID = "airplane-mode-downloads"
  }
}
