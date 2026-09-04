package com.airplanemode.doomscroll

import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import com.airplanemode.doomscroll.data.DoomscrollRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelUrlPolicyTest {
  @Test
  fun acceptsOnlyHttpsMetaCdnHosts() {
    assertTrue(ReelUrlPolicy.isAllowedHttpsUrl("https://scontent.cdninstagram.com/video.mp4"))
    assertTrue(ReelUrlPolicy.isAllowedHttpsUrl("https://video.xx.fbcdn.net/video.mp4"))
    assertFalse(ReelUrlPolicy.isAllowedHttpsUrl("http://scontent.cdninstagram.com/video.mp4"))
    assertFalse(ReelUrlPolicy.isAllowedHttpsUrl("https://cdninstagram.com.example.test/video.mp4"))
    assertFalse(ReelUrlPolicy.isAllowedHttpsUrl("https://example.test/cdninstagram.com/video.mp4"))
    assertFalse(ReelUrlPolicy.isAllowedHttpsUrl("file:///data/user/0/app/video.mp4"))
  }

  @Test
  fun deduplicatesAndOrdersCandidatesByPixelArea() {
    val small = RemoteMediaCandidate(
      url = "https://scontent.cdninstagram.com/small.mp4",
      width = 360,
      height = 640,
    )
    val large = RemoteMediaCandidate(
      url = "https://scontent.cdninstagram.com/large.mp4",
      width = 720,
      height = 1280,
    )
    val blocked = RemoteMediaCandidate(
      url = "https://example.test/blocked.mp4",
      width = 2160,
      height = 3840,
    )

    assertEquals(
      listOf(large, small),
      ReelUrlPolicy.ordered(listOf(small, blocked, large, large)),
    )
  }

  @Test
  fun serializedCandidatesKeepTheQualityPolicyOrder() {
    val large = candidate("large", 1080, 1920)
    val smart = candidate("smart", 720, 1280)
    val compact = candidate("compact", 480, 854)
    val small = candidate("small", 360, 640)
    val candidates = listOf(large, small, compact, smart)

    assertEquals(
      listOf(smart, large, compact, small),
      DoomscrollRepository.selectCandidates(candidates, "smart_hq"),
    )
    assertEquals(
      listOf(smart, large, compact, small),
      DoomscrollRepository.selectCandidates(candidates, "efficient_hq"),
    )
    assertEquals(
      listOf(compact, smart, large, small),
      DoomscrollRepository.selectCandidates(candidates, "compact"),
    )
    assertEquals(
      listOf(large, smart, compact, small),
      DoomscrollRepository.selectCandidates(candidates, "original"),
    )

  }

  private fun candidate(name: String, width: Int, height: Int) = RemoteMediaCandidate(
    url = "https://scontent.cdninstagram.com/$name.mp4",
    width = width,
    height = height,
  )
}
