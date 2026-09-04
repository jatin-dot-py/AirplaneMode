import {
  settledReelIndex,
  settledReelPage,
} from '../src/modules/doomscroller/paging';

describe('settledReelIndex', () => {
  test('selects one stable page only after the scroll offset settles', () => {
    expect(settledReelIndex(0, 800, 1_000)).toBe(0);
    expect(settledReelIndex(399, 800, 1_000)).toBe(0);
    expect(settledReelIndex(401, 800, 1_000)).toBe(1);
    expect(settledReelIndex(800 * 537, 800, 1_000)).toBe(537);
  });

  test('clamps safely at both ends of a 1,000-Reel snapshot', () => {
    expect(settledReelIndex(-8_000, 800, 1_000)).toBe(0);
    expect(settledReelIndex(800 * 1_500, 800, 1_000)).toBe(999);
    expect(settledReelIndex(Number.NaN, 800, 1_000)).toBe(0);
    expect(settledReelIndex(800, 0, 1_000)).toBe(0);
  });

  test('returns the exact offset needed to repair a split page', () => {
    expect(settledReelPage(1_245, 800, 1_000)).toEqual({
      exactOffset: 1_600,
      index: 2,
      needsCorrection: true,
    });
    expect(settledReelPage(1_600.25, 800, 1_000)).toEqual({
      exactOffset: 1_600,
      index: 2,
      needsCorrection: false,
    });
  });

  test('realigns the active Reel when the measured viewport changes', () => {
    const oldViewport = settledReelPage(800 * 537, 800, 1_000);
    const resizedViewport = settledReelPage(921 * oldViewport.index, 921, 1_000);

    expect(resizedViewport).toEqual({
      exactOffset: 494_577,
      index: 537,
      needsCorrection: false,
    });
  });
});
