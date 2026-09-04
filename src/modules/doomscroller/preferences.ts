import type {ReelQualityPolicy} from './types';

export const DOOMSCROLL_QUALITY_PREFERENCE = 'doomscroll.defaultQuality';
export const DOOMSCROLL_SPEED_PREFERENCE = 'doomscroll.playbackSpeed';

export const DEFAULT_REEL_QUALITY: ReelQualityPolicy = 'smart_hq';
export const DEFAULT_REEL_SPEED = 1;

export const REEL_QUALITY_OPTIONS: ReadonlyArray<{
  detail: string;
  label: string;
  value: ReelQualityPolicy;
}> = [
  {
    detail: 'Instagram 720p source. Fastest to save and consistently sharp.',
    label: 'High',
    value: 'smart_hq',
  },
  {
    detail: '720p converted to HEVC when the device can make a meaningfully smaller file.',
    label: 'Efficient',
    value: 'efficient_hq',
  },
  {
    detail: 'Largest progressive source Instagram provides. Uses the most storage.',
    label: 'Original',
    value: 'original',
  },
  {
    detail: 'Instagram 480p source. Smallest files, with a visible quality trade-off.',
    label: 'Compact',
    value: 'compact',
  },
];

export const REEL_PLAYBACK_SPEEDS = [0.5, 1, 1.5, 2] as const;

export function parseReelQuality(value: string | null): ReelQualityPolicy {
  return REEL_QUALITY_OPTIONS.some(option => option.value === value)
    ? value as ReelQualityPolicy
    : DEFAULT_REEL_QUALITY;
}

export function parseReelSpeed(value: string | null): number {
  const parsed = Number(value);
  return REEL_PLAYBACK_SPEEDS.includes(parsed as typeof REEL_PLAYBACK_SPEEDS[number])
    ? parsed
    : DEFAULT_REEL_SPEED;
}
