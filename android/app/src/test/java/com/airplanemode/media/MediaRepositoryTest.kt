package com.airplanemode.media

import com.airplanemode.media.data.MediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaRepositoryTest {
  @Test
  fun stableIdsAreDeterministicAndNamespaced() {
    val first = MediaRepository.stableId("media", "youtube-music:abcdefghijk")
    val second = MediaRepository.stableId("media", "youtube-music:abcdefghijk")
    val collection = MediaRepository.stableId("collection", "youtube-music:abcdefghijk")

    assertEquals(first, second)
    assertNotEquals(first, collection)
    assertEquals(64, first.length)
  }
}
