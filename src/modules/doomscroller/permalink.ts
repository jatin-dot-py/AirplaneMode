export function canonicalReelPermalink(reel: {
  code: string;
  permalink: string;
}) {
  const captured = reel.permalink.trim();
  if (captured) return captured;
  return `https://www.instagram.com/reel/${encodeURIComponent(reel.code)}/`;
}
