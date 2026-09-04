package com.airplanemode.doomscroll

import android.content.Context
import android.content.Intent

object DoomscrollDownloadEvents {
  const val ACTION = "com.airplanemode.doomscroll.DOWNLOAD_STATE_CHANGED"
  const val EXTRA_MEDIA_PK = "mediaPk"
  const val EXTRA_STATE = "state"
  const val EXTRA_PROGRESS = "progress"
  const val EXTRA_ERROR = "error"

  fun send(
    context: Context,
    mediaPk: String,
    state: String,
    progress: Double,
    error: String? = null,
  ) {
    context.sendBroadcast(
      Intent(ACTION)
        .setPackage(context.packageName)
        .putExtra(EXTRA_MEDIA_PK, mediaPk)
        .putExtra(EXTRA_STATE, state)
        .putExtra(EXTRA_PROGRESS, progress.coerceIn(0.0, 1.0))
        .putExtra(EXTRA_ERROR, error),
    )
  }
}
