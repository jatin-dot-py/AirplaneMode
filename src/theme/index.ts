export const colors = {
  canvas: '#090909',
  surface: '#121212',
  surfaceRaised: '#1A1A1A',
  surfaceOverlay: '#202020',
  surfacePressed: '#252525',
  border: '#2A2A2A',
  text: '#F7F7F7',
  textMuted: '#A0A0A0',
  textSubtle: '#737373',
  accent: '#FF1744',
  accentSoft: 'rgba(255, 23, 68, 0.16)',
  verified: '#3A8DFF',
  warning: '#FFB15C',
  white: '#FFFFFF',
  black: '#000000',
  reelFallback: '#161616',
  transparent: 'transparent',
  scrim: 'rgba(0, 0, 0, 0.64)',
  scrimStrong: 'rgba(0, 0, 0, 0.84)',
} as const;

export const spacing = {
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const radii = {
  sm: 6,
  md: 10,
  lg: 16,
  artwork: 8,
  playlist: 12,
  round: 999,
} as const;

export const typography = {
  caption: 11,
  body: 13,
  title: 15,
  playerTitle: 22,
  hero: 27,
  utility: 10,
} as const;

export const layout = {
  sourceBar: 52,
  miniPlayer: 68,
  minTouchTarget: 44,
} as const;

export const motion = {
  fast: 160,
  standard: 220,
} as const;

export const theme = {colors, layout, motion, radii, spacing, typography} as const;

export type AppTheme = typeof theme;
