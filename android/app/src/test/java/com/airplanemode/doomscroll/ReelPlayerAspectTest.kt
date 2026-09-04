package com.airplanemode.doomscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelPlayerAspectTest {
  @Test
  fun squarePixelsRemainSquareAfterCenterCrop() {
    val viewWidth = 1080
    val viewHeight = 2424
    val videoWidth = 720
    val videoHeight = 720
    val scale = centerCropScale(
      viewWidth = viewWidth,
      viewHeight = viewHeight,
      videoWidth = videoWidth,
      videoHeight = videoHeight,
    )

    val effectiveHorizontalScale = viewWidth.toFloat() / videoWidth * scale.scaleX
    val effectiveVerticalScale = viewHeight.toFloat() / videoHeight * scale.scaleY
    assertEquals(effectiveVerticalScale, effectiveHorizontalScale, 0.0001f)
  }

  @Test
  fun verticalVideoExpandsHorizontallyWithoutStretching() {
    val scale = centerCropScale(
      viewWidth = 1080,
      viewHeight = 2424,
      videoWidth = 720,
      videoHeight = 1280,
    )

    assertEquals(1.2625f, scale.scaleX, 0.0001f)
    assertEquals(1f, scale.scaleY, 0.0001f)
  }

  @Test
  fun wideVideoExpandsHorizontallyWithoutStretching() {
    val scale = centerCropScale(
      viewWidth = 1080,
      viewHeight = 2424,
      videoWidth = 1920,
      videoHeight = 1080,
    )

    assertEquals(3.9901f, scale.scaleX, 0.0001f)
    assertEquals(1f, scale.scaleY, 0.0001f)
  }

  @Test
  fun invalidDimensionsUseIdentityTransform() {
    assertEquals(
      ReelContentScale(1f, 1f),
      centerCropScale(0, 2424, 720, 1280),
    )
  }
}
