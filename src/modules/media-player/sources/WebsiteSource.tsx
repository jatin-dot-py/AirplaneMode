import React, {useCallback, useEffect, useRef, useState} from 'react';
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

import {queueYouTubeItems, type DetectedMedia} from '../../../native/MediaEngine';
import {colors, radii, spacing, typography} from '../../../theme';

type Props = {
  homeUrl: string;
  onLibraryChanged: () => void;
};

type CurrentVideoMessage = {
  item: DetectedMedia | null;
  type: 'airplanemode-current-youtube-video';
};

type WebViewHandle = {goBack: () => void};

const AndroidWebView = WebView as unknown as React.ForwardRefExoticComponent<
  AndroidWebViewProps & React.RefAttributes<WebViewHandle>
>;

export const CURRENT_YOUTUBE_SCANNER = String.raw`
  (function () {
    if (window.__airplaneModeCurrentVideoScanner) {
      window.__airplaneModeCurrentVideoScanner.scan();
      return true;
    }
    var lastSignature = '';
    var timer = null;

    function clean(value) {
      return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
    }

    function videoId() {
      try {
        var direct = new URL(window.location.href).searchParams.get('v');
        return direct && /^[a-zA-Z0-9_-]{11}$/.test(direct) ? direct : null;
      } catch (_) {
        return null;
      }
    }

    function normalize(value) {
      if (!value) return '';
      try {
        var parsed = new URL(value, window.location.href);
        return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : '';
      } catch (_) {
        return '';
      }
    }

    function scan() {
      var id = videoId();
      var item = null;
      if (id) {
        var candidates = [];
        function add(value) {
          var normalized = normalize(value);
          if (normalized && candidates.indexOf(normalized) === -1) candidates.push(normalized);
        }
        document.querySelectorAll('meta[property="og:image"], meta[name="twitter:image"], link[rel="image_src"]').forEach(function (node) {
          add(node.getAttribute('content') || node.getAttribute('href'));
        });
        document.querySelectorAll('#player img, ytd-watch-flexy img, video + img').forEach(function (image) {
          var srcset = image.getAttribute('srcset') || '';
          srcset.split(',').reverse().forEach(function (part) { add(part.trim().split(/\s+/)[0]); });
          add(image.currentSrc);
          add(image.getAttribute('src'));
        });
        add('https://i.ytimg.com/vi/' + id + '/maxresdefault.jpg');
        add('https://i.ytimg.com/vi/' + id + '/hqdefault.jpg');

        var titleNode = document.querySelector('meta[property="og:title"]');
        var title = clean(titleNode && titleNode.getAttribute('content')) ||
          clean(document.title).replace(/\s*-\s*YouTube\s*$/i, '') ||
          ('YouTube video ' + id.slice(0, 6));
        var artistNode = document.querySelector(
          'ytd-video-owner-renderer #channel-name a, #owner-name a, .slim-owner-channel-name, meta[itemprop="author"]'
        );
        var artist = clean(artistNode &&
          (artistNode.getAttribute('content') || artistNode.getAttribute('title') || artistNode.textContent));
        item = {
          videoId: id,
          title: title,
          artist: artist || null,
          sourceUrl: 'https://www.youtube.com/watch?v=' + id,
          thumbnailCandidates: candidates.slice(0, 12),
          thumbnailUrl: candidates[0] || null,
          route: window.location.href,
          collectionName: 'YouTube'
        };
      }

      var signature = JSON.stringify(item);
      if (signature !== lastSignature) {
        lastSignature = signature;
        window.ReactNativeWebView.postMessage(JSON.stringify({
          type: 'airplanemode-current-youtube-video',
          item: item
        }));
      }
    }

    function schedule() {
      window.clearTimeout(timer);
      timer = window.setTimeout(scan, 180);
    }

    var observer = new MutationObserver(schedule);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['content', 'href', 'src', 'srcset', 'title'],
      childList: true,
      characterData: true,
      subtree: true
    });
    window.addEventListener('popstate', schedule);
    window.addEventListener('hashchange', schedule);
    window.setInterval(scan, 1000);
    window.__airplaneModeCurrentVideoScanner = {scan: scan};
    scan();
    return true;
  })();
`;

