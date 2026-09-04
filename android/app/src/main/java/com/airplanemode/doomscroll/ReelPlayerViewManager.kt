package com.airplanemode.doomscroll

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.airplanemode.R
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event
import java.io.File

internal fun reelResizeMode(): Int = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

private class ReelPlayerEvent(
  surfaceId: Int,
  viewTag: Int,
  private val emittedName: String,
  private val payload: WritableMap,
  private val coalescable: Boolean,
) : Event<ReelPlayerEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = emittedName

  override fun canCoalesce(): Boolean = coalescable

  protected override fun getEventData(): WritableMap = payload
}

@androidx.annotation.OptIn(UnstableApi::class)
class ReelPlayerViewManager : SimpleViewManager<AirplaneReelPlayerView>() {
  override fun getName(): String = "AirplaneReelPlayerView"

  override fun createViewInstance(context: ThemedReactContext): AirplaneReelPlayerView =
    AirplaneReelPlayerView(context)

  @ReactProp(name = "sourcePath")
  fun setSourcePath(view: AirplaneReelPlayerView, value: String?) = view.setSourcePath(value)

  @ReactProp(name = "resumePositionMs", defaultDouble = 0.0)
  fun setResumePositionMs(view: AirplaneReelPlayerView, value: Double) =
    view.setResumePositionMs(value.toLong().coerceAtLeast(0L))

  @ReactProp(name = "paused", defaultBoolean = false)
  fun setPaused(view: AirplaneReelPlayerView, value: Boolean) = view.setPaused(value)

  @ReactProp(name = "muted", defaultBoolean = false)
  fun setMuted(view: AirplaneReelPlayerView, value: Boolean) = view.setMuted(value)

  @ReactProp(name = "playbackSpeed", defaultFloat = 1f)
  fun setPlaybackSpeed(view: AirplaneReelPlayerView, value: Float) = view.setPlaybackSpeed(value)

  @ReactProp(name = "visibilityQualified", defaultBoolean = false)
  fun setVisibilityQualified(view: AirplaneReelPlayerView, value: Boolean) =
    view.setVisibilityQualified(value)

  override fun onAfterUpdateTransaction(view: AirplaneReelPlayerView) {
    super.onAfterUpdateTransaction(view)
    view.commitSourceProps()
  }

