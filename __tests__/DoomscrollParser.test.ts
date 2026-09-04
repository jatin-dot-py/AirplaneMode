import vm from 'node:vm';

import {DOOMSCROLL_CAPTURE_SCRIPT} from '../src/modules/doomscroller/captureScript';
import {
  createPaginationBody,
  isPaginationRequestBody,
  parseReelsResponseText,
} from '../src/modules/doomscroller/reelParser';

const FIRST_CURSOR = 'cursor-one';

function responsePayload({cursor = FIRST_CURSOR, mediaPk = '123'} = {}) {
  return {
    data: {
      any_future_connection_name: {
        edges: [
          {
            cursor: '',
            node: {
              media: {
                ai_label_info: {label: 'AI info that must not be retained verbatim'},
                can_viewer_reshare: true,
                caption: {text: 'A synthetic caption'},
                clips_metadata: {
                  original_sound_info: {
                    audio_asset_id: 'audio-1',
                    ig_artist: {id: '44', username: 'creator'},
                    original_audio_title: 'Original audio',
                  },
                },
                code: 'SyntheticCode',
                comment_count: 8,
                has_audio: true,
                id: `${mediaPk}_44`,
                image_versions2: {
                  candidates: [
                    {height: 640, url: 'https://scontent.cdninstagram.com/cover.jpg', width: 360},
                  ],
                },
                like_count: 1200,
                logging_info_token: 'must-not-cross-the-bridge',
                media_repost_count: 3,
                media_type: 2,
                organic_tracking_token: 'also-must-not-cross-the-bridge',
                original_height: 1280,
                original_width: 720,
                pk: mediaPk,
                product_type: 'clips',
                taken_at: 1_700_000_000,
                user: {
                  full_name: 'Synthetic Creator',
                  id: '44',
                  is_verified: true,
                  profile_pic_url: 'https://scontent.cdninstagram.com/avatar.jpg',
                  username: 'creator',
                },
                video_dash_manifest:
                  '<MPD mediaPresentationDuration="PT12.5S">sensitive manifest</MPD>',
                video_versions: [
                  {
                    height: 640,
                    type: 102,
                    url: 'https://scontent.cdninstagram.com/sd.mp4',
                    width: 360,
                  },
                  {
                    height: 1280,
                    type: 101,
                    url: 'https://scontent.cdninstagram.com/hd.mp4',
                    width: 720,
                  },
                ],
              },
            },
          },
        ],
        page_info: {
          end_cursor: cursor,
          has_next_page: true,
          has_previous_page: false,
        },
      },
    },
  };
}

describe('Doomscroller GraphQL parser', () => {
  test('finds a structurally matching Reels connection behind an anti-XSSI prefix', () => {
    const parsed = parseReelsResponseText(`for (;;);${JSON.stringify(responsePayload())}`);

    expect(parsed?.pageInfo).toEqual({endCursor: FIRST_CURSOR, hasNextPage: true});
    expect(parsed?.reels).toHaveLength(1);
    expect(parsed?.reels[0]).toMatchObject({
      authorIsVerified: true,
      authorUsername: 'creator',
      caption: 'A synthetic caption',
      code: 'SyntheticCode',
      durationMs: 12_500,
      mediaPk: '123',
    });
    expect(parsed?.reels[0].videoCandidates.map(candidate => candidate.height)).toEqual([
      1280,
      640,
    ]);
  });

  test('parses a newline-streamed payload and ignores unrelated chunks', () => {
    const text = [
      `)]}',${JSON.stringify({extensions: {is_final: false}})}`,
      JSON.stringify(responsePayload({cursor: 'stream-cursor', mediaPk: '456'})),
    ].join('\n');

    expect(parseReelsResponseText(text)).toMatchObject({
      pageInfo: {endCursor: 'stream-cursor', hasNextPage: true},
      reels: [{mediaPk: '456'}],
    });
  });

  test('extracts a pretty-printed JSON payload from a multipart response', () => {
    const boundary = 'graphql-boundary';
    const text = [
      `--${boundary}`,
      'Content-Type: application/json; charset=utf-8',
      '',
      JSON.stringify(responsePayload({cursor: 'multipart-cursor', mediaPk: '789'}), null, 2),
      `--${boundary}--`,
    ].join('\r\n');

    expect(parseReelsResponseText(text)).toMatchObject({
      pageInfo: {endCursor: 'multipart-cursor', hasNextPage: true},
      reels: [{mediaPk: '789'}],
    });
  });

  test('does not mistake an arbitrary paginated photo connection for Reels', () => {
    const payload = responsePayload();
    const media = payload.data.any_future_connection_name.edges[0].node.media;
    media.product_type = 'feed';
    media.media_type = 1;

    expect(parseReelsResponseText(JSON.stringify(payload))).toBeNull();
  });

  test('returns only the explicit metadata whitelist', () => {
    const reel = parseReelsResponseText(JSON.stringify(responsePayload()))?.reels[0];
    const serialized = JSON.stringify(reel);

    expect(serialized).not.toContain('must-not-cross-the-bridge');
    expect(serialized).not.toContain('also-must-not-cross-the-bridge');
    expect(serialized).not.toContain('sensitive manifest');
    expect(serialized).not.toContain('logging_info_token');
    expect(serialized).not.toContain('organic_tracking_token');
    expect(serialized).not.toContain('video_dash_manifest');
  });
});

