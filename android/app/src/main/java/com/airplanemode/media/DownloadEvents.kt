package com.airplanemode.media

import android.content.Context
import android.content.Intent

object DownloadEvents {
  const val ACTION = "com.airplanemode.media.DOWNLOAD_STATE_CHANGED"
  const val EXTRA_ITEM_ID = "itemId"
  const val EXTRA_STATE = "state"
  const val EXTRA_PROGRESS = "progress"
  const val EXTRA_ERROR = "error"

  fun send(
    context: Context,
    itemId: String,
    state: String,
    progress: Double,
    error: String? = null,
  ) {
    context.sendBroadcast(
      Intent(ACTION)
        .setPackage(context.packageName)
        .putExtra(EXTRA_ITEM_ID, itemId)
        .putExtra(EXTRA_STATE, state)
        .putExtra(EXTRA_PROGRESS, progress)
        .putExtra(EXTRA_ERROR, error),
    )
  }
}
