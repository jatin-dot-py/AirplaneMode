package com.airplanemode.media

import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.google.common.util.concurrent.ListenableFuture
import java.util.WeakHashMap

/**
 * MediaSession controllers all target the same ExoPlayer. If two PlayerViews overlap during an
 * Activity transition, the view which detaches last can clear the surface owned by the new view.
 * Keep surface ownership explicit so the inline React view can never steal output from the
 * fullscreen/PiP Activity.
 */
internal object VideoSurfaceCoordinator {
  private val inlineViews = WeakHashMap<PlayerView, Int>()
  private var generation = 0
  private var externalOwnerActive = false

  @Synchronized
  fun registerInline(view: PlayerView): Int = generation.also { inlineViews[view] = it }

  @Synchronized
  fun unregisterInline(view: PlayerView) {
    inlineViews.remove(view)
  }

  @Synchronized
  fun canAttachInline(view: PlayerView, viewGeneration: Int): Boolean =
    !externalOwnerActive && inlineViews[view] == viewGeneration && viewGeneration == generation

  @Synchronized
  fun claimExternal() {
    if (!externalOwnerActive) generation++
    externalOwnerActive = true
    inlineViews.keys.toList().forEach { it.player = null }
  }

  @Synchronized
  fun releaseExternal() {
    externalOwnerActive = false
  }
}

@androidx.annotation.OptIn(UnstableApi::class)
class MediaPlayerViewManager : SimpleViewManager<PlayerView>() {
  private val controllers = WeakHashMap<PlayerView, ListenableFuture<MediaController>>()

  override fun getName(): String = "AirplaneMediaPlayerView"

  override fun createViewInstance(context: ThemedReactContext): PlayerView {
    val view = PlayerView(context).apply {
      // React owns the portrait controls. Media3 owns controls only in
      // VideoPlayerActivity, so two controller layers can never overlap.
      useController = false
      setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
      resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
      setShutterBackgroundColor(Color.BLACK)
      // Never leave a stale decoded frame visible while Media3 is changing surfaces.
      setKeepContentOnPlayerReset(false)
      setBackgroundColor(Color.BLACK)
      keepScreenOn = true
    }
    val surfaceGeneration = VideoSurfaceCoordinator.registerInline(view)
    val future = MediaController.Builder(
      context,
      SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()
    controllers[view] = future
    future.addListener(
      {
        try {
          val controller = future.get()
          if (VideoSurfaceCoordinator.canAttachInline(view, surfaceGeneration)) {
            view.player = controller
          }
        } catch (_: Exception) {}
      },
      ContextCompat.getMainExecutor(context),
    )
    return view
  }

  override fun onDropViewInstance(view: PlayerView) {
    view.player = null
    VideoSurfaceCoordinator.unregisterInline(view)
    controllers.remove(view)?.let(MediaController::releaseFuture)
    super.onDropViewInstance(view)
  }
}
