import {canonicalReelPermalink} from '../src/modules/doomscroller/permalink';

describe('canonicalReelPermalink', () => {
  test('uses the captured canonical URL', () => {
    expect(canonicalReelPermalink({
      code: 'ignored',
      permalink: 'https://www.instagram.com/reel/ABC123/',
    })).toBe('https://www.instagram.com/reel/ABC123/');
  });

  test('constructs a canonical URL from the captured shortcode as a fallback', () => {
    expect(canonicalReelPermalink({code: 'DcocxPQqqV7', permalink: '  '}))
      .toBe('https://www.instagram.com/reel/DcocxPQqqV7/');
  });
});
