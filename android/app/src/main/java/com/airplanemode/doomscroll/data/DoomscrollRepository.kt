package com.airplanemode.doomscroll.data

import android.content.Context
import android.os.StatFs
import androidx.work.WorkManager
import com.airplanemode.doomscroll.DoomscrollDownloadQueue
import com.airplanemode.doomscroll.ReelUrlPolicy
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class DoomscrollRepository private constructor(context: Context) {
  private val appContext = context.applicationContext
  private val database = DoomscrollDatabase.get(appContext)
  private val dao = database.doomscrollDao()

  fun beginSession(
    name: String = "",
    qualityPolicy: String = DEFAULT_QUALITY_POLICY,
  ): String = createSnapshot(name, qualityPolicy)

  fun createSnapshot(name: String, qualityPolicy: String): String {
    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    dao.putSession(
      CaptureSessionEntity(
        id = id,
        startedAt = now,
        finishedAt = null,
        state = "capturing",
        stopReason = null,
        pagesCaptured = 0,
        uniqueReels = 0,
        name = name.trim().take(MAX_NAME_LENGTH).ifBlank { "Reels snapshot" },
        qualityPolicy = normalizedQualityPolicy(qualityPolicy),
        updatedAt = now,
      ),
    )
    dao.putSnapshotProgress(
      SnapshotProgressEntity(
        snapshotId = id,
        resumePosition = 0,
        currentPosition = 0,
        currentMediaPk = null,
        currentPlaybackMs = 0L,
        updatedAt = now,
      ),
    )
    return id
  }

  fun finishSession(sessionId: String, reason: String) {
    val existing = dao.sessionById(sessionId) ?: return
    val now = System.currentTimeMillis()
    dao.putSession(
      existing.copy(
        finishedAt = now,
        state = if (reason == "complete") "complete" else "stopped",
        stopReason = reason.take(MAX_REASON_LENGTH),
        updatedAt = now,
      ),
    )
  }

  fun saveBatch(
    sessionId: String,
    pageIndex: Int,
    records: List<CapturedReelRecord>,
  ): CaptureSaveResult {
    val now = System.currentTimeMillis()
    var session = dao.sessionById(sessionId)
      ?: throw IllegalStateException("The capture snapshot no longer exists.")
    var added = 0
    var updated = 0
    var nextPosition = dao.nextSnapshotPosition(sessionId)
    val toQueue = linkedMapOf<String, Boolean>()
    var pendingReservation = dao.pendingDownloadBytes()
    var storageBlocked = !hasDownloadCapacity(pendingReservation)
    val policy = normalizedQualityPolicy(session.qualityPolicy)

    database.runInTransaction {
      records.take(MAX_BATCH_SIZE).forEach { record ->
        val existingReel = dao.reelByPk(record.mediaPk)
        val existingMembership = dao.snapshotReel(sessionId, record.mediaPk)
        val position = existingMembership?.position ?: nextPosition++
        if (existingMembership == null) {
          added++
          dao.putSnapshotReel(
            SnapshotReelEntity(
              snapshotId = sessionId,
              mediaPk = record.mediaPk,
              position = position,
              capturedAt = now,
              snapshotMetadataJson = record.toSnapshotMetadataJson(),
              qualifiedWatchedAt = null,
              activePlaybackMs = 0L,
              lastPlaybackPositionMs = 0L,
              firstViewedAt = null,
              lastViewedAt = null,
            ),
          )
        } else {
          updated++
        }

        dao.putReel(record.toReelEntity(
          sessionId = sessionId,
          sessionStartedAt = session.startedAt,
          position = position,
          firstCapturedAt = existingReel?.firstCapturedAt ?: now,
          capturedAt = now,
        ))

        val selectedVideoCandidates = selectCandidates(record.videoCandidates, policy)
        val selectedCoverCandidates = selectCandidates(record.coverCandidates, policy)
        val freshVideoJson = ReelUrlPolicy.toJson(selectedVideoCandidates)
        val freshCoverJson = ReelUrlPolicy.toJson(selectedCoverCandidates)
        val existingDownload = dao.downloadByPk(record.mediaPk)
        val filesReady = existingDownload?.state == "ready" &&
          existingDownload.videoLocalPath?.let(::File)?.isFile == true
        val downloadAlreadyActive = existingDownload?.state in setOf("queued", "downloading") &&
          !existingDownload?.workId.isNullOrBlank()
        val usableVideoJson = when {
          selectedVideoCandidates.isNotEmpty() -> freshVideoJson
          !existingDownload?.videoCandidatesJson.isNullOrBlank() -> existingDownload!!.videoCandidatesJson
          else -> "[]"
        }
        val usableCoverJson = when {
          selectedCoverCandidates.isNotEmpty() -> freshCoverJson
          !existingDownload?.coverCandidatesJson.isNullOrBlank() -> existingDownload!!.coverCandidatesJson
          else -> "[]"
        }
        val hasVideoCandidate = ReelUrlPolicy.fromJson(usableVideoJson).isNotEmpty()
        val firstCandidate = selectedVideoCandidates.firstOrNull()
        val estimatedBytes = if (filesReady) {
          existingDownload?.localBytes ?: 0L
        } else {
          estimateVideoBytes(record.durationMs, firstCandidate?.width)
        }
        val needsReservation = !filesReady && !downloadAlreadyActive && hasVideoCandidate
        val hasCapacityForRecord = !needsReservation ||
          (!storageBlocked && hasDownloadCapacity(pendingReservation + estimatedBytes))
        if (needsReservation && !hasCapacityForRecord) storageBlocked = true
        val nextState = when {
          filesReady -> "ready"
          downloadAlreadyActive -> existingDownload!!.state
          !hasVideoCandidate -> "failed"
          !hasCapacityForRecord -> "paused_low_storage"
          else -> "queued"
        }
        val shouldQueue = nextState == "queued" && !downloadAlreadyActive
        if (shouldQueue) pendingReservation += estimatedBytes
        dao.putDownload(
          ReelDownloadEntity(
            mediaPk = record.mediaPk,
            state = nextState,
            progress = when {
              filesReady -> 1.0
              downloadAlreadyActive -> existingDownload!!.progress
              else -> 0.0
            },
            videoCandidatesJson = if (filesReady) "[]" else usableVideoJson,
            coverCandidatesJson = if (filesReady) "[]" else usableCoverJson,
            profilePicRemoteUrl = if (filesReady) null else record.authorProfilePicUrl
              ?.takeIf(ReelUrlPolicy::isAllowedHttpsUrl),
            videoLocalPath = existingDownload?.videoLocalPath,
            coverLocalPath = existingDownload?.coverLocalPath,
            profilePicLocalPath = existingDownload?.profilePicLocalPath,
            localBytes = existingDownload?.localBytes ?: 0L,
            errorCode = when {
              downloadAlreadyActive -> existingDownload!!.errorCode
              !hasVideoCandidate -> "no_progressive_video"
              !hasCapacityForRecord -> "low_storage"
              else -> null
            },
            errorDetail = when {
              downloadAlreadyActive -> existingDownload!!.errorDetail
              !hasVideoCandidate -> "Instagram did not provide a progressive MP4 for this Reel."
              !hasCapacityForRecord -> "At least 1 GiB of free app storage is reserved."
              else -> null
            },
            attemptCount = existingDownload?.attemptCount ?: 0,
            workId = existingDownload?.workId,
            createdAt = existingDownload?.createdAt ?: now,
            updatedAt = now,
            qualityPolicy = if (filesReady) existingDownload?.qualityPolicy ?: policy else policy,
            selectedWidth = if (filesReady) existingDownload?.selectedWidth else firstCandidate?.width,
            selectedHeight = if (filesReady) existingDownload?.selectedHeight else firstCandidate?.height,
            estimatedBytes = estimatedBytes,
          ),
        )
        if (shouldQueue) {
          toQueue[record.mediaPk] = existingDownload?.state in
            setOf("failed", "paused_low_storage", "missing")
        }
      }
      session = session.copy(
        finishedAt = null,
        state = "capturing",
        stopReason = null,
        pagesCaptured = session.pagesCaptured + 1,
        uniqueReels = dao.snapshotReelCount(sessionId),
        updatedAt = now,
      )
      dao.putSession(session)
    }

    toQueue.forEach { (mediaPk, replace) -> queueDownload(mediaPk, replace) }
    val canContinue = !storageBlocked
    return CaptureSaveResult(
      added = added,
      updated = updated,
      persisted = records.take(MAX_BATCH_SIZE).size,
      stats = snapshotStats(sessionId),
      canContinue = canContinue,
      stopReason = if (canContinue) null else "low-storage",
    )
  }

  fun snapshots(): List<SnapshotSummaryRecord> {
    dao.recoverInterruptedSessions(System.currentTimeMillis())
    return dao.allSessions().map(::snapshotSummary)
  }

  fun snapshotSummary(snapshotId: String): SnapshotSummaryRecord = snapshotSummary(
    dao.sessionById(snapshotId) ?: throw IllegalArgumentException("Snapshot not found."),
  )

  private fun snapshotSummary(session: CaptureSessionEntity): SnapshotSummaryRecord {
    val memberships = dao.snapshotReels(session.id)
    val downloads = dao.downloadsForSnapshot(session.id)
    val progress = dao.snapshotProgress(session.id)
    val logicalBytes = downloads.sumOf(ReelDownloadEntity::localBytes)
    val reclaimableBytes = memberships.sumOf { member ->
      if (dao.snapshotReferenceCount(member.mediaPk) <= 1) {
        dao.downloadByPk(member.mediaPk)?.localBytes ?: 0L
      } else {
        0L
      }
    }
    val estimatedBytes = downloads.sumOf { download ->
      if (download.state == "ready") download.localBytes else download.estimatedBytes
    }
    return SnapshotSummaryRecord(
      snapshot = session.copy(uniqueReels = memberships.size),
      stats = statsFromDownloads(memberships.size, downloads),
      watchedCount = dao.watchedCount(session.id),
      resumePosition = progress?.resumePosition ?: 0,
      currentPosition = progress?.currentPosition ?: 0,
      logicalBytes = logicalBytes,
      reclaimableBytes = reclaimableBytes,
      estimatedBytes = estimatedBytes,
      previewCoverPaths = downloads.asSequence()
        .mapNotNull(ReelDownloadEntity::coverLocalPath)
        .filter { File(it).isFile }
        .distinct()
        .take(3)
        .toList(),
    )
  }

  fun snapshotReels(snapshotId: String): List<StoredSnapshotReelRecord> =
    dao.snapshotReels(snapshotId).mapNotNull { membership ->
      val reel = dao.reelByPk(membership.mediaPk) ?: return@mapNotNull null
      val download = dao.downloadByPk(membership.mediaPk) ?: missingDownload(membership.mediaPk)
      StoredSnapshotReelRecord(membership, reel, download)
    }

  fun recordPlayback(
    snapshotId: String,
    mediaPk: String,
    position: Int,
    playbackPositionMs: Long,
    durationMs: Long,
    activeDeltaMs: Long,
  ): PlaybackSaveResult {
    var result: PlaybackSaveResult? = null
    database.runInTransaction {
      val member = dao.snapshotReel(snapshotId, mediaPk)
        ?: throw IllegalArgumentException("Reel is not part of this snapshot.")
      if (member.position != position) throw IllegalArgumentException("Snapshot position mismatch.")
      val now = System.currentTimeMillis()
      val boundedDuration = durationMs.takeIf { it in 1..MAX_DURATION_MS }
        ?: dao.reelByPk(mediaPk)?.durationMs
        ?: DEFAULT_UNKNOWN_DURATION_MS
      val boundedPosition = playbackPositionMs.coerceIn(0L, boundedDuration)
      val nextActive = (member.activePlaybackMs + activeDeltaMs.coerceIn(0L, MAX_PROGRESS_DELTA_MS))
        .coerceAtMost(MAX_DURATION_MS)
      val threshold = minOf(
        WATCH_MAXIMUM_MS,
        maxOf(WATCH_MINIMUM_MS, boundedDuration / 2L),
      )
      val qualifiedAt = member.qualifiedWatchedAt
        ?: now.takeIf { nextActive >= threshold }
      val updatedMember = member.copy(
        qualifiedWatchedAt = qualifiedAt,
        activePlaybackMs = nextActive,
        lastPlaybackPositionMs = boundedPosition,
        firstViewedAt = member.firstViewedAt ?: now,
        lastViewedAt = now,
      )
      dao.putSnapshotReel(updatedMember)

      val current = dao.snapshotProgress(snapshotId) ?: SnapshotProgressEntity(
        snapshotId = snapshotId,
        resumePosition = 0,
        currentPosition = 0,
        currentMediaPk = null,
        currentPlaybackMs = 0L,
        updatedAt = now,
      )
      val nextResume = if (qualifiedAt != null) {
        maxOf(current.resumePosition, member.position + 1)
      } else {
        current.resumePosition
      }
      val canAdvanceCurrent = member.position >= current.currentPosition
      dao.putSnapshotProgress(
        current.copy(
          resumePosition = nextResume,
          currentPosition = if (canAdvanceCurrent) member.position else current.currentPosition,
          currentMediaPk = if (canAdvanceCurrent) mediaPk else current.currentMediaPk,
          currentPlaybackMs = if (canAdvanceCurrent) boundedPosition else current.currentPlaybackMs,
          updatedAt = now,
        ),
      )
      dao.sessionById(snapshotId)?.let { dao.putSession(it.copy(updatedAt = now)) }
      result = PlaybackSaveResult(
        qualified = qualifiedAt != null,
        resumePosition = nextResume,
        activePlaybackMs = nextActive,
      )
    }
    return requireNotNull(result)
  }

  fun reel(mediaPk: String): ReelEntity? = dao.reelByPk(mediaPk)

  fun download(mediaPk: String): ReelDownloadEntity? = dao.downloadByPk(mediaPk)

  fun updateDownload(download: ReelDownloadEntity) = dao.putDownload(download)

  fun hasSnapshotReferences(mediaPk: String): Boolean = dao.snapshotReferenceCount(mediaPk) > 0

  fun stats(): DoomscrollStatsRecord = DoomscrollStatsRecord(
    capturedCount = dao.reelCount(),
    readyCount = dao.downloadCount("ready"),
    queuedCount = dao.downloadCount("queued"),
    downloadingCount = dao.downloadCount("downloading"),
    failedCount = dao.downloadCount("failed"),
    lowStorageCount = dao.downloadCount("paused_low_storage"),
    downloadedBytes = dao.downloadedBytes(),
  )

  fun snapshotStats(snapshotId: String): DoomscrollStatsRecord {
    val count = dao.snapshotReelCount(snapshotId)
    return statsFromDownloads(count, dao.downloadsForSnapshot(snapshotId))
  }

  private fun statsFromDownloads(
    capturedCount: Int,
    downloads: List<ReelDownloadEntity>,
  ) = DoomscrollStatsRecord(
    capturedCount = capturedCount,
    readyCount = downloads.count { it.state == "ready" },
    queuedCount = downloads.count { it.state == "queued" },
    downloadingCount = downloads.count { it.state == "downloading" },
    failedCount = downloads.count { it.state == "failed" || it.state == "missing" },
    lowStorageCount = downloads.count { it.state == "paused_low_storage" },
    downloadedBytes = downloads.sumOf(ReelDownloadEntity::localBytes),
  )

  fun retry(mediaPk: String): Boolean {
    val download = dao.downloadByPk(mediaPk) ?: return false
    if (!hasSnapshotReferences(mediaPk) || download.videoCandidatesJson == "[]") return false
    if (!hasDownloadCapacity()) {
      dao.putDownload(
        download.copy(
          state = "paused_low_storage",
          errorCode = "low_storage",
          errorDetail = "At least 1 GiB of free app storage is reserved.",
          updatedAt = System.currentTimeMillis(),
        ),
      )
      return false
    }
    dao.putDownload(
      download.copy(
        state = "queued",
        progress = 0.0,
        errorCode = null,
        errorDetail = null,
        updatedAt = System.currentTimeMillis(),
      ),
    )
    queueDownload(mediaPk, replace = true)
    return true
  }

  fun deleteSnapshot(snapshotId: String): SnapshotDeleteResult {
    if (dao.sessionById(snapshotId) == null) return SnapshotDeleteResult(false, 0L)
    val mediaPks = dao.snapshotMediaPks(snapshotId)
    val exclusivePks = mediaPks.filter { dao.snapshotReferenceCount(it) <= 1 }
    exclusivePks.forEach { DoomscrollDownloadQueue.cancel(appContext, it) }
    val downloadsToDelete = exclusivePks.mapNotNull(dao::downloadByPk)
    val reclaimed = downloadsToDelete.sumOf(ReelDownloadEntity::localBytes)
    val profilePaths = downloadsToDelete.mapNotNull(ReelDownloadEntity::profilePicLocalPath).distinct()
    database.runInTransaction {
      dao.deleteSnapshotProgress(snapshotId)
      dao.deleteSnapshotReels(snapshotId)
      dao.deleteSession(snapshotId)
      exclusivePks.forEach { mediaPk ->
        dao.deleteDownload(mediaPk)
        dao.deleteReel(mediaPk)
      }
    }
    exclusivePks.forEach(::deleteOwnedReelDirectory)
    profilePaths.forEach { path ->
      if (dao.profilePathReferenceCount(path) == 0) deleteOwnedFile(path)
    }
    return SnapshotDeleteResult(true, reclaimed)
  }

  fun clearAll() {
    try {
      WorkManager.getInstance(appContext)
        .cancelAllWorkByTag(DoomscrollDownloadQueue.TAG_QUEUE)
        .result
        .get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (_: Exception) {
      // Work IDs prevent a canceled or superseded worker from committing late.
    }
    database.runInTransaction {
      dao.clearSnapshotProgress()
      dao.clearSnapshotReels()
      dao.clearDownloads()
      dao.clearReels()
      dao.clearSessions()
    }
    runCatching { database.openHelper.writableDatabase.execSQL("VACUUM") }
    val root = storageRoot()
    if (isOwnedPath(root)) root.deleteRecursively()
  }

  fun storageRoot(): File = File(appContext.filesDir, "doomscroll")

  fun hasDownloadCapacity(requiredBytes: Long = 0L): Boolean {
    val stats = StatFs(appContext.filesDir.absolutePath)
    return stats.availableBytes - requiredBytes >= FREE_SPACE_RESERVE_BYTES
  }

  private fun queueDownload(mediaPk: String, replace: Boolean) {
    DoomscrollDownloadQueue.enqueue(appContext, mediaPk, replace) { workId ->
      dao.downloadByPk(mediaPk)?.let { download ->
        dao.putDownload(
          download.copy(workId = workId.toString(), updatedAt = System.currentTimeMillis()),
        )
      }
    }
  }

  private fun deleteOwnedReelDirectory(mediaPk: String) {
    val directory = File(storageRoot(), "reels/${safeName(mediaPk)}")
    if (isOwnedPath(directory)) directory.deleteRecursively()
  }

  private fun deleteOwnedFile(path: String) {
    val file = File(path)
    if (isOwnedPath(file)) file.delete()
  }

  private fun isOwnedPath(file: File): Boolean = try {
    val root = storageRoot().canonicalFile
    val candidate = file.canonicalFile
    candidate == root || candidate.path.startsWith(root.path + File.separator)
  } catch (_: Exception) {
    false
  }

  private fun safeName(value: String): String = value
    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    .take(160)
    .ifBlank { "unknown" }

  private fun missingDownload(mediaPk: String) = ReelDownloadEntity(
    mediaPk = mediaPk,
    state = "missing",
    progress = 0.0,
    videoCandidatesJson = "[]",
    coverCandidatesJson = "[]",
    profilePicRemoteUrl = null,
    videoLocalPath = null,
    coverLocalPath = null,
    profilePicLocalPath = null,
    localBytes = 0L,
    errorCode = "missing",
    errorDetail = "This Reel needs to be captured again before it can be downloaded.",
    attemptCount = 0,
    workId = null,
    createdAt = 0L,
    updatedAt = 0L,
  )

  companion object {
    const val FREE_SPACE_RESERVE_BYTES = 1L shl 30
    const val DEFAULT_QUALITY_POLICY = "smart_hq"
    const val WATCH_MINIMUM_MS = 3_000L
    const val WATCH_MAXIMUM_MS = 10_000L
    private const val DEFAULT_UNKNOWN_DURATION_MS = 20_000L
    private const val MAX_PROGRESS_DELTA_MS = 5_000L
    private const val MAX_DURATION_MS = 3_600_000L
    private const val MAX_BATCH_SIZE = 25
    private const val MAX_NAME_LENGTH = 120
    private const val MAX_REASON_LENGTH = 300
    private const val CANCEL_WAIT_SECONDS = 10L
    private val QUALITY_POLICIES = setOf(
      "smart_hq",
      "efficient_hq",
      "original",
      "compact",
    )

    @Volatile private var instance: DoomscrollRepository? = null

    fun get(context: Context): DoomscrollRepository = instance ?: synchronized(this) {
      instance ?: DoomscrollRepository(context).also { instance = it }
    }

    private fun normalizedQualityPolicy(value: String): String =
      value.takeIf(QUALITY_POLICIES::contains) ?: DEFAULT_QUALITY_POLICY

    internal fun selectCandidates(
      candidates: List<RemoteMediaCandidate>,
      policy: String,
    ): List<RemoteMediaCandidate> {
      val safe = candidates.asSequence()
        .filter { ReelUrlPolicy.isAllowedHttpsUrl(it.url) }
        .distinctBy(RemoteMediaCandidate::url)
        .toList()
      val targetWidth = when (policy) {
        "compact" -> 480
        "original" -> Int.MAX_VALUE
        else -> 720
      }
      val ordered = if (policy == "original") {
        safe.sortedByDescending(::candidateArea)
      } else {
        val atOrAbove = safe.filter { it.width >= targetWidth }
          .sortedWith(compareBy<RemoteMediaCandidate>(::candidateArea).thenBy { it.width })
        val below = safe.filter { it.width < targetWidth }.sortedByDescending(::candidateArea)
        atOrAbove + below
      }
      return ordered.take(ReelUrlPolicy.MAX_CANDIDATES)
    }

    private fun candidateArea(candidate: RemoteMediaCandidate): Long =
      candidate.width.toLong() * candidate.height.toLong()

    private fun estimateVideoBytes(durationMs: Long?, width: Int?): Long {
      val durationSeconds = ((durationMs ?: DEFAULT_UNKNOWN_DURATION_MS).coerceAtLeast(1_000L) / 1_000.0)
      val bitsPerSecond = when ((width ?: 720).coerceAtLeast(1)) {
        in 1..480 -> 650_000L
        in 481..720 -> 1_400_000L
        in 721..1080 -> 2_700_000L
        else -> 4_000_000L
      }
      return (durationSeconds * bitsPerSecond / 8.0).toLong() + 220_000L
    }
  }
}

private fun CapturedReelRecord.toReelEntity(
  sessionId: String,
  sessionStartedAt: Long,
  position: Int,
  firstCapturedAt: Long,
  capturedAt: Long,
) = ReelEntity(
  mediaPk = mediaPk,
  mediaId = mediaId,
  code = code,
  permalink = permalink,
  authorId = authorId,
  authorUsername = authorUsername,
  authorFullName = authorFullName,
  authorIsVerified = authorIsVerified,
  authorIsPrivate = authorIsPrivate,
  caption = caption,
  takenAt = takenAt,
  mediaType = mediaType,
  productType = productType,
  inventorySource = inventorySource,
  originalWidth = originalWidth,
  originalHeight = originalHeight,
  durationMs = durationMs,
  likeCount = likeCount,
  commentCount = commentCount,
  repostCount = repostCount,
  viewCount = viewCount,
  fbLikeCount = fbLikeCount,
  fbCommentCount = fbCommentCount,
  hasLiked = hasLiked,
  hasViewerSaved = hasViewerSaved,
  canViewerReshare = canViewerReshare,
  hasAudio = hasAudio,
  audioAssetId = audioAssetId,
  audioTitle = audioTitle,
  audioArtistId = audioArtistId,
  audioArtistUsername = audioArtistUsername,
  audioIsExplicit = audioIsExplicit,
  usertagsJson = usertagsJson,
  coauthorsJson = coauthorsJson,
  locationJson = locationJson,
  safeMetadataJson = safeMetadataJson,
  latestSessionId = sessionId,
  latestSessionStartedAt = sessionStartedAt,
  latestPosition = position,
  firstCapturedAt = firstCapturedAt,
  lastCapturedAt = capturedAt,
)

private fun CapturedReelRecord.toSnapshotMetadataJson(): String = JSONObject().apply {
  put("mediaId", mediaId)
  put("code", code)
  put("permalink", permalink)
  put("authorId", authorId)
  put("authorUsername", authorUsername)
  putNullable("authorFullName", authorFullName)
  put("authorIsVerified", authorIsVerified)
  put("authorIsPrivate", authorIsPrivate)
  putNullable("caption", caption)
  putNullable("takenAt", takenAt)
  put("mediaType", mediaType)
  putNullable("productType", productType)
  putNullable("inventorySource", inventorySource)
  putNullable("originalWidth", originalWidth)
  putNullable("originalHeight", originalHeight)
  putNullable("durationMs", durationMs)
  putNullable("likeCount", likeCount)
  putNullable("commentCount", commentCount)
  putNullable("repostCount", repostCount)
  putNullable("viewCount", viewCount)
  putNullable("fbLikeCount", fbLikeCount)
  putNullable("fbCommentCount", fbCommentCount)
  put("hasLiked", hasLiked)
  put("hasViewerSaved", hasViewerSaved)
  put("canViewerReshare", canViewerReshare)
  put("hasAudio", hasAudio)
  putNullable("audioAssetId", audioAssetId)
  putNullable("audioTitle", audioTitle)
  putNullable("audioArtistId", audioArtistId)
  putNullable("audioArtistUsername", audioArtistUsername)
  put("audioIsExplicit", audioIsExplicit)
  put("usertagsJson", usertagsJson)
  put("coauthorsJson", coauthorsJson)
  putNullable("locationJson", locationJson)
  put("safeMetadataJson", safeMetadataJson)
}.toString()

private fun JSONObject.putNullable(key: String, value: Any?) {
  put(key, value ?: JSONObject.NULL)
}
