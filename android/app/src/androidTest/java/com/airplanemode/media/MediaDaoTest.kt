package com.airplanemode.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airplanemode.media.data.MediaDatabase
import com.airplanemode.media.data.MediaItemEntity
import com.airplanemode.media.data.LocalPlaylistEntity
import com.airplanemode.media.data.LocalPlaylistItemEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDaoTest {
  private lateinit var database: MediaDatabase

  @Before
  fun openDatabase() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MediaDatabase::class.java,
    ).allowMainThreadQueries().build()
  }

  @After
  fun closeDatabase() = database.close()

  @Test
  fun sourceAndSourceKeyAreUnique() {
    val original = item(id = "first", title = "Original")
    val duplicate = item(id = "second", title = "Duplicate")

    assertTrue(database.mediaDao().insertMedia(original) >= 0)
    assertEquals(-1L, database.mediaDao().insertMedia(duplicate))
    assertEquals(listOf(original), database.mediaDao().allMedia())
  }

  @Test
  fun localPlaylistKeepsOrderedMediaAndSummaryCount() {
    val media = item(id = "first", title = "Offline song")
    val playlist = LocalPlaylistEntity(
      id = "playlist-one",
      name = "Flight deck",
      pinned = true,
      createdAt = 1L,
      updatedAt = 2L,
    )
    database.mediaDao().insertMedia(media)
    database.mediaDao().insertLocalPlaylist(playlist)
    database.mediaDao().putLocalPlaylistItem(
      LocalPlaylistItemEntity(
        playlistId = playlist.id,
        mediaItemId = media.id,
        position = 0,
        addedAt = 3L,
      ),
    )

    assertEquals(listOf(media), database.mediaDao().mediaForLocalPlaylist(playlist.id))
    assertEquals(1, database.mediaDao().allLocalPlaylists().single().itemCount)
    assertTrue(database.mediaDao().allLocalPlaylists().single().pinned)
  }

  private fun item(id: String, title: String) = MediaItemEntity(
    id = id,
    source = "youtube-music",
    sourceKey = "abcdefghijk",
    mediaType = "audio",
    title = title,
    artist = null,
    durationMs = null,
    width = null,
    height = null,
    thumbnailRemoteUrl = null,
    thumbnailLocalPath = null,
    playbackKind = null,
    playbackValue = null,
    availability = "waiting_for_resolver",
    downloadProgress = 0.0,
    collectionName = "Test collection",
    createdAt = 1L,
    updatedAt = 1L,
  )
}
