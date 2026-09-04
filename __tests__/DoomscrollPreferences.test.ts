import {
  DEFAULT_REEL_QUALITY,
  DEFAULT_REEL_SPEED,
  parseReelQuality,
  parseReelSpeed,
} from '../src/modules/doomscroller/preferences';

describe('Doomscroller preferences', () => {
  test('accepts every supported quality policy and safely defaults unknown values', () => {
    expect(parseReelQuality('smart_hq')).toBe('smart_hq');
    expect(parseReelQuality('efficient_hq')).toBe('efficient_hq');
    expect(parseReelQuality('original')).toBe('original');
    expect(parseReelQuality('compact')).toBe('compact');
    expect(parseReelQuality('future-policy')).toBe(DEFAULT_REEL_QUALITY);
    expect(parseReelQuality(null)).toBe(DEFAULT_REEL_QUALITY);
  });

  test('accepts only player-supported playback speeds', () => {
    for (const speed of [0.5, 1, 1.5, 2]) {
      expect(parseReelSpeed(String(speed))).toBe(speed);
    }
    expect(parseReelSpeed('1.25')).toBe(DEFAULT_REEL_SPEED);
    expect(parseReelSpeed('fast')).toBe(DEFAULT_REEL_SPEED);
    expect(parseReelSpeed(null)).toBe(DEFAULT_REEL_SPEED);
  });
});
