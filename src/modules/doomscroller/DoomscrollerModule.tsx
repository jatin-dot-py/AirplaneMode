import React, { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, Pressable, StyleSheet, Text, View } from 'react-native';
import { ArrowLeft, Circle, Play, Square, X } from 'lucide-react-native';
import WebView from 'react-native-webview';
import type {
  AndroidWebViewProps,
  WebViewErrorEvent,
  WebViewMessageEvent,
  WebViewOpenWindowEvent,
} from 'react-native-webview/lib/WebViewTypes';

import {
  finishCaptureSession,
  getReelSnapshot,
  onDoomscrollChanged,
  saveCaptureBatch,
} from '../../native/DoomscrollEngine';
import { pausePlayback as pauseMediaPlayback } from '../../native/MediaEngine';
import { colors, radii, spacing, typography } from '../../theme';
import { DOOMSCROLL_CAPTURE_SCRIPT } from './captureScript';
import SnapshotLibrarySurface from './SnapshotLibrarySurface';
import type {
  CaptureState,
  CapturedPageMessage,
  DoomscrollStats,
  DoomscrollWebViewMessage,
} from './types';
import { emptyDoomscrollStats } from './types';

type Props = {
  onCapture: (snapshotId: string) => void;
  onOpenSnapshot: (snapshotId: string) => void;
};
type WebViewHandle = {
  goBack: () => void;
  injectJavaScript: (script: string) => void;
  reload: () => void;
};

const AndroidWebView = WebView as unknown as React.ForwardRefExoticComponent<
  AndroidWebViewProps & React.RefAttributes<WebViewHandle>
>;

// Instagram sometimes serves its unsupported-browser page to Android's default `; wv` UA.
const INSTAGRAM_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36';

function DoomscrollerModule({ onCapture, onOpenSnapshot }: Props) {
  return (
    <SnapshotLibrarySurface
      onCapture={onCapture}
      onOpen={snapshot => onOpenSnapshot(snapshot.id)}
    />
  );
}

