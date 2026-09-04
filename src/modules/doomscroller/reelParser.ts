import type {
  CapturedPageInfo,
  CapturedReel,
  ReelCoauthor,
  ReelMediaCandidate,
  ReelTaggedUser,
} from './types';

type JsonObject = Record<string, unknown>;

export type ParsedReelsPage = {
  pageInfo: CapturedPageInfo;
  reels: CapturedReel[];
};

export function parseReelsResponseText(text: string): ParsedReelsPage | null {
  for (const payload of parsePayloads(text)) {
    const connection = findReelsConnection(payload);
    if (!connection) continue;
    const edges = asArray(connection.edges);
    const pageInfo = asObject(connection.page_info);
    const reels = edges
      .map(edge => asObject(asObject(asObject(edge).node).media))
      .filter(media => Object.keys(media).length > 0)
      .map(normalizeReel)
      .filter((reel): reel is CapturedReel => reel !== null);
    if (!reels.length && !Object.keys(pageInfo).length) continue;
    return {
      pageInfo: {
        endCursor: stringValue(pageInfo.end_cursor),
        hasNextPage: booleanValue(pageInfo.has_next_page),
      },
      reels,
    };
  }
  return null;
}

export function createPaginationBody(body: string, cursor: string): string | null {
  try {
    const params = new URLSearchParams(body);
    const variables = JSON.parse(params.get('variables') ?? '{}') as JsonObject;
    if (!isObject(variables)) return null;
    variables.after = cursor;
    params.set('variables', JSON.stringify(variables));
    return params.toString();
  } catch {
    return null;
  }
}

export function isPaginationRequestBody(body: string): boolean {
  try {
    const params = new URLSearchParams(body);
    const variables = JSON.parse(params.get('variables') ?? '{}') as JsonObject;
    const friendlyName = params.get('fb_api_req_friendly_name') ?? '';
    return Object.prototype.hasOwnProperty.call(variables, 'after') || /pagination/i.test(friendlyName);
  } catch {
    return false;
  }
}

function parsePayloads(text: string): unknown[] {
  const cleaned = stripXssi(text.trim());
  if (!cleaned) return [];
  try {
    return [JSON.parse(cleaned)];
  } catch {
    const linePayloads = cleaned
      .split(/\r?\n/)
      .map(line => stripXssi(line.trim()))
      .filter(Boolean)
      .flatMap(line => {
        try {
          return [JSON.parse(line)];
        } catch {
          return [];
        }
      });
    return [...linePayloads, ...extractJsonPayloads(cleaned)];
  }
}

function extractJsonPayloads(value: string): unknown[] {
  const payloads: unknown[] = [];
  let depth = 0;
  let escaped = false;
  let inString = false;
  let start = -1;
  for (let index = 0; index < value.length; index++) {
    const character = value[index];
    if (start < 0) {
      if (character === '{' || character === '[') {
        start = index;
        depth = 1;
      }
      continue;
    }
    if (inString) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === '"') inString = false;
      continue;
    }
    if (character === '"') inString = true;
    else if (character === '{' || character === '[') depth++;
    else if (character === '}' || character === ']') depth--;
    if (depth !== 0) continue;
    try {
      payloads.push(JSON.parse(value.slice(start, index + 1)));
    } catch {
      // Keep scanning after malformed multipart sections.
    }
    start = -1;
  }
  return payloads;
}

function findReelsConnection(value: unknown, depth = 0): JsonObject | null {
  if (depth > 9 || !value) return null;
  if (Array.isArray(value)) {
    for (const child of value) {
      const match = findReelsConnection(child, depth + 1);
      if (match) return match;
    }
    return null;
  }
  if (!isObject(value)) return null;
  if (Array.isArray(value.edges) && isObject(value.page_info)) {
    const looksLikeReels = value.edges.some(edge => {
      const media = asObject(asObject(asObject(edge).node).media);
      return Boolean(stringValue(media.pk) || stringValue(media.id)) &&
        (media.product_type === 'clips' || media.media_type === 2);
    });
    if (looksLikeReels || value.edges.length === 0) return value;
  }
  for (const child of Object.values(value)) {
    if (typeof child === 'string') continue;
    const match = findReelsConnection(child, depth + 1);
    if (match) return match;
  }
  return null;
}

