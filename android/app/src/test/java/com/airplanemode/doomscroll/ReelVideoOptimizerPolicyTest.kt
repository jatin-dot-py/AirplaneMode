package com.airplanemode.doomscroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelVideoOptimizerPolicyTest {
  @Test
  fun keepsOnlyVerifiedSavingsOfMoreThanTenPercent() {
    assertTrue(ReelVideoOptimizer.isMeaningfulSaving(1_000L, 899L))
    assertFalse(ReelVideoOptimizer.isMeaningfulSaving(1_000L, 900L))
    assertFalse(ReelVideoOptimizer.isMeaningfulSaving(1_000L, 0L))
    assertFalse(ReelVideoOptimizer.isMeaningfulSaving(0L, 100L))
  }

  @Test
  fun targetBitrateIsBoundedForQualityAndStorage() {
    assertEquals(750_000, ReelVideoOptimizer.targetBitrateFor(1_100_000L))
    assertEquals(1_360_000, ReelVideoOptimizer.targetBitrateFor(2_000_000L))
    assertEquals(2_200_000, ReelVideoOptimizer.targetBitrateFor(8_000_000L))
  }
}
