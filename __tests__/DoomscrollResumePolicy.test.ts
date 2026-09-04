import {
  reelResumePosition,
  resolveSnapshotContinueIndex,
} from '../src/modules/doomscroller/resumePolicy';

const reel = (
  snapshotPosition: number,
  qualifiedWatched = false,
  savedPlaybackPositionMs = 0,
  durationMs = 20_000,
) => ({durationMs, qualifiedWatched, savedPlaybackPositionMs, snapshotPosition});

describe('Doomscroll snapshot continuation', () => {
  test('starts on the forward-most unqualified Reel and keeps older items above it', () => {
    const reels = [reel(0, true), reel(1, true), reel(2), reel(3)];

    expect(resolveSnapshotContinueIndex(reels, {currentPosition: 1, resumePosition: 2})).toBe(2);
    expect(reels.slice(0, 2).every(item => item.qualifiedWatched)).toBe(true);
  });

  test('never selects an already-qualified Reel when an unwatched one follows', () => {
    const reels = [reel(0, true), reel(1, true), reel(2, false)];

    expect(resolveSnapshotContinueIndex(reels, {currentPosition: 0, resumePosition: 0})).toBe(2);
  });

  test('fully watched snapshots open at the tail for explicit review', () => {
    expect(
      resolveSnapshotContinueIndex(
        [reel(0, true), reel(1, true)],
        {currentPosition: 1, resumePosition: 2},
      ),
    ).toBe(1);
  });

  test('resumes only unfinished Reels and avoids near-end replay', () => {
    expect(reelResumePosition(reel(1, false, 4_000))).toBe(4_000);
    expect(reelResumePosition(reel(1, true, 4_000))).toBe(0);
    expect(reelResumePosition(reel(1, false, 18_000))).toBe(0);
  });
});