function stripXssi(value: string): string {
  return value
    .replace(/^for\s*\(\s*;\s*;\s*\)\s*;?/, '')
    .replace(/^\)\]\}',?/, '')
    .trim();
}

function normalizeReel(media: JsonObject): CapturedReel | null {
  const mediaPk = stringValue(media.pk) ?? stringValue(media.id)?.split('_')[0] ?? null;
  const code = stringValue(media.code);
  if (!mediaPk || !code) return null;
  const user = asObject(media.user);
  const caption = asObject(media.caption);
  const clips = asObject(media.clips_metadata);
  const originalSound = asObject(clips.original_sound_info);
  const music = asObject(clips.music_info);
  const musicAsset = asObject(music.music_asset_info);
  const audioArtist = asObject(originalSound.ig_artist);
  const imageVersions = asObject(media.image_versions2);
  const usertags = normalizeUsertags(asObject(media.usertags).in);
  const coauthors = normalizeCoauthors(media.coauthor_producers);
  const location = normalizeLocation(media.location);
  const durationMs = durationFromMedia(media);
  const coverCandidates = normalizeCandidates(imageVersions.candidates, 4);
  const videoCandidates = normalizeCandidates(media.video_versions, 6);
  const authorId = stringValue(user.pk) ?? stringValue(user.id) ?? '';
  const authorUsername = stringValue(user.username) ?? '';
  const audioTitle = stringValue(musicAsset.title) ?? stringValue(originalSound.original_audio_title);
  const audioArtistUsername = stringValue(musicAsset.display_artist) ?? stringValue(audioArtist.username);
  const audioArtistId = stringValue(audioArtist.pk) ?? stringValue(audioArtist.id);
  const safeMetadata = {
    aiLabel: safeText(asObject(media.ai_label_info).label) ??
      safeText(asObject(media.ai_label_info).title) ??
      safeText(asObject(media.ai_label_info).text),
    aiLabelPresent: Boolean(media.ai_label_info),
    clipsAttributionInfo: safeText(asObject(media.clips_attribution_info).attribution_username),
    commentingDisabled: nullableBoolean(media.comments_disabled),
    friendshipFollowing: nullableBoolean(asObject(user.friendship_status).following),
    isSharedFromBasel: nullableBoolean(media.is_shared_from_basel),
    likeAndViewCountsDisabled: nullableBoolean(media.like_and_view_counts_disabled),
    mediaType: numberValue(media.media_type),
    originalSoundIsExplicit: nullableBoolean(originalSound.is_explicit),
    showAccountTransparencyDetails: nullableBoolean(user.show_account_transparency_details),
    wearableAttributionTitle: safeText(asObject(media.wearable_attribution_info).attribution_title),
  };

  return {
    audioArtistId,
    audioArtistUsername,
    audioAssetId: stringValue(originalSound.audio_asset_id) ?? stringValue(musicAsset.audio_id),
    audioIsExplicit: booleanValue(originalSound.is_explicit) || booleanValue(musicAsset.is_explicit),
    audioTitle,
    authorFullName: stringValue(user.full_name),
    authorId,
    authorIsPrivate: booleanValue(user.is_private),
    authorIsVerified: booleanValue(user.is_verified),
    authorProfilePicUrl: httpsUrl(user.profile_pic_url),
    authorUsername,
    canViewerReshare: booleanValue(media.can_viewer_reshare),
    caption: stringValue(caption.text),
    coauthors,
    code,
    commentCount: nullableNumber(media.comment_count),
    coverCandidates,
    durationMs,
    fbCommentCount: nullableNumber(media.fb_comment_count),
    fbLikeCount: nullableNumber(media.fb_like_count),
    hasAudio: booleanValue(media.has_audio),
    hasLiked: booleanValue(media.has_liked),
    hasViewerSaved: booleanValue(media.has_viewer_saved),
    inventorySource: stringValue(media.inventory_source),
    likeCount: nullableNumber(media.like_count),
    locationJson: location ? JSON.stringify(location) : null,
    mediaId: stringValue(media.id) ?? mediaPk,
    mediaPk,
    mediaType: numberValue(media.media_type),
    originalHeight: nullableNumber(media.original_height),
    originalWidth: nullableNumber(media.original_width),
    permalink: `https://www.instagram.com/reel/${code}/`,
    productType: stringValue(media.product_type),
    repostCount: nullableNumber(media.media_repost_count),
    safeMetadataJson: JSON.stringify(safeMetadata),
    takenAt: nullableNumber(media.taken_at),
    usertags,
    videoCandidates,
    viewCount: nullableNumber(media.view_count) ?? nullableNumber(media.play_count),
  };
}

