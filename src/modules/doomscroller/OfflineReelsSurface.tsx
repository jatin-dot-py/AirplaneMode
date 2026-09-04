import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator,
  AppState,
  Image,
  Pressable,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
  type NativeSyntheticEvent,
} from 'react-native';
import Clipboard from '@react-native-clipboard/clipboard';
import {FlashList, type FlashListRef} from '@shopify/flash-list';
import {useIsFocused} from '@react-navigation/native';
import {
  ArrowLeft,
  BadgeCheck,
  Bookmark,
  DownloadCloud,
  Heart,
  Link2,
  MessageCircle,
  MoreHorizontal,
  Music2,
  Pause,
  Play,
  RefreshCw,
  Send,
  Volume2,
  VolumeX,
  type LucideIcon,
} from 'lucide-react-native';
import LinearGradient from 'react-native-linear-gradient';
import {useSafeAreaInsets} from 'react-native-safe-area-context';

import {
  NativeReelPlayerView,
  getReelSnapshot,
  listSnapshotReels,
  onDoomscrollDownloadChanged,
  recordSnapshotPlayback,
  retryReelDownload,
  type ReelPlaybackProgress,
} from '../../native/DoomscrollEngine';
import {getUiPreference, setUiPreference} from '../../native/MediaEngine';
import {colors, radii, spacing, typography} from '../../theme';
import {settledReelPage} from './paging';
import {canonicalReelPermalink} from './permalink';
import {
  DEFAULT_REEL_SPEED,
  DOOMSCROLL_SPEED_PREFERENCE,
  REEL_PLAYBACK_SPEEDS,
  parseReelSpeed,
} from './preferences';
import {reelResumePosition, resolveSnapshotContinueIndex} from './resumePolicy';
import type {OfflineReel} from './types';

type Props = {
  onBack: () => void;
  snapshotId: string;
};

type PendingProgress = {
  activeDeltaMs: number;
  event: ReelPlaybackProgress;
  reel: OfflineReel;
};

const PROGRESS_WRITE_INTERVAL_MS = 2_000;
const DRAG_END_SETTLE_MS = 80;
const DOWNLOAD_REFRESH_DELAY_MS = 120;
const LAYOUT_CHANGE_TOLERANCE = 0.25;

