import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  ActivityIndicator,
  BackHandler,
  Image,
  Keyboard,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {useIsFocused, useNavigation} from '@react-navigation/native';
import {
  createNativeStackNavigator,
  type NativeStackNavigationProp,
} from '@react-navigation/native-stack';
import {Search, X} from 'lucide-react-native';
import {SafeAreaView} from 'react-native-safe-area-context';

import type {MediaStackParamList} from '../../navigation/types';
import {
  emptyPlayback,
  getPlaybackState,
  importGallery,
  onLibraryChanged,
  onPlaybackStateChanged,
  type PlaybackState,
} from '../../native/MediaEngine';
import {colors, layout, radii, spacing, typography} from '../../theme';
import YouTubeMusicConnector from '../connectors/YouTubeMusicConnector';
import MiniPlayer from './MiniPlayer';
import {mediaSources} from './sources';
import LibrarySurface from './sources/LibrarySurface';
import WebsiteSource from './sources/WebsiteSource';
import type {MediaSource, MediaSurfaceId} from './types';

type NavigableSourceId = Exclude<MediaSurfaceId, 'gallery'>;
type MediaContextValue = {
  galleryBusy: boolean;
  importFromGallery: () => Promise<void>;
  libraryRevision: number;
  markLibraryChanged: () => void;
  playback: PlaybackState;
  searchOpen: boolean;
  searchQuery: string;
  setSearchOpen: (open: boolean) => void;
  setSearchQuery: (query: string) => void;
  showNotice: (message: string) => void;
};

const MediaStack = createNativeStackNavigator<MediaStackParamList>();
const MediaContext = createContext<MediaContextValue | null>(null);

