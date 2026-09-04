import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  ActivityIndicator,
  BackHandler,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type GestureResponderEvent,
  type LayoutChangeEvent,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';

import {
  NativeMediaPlayerView,
  emptyPlayback,
  enterPictureInPicture,
  getPlaybackState,
  onPlaybackStateChanged,
  onVideoPlayerClosed,
  seekTo,
  setPlaybackSpeed,
  setVideoFullscreen,
  skipNext,
  skipPrevious,
  togglePlayback,
  type PlaybackState,
} from '../../native/MediaEngine';
import {colors, layout, radii, spacing, typography} from '../../theme';
import MediaArtwork from './MediaArtwork';

const playbackSpeeds = [0.5, 0.75, 1, 1.25, 1.5, 2] as const;

const PlayerOverlayContext = createContext<{openPlayer: () => void}>({
  openPlayer: () => undefined,
});

export function PlayerOverlayProvider({children}: {children: React.ReactNode}) {
  const [open, setOpen] = useState(false);
  const [playback, setPlayback] = useState<PlaybackState>(emptyPlayback);

  useEffect(() => {
    getPlaybackState().then(setPlayback).catch(() => undefined);
    const subscription = onPlaybackStateChanged(setPlayback);
    return () => subscription?.remove();
  }, []);

  return (
    <PlayerOverlayContext.Provider value={{openPlayer: () => setOpen(true)}}>
      <View style={styles.overlayHost}>
        {children}
        {open ? <FullPlayer playback={playback} onClose={() => setOpen(false)} /> : null}
      </View>
    </PlayerOverlayContext.Provider>
  );
}

function MiniPlayer({playback}: {playback: PlaybackState}) {
  const {openPlayer} = useContext(PlayerOverlayContext);
  const hasMedia = Boolean(playback.mediaId);
  const progress = playback.durationMs > 0
    ? Math.min(playback.positionMs / playback.durationMs, 1)
    : 0;

  return (
    <>
      <View style={styles.miniContainer}>
        <View style={styles.miniProgressTrack}>
          <View style={[styles.progressFill, {width: `${progress * 100}%`}]} />
        </View>
        <View style={styles.miniRow}>
          <Pressable
            accessibilityHint="Open the full player"
            accessibilityLabel={playback.title || 'Nothing playing'}
            accessibilityRole="button"
            disabled={!hasMedia}
            onPress={openPlayer}
            style={({pressed}) => [styles.miniDetails, pressed && styles.pressed]}>
            <MediaArtwork
              path={playback.artworkPath}
              style={styles.miniArtwork}
            />
            <View style={styles.miniCopy}>
              <Text numberOfLines={1} style={styles.miniTitle}>
                {playback.title || 'Nothing playing'}
              </Text>
              <Text numberOfLines={1} style={styles.miniSubtitle}>
                {playback.artist || 'Your offline library'}
              </Text>
            </View>
          </Pressable>

          {hasMedia ? (
            <View style={styles.miniActions}>
              {playback.state === 'buffering' ? (
                <View style={styles.miniLoading}>
                  <ActivityIndicator color={colors.text} size="small" />
                </View>
              ) : (
                <TransportButton
                  accessibilityLabel={playback.isPlaying ? 'Pause' : 'Play'}
                  kind={playback.isPlaying ? 'pause' : 'play'}
                  onPress={() => run(togglePlayback())}
                  primary
                  size="mini"
                />
              )}
              <TransportButton
                accessibilityLabel="Next"
                disabled={!playback.hasNext}
                kind="next"
                onPress={() => run(skipNext())}
                size="mini"
              />
            </View>
          ) : null}
        </View>
      </View>

    </>
  );
}

