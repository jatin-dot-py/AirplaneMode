import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator,
  BackHandler,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {useIsFocused} from '@react-navigation/native';
import WebView from 'react-native-webview';
import type {
  AndroidWebViewProps,
  WebViewMessageEvent,
  WebViewNavigation,
  WebViewOpenWindowEvent,
} from 'react-native-webview/lib/WebViewTypes';

import {queueYouTubeItems, type DetectedMedia} from '../../native/MediaEngine';
import {colors, radii, spacing, typography} from '../../theme';

type WebViewHandle = {goBack: () => void};

type DetectionMessage = {
  count: number;
  currentItem: DetectedMedia | null;
  currentVideoId: string | null;
  items: DetectedMedia[];
  reset: boolean;
  route: string;
  type: 'airplanemode-media-detection';
};

type Props = {
  homeUrl: string;
  onLibraryChanged: () => void;
};

const AndroidWebView = WebView as unknown as React.ForwardRefExoticComponent<
  AndroidWebViewProps & React.RefAttributes<WebViewHandle>
>;

export const PAGE_SCANNER = String.raw`
  (function () {
    if (window.__airplaneModeMediaScanner) {
      window.__airplaneModeMediaScanner.scan();
      return true;
    }

    var state = {
      route: '',
      items: new Map(),
      sent: new Map(),
      lastCount: -1,
      lastCurrentSignature: '',
      timer: null
    };

    function clean(value) {
      return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
    }

    function routeKey() {
      return window.location.origin + window.location.pathname + window.location.search;
    }

    function videoIdFrom(value) {
      if (!value) return null;
      if (/^[a-zA-Z0-9_-]{11}$/.test(value)) return value;
      try {
        var parsed = new URL(value, window.location.href);
        var direct = parsed.searchParams.get('v');
        if (direct && /^[a-zA-Z0-9_-]{11}$/.test(direct)) return direct;
        var shortId = parsed.hostname === 'youtu.be' ? parsed.pathname.slice(1).split('/')[0] : '';
        if (/^[a-zA-Z0-9_-]{11}$/.test(shortId)) return shortId;
      } catch (_) {}
      var encoded = String(value).match(/(?:watch%3Fv%3D|watch%3Fv=)([a-zA-Z0-9_-]{11})/i);
      return encoded ? encoded[1] : null;
    }

    function pageTitle() {
      var header = document.querySelector(
        'ytmusic-detail-header-renderer h2, ytmusic-immersive-header-renderer h2, ytmusic-header-renderer h2'
      );
      return clean(header && (header.getAttribute('title') || header.textContent)) ||
        clean(document.title).replace(/\s*-\s*YouTube Music\s*$/i, '') ||
        'YouTube Music';
    }

    function nearbyRoot(element) {
      return element.closest(
        'ytmusic-responsive-list-item-renderer, ytmusic-two-row-item-renderer, ytmusic-player-queue-item, ytmusic-watch-playlist-panel-video-renderer, ytmusic-player-bar, ytmusic-grid-renderer'
      ) || element.parentElement || element;
    }

    function firstText(root, selectors) {
      for (var index = 0; index < selectors.length; index++) {
        var node = root && root.querySelector ? root.querySelector(selectors[index]) : null;
        var value = clean(node && (node.getAttribute('title') || node.getAttribute('aria-label') || node.textContent));
        if (value) return value;
      }
      return '';
    }

    function normalizeImageUrl(value) {
      if (!value || typeof value !== 'string') return '';
      try {
        var parsed = new URL(value.trim(), window.location.href);
        return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : '';
      } catch (_) {
        return '';
      }
    }

    function srcsetUrls(value) {
      if (!value) return [];
      return value.split(',').map(function (part) {
        var bits = part.trim().split(/\s+/);
        var size = parseInt((bits[1] || '0').replace(/[^0-9]/g, ''), 10) || 0;
        return {url: normalizeImageUrl(bits[0]), size: size};
      }).filter(function (candidate) {
        return Boolean(candidate.url);
      }).sort(function (left, right) {
        return right.size - left.size;
      }).map(function (candidate) {
        return candidate.url;
      });
    }

    function artworkCandidates(root, videoId) {
      var values = [];
      function add(value) {
        var normalized = normalizeImageUrl(value);
        if (normalized && values.indexOf(normalized) === -1) values.push(normalized);
      }
      function addImage(image) {
        srcsetUrls(image.getAttribute('srcset')).forEach(add);
        add(image.currentSrc);
        add(image.getAttribute('data-src'));
        add(image.getAttribute('data-thumb'));
        add(image.getAttribute('src'));
      }

      var images = Array.from((root || document).querySelectorAll('img'));
      if (root && root.matches && root.matches('img')) images.unshift(root);
      images.forEach(addImage);
      document.querySelectorAll('ytmusic-player-bar img, #song-image img, #thumbnail img').forEach(addImage);
      Array.from((root || document).querySelectorAll('[style*="background-image"]')).forEach(function (styled) {
        var background = styled && styled.style && styled.style.backgroundImage;
        var match = background && background.match(/url\(["']?([^"')]+)["']?\)/i);
        add(match && match[1]);
      });
      var openGraph = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
      add(openGraph && openGraph.getAttribute('content'));
      add('https://i.ytimg.com/vi/' + videoId + '/maxresdefault.jpg');
      add('https://i.ytimg.com/vi/' + videoId + '/hqdefault.jpg');
      return values.slice(0, 16);
    }

    function mediaObject(videoId, root, element) {
      var title = clean(element && element.getAttribute &&
        (element.getAttribute('title') || element.getAttribute('aria-label')));
      if (!title || /^(play|pause|menu|more)$/i.test(title)) {
        title = firstText(root, [
          '#video-title', '.title', 'yt-formatted-string.title',
          'a[title][href*="watch"]', 'yt-formatted-string[title]'
        ]);
      }
      var artist = firstText(root, [
        '.secondary-flex-columns yt-formatted-string', '.byline',
        '.subtitle yt-formatted-string', '#byline', '.secondary-flex-columns a'
      ]);
      var existing = state.items.get(videoId) || {};
      var candidates = artworkCandidates(root, videoId);
      return {
        videoId: videoId,
        title: title || existing.title || ('YouTube Music item ' + videoId.slice(0, 6)),
        artist: artist || existing.artist || null,
        sourceUrl: 'https://music.youtube.com/watch?v=' + videoId,
        thumbnailCandidates: candidates.length ? candidates : (existing.thumbnailCandidates || []),
        thumbnailUrl: candidates[0] || existing.thumbnailUrl || null,
        route: state.route,
        collectionName: pageTitle()
      };
    }

    function collect(element, candidate) {
      var id = videoIdFrom(candidate);
      if (!id) return;
      state.items.set(id, mediaObject(id, nearbyRoot(element), element));
    }

    function currentMedia() {
      var id = videoIdFrom(window.location.href);
      if (!id) return null;
      var existing = state.items.get(id);
      if (existing) return existing;
      var player = document.querySelector('ytmusic-player-bar') || document;
      var title = firstText(player, ['.title', 'yt-formatted-string.title', '[title]']) ||
        clean(document.title).replace(/\s*-\s*YouTube Music\s*$/i, '');
      var item = mediaObject(id, player, null);
      if (title) item.title = title;
      return item;
    }

    function scan() {
      var nextRoute = routeKey();
      var reset = state.route !== nextRoute;
      if (reset) {
        state.route = nextRoute;
        state.items = new Map();
        state.sent = new Map();
        state.lastCount = -1;
      }

      document.querySelectorAll('a[href]').forEach(function (anchor) {
        collect(anchor, anchor.getAttribute('href'));
      });
      document.querySelectorAll('[data-video-id]').forEach(function (element) {
        collect(element, element.getAttribute('data-video-id'));
      });

      var current = currentMedia();
      if (current) state.items.set(current.videoId, current);
      var currentSignature = JSON.stringify(current);
      var changed = [];
      state.items.forEach(function (item, id) {
        var signature = JSON.stringify(item);
        if (state.sent.get(id) !== signature) {
          state.sent.set(id, signature);
          changed.push(item);
        }
      });

      if (reset || changed.length || state.lastCount !== state.items.size ||
          state.lastCurrentSignature !== currentSignature) {
        state.lastCount = state.items.size;
        state.lastCurrentSignature = currentSignature;
        window.ReactNativeWebView.postMessage(JSON.stringify({
          type: 'airplanemode-media-detection',
          count: state.items.size,
          currentVideoId: current ? current.videoId : null,
          currentItem: current,
          items: changed,
          reset: reset,
          route: state.route
        }));
      }
    }

    function scheduleScan() {
      window.clearTimeout(state.timer);
      state.timer = window.setTimeout(scan, 180);
    }

    var observer = new MutationObserver(scheduleScan);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['href', 'data-video-id', 'src', 'srcset', 'style', 'title'],
      childList: true,
      characterData: true,
      subtree: true
    });
    window.addEventListener('hashchange', scheduleScan);
    window.addEventListener('popstate', scheduleScan);
    window.addEventListener('scroll', scheduleScan, {passive: true});
    window.setInterval(scan, 1500);

    window.__airplaneModeMediaScanner = {scan: scan};
    scan();
    return true;
  })();
`;

