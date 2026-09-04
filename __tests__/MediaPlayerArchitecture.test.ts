/// <reference types="node" />

import {PAGE_SCANNER} from '../src/modules/connectors/YouTubeMusicConnector';
import {mediaSources} from '../src/modules/media-player/sources';
import {CURRENT_YOUTUBE_SCANNER} from '../src/modules/media-player/sources/WebsiteSource';
import fs from 'node:fs';
import path from 'node:path';

jest.mock('react-native-webview', () => ({
  __esModule: true,
  default: 'WebView',
}));

describe('Media Player architecture', () => {
  test('keeps the required library-first source order', () => {
    expect(mediaSources.map(source => source.id)).toEqual([
      'library',
      'youtube-music',
      'youtube',
      'gallery',
    ]);
  });

  test('scanner is route-scoped, deduplicated, and continuously refreshed', () => {
    expect(PAGE_SCANNER).toContain('items: new Map()');
    expect(PAGE_SCANNER).toContain('state.items = new Map()');
    expect(PAGE_SCANNER).toContain('window.setTimeout(scan, 180)');
    expect(PAGE_SCANNER).toContain('window.setInterval(scan, 1500)');
    expect(PAGE_SCANNER).toContain("attributeFilter: ['href', 'data-video-id', 'src', 'srcset', 'style', 'title']");
    expect(PAGE_SCANNER).toContain('normalizeImageUrl');
    expect(PAGE_SCANNER).toContain("'https://i.ytimg.com/vi/' + videoId + '/maxresdefault.jpg'");
    expect(PAGE_SCANNER).toContain("'https://i.ytimg.com/vi/' + videoId + '/hqdefault.jpg'");
    expect(PAGE_SCANNER).toContain('currentVideoId: current ? current.videoId : null');
    expect(PAGE_SCANNER).toContain('thumbnailCandidates:');
  });

  test('YouTube detects only the current URL video', () => {
    expect(CURRENT_YOUTUBE_SCANNER).toContain("searchParams.get('v')");
    expect(CURRENT_YOUTUBE_SCANNER).toContain("type: 'airplanemode-current-youtube-video'");
    expect(CURRENT_YOUTUBE_SCANNER).not.toContain("querySelectorAll('a[href]')");
  });

  test('uses Gallery as an action and YouTube as the only website source', () => {
    expect(mediaSources.find(source => source.id === 'gallery')?.implementation).toEqual({
      type: 'gallery-import',
    });
    expect(mediaSources.find(source => source.id === 'youtube')?.implementation).toEqual({
      type: 'website',
      homeUrl: 'https://m.youtube.com/',
    });
    expect(mediaSources.map(source => source.name)).not.toContain('Spotify');
  });

  test('keeps only read compatibility for legacy Spotify database rows', () => {
    const contract = fs.readFileSync(
      path.join(process.cwd(), 'src/native/MediaEngine.ts'),
      'utf8',
    );
    const library = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/sources/LibrarySurface.tsx'),
      'utf8',
    );
    const surfaceTypes = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/types.ts'),
      'utf8',
    );

    expect(contract).toContain("| 'spotify'");
    expect(surfaceTypes).not.toContain("| 'spotify'");
    expect(library).not.toContain("{id: 'spotify'");
    expect(library).not.toContain("return 'Spotify'");
    expect(library).toContain("return 'Web import'");
  });

  test('uses native stack destinations and Lucide search controls', () => {
    const mediaPlayer = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/MediaPlayerModule.tsx'),
      'utf8',
    );
    const website = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/sources/WebsiteSource.tsx'),
      'utf8',
    );
    const music = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/connectors/YouTubeMusicConnector.tsx'),
      'utf8',
    );

    expect(mediaPlayer).toContain('createNativeStackNavigator<MediaStackParamList>');
    expect(mediaPlayer).toContain('name="YouTubeMusic"');
    expect(mediaPlayer).toContain('name="YouTube"');
    expect(mediaPlayer).toContain('import {Search, X}');
    expect(mediaPlayer).toContain("Keyboard.addListener('keyboardDidHide', closeSearch)");
    expect(mediaPlayer).not.toContain('function SearchIcon');
    expect(mediaPlayer).not.toContain('function CloseIcon');
    expect(website).toContain("BackHandler.addEventListener('hardwareBackPress'");
    expect(music).toContain("BackHandler.addEventListener('hardwareBackPress'");
  });

  test('keeps storage controls in the app-level Settings module', () => {
    const library = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/sources/LibrarySurface.tsx'),
      'utf8',
    );
    const settings = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/settings/AppSettingsModule.tsx'),
      'utf8',
    );
    expect(library).not.toContain('getStorageStats');
    expect(library).not.toContain('LibraryStats');
    expect(settings).toContain('getStorageStats');
    expect(settings).toContain('clearMediaLibrary');
    expect(settings).toContain('clearOfflineReels');
    expect(settings).toContain('clearInstagramWebCache');
    expect(settings).toContain('clearInstagramWebsiteData');
  });

  test('scopes normal playback by source and playlist playback by playlist order', () => {
    const engine = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/java/com/airplanemode/media/MediaEngineModule.kt'),
      'utf8',
    );
    const resolver = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/java/com/airplanemode/media/PlaybackQueueResolver.kt'),
      'utf8',
    );
    const library = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/sources/LibrarySurface.tsx'),
      'utf8',
    );
    expect(engine).toContain('PlaybackQueueResolver.resolve(id, playableItems, playlistItems)');
    expect(resolver).toContain('playableItems.filter { it.source == selected.source }');
    expect(resolver).toContain('playlistItems');
    expect(library).toContain('playMedia(item.id, activePlaylist?.id ?? null)');
  });

  test('keeps playback responsive while downloads and library refreshes are active', () => {
    const engine = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/java/com/airplanemode/media/MediaEngineModule.kt'),
      'utf8',
    );
    const library = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/sources/LibrarySurface.tsx'),
      'utf8',
    );
    const playMethod = engine.slice(
      engine.indexOf('fun playMedia'),
      engine.indexOf('fun togglePlayback'),
    );

    expect(engine).toContain('private val playbackExecutor = Executors.newSingleThreadExecutor()');
    expect(engine).toContain('private val downloadStateExecutor = Executors.newSingleThreadExecutor()');
    expect(playMethod).toContain('playbackExecutor.execute');
    expect(playMethod).not.toContain('ioExecutor.execute');
    expect(engine).toContain('if (downloadStateRequiresLibraryRefresh(state)) emitLibraryChanged()');
    expect(engine).not.toContain('artworkTasks.forEach');
    expect(library).toContain('onDownloadStateChanged(update =>');
    expect(library).toContain('keyboardShouldPersistTaps="handled"');
    expect(library).not.toContain('run(() => playMedia(');
  });

  test('uses one Media3-owned fullscreen and PiP surface with fitted video', () => {
    const player = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/MiniPlayer.tsx'),
      'utf8',
    );
    const inlineNativeView = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/java/com/airplanemode/media/MediaPlayerViewManager.kt'),
      'utf8',
    );
    const nativeVideoActivity = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/java/com/airplanemode/media/VideoPlayerActivity.kt'),
      'utf8',
    );
    const nativeBridge = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/java/com/airplanemode/media/MediaEngineModule.kt'),
      'utf8',
    );
    const manifest = fs.readFileSync(
      path.join(process.cwd(), 'android/app/src/main/AndroidManifest.xml'),
      'utf8',
    );
    expect(player).toContain('Playback speed');
    expect(player).toContain('setPlaybackSpeed(speed)');
    expect(player.match(/<NativeMediaPlayerView/g)).toHaveLength(1);
    expect(player).toContain('onVideoPlayerClosed');
    expect(player).not.toContain('fullscreenBottomBar');
    expect(nativeBridge).toContain('controller.setPlaybackSpeed(safeSpeed)');
    expect(inlineNativeView).toContain('useController = false');
    expect(nativeVideoActivity).toContain('resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT');
    expect(nativeVideoActivity).toContain('playerView.useController = false');
    expect(nativeVideoActivity).toContain('setSourceRectHint');
    expect(nativeVideoActivity).toContain('setSeamlessResizeEnabled(true)');
    expect(manifest).toContain('android:name=".media.VideoPlayerActivity"');
  });

  test('keeps component colors in the central theme', () => {
    const sourceRoot = path.join(process.cwd(), 'src');
    const files: string[] = [];
    const collect = (directory: string) => {
      for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
        const resolved = path.join(directory, entry.name);
        if (entry.isDirectory()) collect(resolved);
        else if (/\.(ts|tsx)$/.test(entry.name) && resolved !== path.join(sourceRoot, 'theme/index.ts')) {
          files.push(resolved);
        }
      }
    };
    collect(sourceRoot);
    const offenders = files.filter(file => /#[0-9a-f]{6}\b/i.test(fs.readFileSync(file, 'utf8')));
    expect(offenders).toEqual([]);
  });

  test('never maps a normal active-download tap directly to cancellation', () => {
    const library = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/sources/LibrarySurface.tsx'),
      'utf8',
    );
    const primaryAction = library.slice(
      library.indexOf('const primaryAction'),
      library.indexOf('const confirmCancelDownload'),
    );
    expect(primaryAction).not.toContain('cancelDownload');
    expect(library).toContain(
      'else if (activeStates.has(item.availability)) setMenuItem(item)',
    );
    expect(library).toContain("{text: 'Keep downloading', style: 'cancel'}");
  });

  test('uses explicit acquisition language and neutral local fallback artwork', () => {
    const youtubeMusic = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/connectors/YouTubeMusicConnector.tsx'),
      'utf8',
    );
    const artwork = fs.readFileSync(
      path.join(process.cwd(), 'src/modules/media-player/MediaArtwork.tsx'),
      'utf8',
    );
    expect(youtubeMusic).toContain('Add current song');
    expect(youtubeMusic).toContain('detected ${detected.length === 1 ? \'song\' : \'songs\'}');
    expect(artwork).toContain("assets/icons/music-note.png");
    expect(artwork).not.toContain('youtube-music');
  });
});