export function InstagramCaptureSurface({
  initialUrl,
  onBack,
  onOpenSnapshot,
  snapshotId,
}: {
  initialUrl: string;
  onBack: () => void;
  onOpenSnapshot: () => void;
  snapshotId: string;
}) {
  const webViewRef = useRef<WebViewHandle>(null);
  const persistenceChainRef = useRef<Promise<void>>(Promise.resolve());
  const mountedRef = useRef(true);
  const finishPromiseRef = useRef<Promise<void> | null>(null);
  const captureActivityRef = useRef(false);
  const leavingRef = useRef(false);
  const captureStateRef = useRef<CaptureState>('idle');
  const canGoBackRef = useRef(false);
  const [requestedUrl, setRequestedUrl] = useState(initialUrl);
  const [currentUrl, setCurrentUrl] = useState(initialUrl);
  const [loadProgress, setLoadProgress] = useState(0);
  const [loadFailed, setLoadFailed] = useState(false);
  const [captureState, setCaptureState] = useState<CaptureState>('idle');
  const [captureDetail, setCaptureDetail] = useState<string | null>(null);
  const [templateReady, setTemplateReady] = useState(false);
  const [stats, setStats] = useState<DoomscrollStats>(emptyDoomscrollStats);

  useEffect(() => {
    captureStateRef.current = captureState;
  }, [captureState]);

  const injectCommand = useCallback((command: string) => {
    webViewRef.current?.injectJavaScript(
      `window.__airplaneModeDoomscroll && window.__airplaneModeDoomscroll.${command}; true;`,
    );
  }, []);

  const refreshSnapshot = useCallback(async () => {
    const snapshot = await getReelSnapshot(snapshotId).catch(() => null);
    if (snapshot && mountedRef.current) setStats(snapshot);
  }, [snapshotId]);

  const finishOnce = useCallback(
    (reason: string) => {
      if (!finishPromiseRef.current) {
        finishPromiseRef.current = persistenceChainRef.current
          .catch(() => undefined)
          .then(() => finishCaptureSession(snapshotId, reason))
          .then(() => undefined)
          .catch(() => undefined);
      }
      return finishPromiseRef.current;
    },
    [snapshotId],
  );

  useEffect(() => {
    mountedRef.current = true;
    pauseMediaPlayback().catch(() => undefined);
    refreshSnapshot().catch(() => undefined);
    const changed = onDoomscrollChanged(refreshSnapshot);
    return () => {
      mountedRef.current = false;
      changed?.remove();
      injectCommand('stop()');
      finishOnce('capture-closed').catch(() => undefined);
    };
  }, [finishOnce, injectCommand, refreshSnapshot]);

  const acknowledge = useCallback(
    (batchId: string, canContinue: boolean, stopReason: string | null) => {
      injectCommand(
        `ack(${JSON.stringify(batchId)}, ${JSON.stringify({
          canContinue,
          stopReason,
        })})`,
      );
    },
    [injectCommand],
  );

  const persistPage = useCallback(
    async (message: CapturedPageMessage) => {
      try {
        if (finishPromiseRef.current) {
          acknowledge(message.batchId, false, 'snapshot-finished');
          return;
        }
        captureActivityRef.current = true;
        const result = await saveCaptureBatch(
          snapshotId,
          message.pageIndex,
          message.reels,
        );
        if (!mountedRef.current) return;
        setStats(result);
        setTemplateReady(message.templateReady);
        acknowledge(message.batchId, result.canContinue, result.stopReason);
        if (!result.canContinue) {
          setCaptureState(
            result.stopReason === 'low-storage' ? 'low-storage' : 'error',
          );
          setCaptureDetail(
            result.stopReason === 'low-storage'
              ? 'Free up storage to continue. Queued downloads remain safe.'
              : result.stopReason,
          );
        }
      } catch (reason) {
        const detail =
          reason instanceof Error
            ? reason.message
            : 'The captured page could not be saved.';
        if (mountedRef.current) {
          setCaptureState('error');
          setCaptureDetail(detail);
        }
        acknowledge(message.batchId, false, 'persistence-error');
      }
    },
    [acknowledge, snapshotId],
  );

  const handleMessage = useCallback(
    (event: WebViewMessageEvent) => {
      const message = parseWebViewMessage(event.nativeEvent.data);
      if (!message) return;
      if (message.type === 'page') {
        persistenceChainRef.current = persistenceChainRef.current
          .catch(() => undefined)
          .then(() => persistPage(message));
        return;
      }
      if (message.type === 'state') {
        setCaptureState(message.state);
        setCaptureDetail(message.detail);
        setTemplateReady(message.templateReady);
        if (isTerminalCaptureState(message.state)) {
          finishOnce(message.state).catch(() => undefined);
        }
        return;
      }
      const authRequired = message.code === 'auth-required';
      setCaptureState(authRequired ? 'auth-required' : 'error');
      setCaptureDetail(message.message);
      if (authRequired) finishOnce('auth-required').catch(() => undefined);
    },
    [finishOnce, persistPage],
  );

  const updateNavigation = useCallback(
    (url: string) => {
      setCurrentUrl(url);
      if (!isAuthenticationPage(url)) return;

      if (captureActivityRef.current) {
        injectCommand('stop()');
        finishOnce('auth-required').catch(() => undefined);
        setCaptureState('auth-required');
        setCaptureDetail(
          'Instagram ended this capture. Sign in again, then create a new snapshot.',
        );
      }
    },
    [finishOnce, injectCommand],
  );

  const startFetching = useCallback(() => {
    if (!templateReady || finishPromiseRef.current) return;
    captureActivityRef.current = true;
    setCaptureState('fetching');
    setCaptureDetail(null);
    injectCommand('start()');
  }, [injectCommand, templateReady]);

  const stopFetching = useCallback(() => {
    setCaptureState('stopping');
    injectCommand('stop()');
  }, [injectCommand]);

  const leaveCapture = useCallback(
    (openSnapshot: boolean) => {
      if (leavingRef.current) return;
      leavingRef.current = true;
      injectCommand('stop()');
      finishOnce(openSnapshot ? 'view-snapshot' : 'capture-closed').then(() => {
        if (!mountedRef.current) return;
        if (openSnapshot) onOpenSnapshot();
        else onBack();
      });
    },
    [finishOnce, injectCommand, onBack, onOpenSnapshot],
  );

  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        if (
          captureStateRef.current === 'fetching' ||
          captureStateRef.current === 'stopping'
        ) {
          leaveCapture(false);
          return true;
        }
        if (canGoBackRef.current) {
          webViewRef.current?.goBack();
          return true;
        }
        leaveCapture(false);
        return true;
      },
    );
    return () => subscription.remove();
  }, [leaveCapture]);

  const handleOpenWindow = useCallback((event: WebViewOpenWindowEvent) => {
    const target = event.nativeEvent.targetUrl;
    if (isWebNavigation(target)) setRequestedUrl(target);
  }, []);

  const handleError = useCallback(
    (_event: WebViewErrorEvent) => setLoadFailed(true),
    [],
  );
  const retryPage = useCallback(() => {
    setLoadFailed(false);
    setLoadProgress(0);
    webViewRef.current?.reload();
  }, []);

  const overlay = captureOverlayModel({
    captureDetail,
    captureState: isAuthenticationPage(currentUrl)
      ? 'auth-required'
      : captureState,
    onReelsPage: isReelsPage(currentUrl),
    stats,
    templateReady,
  });

  return (
    <View style={styles.container}>
      <View pointerEvents="none" style={styles.progressTrack}>
        <View
          style={[
            styles.progress,
            loadProgress >= 1 && styles.progressComplete,
            { width: `${Math.max(loadProgress * 100, 2)}%` },
          ]}
        />
      </View>

      <AndroidWebView
        allowsFullscreenVideo
        cacheEnabled
        domStorageEnabled
        injectedJavaScript={DOOMSCROLL_CAPTURE_SCRIPT}
        injectedJavaScriptBeforeContentLoaded={DOOMSCROLL_CAPTURE_SCRIPT}
        javaScriptCanOpenWindowsAutomatically
        javaScriptEnabled
        mediaPlaybackRequiresUserAction
        onError={handleError}
        onLoadProgress={event => setLoadProgress(event.nativeEvent.progress)}
        onLoadStart={event => {
          updateNavigation(event.nativeEvent.url);
          setLoadFailed(false);
          setLoadProgress(0);
        }}
        onMessage={handleMessage}
        onNavigationStateChange={state => {
          canGoBackRef.current = state.canGoBack;
          updateNavigation(state.url);
        }}
        onOpenWindow={handleOpenWindow}
        ref={webViewRef}
        setSupportMultipleWindows={false}
        source={{ uri: requestedUrl }}
        thirdPartyCookiesEnabled
        userAgent={INSTAGRAM_USER_AGENT}
      />

      {!loadFailed ? (
        <CaptureStatusPill
          model={overlay}
          onBack={() => leaveCapture(false)}
          onPrimary={() => {
            if (overlay.primaryAction === 'start') startFetching();
            else if (overlay.primaryAction === 'stop') stopFetching();
            else if (overlay.primaryAction === 'view') leaveCapture(true);
            else leaveCapture(false);
          }}
          onView={() => leaveCapture(true)}
          stats={stats}
        />
      ) : null}

      {loadFailed ? (
        <View style={styles.errorSurface}>
          <Text style={styles.errorTitle}>Instagram didn’t load</Text>
          <Text style={styles.errorCopy}>
            The unfinished snapshot is safe. Check your connection and retry.
          </Text>
          <View style={styles.errorActions}>
            <Pressable
              accessibilityLabel="Return to snapshots"
              accessibilityRole="button"
              onPress={() => leaveCapture(false)}
              style={({ pressed }) => [
                styles.secondaryButton,
                pressed && styles.pressed,
              ]}
            >
              <Text style={styles.secondaryButtonText}>Snapshots</Text>
            </Pressable>
            <Pressable
              accessibilityLabel="Retry Instagram"
              accessibilityRole="button"
              onPress={retryPage}
              style={({ pressed }) => [
                styles.primaryButton,
                pressed && styles.pressed,
              ]}
            >
              <Text style={styles.primaryButtonText}>Try again</Text>
            </Pressable>
          </View>
        </View>
      ) : null}
    </View>
  );
}

