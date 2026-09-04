import type {OfflineReel, ReelSnapshot} from './types';

type ResumeReel = Pick<
  OfflineReel,
  'durationMs' | 'qualifiedWatched' | 'savedPlaybackPositionMs' | 'snapshotPosition'
>;

type ResumeSnapshot = Pick<ReelSnapshot, 'currentPosition' | 'resumePosition'>;

export function resolveSnapshotContinueIndex(
  reels: ResumeReel[],
  snapshot: ResumeSnapshot,
) {
  if (reels.length === 0) return 0;
  const desiredPosition = Math.max(snapshot.currentPosition, snapshot.resumePosition);
  const nextUnwatched = reels.findIndex(
    reel => reel.snapshotPosition >= desiredPosition && !reel.qualifiedWatched,
  );
  if (nextUnwatched >= 0) return nextUnwatched;

  // A fully consumed snapshot opens at its tail for deliberate review; all
  // qualified items remain directly above it instead of being removed.
  return reels.length - 1;
}

export function reelResumePosition(reel: ResumeReel | undefined) {
  if (!reel || reel.qualifiedWatched) return 0;
  if (reel.durationMs && reel.savedPlaybackPositionMs >= reel.durationMs * 0.9) return 0;
  return Math.max(reel.savedPlaybackPositionMs, 0);
}
