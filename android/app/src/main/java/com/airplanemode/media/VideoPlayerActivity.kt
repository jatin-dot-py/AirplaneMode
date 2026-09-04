package com.airplanemode.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.airplanemode.R
import com.google.common.util.concurrent.ListenableFuture

/** Owns the single visible Media3 surface while video is fullscreen or in PiP. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerActivity : Activity() {
  private lateinit var playerView: PlayerView
  private lateinit var pipButton: ImageButton
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var controller: MediaController? = null
  private var enterPipWhenReady = false
  private var videoWidth = DEFAULT_VIDEO_WIDTH
  private var videoHeight = DEFAULT_VIDEO_HEIGHT

  private val playerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePictureInPictureParams()
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
      updatePictureInPictureParams()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    VideoSurfaceCoordinator.claimExternal()
    readLaunchIntent(intent)
    requestedOrientation = preferredOrientation(videoWidth, videoHeight)
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK

    playerView = PlayerView(this).apply {
      setBackgroundColor(Color.BLACK)
      setShutterBackgroundColor(Color.BLACK)
      setKeepContentOnPlayerReset(false)
      setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
      resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
      useController = true
      controllerAutoShow = true
      controllerHideOnTouch = true
      controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
      keepScreenOn = true
      setFullscreenButtonState(true)
      setFullscreenButtonClickListener { finish() }
    }

    pipButton = ImageButton(this).apply {
      contentDescription = getString(R.string.enter_picture_in_picture)
      setImageResource(R.drawable.ic_picture_in_picture)
      imageTintList = ContextCompat.getColorStateList(this@VideoPlayerActivity, android.R.color.white)
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(190, 0, 0, 0))
      }
      setPadding(dp(12), dp(12), dp(12), dp(12))
      visibility = if (supportsPictureInPicture()) View.VISIBLE else View.GONE
      setOnClickListener { enterPictureInPictureNow() }
    }

    val root = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      addView(
        playerView,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT,
        ),
      )
      addView(
        pipButton,
        FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
          topMargin = dp(16)
          marginEnd = dp(16)
        },
      )
    }
    setContentView(root)

    playerView.setControllerVisibilityListener(
      PlayerView.ControllerVisibilityListener { visibility ->
        pipButton.visibility = if (
          supportsPictureInPicture() &&
          !isInPictureInPictureMode &&
          visibility == View.VISIBLE
        ) View.VISIBLE else View.GONE
      },
    )
    playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      updatePictureInPictureParams()
    }

    applyFullscreenWindow()
    connectController()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    readLaunchIntent(intent)
    requestedOrientation = preferredOrientation(videoWidth, videoHeight)
    updatePictureInPictureParams()
    if (enterPipWhenReady) playerView.post(::enterPictureInPictureNow)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && !isInPictureInPictureMode) applyFullscreenWindow()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    playerView.post(::updatePictureInPictureParams)
    if (!isInPictureInPictureMode) applyFullscreenWindow()
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    if (isInPictureInPictureMode) {
      playerView.hideController()
      playerView.useController = false
      pipButton.visibility = View.GONE
    } else if (!isFinishing) {
      refreshVideoSurface()
      playerView.useController = true
      applyFullscreenWindow()
      playerView.showController()
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (
      Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
      controller?.isPlaying == true
    ) enterPictureInPictureNow()
  }

  override fun onDestroy() {
    controller?.removeListener(playerListener)
    playerView.player = null
    controllerFuture?.let(MediaController::releaseFuture)
    VideoSurfaceCoordinator.releaseExternal()
    sendBroadcast(Intent(ACTION_CLOSED).setPackage(packageName))
    super.onDestroy()
  }

  /** Rebind after the pinned SurfaceView is expanded so Media3 renders into the new surface. */
  private fun refreshVideoSurface() {
    val activeController = controller ?: return
    playerView.player = null
    playerView.postOnAnimation {
      if (!isDestroyed && !isFinishing && !isInPictureInPictureMode) {
        playerView.player = activeController
        playerView.requestLayout()
        playerView.invalidate()
      }
    }
  }

  private fun connectController() {
    val future = MediaController.Builder(
      this,
      SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java)),
    ).buildAsync()
    controllerFuture = future
    future.addListener(
      {
        try {
          if (!isDestroyed) {
            controller = future.get().also { mediaController ->
              mediaController.addListener(playerListener)
              playerView.player = mediaController
            }
            updatePictureInPictureParams()
            if (enterPipWhenReady) playerView.post(::enterPictureInPictureNow)
            else playerView.showController()
          }
        } catch (_: Exception) {
          finish()
        }
      },
      ContextCompat.getMainExecutor(this),
    )
  }

  private fun readLaunchIntent(intent: Intent) {
    enterPipWhenReady = intent.getBooleanExtra(EXTRA_ENTER_PIP, false)
    videoWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, DEFAULT_VIDEO_WIDTH).coerceAtLeast(1)
    videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, DEFAULT_VIDEO_HEIGHT).coerceAtLeast(1)
  }

  private fun enterPictureInPictureNow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return
    if (!supportsPictureInPicture()) {
      if (enterPipWhenReady) finish()
      return
    }
    enterPipWhenReady = false
    playerView.hideController()
    playerView.useController = false
    pipButton.visibility = View.GONE
    val entered = enterPictureInPictureMode(buildPictureInPictureParams())
    if (!entered) {
      playerView.useController = true
      playerView.showController()
    }
  }

  private fun updatePictureInPictureParams() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
    setPictureInPictureParams(buildPictureInPictureParams())
  }

  private fun buildPictureInPictureParams(): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
      .setAspectRatio(safePictureInPictureRatio(videoWidth, videoHeight))
    videoContentBounds()?.let(builder::setSourceRectHint)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setSeamlessResizeEnabled(true)
      builder.setAutoEnterEnabled(controller?.isPlaying == true)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      controller?.mediaMetadata?.let { metadata ->
        metadata.title?.let(builder::setTitle)
        metadata.artist?.let(builder::setSubtitle)
      }
    }
    return builder.build()
  }

  private fun videoContentBounds(): Rect? {
    if (!::playerView.isInitialized || playerView.width <= 0 || playerView.height <= 0) return null
    val viewWidth = playerView.width
    val viewHeight = playerView.height
    val videoAspect = videoWidth.toDouble() / videoHeight.toDouble()
    val viewAspect = viewWidth.toDouble() / viewHeight.toDouble()
    val contentWidth: Int
    val contentHeight: Int
    if (viewAspect > videoAspect) {
      contentHeight = viewHeight
      contentWidth = (contentHeight * videoAspect).toInt()
    } else {
      contentWidth = viewWidth
      contentHeight = (contentWidth / videoAspect).toInt()
    }
    val location = IntArray(2)
    playerView.getLocationInWindow(location)
    val left = location[0] + (viewWidth - contentWidth) / 2
    val top = location[1] + (viewHeight - contentHeight) / 2
    return Rect(left, top, left + contentWidth, top + contentHeight)
  }

  private fun applyFullscreenWindow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }
  }

  private fun supportsPictureInPicture(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  companion object {
    const val ACTION_CLOSED = "com.airplanemode.VIDEO_PLAYER_CLOSED"
    private const val EXTRA_ENTER_PIP = "enterPip"
    private const val EXTRA_VIDEO_WIDTH = "videoWidth"
    private const val EXTRA_VIDEO_HEIGHT = "videoHeight"
    private const val DEFAULT_VIDEO_WIDTH = 16
    private const val DEFAULT_VIDEO_HEIGHT = 9
    private const val CONTROLLER_TIMEOUT_MS = 3_500

    fun intent(
      context: Context,
      enterPictureInPicture: Boolean,
      width: Int,
      height: Int,
    ): Intent = Intent(context, VideoPlayerActivity::class.java)
      .putExtra(EXTRA_ENTER_PIP, enterPictureInPicture)
      .putExtra(EXTRA_VIDEO_WIDTH, width.coerceAtLeast(1))
      .putExtra(EXTRA_VIDEO_HEIGHT, height.coerceAtLeast(1))

    private fun preferredOrientation(width: Int, height: Int): Int = when {
      width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun safePictureInPictureRatio(width: Int, height: Int): Rational {
      val ratio = width.toDouble() / height.toDouble()
      return when {
        ratio > 2.38 -> Rational(238, 100)
        ratio < 0.43 -> Rational(43, 100)
        else -> Rational(width.coerceAtLeast(1), height.coerceAtLeast(1))
      }
    }
  }
}