  override fun onDropViewInstance(view: AirplaneReelPlayerView) {
    view.release()
    super.onDropViewInstance(view)
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
    MapBuilder.builder<String, Any>()
      .put("topFirstFrame", MapBuilder.of("registrationName", "onFirstFrame"))
      .put("topPlaybackError", MapBuilder.of("registrationName", "onPlaybackError"))
      .put("topPlaybackProgress", MapBuilder.of("registrationName", "onPlaybackProgress"))
      .build()
      .toMutableMap()
}

@androidx.annotation.OptIn(UnstableApi::class)
class AirplaneReelPlayerView(
  private val reactContext: ThemedReactContext,
) : FrameLayout(reactContext), LifecycleEventListener, Player.Listener {
  private val playerView = (
    LayoutInflater.from(reactContext)
      .inflate(R.layout.airplane_reel_player_view, this, false) as PlayerView
    ).apply {
    layoutParams = LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    useController = false
    isClickable = false
    isFocusable = false
    resizeMode = reelResizeMode()
    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    setShutterBackgroundColor(Color.TRANSPARENT)
    setKeepContentOnPlayerReset(false)
    setBackgroundColor(Color.TRANSPARENT)
  }
  private val player = ExoPlayer.Builder(reactContext)
    .setAudioAttributes(
      AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build(),
      true,
    )
    .build()
    .apply {
      repeatMode = Player.REPEAT_MODE_ONE
      addListener(this@AirplaneReelPlayerView)
    }
  private val progressHandler = Handler(Looper.getMainLooper())
  private val progressRunnable = object : Runnable {
    override fun run() {
      if (released) return
      emitProgress()
      progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
    }
  }
  private var sourcePath: String? = null
  private var pendingSourcePath: String? = null
  private var resumePositionMs = 0L
  private var appliedResumePositionMs = 0L
  private var paused = false
  private var muted = false
  private var playbackSpeed = 1f
  private var visibilityQualified = false
  private var hostResumed = true
  private var released = false
  private var sourceGeneration = 0
  private var playbackRetryCount = 0
  private var lastProgressTickMs = SystemClock.elapsedRealtime()

  init {
    setBackgroundColor(Color.TRANSPARENT)
    clipChildren = true
    clipToPadding = true
    addView(playerView)
    playerView.player = player
    reactContext.addLifecycleEventListener(this)
    progressHandler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
  }

  fun setSourcePath(value: String?) {
    pendingSourcePath = value?.takeIf(String::isNotBlank)
  }

  fun setResumePositionMs(value: Long) {
    resumePositionMs = value
  }

  fun commitSourceProps() {
    if (released) return
    val nextSource = pendingSourcePath
    if (nextSource != sourcePath) {
      sourceGeneration++
      playbackRetryCount = 0
      sourcePath = nextSource
      player.stop()
      player.clearMediaItems()
      val file = nextSource?.let(::File)
      if (file != null) {
        if (!isOwnedMediaFile(file)) {
          emitError("Offline Reel video is missing or outside app storage.")
        } else {
          player.setMediaItem(
            MediaItem.Builder()
              .setMediaId(nextSource)
              .setUri(Uri.fromFile(file))
              .build(),
            resumePositionMs,
          )
          appliedResumePositionMs = resumePositionMs
          player.prepare()
        }
      }
      lastProgressTickMs = SystemClock.elapsedRealtime()
    } else if (
      nextSource != null &&
      player.mediaItemCount > 0 &&
      !visibilityQualified &&
      appliedResumePositionMs != resumePositionMs
    ) {
      player.seekTo(resumePositionMs)
      appliedResumePositionMs = resumePositionMs
    }
    updatePlayState()
  }

  fun setPaused(value: Boolean) {
    paused = value
    updatePlayState()
  }

  fun setMuted(value: Boolean) {
    muted = value
    if (!released) player.volume = if (value) 0f else 1f
  }

  fun setPlaybackSpeed(value: Float) {
    playbackSpeed = value.takeIf { it in SUPPORTED_SPEEDS } ?: 1f
    if (!released) player.playbackParameters = PlaybackParameters(playbackSpeed)
  }

  fun setVisibilityQualified(value: Boolean) {
    visibilityQualified = value
    lastProgressTickMs = SystemClock.elapsedRealtime()
    updatePlayState()
  }

  private fun updatePlayState() {
    if (released) return
    val shouldPlay = !paused && visibilityQualified && hostResumed && player.mediaItemCount > 0
    keepScreenOn = shouldPlay
    player.playWhenReady = shouldPlay
    if (shouldPlay) player.play() else player.pause()
    lastProgressTickMs = SystemClock.elapsedRealtime()
  }

  override fun onPlayerError(error: PlaybackException) {
    keepScreenOn = false
    val failedSource = sourcePath
    val generation = sourceGeneration
    val file = failedSource?.let(::File)
    if (
      playbackRetryCount < MAX_PLAYBACK_RETRIES &&
      failedSource != null &&
      file != null &&
      isOwnedMediaFile(file)
    ) {
      playbackRetryCount++
      progressHandler.postDelayed(
        {
          if (
            !released &&
            generation == sourceGeneration &&
            failedSource == sourcePath
          ) {
            player.prepare()
            updatePlayState()
          }
        },
        PLAYBACK_RETRY_DELAY_MS,
      )
      return
    }
    emitError(error.message ?: "This offline Reel could not be played.")
  }

  override fun onVideoSizeChanged(videoSize: VideoSize) {
    if (released || videoSize == VideoSize.UNKNOWN) return
    playerView.requestLayout()
    requestLayout()
  }

  override fun onRenderedFirstFrame() {
    if (released) return
    playbackRetryCount = 0
    val generation = sourceGeneration
    val renderedSource = player.currentMediaItem?.mediaId.orEmpty()
    playerView.requestLayout()
    requestLayout()
    post {
      if (released || generation != sourceGeneration || renderedSource != sourcePath) return@post
      val payload = Arguments.createMap().apply {
        putString("sourcePath", renderedSource)
      }
      dispatchEvent("topFirstFrame", payload, coalescable = false)
    }
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    if (width != oldWidth || height != oldHeight) playerView.requestLayout()
  }

  override fun onHostResume() {
    hostResumed = true
    updatePlayState()
  }

  override fun onHostPause() {
    emitProgress()
    hostResumed = false
    updatePlayState()
  }

  override fun onHostDestroy() = release()

  fun release() {
    if (released) return
    emitProgress()
    keepScreenOn = false
    released = true
    progressHandler.removeCallbacks(progressRunnable)
    reactContext.removeLifecycleEventListener(this)
    playerView.player = null
    player.removeListener(this)
    player.release()
  }

  private fun emitProgress() {
    if (released || player.mediaItemCount == 0) return
    val now = SystemClock.elapsedRealtime()
    val elapsed = (now - lastProgressTickMs).coerceIn(0L, MAX_PROGRESS_DELTA_MS)
    val activelyWatching = player.isPlaying && visibilityQualified && hostResumed && !paused
    lastProgressTickMs = now
    val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
    val payload = Arguments.createMap().apply {
      putString("sourcePath", sourcePath.orEmpty())
      putDouble("playbackPositionMs", player.currentPosition.coerceAtLeast(0L).toDouble())
      putDouble("durationMs", duration.toDouble())
      putDouble("activeDeltaMs", if (activelyWatching) elapsed.toDouble() else 0.0)
      putBoolean("isPlaying", player.isPlaying)
      putBoolean("isBuffering", player.playbackState == Player.STATE_BUFFERING)
    }
    dispatchEvent("topPlaybackProgress", payload, coalescable = true)
  }

  private fun isOwnedMediaFile(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return try {
      val root = File(reactContext.filesDir, "doomscroll").canonicalFile
      file.canonicalFile.path.startsWith(root.path + File.separator)
    } catch (_: Exception) {
      false
    }
  }

  private fun emitError(message: String) {
    val payload = Arguments.createMap().apply {
      putString("sourcePath", sourcePath.orEmpty())
      putString("message", message.take(500))
    }
    dispatchEvent("topPlaybackError", payload, coalescable = false)
  }

  private fun dispatchEvent(
    eventName: String,
    payload: WritableMap,
    coalescable: Boolean,
  ) {
    if (released || id == NO_ID) return
    UIManagerHelper.getEventDispatcher(reactContext)?.dispatchEvent(
      ReelPlayerEvent(
        surfaceId = UIManagerHelper.getSurfaceId(this),
        viewTag = id,
        emittedName = eventName,
        payload = payload,
        coalescable = coalescable,
      ),
    )
  }

  companion object {
    private const val MAX_PROGRESS_DELTA_MS = 1_500L
    private const val MAX_PLAYBACK_RETRIES = 1
    private const val PLAYBACK_RETRY_DELAY_MS = 250L
    private const val PROGRESS_INTERVAL_MS = 750L
    private val SUPPORTED_SPEEDS = setOf(0.5f, 1f, 1.5f, 2f)
  }
}
