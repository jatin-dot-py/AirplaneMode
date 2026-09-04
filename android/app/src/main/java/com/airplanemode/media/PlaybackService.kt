package com.airplanemode.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import androidx.core.content.ContextCompat
import com.airplanemode.MainActivity
import com.airplanemode.media.data.MediaRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
  private lateinit var player: ExoPlayer
  private lateinit var session: MediaLibrarySession
  private val repository by lazy { MediaRepository.get(this) }
  private val libraryExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

  private val libraryCallback = object : MediaLibrarySession.Callback {
    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
      val root = MediaItem.Builder()
        .setMediaId(LIBRARY_ROOT_ID)
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle("AirplaneMode Library")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build(),
        )
        .build()
      return com.google.common.util.concurrent.Futures.immediateFuture(
        LibraryResult.ofItem(root, params),
      )
    }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
      if (parentId != LIBRARY_ROOT_ID) return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      val start = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(1))
      val pageItems = repository.playableItems()
        .drop(start)
        .take(pageSize.coerceAtLeast(1))
        .map(repository::toMedia3)
      LibraryResult.ofItemList(pageItems, params)
    }

    override fun onGetItem(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = libraryExecutor.submit<LibraryResult<MediaItem>> {
      val item = repository.byId(mediaId)
      if (item?.playbackValue == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      else LibraryResult.ofItem(repository.toMedia3(item), null)
    }
  }

  override fun onCreate() {
    super.onCreate()
    player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(DefaultMediaSourceFactory(DownloadStore.playbackFactory(this)))
      .build()
      .apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build(),
          true,
        )
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
          override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = persistPosition()
          override fun onIsPlayingChanged(isPlaying: Boolean) = persistPosition()
          override fun onPlaybackStateChanged(playbackState: Int) = persistPosition()
          override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = persistPosition()
        })
      }
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val launchPendingIntent = PendingIntent.getActivity(
      this,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    session = MediaLibrarySession.Builder(this, player, libraryCallback)
      .setSessionActivity(launchPendingIntent)
      .build()
    restoreQueue()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

  override fun onDestroy() {
    persistPosition()
    session.release()
    player.release()
    libraryExecutor.shutdown()
    super.onDestroy()
  }

  private fun restoreQueue() {
    libraryExecutor.execute {
      val playableItems = repository.playableItems()
      if (playableItems.isEmpty()) return@execute
      val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
      val savedId = preferences.getString("mediaId", null)
      val savedQueueIds = preferences.getString("queueIds", null)
        ?.split(',')
        ?.filter(String::isNotBlank)
        .orEmpty()
      val playableById = playableItems.associateBy { it.id }
      val savedQueue = savedQueueIds.mapNotNull(playableById::get).distinctBy { it.id }
      val fallbackId = savedId ?: playableItems.first().id
      val queue = savedQueue.takeIf { values -> values.any { it.id == fallbackId } }
        ?: PlaybackQueueResolver.resolve(fallbackId, playableItems)
      val items = queue.map(repository::toMedia3)
      if (items.isEmpty()) return@execute
      val index = items.indexOfFirst { it.mediaId == savedId }.coerceAtLeast(0)
      val position = preferences.getLong("positionMs", 0L)
      val speed = preferences.getFloat("playbackSpeed", 1.0f).coerceIn(0.5f, 2.0f)
      ContextCompat.getMainExecutor(this).execute {
        if (player.mediaItemCount == 0) {
          player.setMediaItems(items, index, position)
          player.setPlaybackSpeed(speed)
          player.prepare()
        }
      }
    }
  }

  private fun persistPosition() {
    if (!::player.isInitialized) return
    val queueIds = (0 until player.mediaItemCount)
      .joinToString(",") { index -> player.getMediaItemAt(index).mediaId }
    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
      .putString("mediaId", player.currentMediaItem?.mediaId)
      .putString("queueIds", queueIds)
      .putLong("positionMs", player.currentPosition.coerceAtLeast(0L))
      .putFloat("playbackSpeed", player.playbackParameters.speed)
      .apply()
  }

  companion object {
    private const val PREFS = "media-playback-state"
    private const val LIBRARY_ROOT_ID = "airplanemode-library-root"
  }
}
