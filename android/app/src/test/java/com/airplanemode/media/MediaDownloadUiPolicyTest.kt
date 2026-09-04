package com.airplanemode.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadUiPolicyTest {
  @Test
  fun progressTicksDoNotTriggerFullLibraryReloads() {
    assertFalse(downloadStateRequiresLibraryRefresh("downloading"))
  }

  @Test
  fun structuralDownloadChangesRefreshTheLibrary() {
    listOf("queued", "ready", "failed", "missing", "cancelled").forEach { state ->
      assertTrue(state, downloadStateRequiresLibraryRefresh(state))
    }
  }
}
