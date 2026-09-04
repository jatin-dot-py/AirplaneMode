import {
  NativeEventEmitter,
  NativeModules,
  Platform,
  requireNativeComponent,
  type EmitterSubscription,
  type NativeSyntheticEvent,
  type ViewProps,
} from 'react-native';

import type {
  CaptureBatchResult,
  CapturedReel,
  DoomscrollStats,
  DoomscrollStorageBreakdown,
  OfflineReel,
  PlaybackSaveResult,
  ReelDownloadState,
  ReelQualityPolicy,
  ReelSnapshot,
  SnapshotDeleteResult,
} from '../modules/doomscroller/types';

type NativeDoomscrollEngine = {
  addListener(eventName: string): void;
  beginCaptureSession(): Promise<string>;
  clearInstagramWebCache(): Promise<boolean>;
  clearInstagramWebsiteData(): Promise<boolean>;
  clearOfflineReels(): Promise<boolean>;
  createReelSnapshot(name: string, qualityPolicy: ReelQualityPolicy): Promise<string>;
  deleteReelSnapshot(snapshotId: string): Promise<SnapshotDeleteResult>;
  finishCaptureSession(sessionId: string, reason: string): Promise<boolean>;
  getDoomscrollStats(): Promise<DoomscrollStats>;
  getDoomscrollStorageBreakdown(): Promise<DoomscrollStorageBreakdown>;
  getReelSnapshot(snapshotId: string): Promise<ReelSnapshot>;
  listOfflineReels(): Promise<OfflineReel[]>;
  listReelSnapshots(): Promise<ReelSnapshot[]>;
  listSnapshotReels(snapshotId: string): Promise<OfflineReel[]>;
  recordSnapshotPlayback(
    snapshotId: string,
    mediaPk: string,
    position: number,
    playbackPositionMs: number,
    durationMs: number,
    activeDeltaMs: number,
  ): Promise<PlaybackSaveResult>;
  removeListeners(count: number): void;
  retryReelDownload(mediaPk: string): Promise<boolean>;
  saveCaptureBatch(
    sessionId: string,
    pageIndex: number,
    reels: CapturedReel[],
  ): Promise<CaptureBatchResult>;
};

export type DoomscrollDownloadChanged = {
  error: string | null;
  mediaPk: string;
  progress: number;
  state: ReelDownloadState;
};

export type ReelPlaybackError = {
  message: string;
  sourcePath: string;
};

export type ReelFirstFrame = {
  sourcePath: string;
};

export type ReelPlaybackProgress = {
  activeDeltaMs: number;
  durationMs: number;
  isBuffering: boolean;
  isPlaying: boolean;
  playbackPositionMs: number;
  sourcePath: string;
};

export type NativeReelPlayerProps = ViewProps & {
  muted: boolean;
  onFirstFrame?: (event: NativeSyntheticEvent<ReelFirstFrame>) => void;
  onPlaybackError?: (event: NativeSyntheticEvent<ReelPlaybackError>) => void;
  onPlaybackProgress?: (event: NativeSyntheticEvent<ReelPlaybackProgress>) => void;
  paused: boolean;
  playbackSpeed: number;
  resumePositionMs: number;
  sourcePath: string;
  visibilityQualified: boolean;
};

const nativeEngine = NativeModules.DoomscrollEngine as NativeDoomscrollEngine | undefined;
const emitter = nativeEngine
  ? new NativeEventEmitter(NativeModules.DoomscrollEngine)
  : null;

function requireEngine() {
  if (!nativeEngine) throw new Error('DoomscrollEngine is unavailable on this platform.');
  return nativeEngine;
}

export const beginCaptureSession = () => requireEngine().beginCaptureSession();
export const createReelSnapshot = (name: string, qualityPolicy: ReelQualityPolicy) =>
  requireEngine().createReelSnapshot(name, qualityPolicy);
export const finishCaptureSession = (sessionId: string, reason: string) =>
  requireEngine().finishCaptureSession(sessionId, reason);
export const saveCaptureBatch = (
  sessionId: string,
  pageIndex: number,
  reels: CapturedReel[],
) => requireEngine().saveCaptureBatch(sessionId, pageIndex, reels);
export const listOfflineReels = () => requireEngine().listOfflineReels();
export const listReelSnapshots = () => requireEngine().listReelSnapshots();
export const getReelSnapshot = (snapshotId: string) =>
  requireEngine().getReelSnapshot(snapshotId);
export const listSnapshotReels = (snapshotId: string) =>
  requireEngine().listSnapshotReels(snapshotId);
export const recordSnapshotPlayback = (
  snapshotId: string,
  mediaPk: string,
  position: number,
  playbackPositionMs: number,
  durationMs: number,
  activeDeltaMs: number,
) => requireEngine().recordSnapshotPlayback(
  snapshotId,
  mediaPk,
  position,
  playbackPositionMs,
  durationMs,
  activeDeltaMs,
);
export const deleteReelSnapshot = (snapshotId: string) =>
  requireEngine().deleteReelSnapshot(snapshotId);
export const getDoomscrollStats = () => requireEngine().getDoomscrollStats();
export const getDoomscrollStorageBreakdown = () =>
  requireEngine().getDoomscrollStorageBreakdown();
export const retryReelDownload = (mediaPk: string) =>
  requireEngine().retryReelDownload(mediaPk);
export const clearOfflineReels = () => requireEngine().clearOfflineReels();
export const clearInstagramWebCache = () =>
  requireEngine().clearInstagramWebCache();
export const clearInstagramWebsiteData = () =>
  requireEngine().clearInstagramWebsiteData();

export function onDoomscrollChanged(
  listener: () => void,
): EmitterSubscription | null {
  return emitter?.addListener('DoomscrollChanged', listener) ?? null;
}

export function onDoomscrollDownloadChanged(
  listener: (event: DoomscrollDownloadChanged) => void,
): EmitterSubscription | null {
  return emitter?.addListener(
    'DoomscrollDownloadStateChanged',
    (...args: readonly Object[]) => listener(args[0] as DoomscrollDownloadChanged),
  ) ?? null;
}

export const NativeReelPlayerView =
  Platform.OS === 'android'
    ? requireNativeComponent<NativeReelPlayerProps>('AirplaneReelPlayerView')
    : undefined;