function FullPlayer({
  playback,
  onClose,
}: {
  playback: PlaybackState;
  onClose: () => void;
}) {
  const window = useWindowDimensions();
  const [trackWidth, setTrackWidth] = useState(1);
  const [stage, setStage] = useState({width: 1, height: 1});
  const [externalVideoActive, setExternalVideoActive] = useState(false);
  const [videoSurfaceGeneration, setVideoSurfaceGeneration] = useState(0);
  const [speedMenuOpen, setSpeedMenuOpen] = useState(false);
  const progress = playback.durationMs > 0
    ? Math.min(playback.positionMs / playback.durationMs, 1)
    : 0;
  const isVideo = playback.mediaType === 'video';
  const sourceAspect = playback.width && playback.height
    ? playback.width / playback.height
    : 16 / 9;
  const videoRect = useMemo(
    () => fitRect(stage.width, stage.height, sourceAspect),
    [sourceAspect, stage.height, stage.width],
  );

  const seekFromPress = useCallback((event: GestureResponderEvent) => {
    if (!playback.durationMs) return;
    const ratio = Math.min(Math.max(event.nativeEvent.locationX / trackWidth, 0), 1);
    run(seekTo(ratio * playback.durationMs));
  }, [playback.durationMs, trackWidth]);

  const close = useCallback(() => {
    setSpeedMenuOpen(false);
    onClose();
  }, [onClose]);

  const openFullscreen = useCallback(() => {
    const [width, height] = pictureInPictureRatio(playback.width, playback.height);
    setSpeedMenuOpen(false);
    setExternalVideoActive(true);
    setVideoFullscreen(true, width, height)
      .then(opened => {
        if (!opened) setExternalVideoActive(false);
      })
      .catch(() => setExternalVideoActive(false));
  }, [playback.height, playback.width]);

  const openPictureInPicture = useCallback(() => {
    const [width, height] = pictureInPictureRatio(playback.width, playback.height);
    setSpeedMenuOpen(false);
    setExternalVideoActive(true);
    enterPictureInPicture(width, height)
      .then(entered => {
        if (entered) onClose();
        else setExternalVideoActive(false);
      })
      .catch(() => setExternalVideoActive(false));
  }, [onClose, playback.height, playback.width]);

  useEffect(() => {
    const subscription = onVideoPlayerClosed(() => {
      setExternalVideoActive(false);
      setVideoSurfaceGeneration(value => value + 1);
    });
    return () => subscription?.remove();
  }, []);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (speedMenuOpen) setSpeedMenuOpen(false);
      else close();
      return true;
    });
    return () => subscription.remove();
  }, [close, speedMenuOpen]);

  const changeSpeed = useCallback((speed: number) => {
    setSpeedMenuOpen(false);
    run(setPlaybackSpeed(speed));
  }, []);

  return (
    <View style={styles.playerOverlay}>
      <View style={styles.fullContainer}>
        <SafeAreaView edges={['top', 'bottom']} style={styles.fullSafeArea}>
          <View style={styles.fullTopBar}>
            <Pressable
              accessibilityLabel="Close player"
              hitSlop={10}
              onPress={close}
              style={({pressed}) => [styles.topButton, pressed && styles.pressed]}>
              <ChevronDown />
            </Pressable>
            <View style={styles.topSpacer} />
            <SpeedButton
              onPress={() => setSpeedMenuOpen(true)}
              speed={playback.playbackSpeed}
            />
            {isVideo ? (
              <Pressable
                accessibilityLabel="Enter full screen"
                onPress={openFullscreen}
                style={({pressed}) => [styles.topButton, pressed && styles.pressed]}>
                <FullscreenIcon />
              </Pressable>
            ) : null}
            {isVideo && Number(Platform.Version) >= 26 ? (
              <Pressable
                accessibilityLabel="Enter picture in picture"
                onPress={openPictureInPicture}
                style={({pressed}) => [styles.topButton, pressed && styles.pressed]}>
                <PipIcon />
              </Pressable>
            ) : <View style={styles.topButton} />}
          </View>

          <View
            onLayout={(event: LayoutChangeEvent) => setStage(event.nativeEvent.layout)}
            style={styles.visualStage}>
            {isVideo && NativeMediaPlayerView ? (
              <View style={[styles.videoFrame, videoRect]}>
                {!externalVideoActive ? (
                  <NativeMediaPlayerView
                    key={`${playback.mediaId}-${videoSurfaceGeneration}`}
                    style={styles.videoView}
                  />
                ) : null}
                {playback.state === 'buffering' && !externalVideoActive ? (
                  <ActivityIndicator color={colors.white} size="large" style={styles.videoBuffering} />
                ) : null}
              </View>
            ) : (
              <MediaArtwork
                path={playback.artworkPath}
                style={[
                  styles.artworkLarge,
                  {height: Math.min(window.width - 64, stage.height), width: Math.min(window.width - 64, stage.height)},
                ]}
              />
            )}
          </View>

          <View style={styles.fullControls}>
            <Text numberOfLines={2} style={styles.fullTitle}>{playback.title || 'Nothing playing'}</Text>
            <Text numberOfLines={1} style={styles.fullArtist}>{playback.artist || 'Local library'}</Text>

            <Pressable
              accessibilityLabel="Seek"
              hitSlop={{bottom: 10, left: 0, right: 0, top: 10}}
              onLayout={event => setTrackWidth(event.nativeEvent.layout.width)}
              onPress={seekFromPress}
              style={styles.seekTouchArea}>
              <View style={styles.seekTrack}>
                <View style={[styles.seekProgress, {width: `${progress * 100}%`}]} />
                <View style={[styles.seekThumb, {left: `${progress * 100}%`}]} />
              </View>
            </Pressable>
            <View style={styles.timeRow}>
              <Text style={styles.time}>{formatTime(playback.positionMs)}</Text>
              <Text style={styles.time}>-{formatTime(Math.max(0, playback.durationMs - playback.positionMs))}</Text>
            </View>

            <View style={styles.transport}>
              <TransportButton
                accessibilityLabel="Previous"
                disabled={!playback.hasPrevious}
                kind="previous"
                onPress={() => run(skipPrevious())}
              />
              {playback.state === 'buffering' ? (
                <View style={styles.primaryLoading}>
                  <ActivityIndicator color={colors.black} size="large" />
                </View>
              ) : (
                <TransportButton
                  accessibilityLabel={playback.isPlaying ? 'Pause' : 'Play'}
                  kind={playback.isPlaying ? 'pause' : 'play'}
                  onPress={() => run(togglePlayback())}
                  primary
                />
              )}
              <TransportButton
                accessibilityLabel="Next"
                disabled={!playback.hasNext}
                kind="next"
                onPress={() => run(skipNext())}
              />
            </View>
          </View>
        </SafeAreaView>
      </View>
      {speedMenuOpen ? (
        <PlaybackSpeedMenu
          current={playback.playbackSpeed}
          onClose={() => setSpeedMenuOpen(false)}
          onSelect={changeSpeed}
        />
      ) : null}
    </View>
  );
}

