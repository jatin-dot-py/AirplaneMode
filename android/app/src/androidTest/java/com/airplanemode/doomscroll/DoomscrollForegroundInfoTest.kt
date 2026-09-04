package com.airplanemode.doomscroll

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoomscrollForegroundInfoTest {
  @Test
  fun downloadWorkerDeclaresDataSyncForegroundServiceType() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val info = DoomscrollForegroundInfo.create(context, "123456789", "creator")

    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      info.foregroundServiceType,
    )
  }
}
