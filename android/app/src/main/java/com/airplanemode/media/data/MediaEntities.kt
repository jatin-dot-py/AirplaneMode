package com.airplanemode.media.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
  tableName = "media_items",
  primaryKeys = ["id"],
  indices = [Index(value = ["source", "sourceKey"], unique = true)],
)
data class MediaItemEntity(
  val id: String,
  val source: String,
  val sourceKey: String,
  val mediaType: String,
  val title: String,
  val artist: String?,
  val durationMs: Long?,
  val width: Int?,
  val height: Int?,
  val thumbnailRemoteUrl: String?,
  val thumbnailLocalPath: String?,
  val playbackKind: String?,
  val playbackValue: String?,
  val availability: String,
  val downloadProgress: Double,
  val collectionName: String?,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "collections",
  primaryKeys = ["id"],
  indices = [Index(value = ["source", "sourceKey"], unique = true)],
)
data class CollectionEntity(
  val id: String,
  val source: String,
  val sourceKey: String,
  val name: String,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "collection_memberships",
  primaryKeys = ["collectionId", "mediaItemId"],
)
data class CollectionMembershipEntity(
  val collectionId: String,
  val mediaItemId: String,
  val position: Int,
)

@Entity(
  tableName = "download_jobs",
  primaryKeys = ["id"],
  indices = [Index(value = ["mediaItemId"], unique = true)],
)
data class DownloadJobEntity(
  val id: String,
  val mediaItemId: String,
  val resolver: String,
  val sourceUrl: String?,
  val status: String,
  val progress: Double,
  val resolvedUri: String?,
  val downloadId: String?,
  val workId: String?,
  val attemptCount: Int,
  val outputOwnership: String?,
  val error: String?,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "local_playlists",
  primaryKeys = ["id"],
  indices = [Index(value = ["name"])],
)
data class LocalPlaylistEntity(
  val id: String,
  val name: String,
  val pinned: Boolean,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "local_playlist_items",
  primaryKeys = ["playlistId", "mediaItemId"],
  indices = [Index(value = ["mediaItemId"])],
)
data class LocalPlaylistItemEntity(
  val playlistId: String,
  val mediaItemId: String,
  val position: Int,
  val addedAt: Long,
)

data class LocalPlaylistSummary(
  val id: String,
  val name: String,
  val pinned: Boolean,
  val createdAt: Long,
  val updatedAt: Long,
  val itemCount: Int,
)
