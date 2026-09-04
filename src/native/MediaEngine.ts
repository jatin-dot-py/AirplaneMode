import {
  NativeEventEmitter,
  NativeModules,
  Platform,
  requireNativeComponent,
  type EmitterSubscription,
  type ViewProps,
} from 'react-native';

export type PlaybackLocator =
  | {kind: 'content-uri'; uri: string}
  | {kind: 'media3-download'; downloadId: string}
  | {kind: 'app-file'; path: string}
  | null;

export type MediaAvailability =
  | 'waiting_for_resolver'
  | 'queued'
  | 'downloading'
  | 'ready'
  | 'failed'
  | 'missing'
  | 'cancelled';

export type MediaSourceId =
  | 'youtube-music'
  | 'spotify'
  | 'gallery'
  | 'youtube';

export type MediaItem = {
  id: string;
  source: MediaSourceId;
  sourceKey: string;
  mediaType: 'audio' | 'video' | 'unknown';
  title: string;
  artist: string | null;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  thumbnailRemoteUrl: string | null;
  thumbnailLocalPath: string | null;
  playbackLocator: PlaybackLocator;
  availability: MediaAvailability;
  downloadProgress: number;
  collectionName: string | null;
  createdAt: number;
  updatedAt: number;
};

export type DetectedMedia = {
  videoId: string;
  title: string;
  artist: string | null;
  sourceUrl: string;
  thumbnailCandidates: string[];
  thumbnailUrl?: string | null;
  route: string;
  collectionName: string;
};

export type DownloadStateChanged = {
  itemId: string;
  state: MediaAvailability;
  progress: number;
  error: string | null;
};

export type LocalPlaylist = {
  id: string;
  name: string;
  pinned: boolean;
  itemCount: number;
  mediaItemIds: string[];
  artworkPaths: string[];
  createdAt: number;
  updatedAt: number;
};

export type StorageStats = {
  appMediaBytes: number;
  artworkBytes: number;
  databaseBytes: number;
  galleryReferencedBytes: number;
  libraryItemCount: number;
  readyItemCount: number;
  activeDownloadCount: number;
  downloadingItemCount: number;
  queuedDownloadCount: number;
  downloadedItemCount: number;
};

export type PlaybackState = {
  mediaId: string | null;
  title: string | null;
  artist: string | null;
  mediaType: 'audio' | 'video' | null;
  artworkPath: string | null;
  width: number | null;
  height: number | null;
  isPlaying: boolean;
  state: 'idle' | 'buffering' | 'ready' | 'ended';
  positionMs: number;
  durationMs: number;
  playbackSpeed: number;
  hasNext: boolean;
  hasPrevious: boolean;
};

export type MediaEngineStatus = {
  state: 'ready' | 'unavailable';
  platform: string;
  engine: string;
  version: string;
};

export type AddDetectedResult = {
  added: number;
  updated: number;
  total: number;
};

type NativeMediaEngine = {
  getStatus(): Promise<MediaEngineStatus>;
  listMediaItems(filter: string): Promise<MediaItem[]>;
  addDetectedItems(items: DetectedMedia[]): Promise<AddDetectedResult>;
  queueYouTubeItems(
    source: Extract<MediaSourceId, 'youtube-music' | 'youtube'>,
    items: DetectedMedia[],
  ): Promise<AddDetectedResult>;
  cancelDownload(mediaItemId: string): Promise<boolean>;
  clearMediaLibrary(): Promise<boolean>;
  retryDownload(mediaItemId: string): Promise<boolean>;
  removeLibraryItem(mediaItemId: string): Promise<boolean>;
  removeLibraryItems(mediaItemIds: string[]): Promise<number>;
  listLocalPlaylists(): Promise<LocalPlaylist[]>;
  createLocalPlaylist(name: string, mediaItemIds: string[]): Promise<string>;
  addItemsToLocalPlaylist(playlistId: string, mediaItemIds: string[]): Promise<boolean>;
  removeItemsFromLocalPlaylist(playlistId: string, mediaItemIds: string[]): Promise<boolean>;
  setLocalPlaylistPinned(playlistId: string, pinned: boolean): Promise<boolean>;
  deleteLocalPlaylist(playlistId: string): Promise<boolean>;
  getStorageStats(): Promise<StorageStats>;
  importGallery(): Promise<MediaItem[]>;
  enqueueResolvedDownload(
    itemId: string,
    authorizedUri: string,
    mimeType: string | null,
  ): Promise<string>;
  playMedia(id: string, playlistId: string | null): Promise<boolean>;
  togglePlayback(): Promise<boolean>;
  seekTo(positionMs: number): Promise<boolean>;
  setPlaybackSpeed(speed: number): Promise<number>;
  skipNext(): Promise<boolean>;
  skipPrevious(): Promise<boolean>;
  getPlaybackState(): Promise<PlaybackState>;
  enterPictureInPicture(width: number, height: number): Promise<boolean>;
  setVideoFullscreen(enabled: boolean, width: number, height: number): Promise<boolean>;
  getUiPreference(key: string): Promise<string | null>;
  setUiPreference(key: string, value: string): Promise<boolean>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
};

const nativeEngine = NativeModules.MediaEngine as NativeMediaEngine | undefined;
const emitter = nativeEngine ? new NativeEventEmitter(NativeModules.MediaEngine) : null;

