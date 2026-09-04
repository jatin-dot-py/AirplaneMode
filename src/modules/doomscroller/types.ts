export type ReelDownloadState =
  | 'queued'
  | 'downloading'
  | 'ready'
  | 'failed'
  | 'paused_low_storage'
  | 'missing';

export type ReelQualityPolicy =
  | 'smart_hq'
  | 'efficient_hq'
  | 'original'
  | 'compact';

export type ReelMediaCandidate = {
  height: number;
  url: string;
  width: number;
};

export type ReelTaggedUser = {
  fullName: string | null;
  id: string;
  isVerified: boolean;
  position: [number, number] | null;
  username: string;
};

export type ReelCoauthor = {
  fullName: string | null;
  id: string;
  isVerified: boolean;
  username: string;
};

export type CapturedReel = {
  audioArtistId: string | null;
  audioArtistUsername: string | null;
  audioAssetId: string | null;
  audioIsExplicit: boolean;
  audioTitle: string | null;
  authorFullName: string | null;
  authorId: string;
  authorIsPrivate: boolean;
  authorIsVerified: boolean;
  authorProfilePicUrl: string | null;
  authorUsername: string;
  canViewerReshare: boolean;
  caption: string | null;
  coauthors: ReelCoauthor[];
  code: string;
  commentCount: number | null;
  coverCandidates: ReelMediaCandidate[];
  durationMs: number | null;
  fbCommentCount: number | null;
  fbLikeCount: number | null;
  hasAudio: boolean;
  hasLiked: boolean;
  hasViewerSaved: boolean;
  inventorySource: string | null;
  likeCount: number | null;
  locationJson: string | null;
  mediaId: string;
  mediaPk: string;
  mediaType: number;
  originalHeight: number | null;
  originalWidth: number | null;
  permalink: string;
  productType: string | null;
  repostCount: number | null;
  safeMetadataJson: string;
  takenAt: number | null;
  usertags: ReelTaggedUser[];
  videoCandidates: ReelMediaCandidate[];
  viewCount: number | null;
};

export type OfflineReel = Omit<
  CapturedReel,
  'coverCandidates' | 'videoCandidates' | 'authorProfilePicUrl'
> & {
  activePlaybackMs: number;
  authorProfilePicLocalPath: string | null;
  capturedAt: number;
  coverLocalPath: string | null;
  downloadError: string | null;
  downloadProgress: number;
  downloadState: ReelDownloadState;
  estimatedBytes: number;
  localBytes: number;
  qualifiedWatched: boolean;
  qualifiedWatchedAt: number | null;
  qualityPolicy: ReelQualityPolicy;
  savedPlaybackPositionMs: number;
  selectedHeight: number | null;
  selectedWidth: number | null;
  snapshotId: string;
  snapshotPosition: number;
  videoLocalPath: string | null;
};

export type DoomscrollStats = {
  capturedCount: number;
  downloadedBytes: number;
  downloadingCount: number;
  failedCount: number;
  lowStorageCount: number;
  queuedCount: number;
  readyCount: number;
};

export type DoomscrollStorageBreakdown = {
  databaseBytes: number;
  mediaBytes: number;
  websiteDataBytes: number;
};

export type ReelSnapshot = DoomscrollStats & {
  createdAt: number;
  currentPosition: number;
  estimatedBytes: number;
  finishedAt: number | null;
  id: string;
  logicalBytes: number;
  name: string;
  pagesCaptured: number;
  previewCoverPaths: string[];
  qualityPolicy: ReelQualityPolicy;
  reclaimableBytes: number;
  resumePosition: number;
  state: 'capturing' | 'stopped' | 'complete';
  stopReason: string | null;
  updatedAt: number;
  watchedCount: number;
};

export type CaptureBatchResult = DoomscrollStats & {
  added: number;
  canContinue: boolean;
  persisted: number;
  stopReason: string | null;
  updated: number;
};

export type PlaybackSaveResult = {
  activePlaybackMs: number;
  qualified: boolean;
  resumePosition: number;
};

export type SnapshotDeleteResult = {
  deleted: boolean;
  reclaimedBytes: number;
};

export type CaptureState =
  | 'idle'
  | 'awaiting-pagination'
  | 'ready'
  | 'fetching'
  | 'stopping'
  | 'stopped'
  | 'complete'
  | 'auth-required'
  | 'rate-limited'
  | 'low-storage'
  | 'error';

export type CapturedPageInfo = {
  endCursor: string | null;
  hasNextPage: boolean;
};

type WebViewMessageBase = {
  channel: 'airplanemode-doomscroll';
  version: 2;
};

export type CapturedPageMessage = WebViewMessageBase & {
  batchId: string;
  pageIndex: number;
  pageInfo: CapturedPageInfo;
  reels: CapturedReel[];
  templateReady: boolean;
  type: 'page';
};

export type CaptureStateMessage = WebViewMessageBase & {
  capturedCount: number;
  detail: string | null;
  state: CaptureState;
  templateReady: boolean;
  type: 'state';
};

export type CaptureErrorMessage = WebViewMessageBase & {
  code: string;
  message: string;
  recoverable: boolean;
  type: 'error';
};

export type DoomscrollWebViewMessage =
  | CapturedPageMessage
  | CaptureStateMessage
  | CaptureErrorMessage;

export const emptyDoomscrollStats: DoomscrollStats = {
  capturedCount: 0,
  downloadedBytes: 0,
  downloadingCount: 0,
  failedCount: 0,
  lowStorageCount: 0,
  queuedCount: 0,
  readyCount: 0,
};
