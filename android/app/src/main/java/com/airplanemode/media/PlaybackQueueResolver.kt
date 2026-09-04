package com.airplanemode.media

import com.airplanemode.media.data.MediaItemEntity

object PlaybackQueueResolver {
  fun resolve(
    selectedId: String,
    playableItems: List<MediaItemEntity>,
    playlistItems: List<MediaItemEntity>? = null,
  ): List<MediaItemEntity> {
    val selected = playableItems.firstOrNull { it.id == selectedId } ?: return emptyList()
    val playableById = playableItems.associateBy { it.id }
    val candidates = playlistItems
      ?.mapNotNull { playableById[it.id] }
      ?: playableItems.filter { it.source == selected.source }
    val queue = candidates.distinctBy { it.id }
    return queue.takeIf { values -> values.any { it.id == selectedId } } ?: emptyList()
  }
}