function MediaPlayerModule() {
  const [libraryRevision, setLibraryRevision] = useState(0);
  const [playback, setPlayback] = useState<PlaybackState>(emptyPlayback);
  const [galleryBusy, setGalleryBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const markLibraryChanged = useCallback(() => {
    setLibraryRevision(value => value + 1);
  }, []);

  const showNotice = useCallback((message: string) => {
    if (noticeTimer.current) clearTimeout(noticeTimer.current);
    setNotice(message);
    noticeTimer.current = setTimeout(() => setNotice(null), 2800);
  }, []);

  useEffect(() => {
    getPlaybackState().then(setPlayback).catch(() => undefined);
    const playbackSubscription = onPlaybackStateChanged(setPlayback);
    const librarySubscription = onLibraryChanged(markLibraryChanged);
    return () => {
      playbackSubscription?.remove();
      librarySubscription?.remove();
      if (noticeTimer.current) clearTimeout(noticeTimer.current);
    };
  }, [markLibraryChanged]);

  const importFromGallery = useCallback(async () => {
    if (galleryBusy) return;
    setGalleryBusy(true);
    try {
      const imported = await importGallery();
      if (imported.length) {
        markLibraryChanged();
        showNotice(
          `${imported.length} ${imported.length === 1 ? 'item' : 'items'} imported`,
        );
      }
    } catch (reason) {
      showNotice(
        reason instanceof Error
          ? reason.message
          : 'Could not open the media picker',
      );
    } finally {
      setGalleryBusy(false);
    }
  }, [galleryBusy, markLibraryChanged, showNotice]);

  const context = useMemo<MediaContextValue>(() => ({
    galleryBusy,
    importFromGallery,
    libraryRevision,
    markLibraryChanged,
    playback,
    searchOpen,
    searchQuery,
    setSearchOpen,
    setSearchQuery,
    showNotice,
  }), [
    galleryBusy,
    importFromGallery,
    libraryRevision,
    markLibraryChanged,
    playback,
    searchOpen,
    searchQuery,
    showNotice,
  ]);

  return (
    <SafeAreaView edges={['top']} style={styles.safeArea}>
      <MediaContext.Provider value={context}>
        <View style={styles.container}>
          <MediaStack.Navigator
            initialRouteName="MediaHome"
            screenOptions={{
              animation: 'slide_from_right',
              contentStyle: styles.container,
              headerShown: false,
            }}>
            <MediaStack.Screen component={MediaHomeScreen} name="MediaHome" />
            <MediaStack.Screen component={YouTubeMusicScreen} name="YouTubeMusic" />
            <MediaStack.Screen component={YouTubeScreen} name="YouTube" />
          </MediaStack.Navigator>

          {notice ? (
            <View pointerEvents="none" style={styles.notice}>
              <Text numberOfLines={2} style={styles.noticeText}>{notice}</Text>
            </View>
          ) : null}
          <MiniPlayer playback={playback} />
        </View>
      </MediaContext.Provider>
    </SafeAreaView>
  );
}

function MediaHomeScreen() {
  const media = useMediaContext();
  return (
    <MediaSurfaceFrame activeSourceId="library">
      <LibrarySurface
        onNotice={media.showNotice}
        playback={media.playback}
        query={media.searchQuery}
        revision={media.libraryRevision}
      />
    </MediaSurfaceFrame>
  );
}

function YouTubeMusicScreen() {
  const media = useMediaContext();
  return (
    <MediaSurfaceFrame activeSourceId="youtube-music">
      <YouTubeMusicConnector
        homeUrl="https://music.youtube.com/"
        onLibraryChanged={media.markLibraryChanged}
      />
    </MediaSurfaceFrame>
  );
}

function YouTubeScreen() {
  const media = useMediaContext();
  return (
    <MediaSurfaceFrame activeSourceId="youtube">
      <WebsiteSource
        homeUrl="https://m.youtube.com/"
        onLibraryChanged={media.markLibraryChanged}
      />
    </MediaSurfaceFrame>
  );
}

function MediaSurfaceFrame({
  activeSourceId,
  children,
}: {
  activeSourceId: NavigableSourceId;
  children: React.ReactNode;
}) {
  const media = useMediaContext();
  const focused = useIsFocused();
  const navigation = useNavigation<NativeStackNavigationProp<MediaStackParamList>>();

  const closeSearch = useCallback(() => {
    media.setSearchOpen(false);
    media.setSearchQuery('');
  }, [media]);

  useEffect(() => {
    if (!focused || activeSourceId !== 'library' || !media.searchOpen) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      closeSearch();
      return true;
    });
    return () => subscription.remove();
  }, [activeSourceId, closeSearch, focused, media.searchOpen]);

  useEffect(() => {
    if (!focused || activeSourceId !== 'library' || !media.searchOpen) return;
    const subscription = Keyboard.addListener('keyboardDidHide', closeSearch);
    return () => subscription.remove();
  }, [activeSourceId, closeSearch, focused, media.searchOpen]);

  const openSource = useCallback(async (source: MediaSource) => {
    if (source.implementation.type === 'gallery-import') {
      await media.importFromGallery();
      if (activeSourceId !== 'library') navigation.popToTop();
      return;
    }

    const id = source.id as NavigableSourceId;
    if (id === activeSourceId) return;
    closeSearch();
    if (id === 'library') {
      navigation.popToTop();
      return;
    }

    const target = id === 'youtube' ? 'YouTube' : 'YouTubeMusic';
    if (activeSourceId === 'library') navigation.navigate(target);
    else navigation.replace(target);
  }, [activeSourceId, closeSearch, media, navigation]);

  return (
    <View style={styles.container}>
      <View style={styles.sourceBar}>
        {media.searchOpen && activeSourceId === 'library' ? (
          <View style={styles.searchRow}>
            <Search color={colors.text} size={20} strokeWidth={1.8} />
            <TextInput
              accessibilityLabel="Search local library"
              autoFocus
              onChangeText={media.setSearchQuery}
              placeholder="Search downloads and imports"
              placeholderTextColor={colors.textMuted}
              selectionColor={colors.accent}
              style={styles.searchInput}
              value={media.searchQuery}
            />
            <Pressable
              accessibilityLabel="Close search"
              hitSlop={8}
              onPress={closeSearch}
              style={({pressed}) => [styles.iconButton, pressed && styles.pressed]}>
              <X color={colors.text} size={20} strokeWidth={1.8} />
            </Pressable>
          </View>
        ) : (
          <>
            <ScrollView
              accessibilityLabel="Media sources"
              contentContainerStyle={styles.sourceList}
              horizontal
              showsHorizontalScrollIndicator={false}>
              {mediaSources.map(source => (
                <SourceButton
                  active={source.id === activeSourceId}
                  busy={source.id === 'gallery' && media.galleryBusy}
                  key={source.id}
                  onPress={() => { openSource(source).catch(() => undefined); }}
                  source={source}
                />
              ))}
            </ScrollView>
            {activeSourceId === 'library' ? (
              <Pressable
                accessibilityLabel="Search local library"
                onPress={() => media.setSearchOpen(true)}
                style={({pressed}) => [styles.searchButton, pressed && styles.pressed]}>
                <Search color={colors.text} size={20} strokeWidth={1.8} />
              </Pressable>
            ) : null}
          </>
        )}
      </View>
      <View style={styles.surfaceStage}>{children}</View>
    </View>
  );
}

