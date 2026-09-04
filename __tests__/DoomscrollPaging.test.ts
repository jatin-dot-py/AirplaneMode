import { settledReelIndex } from '../src/modules/doomscroller/paging';

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

  test('rounds a native settled offset without issuing a second scroll', () => {
    expect(settledReelIndex(1_245, 800, 1_000)).toBe(2);
    expect(settledReelIndex(1_600.25, 800, 1_000)).toBe(2);
  });

  test('keeps the active index stable when the measured viewport changes', () => {
    const oldIndex = settledReelIndex(800 * 537, 800, 1_000);
    const resizedIndex = settledReelIndex(921 * oldIndex, 921, 1_000);

    expect(resizedIndex).toBe(537);
  });
});
