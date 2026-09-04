package com.airplanemode.doomscroll.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
  tableName = "doomscroll_capture_sessions",
  primaryKeys = ["id"],
  indices = [Index(value = ["startedAt"])],
)
data class CaptureSessionEntity(
  val id: String,
  val startedAt: Long,
  val finishedAt: Long?,
  val state: String,
  val stopReason: String?,
  val pagesCaptured: Int,
  val uniqueReels: Int,
  @ColumnInfo(defaultValue = "''") val name: String = "",
  @ColumnInfo(defaultValue = "'smart_hq'") val qualityPolicy: String = "smart_hq",
  @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

@Entity(
  tableName = "doomscroll_reels",
  primaryKeys = ["mediaPk"],
  indices = [
    Index(value = ["authorUsername"]),
    Index(value = ["latestSessionStartedAt", "latestPosition"]),
  ],
)
data class ReelEntity(
  val mediaPk: String,
  val mediaId: String,
  val code: String,
  val permalink: String,
  val authorId: String,
  val authorUsername: String,
  val authorFullName: String?,
  val authorIsVerified: Boolean,
  val authorIsPrivate: Boolean,
  val caption: String?,
  val takenAt: Long?,
  val mediaType: Int,
  val productType: String?,
  val inventorySource: String?,
  val originalWidth: Int?,
  val originalHeight: Int?,
  val durationMs: Long?,
  val likeCount: Long?,
  val commentCount: Long?,
  val repostCount: Long?,
  val viewCount: Long?,
  val fbLikeCount: Long?,
  val fbCommentCount: Long?,
  val hasLiked: Boolean,
  val hasViewerSaved: Boolean,
  val canViewerReshare: Boolean,
  val hasAudio: Boolean,
  val audioAssetId: String?,
  val audioTitle: String?,
  val audioArtistId: String?,
  val audioArtistUsername: String?,
  val audioIsExplicit: Boolean,
  val usertagsJson: String,
  val coauthorsJson: String,
  val locationJson: String?,
  val safeMetadataJson: String,
  // Retained only for a safe v1 migration. Snapshot ordering never reads these fields.
  val latestSessionId: String,
  val latestSessionStartedAt: Long,
  val latestPosition: Int,
  val firstCapturedAt: Long,
  val lastCapturedAt: Long,
)

@Entity(
  tableName = "doomscroll_snapshot_reels",
  primaryKeys = ["snapshotId", "mediaPk"],
  indices = [
    Index(value = ["snapshotId", "position"]),
    Index(value = ["mediaPk"]),
    Index(value = ["snapshotId", "qualifiedWatchedAt"]),
  ],
)
data class SnapshotReelEntity(
  val snapshotId: String,
  val mediaPk: String,
  val position: Int,
  val capturedAt: Long,
  val snapshotMetadataJson: String,
  val qualifiedWatchedAt: Long?,
  val activePlaybackMs: Long,
  val lastPlaybackPositionMs: Long,
  val firstViewedAt: Long?,
  val lastViewedAt: Long?,
)

@Entity(
  tableName = "doomscroll_snapshot_progress",
  primaryKeys = ["snapshotId"],
)
data class SnapshotProgressEntity(
  val snapshotId: String,
  val resumePosition: Int,
  val currentPosition: Int,
  val currentMediaPk: String?,
  val currentPlaybackMs: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "doomscroll_downloads",
  primaryKeys = ["mediaPk"],
  indices = [Index(value = ["state"]), Index(value = ["updatedAt"])],
)
data class ReelDownloadEntity(
  val mediaPk: String,
  val state: String,
  val progress: Double,
  val videoCandidatesJson: String,
  val coverCandidatesJson: String,
  val profilePicRemoteUrl: String?,
  val videoLocalPath: String?,
  val coverLocalPath: String?,
  val profilePicLocalPath: String?,
  val localBytes: Long,
  val errorCode: String?,
  val errorDetail: String?,
  val attemptCount: Int,
  val workId: String?,
  val createdAt: Long,
  val updatedAt: Long,
  @ColumnInfo(defaultValue = "'smart_hq'") val qualityPolicy: String = "smart_hq",
  @ColumnInfo(defaultValue = "NULL") val selectedWidth: Int? = null,
  @ColumnInfo(defaultValue = "NULL") val selectedHeight: Int? = null,
  @ColumnInfo(defaultValue = "0") val estimatedBytes: Long = 0L,
)
