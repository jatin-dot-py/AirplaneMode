package com.airplanemode.doomscroll

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReelPlayerAspectTest {
  @Test
  fun usesMedia3ZoomWithoutStretchingEitherAxis() {
    assertEquals(
      AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
      reelResizeMode(),
    )
    assertNotEquals(
      AspectRatioFrameLayout.RESIZE_MODE_FILL,
      reelResizeMode(),
    )
  }
}