type OverlayModel = {
  detail: string;
  live: boolean;
  primaryAction: 'back' | 'start' | 'stop' | 'view';
  primaryLabel: string;
  title: string;
};

function CaptureStatusPill({
  model,
  onBack,
  onPrimary,
  onView,
  stats,
}: {
  model: OverlayModel;
  onBack: () => void;
  onPrimary: () => void;
  onView: () => void;
  stats: DoomscrollStats;
}) {
  return (
    <View style={styles.capturePill}>
      <Pressable
        accessibilityLabel="Close Instagram capture"
        accessibilityRole="button"
        onPress={onBack}
        style={({ pressed }) => [styles.closeButton, pressed && styles.pressed]}
      >
        <X color={colors.white} size={20} strokeWidth={1.9} />
      </Pressable>
      <View style={styles.captureCopy}>
        <View style={styles.captureTitleRow}>
          {model.live ? (
            <Circle color={colors.accent} fill={colors.accent} size={7} />
          ) : null}
          <Text
            numberOfLines={1}
            style={[styles.captureTitle, model.live && styles.captureTitleLive]}
          >
            {model.title}
          </Text>
        </View>
        <Text numberOfLines={1} style={styles.captureDetail}>
          {model.detail}
        </Text>
      </View>
      {stats.capturedCount > 0 && model.primaryAction !== 'view' ? (
        <Pressable
          accessibilityLabel="View this captured snapshot"
          accessibilityRole="button"
          onPress={onView}
          style={({ pressed }) => [
            styles.captureSecondary,
            pressed && styles.pressed,
          ]}
        >
          <Text style={styles.captureSecondaryText}>View</Text>
        </Pressable>
      ) : null}
      <Pressable
        accessibilityLabel={model.primaryLabel}
        accessibilityRole="button"
        onPress={onPrimary}
        style={({ pressed }) => [
          styles.capturePrimary,
          model.primaryAction === 'stop' && styles.captureStop,
          pressed && styles.pressed,
        ]}
      >
        {model.primaryAction === 'stop' ? (
          <Square
            color={colors.white}
            fill={colors.white}
            size={10}
            strokeWidth={1.5}
          />
        ) : model.primaryAction === 'start' ? (
          <Play
            color={colors.black}
            fill={colors.black}
            size={12}
            strokeWidth={1.5}
          />
        ) : model.primaryAction === 'view' ? (
          <Play
            color={colors.black}
            fill={colors.black}
            size={12}
            strokeWidth={1.5}
          />
        ) : (
          <ArrowLeft color={colors.black} size={13} strokeWidth={2} />
        )}
        <Text style={styles.capturePrimaryText}>{model.primaryLabel}</Text>
      </Pressable>
    </View>
  );
}

