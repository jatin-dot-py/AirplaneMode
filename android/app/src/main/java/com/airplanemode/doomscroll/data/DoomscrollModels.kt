package com.airplanemode.doomscroll.data

data class RemoteMediaCandidate(
  val url: String,
  val width: Int,
  val height: Int,
)

data class CapturedReelRecord(
  val mediaPk: String,
  val mediaId: String,
  val code: String,
  val permalink: String,
  val authorId: String,
  val authorUsername: String,
  val authorFullName: String?,
  val authorIsVerified: Boolean,
  val authorIsPrivate: Boolean,
  val authorProfilePicUrl: String?,
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
  val videoCandidates: List<RemoteMediaCandidate>,
  val coverCandidates: List<RemoteMediaCandidate>,
)

data class DoomscrollStatsRecord(
  val capturedCount: Int,
  val readyCount: Int,
  val queuedCount: Int,
  val downloadingCount: Int,
  val failedCount: Int,
  val lowStorageCount: Int,
  val downloadedBytes: Long,
)

data class CaptureSaveResult(
  val added: Int,
  val updated: Int,
  val persisted: Int,
  val stats: DoomscrollStatsRecord,
  val canContinue: Boolean,
  val stopReason: String?,
)

data class SnapshotSummaryRecord(
  val snapshot: CaptureSessionEntity,
  val stats: DoomscrollStatsRecord,
  val watchedCount: Int,
  val resumePosition: Int,
  val currentPosition: Int,
  val logicalBytes: Long,
  val reclaimableBytes: Long,
  val estimatedBytes: Long,
  val previewCoverPaths: List<String>,
)

data class StoredSnapshotReelRecord(
  val snapshot: SnapshotReelEntity,
  val reel: ReelEntity,
  val download: ReelDownloadEntity,
)

data class PlaybackSaveResult(
  val qualified: Boolean,
  val resumePosition: Int,
  val activePlaybackMs: Long,
)

data class SnapshotDeleteResult(
  val deleted: Boolean,
  val reclaimedBytes: Long,
)
