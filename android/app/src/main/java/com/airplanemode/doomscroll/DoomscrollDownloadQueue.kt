package com.airplanemode.doomscroll

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

object DoomscrollDownloadQueue {
  const val INPUT_MEDIA_PK = "mediaPk"
  const val TAG_QUEUE = "airplanemode-doomscroll-download"

  fun enqueue(
    context: Context,
    mediaPk: String,
    replace: Boolean = false,
    onPrepared: (UUID) -> Unit = {},
  ): UUID {
    val request = OneTimeWorkRequestBuilder<DoomscrollDownloadWorker>()
      .setInputData(workDataOf(INPUT_MEDIA_PK to mediaPk))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
      .addTag(TAG_QUEUE)
      .addTag(itemTag(mediaPk))
      .build()
    // Persist the exact ID before WorkManager can dispatch this request.
    onPrepared(request.id)
    WorkManager.getInstance(context).enqueueUniqueWork(
      workName(mediaPk),
      if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
      request,
    )
    return request.id
  }

  fun cancel(context: Context, mediaPk: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(mediaPk))
  }

  private fun workName(mediaPk: String) = "airplanemode-doomscroll-$mediaPk"
  private fun itemTag(mediaPk: String) = "airplanemode-doomscroll-item-$mediaPk"
}