function YouTubeMusicConnector({homeUrl, onLibraryChanged}: Props) {
  const webViewRef = useRef<WebViewHandle>(null);
  const canGoBackRef = useRef(false);
  const focused = useIsFocused();
  const routeRef = useRef('');
  const detectedRef = useRef(new Map<string, DetectedMedia>());
  const [detected, setDetected] = useState<DetectedMedia[]>([]);
  const [currentItem, setCurrentItem] = useState<DetectedMedia | null>(null);
  const [requestedUrl, setRequestedUrl] = useState(homeUrl);
  const [loadProgress, setLoadProgress] = useState(0);
  const [adding, setAdding] = useState<'current' | 'all' | null>(null);
  const [addProgress, setAddProgress] = useState(0);

  const handleMessage = useCallback((event: WebViewMessageEvent) => {
    try {
      const message = JSON.parse(event.nativeEvent.data) as DetectionMessage;
      if (message.type !== 'airplanemode-media-detection') return;
      if (message.reset || routeRef.current !== message.route) {
        routeRef.current = message.route;
        detectedRef.current = new Map();
      }
      message.items.forEach(item => detectedRef.current.set(item.videoId, item));
      setDetected(Array.from(detectedRef.current.values()));
      setCurrentItem(message.currentItem);
    } catch {
      // Other scripts hosted by the site may also use postMessage.
    }
  }, []);

  const onNavigation = useCallback((state: WebViewNavigation) => {
    canGoBackRef.current = state.canGoBack;
    if (!videoIdFromUrl(state.url)) setCurrentItem(null);
  }, []);

  useEffect(() => {
    if (!focused) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (!canGoBackRef.current) return false;
      webViewRef.current?.goBack();
      return true;
    });
    return () => subscription.remove();
  }, [focused]);

  const onOpenWindow = useCallback((event: WebViewOpenWindowEvent) => {
    const target = event.nativeEvent.targetUrl;
    if (isAllowedUrl(target)) setRequestedUrl(target);
  }, []);

  const addItems = useCallback(async (
    mode: 'current' | 'all',
    items: DetectedMedia[],
  ) => {
    if (!items.length || adding) return;
    setAdding(mode);
    setAddProgress(0);
    try {
      if (mode === 'all') {
        for (let index = 0; index < items.length; index++) {
          await queueYouTubeItems('youtube-music', [items[index]]);
          setAddProgress(index + 1);
        }
      } else {
        await queueYouTubeItems('youtube-music', items);
      }
      onLibraryChanged();
    } finally {
      setAdding(null);
    }
  }, [adding, onLibraryChanged]);

  const allLabel = useMemo(
    () => adding === 'all'
      ? `Adding ${addProgress} of ${detected.length}…`
      : `Add ${detected.length} detected ${detected.length === 1 ? 'song' : 'songs'}`,
    [addProgress, adding, detected.length],
  );

  return (
    <View style={styles.container}>
      <View style={styles.progressTrack}>
        <View
          style={[
            styles.progress,
            loadProgress >= 1 && styles.progressComplete,
            {width: `${Math.max(loadProgress * 100, 2)}%`},
          ]}
        />
      </View>

      <AndroidWebView
        domStorageEnabled
        injectedJavaScript={PAGE_SCANNER}
        javaScriptCanOpenWindowsAutomatically
        javaScriptEnabled
        onLoadProgress={event => setLoadProgress(event.nativeEvent.progress)}
        onMessage={handleMessage}
        onNavigationStateChange={onNavigation}
        onOpenWindow={onOpenWindow}
        onShouldStartLoadWithRequest={request => isAllowedUrl(request.url)}
        ref={webViewRef}
        setSupportMultipleWindows={false}
        source={{uri: requestedUrl}}
        thirdPartyCookiesEnabled
      />

      {(currentItem || detected.length > 0) ? (
        <View style={styles.actionOverlay}>
          {currentItem ? (
            <Action
              disabled={adding !== null}
              label={adding === 'current' ? 'Adding current song…' : 'Add current song'}
              loading={adding === 'current'}
              onPress={() => addItems('current', [currentItem])}
            />
          ) : null}
          {currentItem && detected.length > 0 ? <View style={styles.segmentDivider} /> : null}
          {detected.length > 0 ? (
            <Action
              disabled={adding !== null}
              label={allLabel}
              loading={adding === 'all'}
              onPress={() => addItems('all', detected)}
            />
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

function Action({disabled, label, loading, onPress}: {
  disabled: boolean;
  label: string;
  loading: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={({pressed}) => [styles.action, pressed && styles.pressed]}>
      {loading ? <ActivityIndicator color={colors.text} size="small" /> : null}
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}

function videoIdFromUrl(url: string) {
  try {
    const value = new URL(url).searchParams.get('v');
    return value && /^[a-zA-Z0-9_-]{11}$/.test(value) ? value : null;
  } catch {
    return null;
  }
}

function hostname(url: string) {
  try {
    return new URL(url).hostname;
  } catch {
    return '';
  }
}

function isAllowedUrl(url: string) {
  if (url === 'about:blank') return true;
  const host = hostname(url);
  return isHost(host, 'google.com') || isHost(host, 'youtube.com');
}

function isHost(host: string, allowedHost: string) {
  return host === allowedHost || host.endsWith(`.${allowedHost}`);
}

const styles = StyleSheet.create({
  container: {backgroundColor: colors.canvas, flex: 1},
  progressTrack: {backgroundColor: colors.surface, height: 2},
  progress: {backgroundColor: colors.accent, height: 2},
  progressComplete: {opacity: 0},
  actionOverlay: {
    alignItems: 'stretch',
    backgroundColor: colors.scrimStrong,
    borderColor: colors.border,
    borderRadius: radii.round,
    borderWidth: StyleSheet.hairlineWidth,
    bottom: spacing.md,
    flexDirection: 'row',
    overflow: 'hidden',
    position: 'absolute',
    right: spacing.md,
  },
  action: {
    alignItems: 'center',
    flexDirection: 'row',
    height: 40,
    justifyContent: 'center',
    minWidth: 78,
    paddingHorizontal: spacing.md,
  },
  actionText: {color: colors.text, fontSize: typography.caption, fontWeight: '700'},
  segmentDivider: {backgroundColor: colors.border, width: StyleSheet.hairlineWidth},
  pressed: {backgroundColor: colors.surfaceRaised},
});

export default YouTubeMusicConnector;
