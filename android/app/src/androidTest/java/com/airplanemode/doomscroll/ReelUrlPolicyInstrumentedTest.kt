package com.airplanemode.doomscroll

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airplanemode.doomscroll.data.DoomscrollRepository
import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReelUrlPolicyInstrumentedTest {
  @Test
  fun serializationPreservesTheSelectedQualityOrder() {
    val candidates = listOf(
      candidate("large", 1080, 1920),
      candidate("small", 360, 640),
      candidate("compact", 480, 854),
      candidate("smart", 720, 1280),
    )
    val selected = DoomscrollRepository.selectCandidates(candidates, "compact")

    assertEquals(selected, ReelUrlPolicy.fromJson(ReelUrlPolicy.toJson(selected)))
    assertEquals(480, ReelUrlPolicy.fromJson(ReelUrlPolicy.toJson(selected)).first().width)
  }

  private fun candidate(name: String, width: Int, height: Int) = RemoteMediaCandidate(
    url = "https://scontent.cdninstagram.com/$name.mp4",
    width = width,
    height = height,
  )
}