function captureOverlayModel({
  captureDetail,
  captureState,
  onReelsPage,
  stats,
  templateReady,
}: {
  captureDetail: string | null;
  captureState: CaptureState;
  onReelsPage: boolean;
  stats: DoomscrollStats;
  templateReady: boolean;
}): OverlayModel {
  const counts = `${stats.capturedCount} captured · ${stats.readyCount} downloaded`;
  if (captureState === 'fetching' || captureState === 'stopping') {
    return {
      detail:
        captureState === 'stopping' ? 'Finishing the current page…' : counts,
      live: true,
      primaryAction: 'stop',
      primaryLabel: 'Stop',
      title: 'Capturing Reels',
    };
  }
  if (captureState === 'ready' && templateReady) {
    return {
      detail: counts,
      live: false,
      primaryAction: 'start',
      primaryLabel: 'Start',
      title: 'Ready to fetch',
    };
  }
  if (captureState === 'awaiting-pagination') {
    return {
      detail: 'Swipe once in Reels to expose the next-page request',
      live: false,
      primaryAction: 'back',
      primaryLabel: 'Close',
      title: 'First page saved',
    };
  }
  if (captureState === 'auth-required') {
    return {
      detail: 'Sign in above; credentials never leave this WebView',
      live: false,
      primaryAction: 'back',
      primaryLabel: 'Cancel',
      title: 'Sign in to Instagram',
    };
  }
  if (captureState === 'low-storage') {
    return {
      detail: 'The 1 GiB safety reserve was reached',
      live: false,
      primaryAction: stats.capturedCount > 0 ? 'view' : 'back',
      primaryLabel: stats.capturedCount > 0 ? 'View' : 'Back',
      title: 'Capture paused',
    };
  }
  if (captureState === 'error' || captureState === 'rate-limited') {
    return {
      detail: captureDetail || 'Pagination stopped safely',
      live: false,
      primaryAction: stats.capturedCount > 0 ? 'view' : 'back',
      primaryLabel: stats.capturedCount > 0 ? 'View' : 'Back',
      title:
        captureState === 'rate-limited'
          ? 'Instagram rate limit'
          : 'Capture stopped',
    };
  }
  if (captureState === 'complete' || captureState === 'stopped') {
    return {
      detail: counts,
      live: false,
      primaryAction: stats.capturedCount > 0 ? 'view' : 'back',
      primaryLabel: stats.capturedCount > 0 ? 'View' : 'Back',
      title:
        captureState === 'complete' ? 'Snapshot complete' : 'Snapshot stopped',
    };
  }
  if (!onReelsPage) {
    return {
      detail: 'Navigate to Reels in Instagram when you are ready',
      live: false,
      primaryAction: 'back',
      primaryLabel: 'Close',
      title: 'New snapshot',
    };
  }
  return {
    detail:
      stats.capturedCount > 0
        ? counts
        : 'Waiting for Instagram’s first Reels response…',
    live: false,
    primaryAction: 'back',
    primaryLabel: 'Close',
    title: 'Preparing snapshot',
  };
}

