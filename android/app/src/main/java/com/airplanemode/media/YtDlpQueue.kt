package com.airplanemode.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

object YtDlpQueue {
  const val INPUT_MEDIA_ITEM_ID = "mediaItemId"
  private const val TAG_QUEUE = "airplanemode-yt-dlp"

  fun enqueue(context: Context, mediaItemId: String, replace: Boolean = false): UUID {
    val request = OneTimeWorkRequestBuilder<YtDlpDownloadWorker>()
      .setInputData(workDataOf(INPUT_MEDIA_ITEM_ID to mediaItemId))
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .build(),
      )
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
      .addTag(TAG_QUEUE)
      .addTag(tagFor(mediaItemId))
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(mediaItemId),
      if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
      request,
    )
    return request.id
  }

  fun cancel(context: Context, mediaItemId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(mediaItemId))
  }

  private fun workName(mediaItemId: String) = "airplanemode-yt-dlp-$mediaItemId"
  private fun tagFor(mediaItemId: String) = "airplanemode-media-$mediaItemId"
}