describe('Doomscroller pagination request mutation', () => {
  test('changes only variables.after', () => {
    const before = new URLSearchParams({
      __req: '1f',
      doc_id: 'synthetic-document',
      fb_dtsg: 'page-memory-only-token',
      variables: JSON.stringify({
        after: 'old-cursor',
        before: 'keep-this-value',
        data: {container_module: 'clips_viewer_clips_tab', seen_reels: '[]'},
        first: 10,
      }),
    });

    const result = createPaginationBody(before.toString(), 'new-cursor');
    expect(result).not.toBeNull();
    const after = new URLSearchParams(result!);
    const variables = JSON.parse(after.get('variables')!);

    expect(variables).toEqual({
      after: 'new-cursor',
      before: 'keep-this-value',
      data: {container_module: 'clips_viewer_clips_tab', seen_reels: '[]'},
      first: 10,
    });
    expect(after.get('__req')).toBe('1f');
    expect(after.get('doc_id')).toBe('synthetic-document');
    expect(after.get('fb_dtsg')).toBe('page-memory-only-token');
  });

  test('arms only request bodies with an after field or pagination operation name', () => {
    expect(
      isPaginationRequestBody(
        new URLSearchParams({variables: JSON.stringify({after: null, first: 10})}).toString(),
      ),
    ).toBe(true);
    expect(
      isPaginationRequestBody(
        new URLSearchParams({
          fb_api_req_friendly_name: 'PolarisClipsTabPaginationQuery',
          variables: JSON.stringify({first: 10}),
        }).toString(),
      ),
    ).toBe(true);
    expect(
      isPaginationRequestBody(
        new URLSearchParams({variables: JSON.stringify({first: 10})}).toString(),
      ),
    ).toBe(false);
  });
});

describe('in-page pagination controller', () => {
  test('does not clone or consume unrelated fetch responses', async () => {
    let cloneCount = 0;
    const unrelatedResponse = {
      clone: () => {
        cloneCount++;
        throw new Error('Unrelated responses must not be cloned');
      },
    } as unknown as Response;
    const originalFetch = jest.fn(() => Promise.resolve(unrelatedResponse));
    class FakeXhr {
      addEventListener() {}
      open() {}
      send() {}
      setRequestHeader() {}
    }
    const windowObject: Record<string, unknown> = {
      ReactNativeWebView: {postMessage: () => undefined},
      fetch: originalFetch,
      location: {href: 'https://www.instagram.com/reels/', pathname: '/reels/'},
      clearTimeout,
      setTimeout,
    };
    vm.runInNewContext(DOOMSCROLL_CAPTURE_SCRIPT, {
      AbortController,
      DOMException,
      Headers,
      Map,
      Request,
      Response,
      Set,
      URL,
      URLSearchParams,
      XMLHttpRequest: FakeXhr,
      clearTimeout,
      setTimeout,
      window: windowObject,
    });

    const result = await (windowObject.fetch as typeof fetch)(
      'https://www.instagram.com/api/v1/feed/timeline/',
      {method: 'GET'},
    );
    expect(result).toBe(unrelatedResponse);
    expect(cloneCount).toBe(0);
  });

  test('waits for first-page persistence and aborts the active request on Stop', async () => {
    const messages: Array<Record<string, unknown>> = [];
    let internalRequestAborted = false;
    let requestCount = 0;
    const originalFetch = jest.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      requestCount++;
      if (requestCount === 1) {
        return Promise.resolve(new Response(JSON.stringify(responsePayload()), {status: 200}));
      }
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          internalRequestAborted = true;
          reject(new DOMException('Stopped', 'AbortError'));
        });
      });
    });
    class FakeXhr {
      addEventListener() {}
      open() {}
      send() {}
      setRequestHeader() {}
    }
    const windowObject: Record<string, unknown> = {
      ReactNativeWebView: {
        postMessage: (value: string) => messages.push(JSON.parse(value)),
      },
      fetch: originalFetch,
      location: {href: 'https://www.instagram.com/reels/', pathname: '/reels/'},
      clearTimeout,
      setTimeout,
    };
    const context = {
      AbortController,
      DOMException,
      Headers,
      Map,
      Request,
      Response,
      Set,
      URL,
      URLSearchParams,
      XMLHttpRequest: FakeXhr,
      clearTimeout,
      console,
      setTimeout,
      window: windowObject,
    };
    vm.runInNewContext(DOOMSCROLL_CAPTURE_SCRIPT, context);

    const variables = JSON.stringify({after: null, data: {container_module: 'clips_viewer_clips_tab'}});
    await (windowObject.fetch as typeof fetch)('https://www.instagram.com/graphql/query', {
      body: new URLSearchParams({
        fb_api_req_friendly_name: 'PolarisClipsTabPaginationQuery',
        variables,
      }).toString(),
      method: 'POST',
    });
    await flushPromises();
    const page = messages.find(message => message.type === 'page');
    expect(page).toBeDefined();

    const controller = windowObject.__airplaneModeDoomscroll as {
      ack: (id: string, result: {canContinue: boolean}) => void;
      start: () => Promise<void>;
      stop: () => void;
    };
    const running = controller.start();
    await flushPromises();
    expect(requestCount).toBe(1);

    controller.ack(page!.batchId as string, {canContinue: true});
    await flushPromises();
    expect(requestCount).toBe(2);

    controller.stop();
    await running;

    expect(internalRequestAborted).toBe(true);
    expect(messages.map(message => message.state)).toContain('stopping');
    expect(messages.map(message => message.state)).toContain('stopped');

    internalRequestAborted = false;
    const resumed = controller.start();
    await flushPromises();
    expect(requestCount).toBe(3);
    controller.stop();
    await resumed;
    expect(internalRequestAborted).toBe(true);
    expect(messages.map(message => message.detail)).not.toContain(
      'Instagram repeated the same page cursor.',
    );
  });
});

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
  await new Promise(resolve => setImmediate(resolve));
}