function parseWebViewMessage(value: string): DoomscrollWebViewMessage | null {
  try {
    const parsed = JSON.parse(value) as Partial<DoomscrollWebViewMessage>;
    if (parsed.channel !== 'airplanemode-doomscroll' || parsed.version !== 2)
      return null;
    if (parsed.type === 'page') {
      if (
        typeof parsed.batchId !== 'string' ||
        typeof parsed.pageIndex !== 'number' ||
        !Array.isArray(parsed.reels)
      )
        return null;
      return parsed as CapturedPageMessage;
    }
    if (parsed.type === 'state' || parsed.type === 'error')
      return parsed as DoomscrollWebViewMessage;
    return null;
  } catch {
    return null;
  }
}

function isTerminalCaptureState(state: CaptureState) {
  return [
    'complete',
    'stopped',
    'auth-required',
    'rate-limited',
    'low-storage',
    'error',
  ].includes(state);
}

function isReelsPage(value: string) {
  try {
    return /^\/reels(?:\/|$)/.test(new URL(value).pathname);
  } catch {
    return false;
  }
}

function isAuthenticationPage(value: string) {
  try {
    const path = new URL(value).pathname;
    return /\/(?:accounts\/login|challenge|checkpoint)(?:\/|$)/.test(path);
  } catch {
    return false;
  }
}

function isWebNavigation(value: string) {
  if (value === 'about:blank') return true;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

const styles = StyleSheet.create({
  container: { backgroundColor: colors.canvas, flex: 1 },
  progressTrack: {
    backgroundColor: colors.surface,
    height: 2,
    left: 0,
    position: 'absolute',
    right: 0,
    top: 0,
    zIndex: 30,
  },
  progress: { backgroundColor: colors.accent, height: 2 },
  progressComplete: { opacity: 0 },
  capturePill: {
    alignItems: 'center',
    backgroundColor: 'rgba(18,18,18,0.94)',
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: radii.md,
    borderWidth: StyleSheet.hairlineWidth,
    bottom: spacing.md,
    elevation: 16,
    flexDirection: 'row',
    left: spacing.sm,
    minHeight: 58,
    paddingRight: spacing.xs,
    position: 'absolute',
    right: spacing.sm,
    zIndex: 20,
  },
  closeButton: {
    alignItems: 'center',
    height: 48,
    justifyContent: 'center',
    width: 42,
  },
  captureCopy: { flex: 1, minWidth: 0, paddingLeft: spacing.xs },
  captureTitle: { color: colors.white, fontSize: 11, fontWeight: '700' },
  captureTitleLive: { marginLeft: spacing.xs },
  captureTitleRow: { alignItems: 'center', flexDirection: 'row' },
  captureDetail: { color: 'rgba(255,255,255,0.62)', fontSize: 9, marginTop: 3 },
  capturePrimary: {
    alignItems: 'center',
    backgroundColor: colors.white,
    borderRadius: radii.sm,
    flexDirection: 'row',
    gap: spacing.xs,
    justifyContent: 'center',
    marginLeft: spacing.xs,
    minHeight: 42,
    paddingHorizontal: spacing.md,
  },
  captureStop: { backgroundColor: colors.accent },
  capturePrimaryText: { color: colors.black, fontSize: 10, fontWeight: '800' },
  captureSecondary: {
    justifyContent: 'center',
    minHeight: 42,
    paddingHorizontal: spacing.sm,
  },
  captureSecondaryText: {
    color: colors.white,
    fontSize: 10,
    fontWeight: '700',
  },
  errorSurface: {
    alignItems: 'center',
    backgroundColor: colors.canvas,
    bottom: 0,
    justifyContent: 'center',
    left: 0,
    paddingHorizontal: spacing.xxl,
    position: 'absolute',
    right: 0,
    top: 2,
  },
  errorTitle: {
    color: colors.text,
    fontSize: typography.title,
    fontWeight: '800',
  },
  errorCopy: {
    color: colors.textMuted,
    fontSize: typography.body,
    lineHeight: 19,
    marginTop: spacing.sm,
    maxWidth: 380,
    textAlign: 'center',
  },
  errorActions: { flexDirection: 'row', marginTop: spacing.xl },
  primaryButton: {
    backgroundColor: colors.text,
    borderRadius: radii.sm,
    minWidth: 112,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },
  primaryButtonText: {
    color: colors.black,
    fontSize: typography.body,
    fontWeight: '900',
    textAlign: 'center',
  },
  secondaryButton: {
    borderColor: colors.border,
    borderRadius: radii.sm,
    borderWidth: 1,
    marginRight: spacing.sm,
    minWidth: 112,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },
  secondaryButtonText: {
    color: colors.text,
    fontSize: typography.body,
    fontWeight: '800',
    textAlign: 'center',
  },
  pressed: { opacity: 0.62 },
});

export default DoomscrollerModule;