function SourceButton({
  active,
  busy,
  onPress,
  source,
}: {
  active: boolean;
  busy: boolean;
  onPress: () => void;
  source: MediaSource;
}) {
  const isMonochrome = ['library', 'gallery'].includes(source.id);
  return (
    <Pressable
      accessibilityHint={source.description}
      accessibilityLabel={source.name}
      accessibilityRole="button"
      accessibilityState={{selected: active, busy}}
      disabled={busy}
      onPress={onPress}
      style={({pressed}) => [styles.sourceButton, pressed && styles.pressed]}>
      {busy ? (
        <ActivityIndicator color={colors.text} size="small" />
      ) : (
        <Image
          accessibilityIgnoresInvertColors
          resizeMode="contain"
          source={source.icon}
          style={[styles.sourceIcon, !active && styles.sourceIconInactive]}
          tintColor={isMonochrome ? (active ? colors.text : colors.textMuted) : undefined}
        />
      )}
      {active && source.id !== 'gallery' ? <View style={styles.activeMarker} /> : null}
    </Pressable>
  );
}

function useMediaContext() {
  const context = useContext(MediaContext);
  if (!context) throw new Error('Media surfaces must be rendered inside MediaPlayerModule.');
  return context;
}

const styles = StyleSheet.create({
  activeMarker: {
    backgroundColor: colors.text,
    bottom: 0,
    height: 2,
    left: spacing.sm,
    position: 'absolute',
    right: spacing.sm,
  },
  container: {backgroundColor: colors.canvas, flex: 1},
  iconButton: {
    alignItems: 'center',
    height: 44,
    justifyContent: 'center',
    width: 44,
  },
  notice: {
    alignSelf: 'center',
    backgroundColor: colors.surfaceRaised,
    borderColor: colors.border,
    borderRadius: radii.md,
    borderWidth: StyleSheet.hairlineWidth,
    bottom: layout.miniPlayer + spacing.md,
    elevation: 8,
    maxWidth: '86%',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    position: 'absolute',
    zIndex: 10,
  },
  noticeText: {
    color: colors.text,
    fontSize: typography.caption,
    fontWeight: '600',
  },
  pressed: {opacity: 0.58},
  safeArea: {backgroundColor: colors.canvas, flex: 1},
  searchButton: {
    alignItems: 'center',
    borderLeftColor: colors.border,
    borderLeftWidth: StyleSheet.hairlineWidth,
    height: layout.sourceBar,
    justifyContent: 'center',
    width: layout.minTouchTarget,
  },
  searchInput: {
    color: colors.text,
    flex: 1,
    fontSize: typography.body,
    height: layout.sourceBar,
    paddingHorizontal: spacing.md,
    paddingVertical: 0,
  },
  searchRow: {
    alignItems: 'center',
    flex: 1,
    flexDirection: 'row',
    paddingLeft: spacing.lg,
  },
  sourceBar: {
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderBottomColor: colors.border,
    borderBottomWidth: StyleSheet.hairlineWidth,
    flexDirection: 'row',
    height: layout.sourceBar,
  },
  sourceButton: {
    alignItems: 'center',
    height: layout.sourceBar,
    justifyContent: 'center',
    minWidth: 48,
    paddingHorizontal: spacing.sm,
    position: 'relative',
  },
  sourceIcon: {height: 25, width: 25},
  sourceIconInactive: {opacity: 0.42},
  sourceList: {alignItems: 'stretch', paddingHorizontal: spacing.xs},
  surfaceStage: {flex: 1, overflow: 'hidden'},
});

export default MediaPlayerModule;