function normalizeCandidates(value: unknown, limit: number): ReelMediaCandidate[] {
  const seen = new Set<string>();
  return asArray(value)
    .map(candidate => asObject(candidate))
    .map(candidate => ({
      height: numberValue(candidate.height),
      url: httpsUrl(candidate.url) ?? '',
      width: numberValue(candidate.width),
    }))
    .filter(candidate => {
      if (!candidate.url || seen.has(candidate.url)) return false;
      seen.add(candidate.url);
      return true;
    })
    .sort((left, right) => right.width * right.height - left.width * left.height)
    .slice(0, limit);
}

function normalizeUsertags(value: unknown): ReelTaggedUser[] {
  return asArray(value).slice(0, 30).flatMap(entry => {
    const tag = asObject(entry);
    const user = asObject(tag.user);
    const id = stringValue(user.pk) ?? stringValue(user.id);
    const username = stringValue(user.username);
    if (!id || !username) return [];
    const position = asArray(tag.position).map(numberValue);
    return [{
      fullName: stringValue(user.full_name),
      id,
      isVerified: booleanValue(user.is_verified),
      position: position.length >= 2 ? [position[0], position[1]] : null,
      username,
    }];
  });
}

function normalizeCoauthors(value: unknown): ReelCoauthor[] {
  return asArray(value).slice(0, 20).flatMap(entry => {
    const user = asObject(entry);
    const id = stringValue(user.pk) ?? stringValue(user.id);
    const username = stringValue(user.username);
    if (!id) return [];
    return [{
      fullName: stringValue(user.full_name),
      id,
      isVerified: booleanValue(user.is_verified),
      username: username ?? '',
    }];
  });
}

function normalizeLocation(value: unknown): JsonObject | null {
  const location = asObject(value);
  if (!Object.keys(location).length) return null;
  return {
    address: safeText(location.address),
    city: safeText(location.city),
    id: safeText(location.pk) ?? safeText(location.id),
    latitude: nullableNumber(location.lat),
    longitude: nullableNumber(location.lng),
    name: safeText(location.name),
  };
}

function durationFromMedia(media: JsonObject): number | null {
  const directSeconds = nullableNumber(media.video_duration);
  if (directSeconds !== null) return Math.round(directSeconds * 1000);
  const manifest = stringValue(media.video_dash_manifest);
  const match = manifest?.match(/mediaPresentationDuration="PT([0-9.]+)S"/i);
  return match ? Math.round(Number.parseFloat(match[1]) * 1000) : null;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asObject(value: unknown): JsonObject {
  return isObject(value) ? value : {};
}

function isObject(value: unknown): value is JsonObject {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function safeText(value: unknown): string | null {
  return stringValue(value)?.slice(0, 500) ?? null;
}

function httpsUrl(value: unknown): string | null {
  const candidate = stringValue(value);
  if (!candidate) return null;
  try {
    const parsed = new URL(candidate);
    return parsed.protocol === 'https:' ? parsed.href : null;
  } catch {
    return null;
  }
}

function numberValue(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function nullableNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function booleanValue(value: unknown): boolean {
  return value === true;
}

function nullableBoolean(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null;
}