function SpeedButton({
  onPress,
  speed,
}: {
  onPress: () => void;
  speed: number;
}) {
  const label = formatSpeed(speed);
  return (
    <Pressable
      accessibilityLabel={`Playback speed, ${label}`}
      accessibilityRole="button"
      onPress={onPress}
      style={({pressed}) => [
        styles.speedButton,
        pressed && styles.pressed,
      ]}>
      <Text style={styles.speedButtonText}>{label}</Text>
    </Pressable>
  );
}

function PlaybackSpeedMenu({
  current,
  onClose,
  onSelect,
}: {
  current: number;
  onClose: () => void;
  onSelect: (speed: number) => void;
}) {
  return (
    <View accessibilityViewIsModal style={styles.speedMenuLayer}>
      <Pressable
        accessibilityLabel="Close playback speed menu"
        onPress={onClose}
        style={styles.speedMenuBackdrop}
      />
      <View style={styles.speedMenu}>
        <Text style={styles.speedMenuTitle}>Playback speed</Text>
        {playbackSpeeds.map(speed => {
          const selected = Math.abs(current - speed) < 0.01;
          return (
            <Pressable
              accessibilityLabel={`${formatSpeed(speed)} playback speed`}
              accessibilityRole="button"
              accessibilityState={{selected}}
              key={speed}
              onPress={() => onSelect(speed)}
              style={({pressed}) => [styles.speedOption, pressed && styles.pressed]}>
              <Text style={[styles.speedOptionText, selected && styles.speedOptionTextSelected]}>
                {formatSpeed(speed)}
              </Text>
              {selected ? <Text style={styles.speedCheck}>✓</Text> : null}
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

function TransportButton({
  accessibilityLabel,
  disabled = false,
  kind,
  onPress,
  primary = false,
  size = 'full',
}: {
  accessibilityLabel: string;
  disabled?: boolean;
  kind: 'play' | 'pause' | 'previous' | 'next';
  onPress: () => void;
  primary?: boolean;
  size?: 'mini' | 'full';
}) {
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={({pressed}) => [
        styles.transportButton,
        size === 'mini' && styles.transportButtonMini,
        primary && size === 'full' && styles.transportButtonPrimary,
        disabled && styles.transportButtonDisabled,
        pressed && styles.pressed,
      ]}>
      {kind === 'play' ? <PlayIcon mini={size === 'mini'} /> : null}
      {kind === 'pause' ? <PauseIcon mini={size === 'mini'} /> : null}
      {kind === 'previous' || kind === 'next' ? (
        <SkipIcon direction={kind} mini={size === 'mini'} />
      ) : null}
    </Pressable>
  );
}

function PlayIcon({mini}: {mini: boolean}) {
  return <View style={[styles.playIcon, mini && styles.playIconMini]} />;
}

function PauseIcon({mini}: {mini: boolean}) {
  return (
    <View style={styles.pauseIcon}>
      <View style={[styles.pauseBar, mini && styles.pauseBarMini]} />
      <View style={[styles.pauseBar, mini && styles.pauseBarMini]} />
    </View>
  );
}

function SkipIcon({direction, mini}: {direction: 'previous' | 'next'; mini: boolean}) {
  return (
    <View style={[styles.skipIcon, direction === 'previous' && styles.skipIconReverse]}>
      <View style={[styles.skipTriangle, mini && styles.skipTriangleMini]} />
      <View style={[styles.skipBar, mini && styles.skipBarMini]} />
    </View>
  );
}

function ChevronDown() {
  return <View style={styles.chevronDown} />;
}

function PipIcon() {
  return <View style={styles.pipOutline}><View style={styles.pipWindow} /></View>;
}

function FullscreenIcon() {
  return (
    <View style={styles.fullscreenIcon}>
      <View style={[styles.fullscreenCorner, styles.cornerTopLeft]} />
      <View style={[styles.fullscreenCorner, styles.cornerTopRight]} />
      <View style={[styles.fullscreenCorner, styles.cornerBottomLeft]} />
      <View style={[styles.fullscreenCorner, styles.cornerBottomRight]} />
    </View>
  );
}

function fitRect(width: number, height: number, aspect: number) {
  if (width <= 1 || height <= 1 || !Number.isFinite(aspect) || aspect <= 0) {
    return {width: '100%' as const, aspectRatio: 16 / 9};
  }
  if (width / height > aspect) return {height, width: height * aspect};
  return {height: width / aspect, width};
}

function pictureInPictureRatio(width: number | null, height: number | null): [number, number] {
  if (!width || !height || width <= 0 || height <= 0) return [16, 9];
  const ratio = width / height;
  if (ratio > 2.38) return [238, 100];
  if (ratio < 0.43) return [43, 100];
  return [Math.round(width), Math.round(height)];
}

function formatTime(milliseconds: number) {
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) return '0:00';
  const totalSeconds = Math.floor(milliseconds / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

function formatSpeed(speed: number) {
  const safeSpeed = Number.isFinite(speed) && speed > 0 ? speed : 1;
  return `${Number(safeSpeed.toFixed(2))}×`;
}

function run(promise: Promise<unknown>) {
  promise.catch(() => undefined);
}

const styles = StyleSheet.create({
  overlayHost: {backgroundColor: colors.canvas, flex: 1},
  playerOverlay: {backgroundColor: colors.canvas, bottom: 0, elevation: 30, left: 0, position: 'absolute', right: 0, top: 0, zIndex: 100},
  miniContainer: {backgroundColor: colors.surface, borderTopColor: colors.border, borderTopWidth: StyleSheet.hairlineWidth, height: layout.miniPlayer},
  miniProgressTrack: {backgroundColor: colors.border, height: 2},
  progressFill: {backgroundColor: colors.accent, height: 2},
  miniRow: {alignItems: 'center', flex: 1, flexDirection: 'row', paddingHorizontal: spacing.sm},
  miniDetails: {alignItems: 'center', flex: 1, flexDirection: 'row', minWidth: 0},
  miniArtwork: {borderRadius: radii.artwork, height: 48, width: 48},
  miniCopy: {flex: 1, marginLeft: spacing.md, minWidth: 0},
  miniTitle: {color: colors.text, fontSize: typography.body, fontWeight: '700'},
  miniSubtitle: {color: colors.textMuted, fontSize: typography.caption, marginTop: spacing.xxs},
  miniActions: {alignItems: 'center', flexDirection: 'row'},
  miniLoading: {alignItems: 'center', height: 44, justifyContent: 'center', width: 44},
  fullContainer: {backgroundColor: colors.canvas, flex: 1},
  fullSafeArea: {flex: 1},
  fullTopBar: {alignItems: 'center', flexDirection: 'row', height: 52, paddingHorizontal: spacing.md},
  topButton: {alignItems: 'center', height: 44, justifyContent: 'center', width: 44},
  speedButton: {alignItems: 'center', height: 44, justifyContent: 'center', width: 44},
  speedButtonText: {color: colors.text, fontSize: typography.caption, fontVariant: ['tabular-nums'], fontWeight: '800'},
  topSpacer: {flex: 1},
  visualStage: {alignItems: 'center', flex: 1, justifyContent: 'center', marginHorizontal: spacing.xl, overflow: 'hidden'},
  artworkLarge: {backgroundColor: colors.surfaceRaised, borderRadius: radii.lg, overflow: 'hidden'},
  videoFrame: {backgroundColor: colors.black, borderRadius: radii.md, overflow: 'hidden'},
  videoView: {height: '100%', width: '100%'},
  videoBuffering: {bottom: 0, left: 0, position: 'absolute', right: 0, top: 0},
  fullControls: {paddingBottom: spacing.xxl, paddingHorizontal: spacing.xl, paddingTop: spacing.xl},
  fullTitle: {color: colors.text, fontSize: typography.playerTitle, fontWeight: '800', letterSpacing: -0.4},
  fullArtist: {color: colors.textMuted, fontSize: typography.body, marginTop: spacing.xs},
  seekTouchArea: {height: 28, justifyContent: 'center', marginTop: spacing.xl},
  seekTrack: {backgroundColor: colors.border, height: 3, position: 'relative'},
  seekProgress: {backgroundColor: colors.text, height: 3},
  seekThumb: {backgroundColor: colors.text, borderRadius: 5, height: 10, marginLeft: -5, marginTop: -6.5, position: 'absolute', top: '50%', width: 10},
  timeRow: {flexDirection: 'row', justifyContent: 'space-between'},
  time: {color: colors.textMuted, fontSize: typography.utility},
  transport: {alignItems: 'center', flexDirection: 'row', justifyContent: 'space-around', marginTop: spacing.xl},
  transportButton: {alignItems: 'center', height: 52, justifyContent: 'center', width: 52},
  transportButtonMini: {height: 44, width: 44},
  transportButtonPrimary: {backgroundColor: colors.text, borderRadius: radii.round, height: 60, width: 60},
  transportButtonDisabled: {opacity: 0.24},
  primaryLoading: {alignItems: 'center', backgroundColor: colors.text, borderRadius: radii.round, height: 60, justifyContent: 'center', width: 60},
  playIcon: {borderBottomColor: colors.transparent, borderBottomWidth: 10, borderLeftColor: colors.canvas, borderLeftWidth: 16, borderTopColor: colors.transparent, borderTopWidth: 10, height: 0, marginLeft: 4, width: 0},
  playIconMini: {borderBottomWidth: 7, borderLeftColor: colors.text, borderLeftWidth: 11, borderTopWidth: 7, marginLeft: 2},
  pauseIcon: {flexDirection: 'row'},
  pauseBar: {backgroundColor: colors.canvas, height: 20, marginHorizontal: 2.5, width: 5},
  pauseBarMini: {backgroundColor: colors.text, height: 15, marginHorizontal: 2, width: 3.5},
  skipIcon: {alignItems: 'center', flexDirection: 'row'},
  skipIconReverse: {transform: [{rotate: '180deg'}]},
  skipTriangle: {borderBottomColor: colors.transparent, borderBottomWidth: 9, borderLeftColor: colors.text, borderLeftWidth: 13, borderTopColor: colors.transparent, borderTopWidth: 9, height: 0, width: 0},
  skipTriangleMini: {borderBottomWidth: 6, borderLeftWidth: 9, borderTopWidth: 6},
  skipBar: {backgroundColor: colors.text, height: 19, width: 2.5},
  skipBarMini: {height: 13, width: 2},
  chevronDown: {borderBottomColor: colors.text, borderBottomWidth: 1.8, borderRightColor: colors.text, borderRightWidth: 1.8, height: 12, transform: [{rotate: '45deg'}], width: 12},
  pipOutline: {borderColor: colors.text, borderRadius: 2, borderWidth: 1.5, height: 18, width: 22},
  pipWindow: {borderColor: colors.text, borderRadius: 1, borderWidth: 1.5, bottom: 2, height: 7, position: 'absolute', right: 2, width: 9},
  fullscreenIcon: {height: 20, position: 'relative', width: 20},
  fullscreenCorner: {borderColor: colors.text, height: 7, position: 'absolute', width: 7},
  cornerTopLeft: {borderLeftWidth: 1.5, borderTopWidth: 1.5, left: 0, top: 0},
  cornerTopRight: {borderRightWidth: 1.5, borderTopWidth: 1.5, right: 0, top: 0},
  cornerBottomLeft: {borderBottomWidth: 1.5, borderLeftWidth: 1.5, bottom: 0, left: 0},
  cornerBottomRight: {borderBottomWidth: 1.5, borderRightWidth: 1.5, bottom: 0, right: 0},
  speedMenuLayer: {bottom: 0, left: 0, position: 'absolute', right: 0, top: 0, zIndex: 120},
  speedMenuBackdrop: {bottom: 0, left: 0, position: 'absolute', right: 0, top: 0},
  speedMenu: {backgroundColor: colors.surfaceOverlay, borderColor: colors.border, borderRadius: radii.md, borderWidth: StyleSheet.hairlineWidth, elevation: 18, overflow: 'hidden', position: 'absolute', right: spacing.lg, top: 64, width: 164},
  speedMenuTitle: {borderBottomColor: colors.border, borderBottomWidth: StyleSheet.hairlineWidth, color: colors.textMuted, fontSize: typography.utility, fontWeight: '700', letterSpacing: 0.4, paddingHorizontal: spacing.lg, paddingVertical: spacing.md, textTransform: 'uppercase'},
  speedOption: {alignItems: 'center', borderBottomColor: colors.border, borderBottomWidth: StyleSheet.hairlineWidth, flexDirection: 'row', minHeight: 44, paddingHorizontal: spacing.lg},
  speedOptionText: {color: colors.text, flex: 1, fontSize: typography.body, fontVariant: ['tabular-nums']},
  speedOptionTextSelected: {color: colors.accent, fontWeight: '800'},
  speedCheck: {color: colors.accent, fontSize: typography.body, fontWeight: '800'},
  pressed: {opacity: 0.58},
});

export default MiniPlayer;