function OfflineReelsSurface({onBack, snapshotId}: Props) {
  const insets = useSafeAreaInsets();
  const focused = useIsFocused();
  const listRef = useRef<FlashListRef<OfflineReel>>(null);
  const progressChainRef = useRef<Promise<void>>(Promise.resolve());
  const initializedRef = useRef(false);
  const mountedRef = useRef(true);
  const activeIndexRef = useRef(0);
  const settledRef = useRef(false);
  const reelsRef = useRef<OfflineReel[]>([]);
  const appActiveRef = useRef(AppState.currentState === 'active');
  const qualifiedIdsRef = useRef<Set<string>>(new Set());
  const resumePositionsRef = useRef<Map<string, number>>(new Map());
  const pendingProgressRef = useRef<PendingProgress | null>(null);
  const lastProgressWriteRef = useRef(0);
  const lastOffsetRef = useRef(0);
  const surfaceHeightRef = useRef(0);
  const momentumRef = useRef(false);
  const interactionGenerationRef = useRef(0);
  const dragEndTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const downloadRefreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingDownloadRefreshRef = useRef(false);
  const alignmentFrameRef = useRef<number | null>(null);
  const [reels, setReels] = useState<OfflineReel[]>([]);
  const [surfaceHeight, setSurfaceHeight] = useState(0);
  const [activeIndex, setActiveIndex] = useState(0);
  const [settled, setSettled] = useState(false);
  const [appActive, setAppActive] = useState(appActiveRef.current);
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(DEFAULT_REEL_SPEED);
  const [speedMenuOpen, setSpeedMenuOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [expandedCaptions, setExpandedCaptions] = useState<Set<string>>(new Set());
  const [renderedSourcePath, setRenderedSourcePath] = useState<string | null>(null);

  useEffect(() => {
    settledRef.current = settled;
  }, [settled]);

  useEffect(() => {
    reelsRef.current = reels;
  }, [reels]);

  const refresh = useCallback(async () => {
    try {
      const [nextReels, snapshot] = await Promise.all([
        listSnapshotReels(snapshotId),
        getReelSnapshot(snapshotId),
      ]);
      reelsRef.current = nextReels;
      setReels(nextReels);
      qualifiedIdsRef.current = new Set(
        nextReels.filter(reel => reel.qualifiedWatched).map(reel => reel.mediaPk),
      );
      resumePositionsRef.current = new Map(
        nextReels.map(reel => [reel.mediaPk, reel.savedPlaybackPositionMs]),
      );
      if (!initializedRef.current) {
        initializedRef.current = true;
        const continueIndex = resolveSnapshotContinueIndex(nextReels, snapshot);
        activeIndexRef.current = continueIndex;
        setActiveIndex(continueIndex);
      } else {
        setActiveIndex(current => {
          const bounded = Math.min(current, Math.max(nextReels.length - 1, 0));
          activeIndexRef.current = bounded;
          return bounded;
        });
      }
      setLoadError(null);
    } catch (reason) {
      setLoadError(
        reason instanceof Error
          ? reason.message
          : 'This snapshot could not be loaded.',
      );
    } finally {
      setLoading(false);
    }
  }, [snapshotId]);

  useEffect(() => {
    mountedRef.current = true;
    getUiPreference(DOOMSCROLL_SPEED_PREFERENCE)
      .then(value => setPlaybackSpeed(parseReelSpeed(value)))
      .catch(() => undefined);
    refresh().catch(() => undefined);
    const downloadChanged = onDoomscrollDownloadChanged(event => {
      setReels(current => current.map(reel => reel.mediaPk === event.mediaPk
        ? {
            ...reel,
            downloadError: event.error,
            downloadProgress: event.progress,
            downloadState: event.state,
          }
        : reel));
      if (event.state === 'ready') {
        pendingDownloadRefreshRef.current = true;
        if (settledRef.current && !momentumRef.current) {
          if (downloadRefreshTimerRef.current) {
            clearTimeout(downloadRefreshTimerRef.current);
          }
          downloadRefreshTimerRef.current = setTimeout(() => {
            downloadRefreshTimerRef.current = null;
            pendingDownloadRefreshRef.current = false;
            refresh().catch(() => undefined);
          }, DOWNLOAD_REFRESH_DELAY_MS);
        }
      }
    });
    const appStateChanged = AppState.addEventListener('change', state => {
      const active = state === 'active';
      appActiveRef.current = active;
      setAppActive(active);
    });
    return () => {
      mountedRef.current = false;
      downloadChanged?.remove();
      appStateChanged.remove();
      if (dragEndTimerRef.current) clearTimeout(dragEndTimerRef.current);
      if (downloadRefreshTimerRef.current) {
        clearTimeout(downloadRefreshTimerRef.current);
      }
      if (alignmentFrameRef.current !== null) {
        cancelAnimationFrame(alignmentFrameRef.current);
      }
    };
  }, [refresh]);

  const persistProgress = useCallback((
    reel: OfflineReel,
    event: ReelPlaybackProgress | null,
  ) => {
    if (event) {
      resumePositionsRef.current.set(reel.mediaPk, event.playbackPositionMs);
    }
    progressChainRef.current = progressChainRef.current
      .catch(() => undefined)
      .then(async () => {
        const alreadyQualified = qualifiedIdsRef.current.has(reel.mediaPk);
        const result = await recordSnapshotPlayback(
          snapshotId,
          reel.mediaPk,
          reel.snapshotPosition,
          event?.playbackPositionMs ??
            resumePositionsRef.current.get(reel.mediaPk) ??
            reel.savedPlaybackPositionMs,
          event?.durationMs || reel.durationMs || 0,
          event?.activeDeltaMs ?? 0,
        );
        if (result.qualified) qualifiedIdsRef.current.add(reel.mediaPk);
        if (!mountedRef.current || alreadyQualified === result.qualified) return;
        setReels(current => current.map(item => item.mediaPk === reel.mediaPk
          ? {
              ...item,
              activePlaybackMs: result.activePlaybackMs,
              qualifiedWatched: result.qualified,
              qualifiedWatchedAt: result.qualified
                ? item.qualifiedWatchedAt ?? Date.now()
                : item.qualifiedWatchedAt,
            }
          : item));
      });
  }, [snapshotId]);

  const flushPendingProgress = useCallback(() => {
    const pending = pendingProgressRef.current;
    if (!pending) return;
    pendingProgressRef.current = null;
    lastProgressWriteRef.current = Date.now();
    persistProgress(pending.reel, {
      ...pending.event,
      activeDeltaMs: pending.activeDeltaMs,
    });
  }, [persistProgress]);

  useEffect(() => () => flushPendingProgress(), [flushPendingProgress]);

  const handlePlaybackProgress = useCallback((
    event: NativeSyntheticEvent<ReelPlaybackProgress>,
  ) => {
    const reel = reelsRef.current[activeIndexRef.current];
    if (
      !reel ||
      !settledRef.current ||
      !focused ||
      !appActiveRef.current ||
      event.nativeEvent.sourcePath !== reel.videoLocalPath
    ) return;

    const previous = pendingProgressRef.current;
    pendingProgressRef.current = {
      activeDeltaMs:
        (previous?.reel.mediaPk === reel.mediaPk ? previous.activeDeltaMs : 0) +
        event.nativeEvent.activeDeltaMs,
      event: event.nativeEvent,
      reel,
    };
    if (
      Date.now() - lastProgressWriteRef.current >= PROGRESS_WRITE_INTERVAL_MS ||
      !event.nativeEvent.isPlaying
    ) flushPendingProgress();
  }, [flushPendingProgress, focused]);

  const activeReel = reels[activeIndex];
  const playbackQualified = focused && appActive && settled;
  const activeVideoPath = activeReel?.downloadState === 'ready'
    ? activeReel.videoLocalPath || ''
    : '';
  const activeVideoPathRef = useRef(activeVideoPath);
  activeVideoPathRef.current = activeVideoPath;
  const activeResumePositionMs = reelResumePosition(activeReel ? {
    ...activeReel,
    qualifiedWatched: qualifiedIdsRef.current.has(activeReel.mediaPk),
    savedPlaybackPositionMs:
      resumePositionsRef.current.get(activeReel.mediaPk) ??
      activeReel.savedPlaybackPositionMs,
  } : undefined);
  const activeVideoVisible = Boolean(activeVideoPath) &&
    playbackQualified &&
    renderedSourcePath === activeVideoPath;

  useEffect(() => {
    if (renderedSourcePath && renderedSourcePath !== activeVideoPath) {
      setRenderedSourcePath(null);
    }
  }, [activeVideoPath, renderedSourcePath]);

  useEffect(() => {
    if (!activeReel || !playbackQualified) return;
    persistProgress(activeReel, null);
  }, [activeReel, persistProgress, playbackQualified]);

  const toggleCaption = useCallback((mediaPk: string) => {
    setExpandedCaptions(current => {
      const next = new Set(current);
      if (next.has(mediaPk)) next.delete(mediaPk);
      else next.add(mediaPk);
      return next;
    });
  }, []);

  const retry = useCallback(async (mediaPk: string) => {
    const queued = await retryReelDownload(mediaPk).catch(() => false);
    ToastAndroid.show(
      queued ? 'Download queued' : 'Capture this Reel again on Instagram',
      ToastAndroid.SHORT,
    );
    refresh().catch(() => undefined);
  }, [refresh]);

  const chooseSpeed = useCallback((value: number) => {
    setPlaybackSpeed(value);
    setSpeedMenuOpen(false);
    setUiPreference(DOOMSCROLL_SPEED_PREFERENCE, String(value)).catch(() => undefined);
  }, []);

  const settleAtOffset = useCallback((offset: number) => {
    if (!surfaceHeight || reels.length === 0) return;
    const page = settledReelPage(offset, surfaceHeight, reels.length);
    const generation = interactionGenerationRef.current;
    const finish = () => {
      if (generation !== interactionGenerationRef.current) return;
      alignmentFrameRef.current = null;
      lastOffsetRef.current = page.exactOffset;
      if (page.index !== activeIndexRef.current) {
        setRenderedSourcePath(null);
        activeIndexRef.current = page.index;
        setActiveIndex(page.index);
        setPaused(false);
      }
      settledRef.current = true;
      setSettled(true);
      if (pendingDownloadRefreshRef.current && !downloadRefreshTimerRef.current) {
        downloadRefreshTimerRef.current = setTimeout(() => {
          downloadRefreshTimerRef.current = null;
          pendingDownloadRefreshRef.current = false;
          refresh().catch(() => undefined);
        }, DOWNLOAD_REFRESH_DELAY_MS);
      }
    };

    const list = listRef.current;
    if (page.needsCorrection && list) {
      lastOffsetRef.current = page.exactOffset;
      if (alignmentFrameRef.current !== null) {
        cancelAnimationFrame(alignmentFrameRef.current);
      }
      list.scrollToIndex({
        animated: false,
        index: page.index,
        viewPosition: 0,
      }).then(() => {
        if (generation !== interactionGenerationRef.current) return;
        alignmentFrameRef.current = requestAnimationFrame(finish);
      }).catch(() => {
        if (generation !== interactionGenerationRef.current) return;
        list.scrollToOffset({animated: false, offset: page.exactOffset});
        alignmentFrameRef.current = requestAnimationFrame(finish);
      });
      return;
    }
    finish();
  }, [reels.length, refresh, surfaceHeight]);

  const cancelDragEndSettle = useCallback(() => {
    if (!dragEndTimerRef.current) return;
    clearTimeout(dragEndTimerRef.current);
    dragEndTimerRef.current = null;
  }, []);

  const beginScroll = useCallback(() => {
    flushPendingProgress();
    interactionGenerationRef.current += 1;
    cancelDragEndSettle();
    momentumRef.current = false;
    if (downloadRefreshTimerRef.current) {
      clearTimeout(downloadRefreshTimerRef.current);
      downloadRefreshTimerRef.current = null;
      pendingDownloadRefreshRef.current = true;
    }
    if (alignmentFrameRef.current !== null) {
      cancelAnimationFrame(alignmentFrameRef.current);
      alignmentFrameRef.current = null;
    }
    settledRef.current = false;
    setSettled(false);
    setSpeedMenuOpen(false);
  }, [cancelDragEndSettle, flushPendingProgress]);

  const handleSurfaceLayout = useCallback((height: number) => {
    const nextHeight = height;
    if (
      nextHeight <= 0 ||
      Math.abs(nextHeight - surfaceHeightRef.current) <= LAYOUT_CHANGE_TOLERANCE
    ) return;
    surfaceHeightRef.current = nextHeight;
    lastOffsetRef.current = activeIndexRef.current * nextHeight;
    settledRef.current = false;
    setSettled(false);
    setSurfaceHeight(nextHeight);
  }, []);

  const positionInitialPage = useCallback(() => {
    if (!surfaceHeight || reels.length === 0 || !listRef.current) return;
    const targetIndex = Math.min(
      activeIndexRef.current,
      reels.length - 1,
    );
    const targetOffset = targetIndex * surfaceHeight;
    const list = listRef.current;
    const generation = interactionGenerationRef.current;
    lastOffsetRef.current = targetOffset;
    if (alignmentFrameRef.current !== null) {
      cancelAnimationFrame(alignmentFrameRef.current);
    }
    const finishInitialPosition = () => {
      if (generation !== interactionGenerationRef.current) return;
      alignmentFrameRef.current = requestAnimationFrame(() => {
        alignmentFrameRef.current = null;
        const actualOffset = list.getAbsoluteLastScrollOffset();
        lastOffsetRef.current = actualOffset;
        settleAtOffset(actualOffset);
      });
    };
    list.scrollToIndex({
      animated: false,
      index: targetIndex,
      viewPosition: 0,
    }).then(finishInitialPosition).catch(() => {
      list.scrollToOffset({animated: false, offset: targetOffset});
      finishInitialPosition();
    });
  }, [reels.length, settleAtOffset, surfaceHeight]);

  const copyLink = useCallback((reel: OfflineReel) => {
    Clipboard.setString(canonicalReelPermalink(reel));
    ToastAndroid.show('Link copied', ToastAndroid.SHORT);
  }, []);

  const listExtraData = useMemo(() => ({
    activeIndex,
    activeVideoVisible,
    expandedCaptions,
  }), [
    activeIndex,
    activeVideoVisible,
    expandedCaptions,
  ]);

  if (loading && reels.length === 0) {
    return (
      <View style={styles.centerState}>
        <ActivityIndicator color={colors.textMuted} />
        <Text style={styles.centerCopy}>Opening snapshot…</Text>
      </View>
    );
  }

  if (loadError || reels.length === 0) {
    return (
      <View style={styles.centerState}>
        <Text style={styles.centerTitle}>This snapshot is empty</Text>
        <Text style={styles.centerCopy}>
          {loadError || 'No captured Reels were found.'}
        </Text>
        <Pressable onPress={onBack} style={styles.centerButton}>
          <Text style={styles.centerButtonText}>Back to snapshots</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View
      onLayout={event => handleSurfaceLayout(event.nativeEvent.layout.height)}
      style={styles.container}>
      {surfaceHeight > 0 && NativeReelPlayerView ? (
        <NativeReelPlayerView
          muted={muted}
          onFirstFrame={event => {
            if (event.nativeEvent.sourcePath === activeVideoPathRef.current) {
              setRenderedSourcePath(event.nativeEvent.sourcePath);
            }
          }}
          onPlaybackError={event => {
            if (event.nativeEvent.sourcePath !== activeVideoPathRef.current) return;
            setRenderedSourcePath(null);
            ToastAndroid.show(event.nativeEvent.message, ToastAndroid.LONG);
          }}
          onPlaybackProgress={handlePlaybackProgress}
          paused={paused || !playbackQualified || !activeVideoPath}
          pointerEvents="none"
          playbackSpeed={playbackSpeed}
          resumePositionMs={activeResumePositionMs}
          sourcePath={activeVideoPath}
          style={styles.player}
          visibilityQualified={Boolean(activeVideoPath) && playbackQualified}
        />
      ) : null}

      {surfaceHeight > 0 ? (
        <FlashList
          bounces={false}
          data={reels}
          decelerationRate="fast"
          disableIntervalMomentum
          drawDistance={surfaceHeight * 2}
          extraData={listExtraData}
          initialScrollIndex={activeIndex}
          key={`${snapshotId}:${surfaceHeight}`}
          keyExtractor={item => item.mediaPk}
          maintainVisibleContentPosition={{disabled: true}}
          onLoad={positionInitialPage}
          onMomentumScrollBegin={() => {
            momentumRef.current = true;
            cancelDragEndSettle();
          }}
          onMomentumScrollEnd={event => {
            momentumRef.current = false;
            cancelDragEndSettle();
            lastOffsetRef.current = event.nativeEvent.contentOffset.y;
            settleAtOffset(lastOffsetRef.current);
          }}
          onScroll={event => {
            lastOffsetRef.current = event.nativeEvent.contentOffset.y;
          }}
          onScrollBeginDrag={beginScroll}
          onScrollEndDrag={event => {
            lastOffsetRef.current = event.nativeEvent.contentOffset.y;
            cancelDragEndSettle();
            dragEndTimerRef.current = setTimeout(() => {
              dragEndTimerRef.current = null;
              if (!momentumRef.current) {
                settleAtOffset(lastOffsetRef.current);
              }
            }, DRAG_END_SETTLE_MS);
          }}
          overScrollMode="never"
          ref={listRef}
          removeClippedSubviews={false}
          renderItem={({item, index}) => (
            <OfflineReelPage
              bottomInset={insets.bottom}
              expanded={expandedCaptions.has(item.mediaPk)}
              height={surfaceHeight}
              item={item}
              onCopyLink={() => copyLink(item)}
              onRetry={() => { retry(item.mediaPk).catch(() => undefined); }}
              onToggleCaption={() => toggleCaption(item.mediaPk)}
              videoVisible={index === activeIndex && activeVideoVisible}
            />
          )}
          scrollEventThrottle={16}
          showsVerticalScrollIndicator={false}
          snapToAlignment="start"
          snapToInterval={surfaceHeight}
        />
      ) : null}

      <View
        pointerEvents="box-none"
        style={[styles.header, {top: insets.top + spacing.sm}]}>
        <ControlButton label="Back to Reel snapshots" onPress={onBack}>
          <ArrowLeft color={colors.white} size={24} strokeWidth={2} />
        </ControlButton>
        <View pointerEvents="none" style={styles.headerTitleWrap}>
          <Text style={styles.headerTitle}>Reels</Text>
          <Text style={styles.headerStats}>{activeIndex + 1} of {reels.length}</Text>
        </View>
        <ControlButton
          label={paused ? 'Play Reel' : 'Pause Reel'}
          onPress={() => setPaused(value => !value)}>
          {paused ? (
            <Play color={colors.white} fill={colors.white} size={19} strokeWidth={1.8} />
          ) : (
            <Pause color={colors.white} fill={colors.white} size={19} strokeWidth={1.8} />
          )}
        </ControlButton>
        <Pressable
          accessibilityLabel={`Playback speed ${playbackSpeed} times`}
          accessibilityRole="button"
          onPress={() => setSpeedMenuOpen(value => !value)}
          style={({pressed}) => [styles.speedButton, pressed && styles.pressed]}>
          <Text style={styles.speedButtonText}>{playbackSpeed}×</Text>
        </Pressable>
        <ControlButton
          label={muted ? 'Unmute Reel' : 'Mute Reel'}
          onPress={() => setMuted(value => !value)}>
          {muted ? (
            <VolumeX color={colors.white} size={21} strokeWidth={2} />
          ) : (
            <Volume2 color={colors.white} size={21} strokeWidth={2} />
          )}
        </ControlButton>
      </View>

      {speedMenuOpen ? (
        <View style={[styles.speedMenu, {top: insets.top + 62}]}>
          {REEL_PLAYBACK_SPEEDS.map(value => (
            <Pressable
              accessibilityLabel={`${value} times playback speed`}
              accessibilityRole="radio"
              accessibilityState={{checked: playbackSpeed === value}}
              key={value}
              onPress={() => chooseSpeed(value)}
              style={({pressed}) => [styles.speedMenuItem, pressed && styles.pressed]}>
              <Text style={[
                styles.speedMenuText,
                playbackSpeed === value && styles.speedMenuTextSelected,
              ]}>
                {value}×
              </Text>
            </Pressable>
          ))}
        </View>
      ) : null}
    </View>
  );
}

function OfflineReelPage({
  bottomInset,
  expanded,
  height,
  item,
  onCopyLink,
  onRetry,
  onToggleCaption,
  videoVisible,
}: {
  bottomInset: number;
  expanded: boolean;
  height: number;
  item: OfflineReel;
  onCopyLink: () => void;
  onRetry: () => void;
  onToggleCaption: () => void;
  videoVisible: boolean;
}) {
  const videoReady = item.downloadState === 'ready' && Boolean(item.videoLocalPath);
  const coverUri = localFileUri(item.coverLocalPath);
  const avatarUri = localFileUri(item.authorProfilePicLocalPath);

  return (
    <View style={[
      styles.reel,
      videoVisible && styles.reelVideoVisible,
      {height},
    ]}>
      {!videoVisible || !videoReady ? (
        coverUri ? (
          <Image
            accessibilityIgnoresInvertColors
            resizeMode="cover"
            source={{uri: coverUri}}
            style={StyleSheet.absoluteFill}
          />
        ) : (
          <View style={[StyleSheet.absoluteFill, styles.missingCover]}>
            <FilmPlaceholder />
          </View>
        )
      ) : null}

      <LinearGradient
        colors={[
          colors.transparent,
          'rgba(0,0,0,0.08)',
          'rgba(0,0,0,0.68)',
        ]}
        locations={[0, 0.42, 1]}
        pointerEvents="none"
        style={styles.bottomGradient}
      />

      <View style={[styles.reelMeta, {bottom: bottomInset + spacing.xl}]}>
        <View style={styles.authorRow}>
          <View style={styles.avatar}>
            {avatarUri ? (
              <Image
                accessibilityIgnoresInvertColors
                source={{uri: avatarUri}}
                style={styles.avatarImage}
              />
            ) : (
              <Text style={styles.avatarFallback}>
                {(item.authorUsername[0] || '?').toUpperCase()}
              </Text>
            )}
          </View>
          <Text numberOfLines={1} style={styles.username}>
            {item.authorUsername || item.authorFullName || 'Instagram creator'}
          </Text>
          {item.authorIsVerified ? (
            <BadgeCheck
              color={colors.white}
              fill={colors.verified}
              size={16}
              strokeWidth={2.1}
              style={styles.verifiedBadge}
            />
          ) : null}
        </View>

        {item.caption ? (
          <Pressable accessibilityRole="button" onPress={onToggleCaption}>
            <Text numberOfLines={expanded ? undefined : 2} style={styles.caption}>
              {item.caption}
            </Text>
          </Pressable>
        ) : null}

        <View style={styles.audioRow}>
          <Music2 color={colors.white} size={14} strokeWidth={2} />
          <Text numberOfLines={1} style={styles.audioText}>{audioLabel(item)}</Text>
        </View>
        {!videoReady ? <DownloadNotice item={item} onRetry={onRetry} /> : null}
      </View>

      <View style={[styles.actionRail, {bottom: bottomInset + spacing.xl}]}>
        <OnlineAction
          color={item.hasLiked ? colors.accent : colors.white}
          count={item.likeCount}
          filled={item.hasLiked}
          icon={Heart}
          label="Like"
        />
        <OnlineAction count={item.commentCount} icon={MessageCircle} label="Comments" />
        <OnlineAction count={item.repostCount} icon={Send} label="Share" />
        <DirectAction icon={Link2} label="Copy Reel link" onPress={onCopyLink} />
        <OnlineAction filled={item.hasViewerSaved} icon={Bookmark} label="Save" />
        <OnlineAction icon={MoreHorizontal} label="More" />
      </View>
    </View>
  );
}

function ControlButton({
  children,
  label,
  onPress,
}: {
  children: React.ReactNode;
  label: string;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      onPress={onPress}
      style={({pressed}) => [styles.headerIconButton, pressed && styles.pressed]}>
      {children}
    </Pressable>
  );
}

function FilmPlaceholder() {
  return (
    <View style={styles.placeholderCircle}>
      <Play color={colors.textSubtle} size={28} strokeWidth={1.5} />
    </View>
  );
}

function DownloadNotice({item, onRetry}: {item: OfflineReel; onRetry: () => void}) {
  const active = item.downloadState === 'queued' || item.downloadState === 'downloading';
  return (
    <View style={styles.downloadNotice}>
      {active ? (
        <DownloadCloud color={colors.white} size={18} strokeWidth={1.8} />
      ) : (
        <RefreshCw color={colors.white} size={17} strokeWidth={1.8} />
      )}
      <View style={styles.downloadCopy}>
        <Text style={styles.downloadTitle}>
          {active
            ? `Saving for offline · ${Math.round(item.downloadProgress * 100)}%`
            : item.downloadState === 'paused_low_storage'
              ? 'Paused — low storage'
              : 'Video unavailable offline'}
        </Text>
        {!active && item.downloadError ? (
          <Text numberOfLines={1} style={styles.downloadDetail}>
            {item.downloadError}
          </Text>
        ) : null}
      </View>
      {!active ? (
        <Pressable
          accessibilityLabel="Retry Reel download"
          onPress={onRetry}
          style={styles.retryDownload}>
          <Text style={styles.retryDownloadText}>Retry</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

function DirectAction({
  icon: Icon,
  label,
  onPress,
}: {
  icon: LucideIcon;
  label: string;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      onPress={onPress}
      style={({pressed}) => [styles.action, pressed && styles.pressed]}>
      <Icon color={colors.white} size={28} strokeWidth={1.9} />
    </Pressable>
  );
}

function OnlineAction({
  color = colors.white,
  count,
  filled = false,
  icon: Icon,
  label,
}: {
  color?: string;
  count?: number | null;
  filled?: boolean;
  icon: LucideIcon;
  label: string;
}) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      onPress={() => ToastAndroid.show(
        'Available on Instagram when connected',
        ToastAndroid.SHORT,
      )}
      style={({pressed}) => [styles.action, pressed && styles.pressed]}>
      <Icon color={color} fill={filled ? color : 'none'} size={29} strokeWidth={1.9} />
      {count !== undefined && count !== null ? (
        <Text style={styles.actionCount}>{formatCount(count)}</Text>
      ) : null}
    </Pressable>
  );
}

function localFileUri(path: string | null | undefined) {
  if (!path) return null;
  return path.startsWith('file://') ? path : `file://${path}`;
}

function audioLabel(item: OfflineReel) {
  const title = item.audioTitle || 'Original audio';
  const artist = item.audioArtistUsername || item.authorUsername;
  return artist ? `${artist} · ${title}` : title;
}

function formatCount(value: number) {
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 0 : 1)}M`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(value >= 100_000 ? 0 : 1)}K`;
  }
  return String(value);
}

const textShadow = {
  textShadowColor: colors.black,
  textShadowOffset: {height: 1, width: 0},
  textShadowRadius: 4,
} as const;

const styles = StyleSheet.create({
  action: {
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.sm,
    minHeight: 48,
    minWidth: 48,
  },
  actionCount: {
    ...textShadow,
    color: colors.white,
    fontSize: 10,
    fontWeight: '700',
    marginTop: -1,
  },
  actionRail: {alignItems: 'center', position: 'absolute', right: spacing.sm},
  audioRow: {alignItems: 'center', flexDirection: 'row', marginTop: spacing.md},
  audioText: {
    ...textShadow,
    color: colors.white,
    flexShrink: 1,
    fontSize: 11,
    fontWeight: '600',
    marginLeft: spacing.sm,
  },
  authorRow: {alignItems: 'center', flexDirection: 'row', marginBottom: spacing.sm},
  avatar: {
    alignItems: 'center',
    backgroundColor: colors.surfaceRaised,
    borderColor: colors.white,
    borderRadius: radii.round,
    borderWidth: 1.5,
    height: 36,
    justifyContent: 'center',
    overflow: 'hidden',
    width: 36,
  },
  avatarFallback: {color: colors.white, fontSize: 12, fontWeight: '800'},
  avatarImage: {height: 36, width: 36},
  bottomGradient: {
    bottom: 0,
    height: 228,
    left: 0,
    position: 'absolute',
    right: 0,
  },
  caption: {
    ...textShadow,
    color: colors.white,
    fontSize: 12,
    lineHeight: 17,
  },
  centerButton: {
    backgroundColor: colors.text,
    borderRadius: radii.sm,
    marginTop: spacing.xl,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
  },
  centerButtonText: {color: colors.black, fontSize: typography.body, fontWeight: '700'},
  centerCopy: {
    color: colors.textMuted,
    fontSize: typography.body,
    marginTop: spacing.md,
    textAlign: 'center',
  },
  centerState: {
    alignItems: 'center',
    backgroundColor: colors.canvas,
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: spacing.xxl,
  },
  centerTitle: {color: colors.text, fontSize: 18, fontWeight: '700'},
  container: {backgroundColor: colors.black, flex: 1, overflow: 'hidden'},
  downloadCopy: {flex: 1, marginLeft: spacing.sm, minWidth: 0},
  downloadDetail: {color: 'rgba(255,255,255,0.62)', fontSize: 9, marginTop: 2},
  downloadNotice: {
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.62)',
    borderRadius: radii.sm,
    flexDirection: 'row',
    marginTop: spacing.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  downloadTitle: {color: colors.white, fontSize: 10, fontWeight: '700'},
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    left: spacing.sm,
    position: 'absolute',
    right: spacing.sm,
    zIndex: 20,
  },
  headerIconButton: {
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.28)',
    borderRadius: radii.round,
    height: 48,
    justifyContent: 'center',
    width: 48,
  },
  headerStats: {
    ...textShadow,
    color: 'rgba(255,255,255,0.72)',
    fontSize: 9,
    marginTop: 1,
  },
  headerTitle: {
    ...textShadow,
    color: colors.white,
    fontSize: 17,
    fontWeight: '800',
  },
  headerTitleWrap: {flex: 1, marginLeft: spacing.sm, minWidth: 0},
  missingCover: {
    alignItems: 'center',
    backgroundColor: colors.reelFallback,
    justifyContent: 'center',
  },
  placeholderCircle: {
    alignItems: 'center',
    borderColor: colors.textSubtle,
    borderRadius: radii.round,
    borderWidth: 1,
    height: 58,
    justifyContent: 'center',
    width: 58,
  },
  player: {
    backgroundColor: colors.transparent,
    bottom: 0,
    left: 0,
    position: 'absolute',
    right: 0,
    top: 0,
  },
  pressed: {opacity: 0.58},
  reel: {backgroundColor: colors.black, overflow: 'hidden', width: '100%'},
  reelVideoVisible: {backgroundColor: colors.transparent},
  reelMeta: {left: spacing.lg, maxWidth: '78%', position: 'absolute', right: 74},
  retryDownload: {justifyContent: 'center', minHeight: 38, paddingLeft: spacing.md},
  retryDownloadText: {color: colors.white, fontSize: 11, fontWeight: '700'},
  speedButton: {
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.28)',
    borderRadius: radii.round,
    height: 48,
    justifyContent: 'center',
    marginHorizontal: spacing.xs,
    minWidth: 48,
    paddingHorizontal: spacing.sm,
  },
  speedButtonText: {color: colors.white, fontSize: 12, fontWeight: '800'},
  speedMenu: {
    backgroundColor: 'rgba(18,18,18,0.96)',
    borderRadius: radii.md,
    elevation: 14,
    overflow: 'hidden',
    position: 'absolute',
    right: 58,
    width: 76,
    zIndex: 30,
  },
  speedMenuItem: {
    alignItems: 'center',
    borderBottomColor: 'rgba(255,255,255,0.08)',
    borderBottomWidth: StyleSheet.hairlineWidth,
    height: 44,
    justifyContent: 'center',
  },
  speedMenuText: {color: colors.textMuted, fontSize: 12, fontWeight: '700'},
  speedMenuTextSelected: {color: colors.white},
  username: {
    ...textShadow,
    color: colors.white,
    flexShrink: 1,
    fontSize: 13,
    fontWeight: '700',
    marginLeft: spacing.sm,
  },
  verifiedBadge: {marginLeft: spacing.xs},
});

export default OfflineReelsSurface;