export const emptyPlayback: PlaybackState = {
  mediaId: null,
  title: null,
  artist: null,
  mediaType: null,
  artworkPath: null,
  width: null,
  height: null,
  isPlaying: false,
  state: 'idle',
  positionMs: 0,
  durationMs: 0,
  playbackSpeed: 1,
  hasNext: false,
  hasPrevious: false,
};

function requireEngine(): NativeMediaEngine {
  if (!nativeEngine) throw new Error('MediaEngine is unavailable on this platform.');
  return nativeEngine;
}

export async function getMediaEngineStatus(): Promise<MediaEngineStatus> {
  if (!nativeEngine) {
    return {
      state: 'unavailable',
      platform: Platform.OS,
      engine: 'Native module not linked',
      version: '0.0.0',
    };
  }
  return nativeEngine.getStatus();
}

export const listMediaItems = (filter: string = 'all') =>
  requireEngine().listMediaItems(filter);
export const addDetectedItems = (items: DetectedMedia[]) =>
  requireEngine().addDetectedItems(items);
export const queueYouTubeItems = (
  source: Extract<MediaSourceId, 'youtube-music' | 'youtube'>,
  items: DetectedMedia[],
) => requireEngine().queueYouTubeItems(source, items);
export const cancelDownload = (mediaItemId: string) =>
  requireEngine().cancelDownload(mediaItemId);
export const clearMediaLibrary = () => requireEngine().clearMediaLibrary();
export const retryDownload = (mediaItemId: string) =>
  requireEngine().retryDownload(mediaItemId);
export const removeLibraryItem = (mediaItemId: string) =>
  requireEngine().removeLibraryItem(mediaItemId);
export const removeLibraryItems = (mediaItemIds: string[]) =>
  requireEngine().removeLibraryItems(mediaItemIds);
export const listLocalPlaylists = () => requireEngine().listLocalPlaylists();
export const createLocalPlaylist = (name: string, mediaItemIds: string[] = []) =>
  requireEngine().createLocalPlaylist(name, mediaItemIds);
export const addItemsToLocalPlaylist = (playlistId: string, mediaItemIds: string[]) =>
  requireEngine().addItemsToLocalPlaylist(playlistId, mediaItemIds);
export const removeItemsFromLocalPlaylist = (playlistId: string, mediaItemIds: string[]) =>
  requireEngine().removeItemsFromLocalPlaylist(playlistId, mediaItemIds);
export const setLocalPlaylistPinned = (playlistId: string, pinned: boolean) =>
  requireEngine().setLocalPlaylistPinned(playlistId, pinned);
export const deleteLocalPlaylist = (playlistId: string) =>
  requireEngine().deleteLocalPlaylist(playlistId);
export const getStorageStats = () => requireEngine().getStorageStats();
export const importGallery = () => requireEngine().importGallery();
export const enqueueResolvedDownload = (
  itemId: string,
  authorizedUri: string,
  mimeType: string | null = null,
) => requireEngine().enqueueResolvedDownload(itemId, authorizedUri, mimeType);
export const playMedia = (id: string, playlistId: string | null = null) =>
  requireEngine().playMedia(id, playlistId);
export const togglePlayback = () => requireEngine().togglePlayback();
export const seekTo = (positionMs: number) => requireEngine().seekTo(positionMs);
export const setPlaybackSpeed = (speed: number) =>
  requireEngine().setPlaybackSpeed(speed);
export const skipNext = () => requireEngine().skipNext();
export const skipPrevious = () => requireEngine().skipPrevious();
export const enterPictureInPicture = (width = 16, height = 9) =>
  requireEngine().enterPictureInPicture(width, height);
export const setVideoFullscreen = (enabled: boolean, width = 16, height = 9) =>
  requireEngine().setVideoFullscreen(enabled, width, height);
export const getUiPreference = (key: string) =>
  requireEngine().getUiPreference(key);
export const setUiPreference = (key: string, value: string) =>
  requireEngine().setUiPreference(key, value);

export async function getPlaybackState(): Promise<PlaybackState> {
  return nativeEngine ? nativeEngine.getPlaybackState() : emptyPlayback;
}

export function onLibraryChanged(listener: () => void): EmitterSubscription | null {
  return emitter?.addListener('MediaLibraryChanged', listener) ?? null;
}

export function onPlaybackStateChanged(
  listener: (state: PlaybackState) => void,
): EmitterSubscription | null {
  return emitter?.addListener('PlaybackStateChanged', (...args: readonly Object[]) => {
    listener(args[0] as PlaybackState);
  }) ?? null;
}

export function onDownloadStateChanged(
  listener: (state: DownloadStateChanged) => void,
): EmitterSubscription | null {
  return emitter?.addListener('DownloadStateChanged', (...args: readonly Object[]) => {
    listener(args[0] as DownloadStateChanged);
  }) ?? null;
}

export function onVideoPlayerClosed(listener: () => void): EmitterSubscription | null {
  return emitter?.addListener('VideoPlayerClosed', listener) ?? null;
}

export const NativeMediaPlayerView =
  Platform.OS === 'android'
    ? requireNativeComponent<ViewProps>('AirplaneMediaPlayerView')
    : undefined;
