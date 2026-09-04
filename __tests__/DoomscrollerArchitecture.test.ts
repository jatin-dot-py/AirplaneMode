/// <reference types="node" />

import fs from 'node:fs';
import path from 'node:path';

const read = (file: string) =>
  fs.readFileSync(path.join(process.cwd(), file), 'utf8');

describe('Doomscroller architecture', () => {
  test('uses a real root stack and restrained bottom tabs without the old shell', () => {
    const app = read('App.tsx');
    const navigator = read('src/navigation/AppNavigator.tsx');
    const routeTypes = read('src/navigation/types.ts');

    expect(app).toContain('<NavigationContainer');
    expect(app).not.toContain('ModuleShell');
    expect(app).not.toContain('sidebarHidden');
    expect(app).not.toContain('airplanemode-sidebar');
    expect(navigator).toContain('backBehavior="initialRoute"');
    expect(navigator).toContain('initialRouteName="Media"');
    expect(navigator).toContain('name="Media"');
    expect(navigator).toContain('name="Reels"');
    expect(navigator).toContain('name="Settings"');
    expect(routeTypes).toContain('InstagramCapture: {snapshotId: string}');
    expect(routeTypes).toContain('OfflineReels: {snapshotId: string}');
  });

  test('initializes react-native-screens before fragment restoration', () => {
    const activity = read(
      'android/app/src/main/java/com/airplanemode/MainActivity.kt',
    );
    const manifest = read('android/app/src/main/AndroidManifest.xml');

    expect(activity).toContain('RNScreensFragmentFactory');
    expect(
      activity.indexOf('fragmentFactory = RNScreensFragmentFactory()'),
    ).toBeLessThan(activity.indexOf('super.onCreate(savedInstanceState)'));
    expect(manifest).toContain('android:enableOnBackInvokedCallback="false"');
  });

  test('keeps login cookies and storage available in the controlled WebView', () => {
    const webView = read('src/modules/doomscroller/DoomscrollerModule.tsx');

    expect(webView).toContain('domStorageEnabled');
    expect(webView).toContain('thirdPartyCookiesEnabled');
    expect(webView).toContain('setSupportMultipleWindows={false}');
    expect(webView).toContain('isWebNavigation');
    expect(webView).not.toContain('onShouldStartLoadWithRequest=');
    expect(webView).not.toContain('incognito');
    expect(webView).not.toContain('onHttpError=');
  });

  test('opens every new snapshot at Reels and lets Instagram handle authentication', () => {
    const capture = read('src/modules/doomscroller/DoomscrollerModule.tsx');
    const navigator = read('src/navigation/AppNavigator.tsx');

    expect(navigator).toContain(
      "const INSTAGRAM_REELS_URL = 'https://www.instagram.com/reels/'",
    );
    expect(navigator).toContain('initialUrl={INSTAGRAM_REELS_URL}');
    expect(capture).toContain('useState(initialUrl)');
    expect(capture).not.toContain('isAuthenticated');
    expect(capture).not.toContain('window.location.assign');
  });

  test('consumes WebView Back and safely finalizes active capture before leaving', () => {
    const capture = read('src/modules/doomscroller/DoomscrollerModule.tsx');

    expect(capture).toMatch(
      /BackHandler\.addEventListener\(\s*'hardwareBackPress'/,
    );
    expect(capture).toContain("captureStateRef.current === 'fetching'");
    expect(capture).toContain("injectCommand('stop()')");
    expect(capture).toContain('webViewRef.current?.goBack()');
    expect(capture).toContain(
      "finishOnce(openSnapshot ? 'view-snapshot' : 'capture-closed')",
    );
  });

  test('allows device-owner CAs without enabling release cleartext transport', () => {
    const productionConfig = read(
      'android/app/src/main/res/xml/network_security_config.xml',
    );
    const debugConfig = read(
      'android/app/src/debug/res/xml/debug_network_security_config.xml',
    );
    const previewConfig = read(
      'android/app/src/preview/res/xml/network_security_config.xml',
    );

    expect(productionConfig).toContain('cleartextTrafficPermitted="false"');
    expect(productionConfig).toContain('<certificates src="system" />');
    expect(productionConfig).toContain('<certificates src="user" />');
    expect(productionConfig).not.toContain('overridePins="true"');
    expect(debugConfig).toContain('cleartextTrafficPermitted="true"');
    expect(debugConfig).toContain('<certificates src="user" />');
    expect(previewConfig).toContain('cleartextTrafficPermitted="false"');
    expect(previewConfig).toContain('<certificates src="system" />');
    expect(previewConfig).toContain('<certificates src="user" />');
  });

  test('captures fetch and XHR, waits for persistence, and keeps credentials in-page', () => {
    const script = read('src/modules/doomscroller/captureScript.ts');
    const module = read('src/modules/doomscroller/DoomscrollerModule.tsx');

    expect(script).toContain('window.fetch = function');
    expect(script).toContain('XMLHttpRequest.prototype.send = function');
    expect(script).toContain('var pendingAcks = new Map()');
    expect(script).toContain('new AbortController()');
    expect(script).toContain('await delay(1500,');
    expect(script).toContain("credentials: 'include'");
    expect(script).not.toContain('template: state.template');
    expect(module).toMatch(
      /saveCaptureBatch\(\s*snapshotId,\s*message\.pageIndex,\s*message\.reels,?\s*\)/,
    );
    expect(module).toContain(
      'acknowledge(message.batchId, result.canContinue, result.stopReason)',
    );
  });

  test('stores Reels separately without request credentials or tracking payloads', () => {
    const entities = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/data/DoomscrollEntities.kt',
    );
    const database = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/data/DoomscrollDatabase.kt',
    );

    expect(database).toContain('airplane-mode-doomscroll.db');
    expect(database).not.toContain('MediaDatabase');
    for (const forbidden of [
      'sessionid',
      'csrftoken',
      'fb_dtsg',
      'requestHeaders',
      'requestBody',
      'loggingInfoToken',
      'organicTrackingToken',
      'videoDashManifest',
      'rawGraphql',
    ]) {
      expect(entities).not.toMatch(
        new RegExp(`\\bval\\s+${forbidden}\\b`, 'i'),
      );
    }
  });

  test('owns video inside exact native-paged cells and prepares only neighbours', () => {
    const feed = read('src/modules/doomscroller/OfflineReelsSurface.tsx');
    const paging = read('src/modules/doomscroller/paging.ts');
    const nativePlayer = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/ReelPlayerViewManager.kt',
    );
    const nativePlayerLayout = read(
      'android/app/src/main/res/layout/airplane_reel_player_view.xml',
    );

    expect(feed).toContain('FlatList');
    expect(feed).not.toContain("from '@shopify/flash-list'");
    expect(feed.match(/<NativeReelPlayerView/g)).toHaveLength(1);
    expect(feed).toContain('sourcePath={videoPath}');
    expect(feed).toContain('Math.abs(index - activeIndex) <=');
    expect(feed).toContain('PLAYER_NEIGHBOUR_DISTANCE');
    expect(feed).not.toContain('snapToInterval');
    expect(feed).toContain('disableIntervalMomentum');
    expect(feed).toContain('pagingEnabled');
    expect(feed).toContain('getItemLayout={getItemLayout}');
    expect(feed).not.toContain('getAbsoluteLastScrollOffset()');
    expect(feed).not.toContain('interactionGenerationRef');
    expect(feed).not.toContain('settleAtOffset');
    expect(feed).toContain('removeClippedSubviews={false}');
    expect(feed).not.toContain('onViewableItemsChanged');
    expect(feed).toContain('visibilityQualified={active && playbackEnabled}');
    expect(paging).not.toContain('needsCorrection');
    expect(nativePlayer).toContain('Player.REPEAT_MODE_ONE');
    expect(nativePlayer).toContain('AspectRatioFrameLayout.RESIZE_MODE_ZOOM');
    expect(nativePlayer).not.toContain('centerCropScale(');
    expect(nativePlayer).not.toContain('setTransform(');
    expect(nativePlayer).toContain('setKeepContentOnPlayerReset(false)');
    expect(nativePlayerLayout).toContain('app:surface_type="texture_view"');
    expect(nativePlayerLayout).toContain('app:resize_mode="zoom"');
  });

  test('keeps the cover until first frame and manages the screen-awake lifecycle', () => {
    const feed = read('src/modules/doomscroller/OfflineReelsSurface.tsx');
    const nativeContract = read('src/native/DoomscrollEngine.ts');
    const nativePlayer = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/ReelPlayerViewManager.kt',
    );

    expect(feed).toContain('renderedSourcePath === videoPath');
    expect(feed).toContain('onFirstFrame={event =>');
    expect(feed).toContain(
      'setRenderedSourcePath(event.nativeEvent.sourcePath)',
    );
    expect(feed).toContain('!firstFrameVisible');
    expect(nativeContract).toContain('onFirstFrame?');
    expect(nativeContract).not.toContain('nextSourcePath');
    expect(nativePlayer).toContain('topFirstFrame');
    expect(nativePlayer).toContain('UIManagerHelper.getEventDispatcher');
    expect(nativePlayer).not.toContain('RCTEventEmitter');
    expect(nativePlayer).toContain('keepScreenOn = shouldPlay');
    expect(nativePlayer).toContain('keepScreenOn = false');
    expect(nativePlayer).not.toContain('PRELOAD_BYTES');
    expect(nativePlayer).not.toContain('warmNextFile');
  });

  test('does not issue a correction scroll when a gesture settles', () => {
    const feed = read('src/modules/doomscroller/OfflineReelsSurface.tsx');
    const settlement = feed.slice(
      feed.indexOf('const finishScroll'),
      feed.indexOf('const beginScroll'),
    );

    expect(settlement).toContain('settledReelIndex');
    expect(settlement).not.toContain('scrollToIndex');
    expect(settlement).not.toContain('scrollToOffset');
    expect(feed).not.toContain('DRAG_END_SETTLE_MS');
  });

  test('pauses Media Player audio before capture or Reel playback takes ownership', () => {
    const capture = read('src/modules/doomscroller/DoomscrollerModule.tsx');
    const feed = read('src/modules/doomscroller/OfflineReelsSurface.tsx');
    const contract = read('src/native/MediaEngine.ts');
    const module = read(
      'android/app/src/main/java/com/airplanemode/media/MediaEngineModule.kt',
    );

    expect(capture).toContain('pauseMediaPlayback()');
    expect(feed).toContain('pauseMediaPlayback()');
    expect(feed).toContain('playbackOwnershipReady');
    expect(contract).toContain('pausePlayback(): Promise<boolean>');
    expect(module).toContain('fun pausePlayback(promise: Promise)');
  });

  test('uses a gradient, explicit pause, and copy-link action without a tap-to-pause layer', () => {
    const feed = read('src/modules/doomscroller/OfflineReelsSurface.tsx');

    expect(feed).toContain("from 'react-native-linear-gradient'");
    expect(feed).toContain("from '@react-native-clipboard/clipboard'");
    expect(feed).toContain('canonicalReelPermalink');
    expect(feed).toContain("ToastAndroid.show('Link copied'");
    expect(feed).toContain("label={paused ? 'Play Reel' : 'Pause Reel'}");
    expect(feed).not.toContain('bottomScrim');
    expect(feed).not.toContain('onTogglePaused');
    expect(feed).toContain('Available on Instagram when connected');
  });

  test('keeps watched Reels above the continuation point so users can scroll back', () => {
    const resumePolicy = read('src/modules/doomscroller/resumePolicy.ts');
    const feed = read('src/modules/doomscroller/OfflineReelsSurface.tsx');

    expect(resumePolicy).toContain('resolveSnapshotContinueIndex');
    expect(resumePolicy).toContain('qualifiedWatched');
    expect(feed).toContain('initialScrollIndex={activeIndex}');
    expect(feed).toContain('data={reels}');
    expect(feed).not.toContain('reels.filter');
  });

  test('offers HEVC optimization only as a verified space-saving policy', () => {
    const preferences = read('src/modules/doomscroller/preferences.ts');
    const worker = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/DoomscrollDownloadWorker.kt',
    );
    const optimizer = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/ReelVideoOptimizer.kt',
    );

    expect(preferences).toContain("value: 'efficient_hq'");
    expect(worker).toContain('ReelVideoOptimizer');
    expect(optimizer).toContain('MimeTypes.VIDEO_H265');
    expect(optimizer).toContain('inspectLocalVideo(output, audioRequired)');
    expect(optimizer).toContain('MINIMUM_SAVING_RATIO');
  });

  test('limits native downloads to two and preserves queued work after capture stops', () => {
    const worker = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/DoomscrollDownloadWorker.kt',
    );
    const repository = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/data/DoomscrollRepository.kt',
    );
    const queue = read(
      'android/app/src/main/java/com/airplanemode/doomscroll/DoomscrollDownloadQueue.kt',
    );
    const mediaQueue = read(
      'android/app/src/main/java/com/airplanemode/media/YtDlpQueue.kt',
    );
    const mediaModule = read(
      'android/app/src/main/java/com/airplanemode/media/MediaEngineModule.kt',
    );

    expect(worker).toContain('Semaphore(2, true)');
    expect(worker).toContain('Result.retry()');
    expect(repository).toContain('FREE_SPACE_RESERVE_BYTES = 1L shl 30');
    expect(repository).toContain(
      'videoCandidatesJson = if (filesReady) "[]" else usableVideoJson',
    );
    expect(repository).toContain('fun refreshDownloadQueuePolicy()');
    expect(queue).not.toContain('NetworkType.CONNECTED');
    expect(queue).not.toContain('setRequiredNetworkType');
    expect(mediaQueue).not.toContain('NetworkType.CONNECTED');
    expect(mediaQueue).not.toContain('setRequiredNetworkType');
    expect(mediaModule).toContain('replace = replaceConstrainedJobs');
  });
});
