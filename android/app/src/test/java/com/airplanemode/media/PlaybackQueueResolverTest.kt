package com.airplanemode.media

import com.airplanemode.media.data.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueResolverTest {
  @Test
  fun sourcePlaybackKeepsOnlyTheSelectedSource() {
    val playable = listOf(
      item("music-1", "youtube-music"),
      item("video-1", "youtube"),
      item("music-2", "youtube-music"),
      item("gallery-1", "gallery"),
    )

    val queue = PlaybackQueueResolver.resolve("music-1", playable)

    assertEquals(listOf("music-1", "music-2"), queue.map { it.id })
  }

  @Test
  fun playlistPlaybackKeepsPlaylistOrderAcrossSources() {
    val music = item("music-1", "youtube-music")
    val video = item("video-1", "youtube")
    val gallery = item("gallery-1", "gallery")
    val unavailable = item("missing", "youtube-music", playable = false)

    val queue = PlaybackQueueResolver.resolve(
      selectedId = "video-1",
      playableItems = listOf(music, video, gallery),
      playlistItems = listOf(gallery, unavailable, video, music),
    )

    assertEquals(listOf("gallery-1", "video-1", "music-1"), queue.map { it.id })
  }

  private fun item(id: String, source: String, playable: Boolean = true) = MediaItemEntity(
    id = id,
    source = source,
    sourceKey = id,
    mediaType = if (source == "youtube") "video" else "audio",
    title = id,
    artist = null,
    durationMs = 1_000L,
    width = null,
    height = null,
    thumbnailRemoteUrl = null,
    thumbnailLocalPath = null,
    playbackKind = if (playable) "app-file" else null,
    playbackValue = if (playable) "/tmp/$id" else null,
    availability = if (playable) "ready" else "missing",
    downloadProgress = if (playable) 1.0 else 0.0,
    collectionName = null,
    createdAt = 0L,
    updatedAt = 0L,
  )
}