function WebsiteSource({homeUrl, onLibraryChanged}: Props) {
  const webViewRef = useRef<WebViewHandle>(null);
  const canGoBackRef = useRef(false);
  const focused = useIsFocused();
  const [url, setUrl] = useState(homeUrl);
  const [progress, setProgress] = useState(0);
  const [currentVideo, setCurrentVideo] = useState<DetectedMedia | null>(null);
  const [adding, setAdding] = useState(false);

  const onOpenWindow = useCallback((event: WebViewOpenWindowEvent) => {
    const target = event.nativeEvent.targetUrl;
    if (isWebUrl(target)) setUrl(target);
  }, []);

  const onNavigation = useCallback((state: WebViewNavigation) => {
    canGoBackRef.current = state.canGoBack;
    const id = videoIdFromUrl(state.url);
    setCurrentVideo(current => id ? (
      current?.videoId === id ? current : fallbackVideo(id, state.url, state.title)
    ) : null);
  }, []);

  const onMessage = useCallback((event: WebViewMessageEvent) => {
    try {
      const message = JSON.parse(event.nativeEvent.data) as CurrentVideoMessage;
      if (message.type === 'airplanemode-current-youtube-video') {
        setCurrentVideo(message.item);
      }
    } catch {
      // Ignore messages not emitted by the acquisition scanner.
    }
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

  const addCurrent = useCallback(async () => {
    if (!currentVideo || adding) return;
    setAdding(true);
    try {
      await queueYouTubeItems('youtube', [currentVideo]);
      onLibraryChanged();
    } finally {
      setAdding(false);
    }
  }, [adding, currentVideo, onLibraryChanged]);

  return (
    <View style={styles.container}>
      <View style={styles.progressTrack}>
        <View
          style={[
            styles.progress,
            progress >= 1 && styles.progressComplete,
            {width: `${Math.max(progress * 100, 2)}%`},
          ]}
        />
      </View>
      <AndroidWebView
        domStorageEnabled
        injectedJavaScript={CURRENT_YOUTUBE_SCANNER}
        javaScriptCanOpenWindowsAutomatically
        javaScriptEnabled
        onLoadProgress={event => setProgress(event.nativeEvent.progress)}
        onMessage={onMessage}
        onNavigationStateChange={onNavigation}
        onOpenWindow={onOpenWindow}
        onShouldStartLoadWithRequest={request => isWebUrl(request.url)}
        ref={webViewRef}
        setSupportMultipleWindows={false}
        source={{uri: url}}
        thirdPartyCookiesEnabled
      />
      {currentVideo ? (
        <Pressable
          accessibilityLabel="Add video to local library"
          accessibilityRole="button"
          disabled={adding}
          onPress={addCurrent}
          style={({pressed}) => [styles.action, pressed && styles.pressed]}>
          {adding ? <ActivityIndicator color={colors.text} size="small" /> : null}
          <Text style={styles.actionText}>{adding ? 'Adding current video…' : 'Add current video'}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

function fallbackVideo(id: string, route: string, pageTitle?: string): DetectedMedia {
  const title = pageTitle?.replace(/\s*-\s*YouTube\s*$/i, '').trim();
  const maxres = `https://i.ytimg.com/vi/${id}/maxresdefault.jpg`;
  const hq = `https://i.ytimg.com/vi/${id}/hqdefault.jpg`;
  return {
    artist: null,
    collectionName: 'YouTube',
    route,
    sourceUrl: `https://www.youtube.com/watch?v=${id}`,
    thumbnailCandidates: [maxres, hq],
    thumbnailUrl: maxres,
    title: title || `YouTube video ${id.slice(0, 6)}`,
    videoId: id,
  };
}

function videoIdFromUrl(url: string) {
  try {
    const value = new URL(url).searchParams.get('v');
    return value && /^[a-zA-Z0-9_-]{11}$/.test(value) ? value : null;
  } catch {
    return null;
  }
}

function isWebUrl(value: string) {
  return value === 'about:blank' || /^https?:\/\//i.test(value);
}

const styles = StyleSheet.create({
  container: {backgroundColor: colors.canvas, flex: 1},
  progressTrack: {backgroundColor: colors.surface, height: 2},
  progress: {backgroundColor: colors.accent, height: 2},
  progressComplete: {opacity: 0},
  action: {
    alignItems: 'center',
    backgroundColor: colors.scrimStrong,
    borderColor: colors.border,
    borderRadius: radii.round,
    borderWidth: StyleSheet.hairlineWidth,
    bottom: spacing.md,
    flexDirection: 'row',
    height: 40,
    justifyContent: 'center',
    minWidth: 96,
    paddingHorizontal: spacing.lg,
    position: 'absolute',
    right: spacing.md,
  },
  actionText: {color: colors.text, fontSize: typography.caption, fontWeight: '700'},
  pressed: {backgroundColor: colors.surfaceRaised},
});

export default WebsiteSource;
