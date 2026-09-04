export const DOOMSCROLL_CAPTURE_SCRIPT = String.raw`
  (function () {
    if (window.__airplaneModeDoomscroll && window.__airplaneModeDoomscroll.version === 2) {
      window.__airplaneModeDoomscroll.report();
      return true;
    }

    var originalFetch = window.fetch.bind(window);
    var originalXhrOpen = XMLHttpRequest.prototype.open;
    var originalXhrSend = XMLHttpRequest.prototype.send;
    var originalXhrSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
    var pendingAcks = new Map();
    var state = {
      accepting: true,
      capturedCount: 0,
      controller: null,
      deliveryChain: Promise.resolve({canContinue: true, stopReason: null}),
      emptyPages: 0,
      hasNextPage: false,
      lastState: 'idle',
      nextCursor: null,
      pageIndex: 0,
      running: false,
      seenCursors: new Set(),
      seenMedia: new Set(),
      starting: false,
      stopRequested: false,
      template: null,
      templateReady: false
    };

    function post(message) {
      try {
        window.ReactNativeWebView.postMessage(JSON.stringify(Object.assign({
          channel: 'airplanemode-doomscroll',
          version: 2
        }, message)));
      } catch (_) {}
    }

    function report(nextState, detail) {
      if (nextState) state.lastState = nextState;
      post({
        type: 'state',
        state: state.lastState,
        capturedCount: state.capturedCount,
        detail: detail || null,
        templateReady: state.templateReady
      });
    }

    function clean(value, limit) {
      if (typeof value !== 'string') return null;
      var result = value.replace(/\s+/g, ' ').trim();
      return result ? result.slice(0, limit || 20000) : null;
    }

    function safeNumber(value) {
      return typeof value === 'number' && isFinite(value) ? value : null;
    }

    function safeBoolean(value) {
      return value === true;
    }

    function object(value) {
      return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    }

    function array(value) {
      return Array.isArray(value) ? value : [];
    }

    function httpsUrl(value) {
      var candidate = clean(value, 12000);
      if (!candidate) return null;
      try {
        var parsed = new URL(candidate, window.location.href);
        return parsed.protocol === 'https:' ? parsed.href : null;
      } catch (_) {
        return null;
      }
    }

    function parsePayloads(text) {
      var cleaned = String(text || '').trim()
        .replace(/^for\s*\(\s*;\s*;\s*\)\s*;?/, '')
        .replace(/^\)\]\}',?/, '')
        .trim();
      if (!cleaned) return [];
      try {
        return [JSON.parse(cleaned)];
      } catch (_) {
        var linePayloads = cleaned.split(/\r?\n/).map(function (line) {
          return line.trim()
            .replace(/^for\s*\(\s*;\s*;\s*\)\s*;?/, '')
            .replace(/^\)\]\}',?/, '')
            .trim();
        }).filter(Boolean).map(function (line) {
          try { return JSON.parse(line); } catch (_) { return null; }
        }).filter(Boolean);
        return linePayloads.concat(extractJsonPayloads(cleaned));
      }
    }

    function extractJsonPayloads(value) {
      var payloads = [];
      var depth = 0;
      var escaped = false;
      var inString = false;
      var start = -1;
      for (var index = 0; index < value.length; index++) {
        var character = value[index];
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
        try { payloads.push(JSON.parse(value.slice(start, index + 1))); } catch (_) {}
        start = -1;
      }
      return payloads;
    }

    function findConnection(value, depth, allowEmpty) {
      if (!value || (depth || 0) > 9) return null;
      if (Array.isArray(value)) {
        for (var listIndex = 0; listIndex < value.length; listIndex++) {
          var listMatch = findConnection(value[listIndex], (depth || 0) + 1, allowEmpty);
          if (listMatch) return listMatch;
        }
        return null;
      }
      if (typeof value !== 'object') return null;
      if (Array.isArray(value.edges) && value.page_info && typeof value.page_info === 'object') {
        var reelEdge = value.edges.find(function (edge) {
          var media = object(object(object(edge).node).media);
          return Boolean(media.pk || media.id) && (media.product_type === 'clips' || media.media_type === 2);
        });
        if (reelEdge || (allowEmpty && value.edges.length === 0)) return value;
      }
      var values = Object.keys(value).map(function (key) { return value[key]; });
      for (var index = 0; index < values.length; index++) {
        if (typeof values[index] === 'string') continue;
        var match = findConnection(values[index], (depth || 0) + 1, allowEmpty);
        if (match) return match;
      }
      return null;
    }

    function candidates(value, limit) {
      var seen = new Set();
      return array(value).map(function (entry) {
        var candidate = object(entry);
        return {
          height: safeNumber(candidate.height) || 0,
          url: httpsUrl(candidate.url) || '',
          width: safeNumber(candidate.width) || 0
        };
      }).filter(function (candidate) {
        if (!candidate.url || seen.has(candidate.url)) return false;
        seen.add(candidate.url);
        return true;
      }).sort(function (left, right) {
        return (right.width * right.height) - (left.width * left.height);
      }).slice(0, limit);
    }

    function taggedUsers(value) {
      return array(value).slice(0, 30).map(function (entry) {
        var tag = object(entry);
        var user = object(tag.user);
        var id = clean(user.pk || user.id, 100);
        var username = clean(user.username, 100);
        var position = array(tag.position);
        if (!id || !username) return null;
        return {
          id: id,
          username: username,
          fullName: clean(user.full_name, 300),
          isVerified: safeBoolean(user.is_verified),
          position: position.length >= 2 ? [safeNumber(position[0]) || 0, safeNumber(position[1]) || 0] : null
        };
      }).filter(Boolean);
    }

    function coauthors(value) {
      return array(value).slice(0, 20).map(function (entry) {
        var user = object(entry);
        var id = clean(user.pk || user.id, 100);
        var username = clean(user.username, 100);
        if (!id) return null;
        return {
          id: id,
          username: username || '',
          fullName: clean(user.full_name, 300),
          isVerified: safeBoolean(user.is_verified)
        };
      }).filter(Boolean);
    }

    function locationJson(value) {
      var location = object(value);
      if (!Object.keys(location).length) return null;
      return JSON.stringify({
        id: clean(location.pk || location.id, 100),
        name: clean(location.name, 500),
        address: clean(location.address, 500),
        city: clean(location.city, 300),
        latitude: safeNumber(location.lat),
        longitude: safeNumber(location.lng)
      });
    }

    function durationMs(media) {
      if (safeNumber(media.video_duration) !== null) return Math.round(media.video_duration * 1000);
      var manifest = typeof media.video_dash_manifest === 'string' ? media.video_dash_manifest : '';
      var match = manifest.match(/mediaPresentationDuration="PT([0-9.]+)S"/i);
      return match ? Math.round(parseFloat(match[1]) * 1000) : null;
    }

    function cleanCaption(value, limit) {
      if (typeof value !== 'string') return null;
      var result = value
        .replace(/\r\n?/g, '\n')
        .replace(/[ \t]+\n/g, '\n')
        .replace(/\n{4,}/g, '\n\n\n')
        .trim();
      return result ? result.slice(0, limit || 20000) : null;
    }

    function normalizeMedia(value) {
      var media = object(value);
      var mediaId = clean(media.id, 200);
      var mediaPk = clean(media.pk, 100) || (mediaId ? mediaId.split('_')[0] : null);
      var code = clean(media.code, 100);
      if (!mediaPk || !code) return null;
      var user = object(media.user);
      var caption = object(media.caption);
      var clips = object(media.clips_metadata);
      var originalSound = object(clips.original_sound_info);
      var music = object(clips.music_info);
      var musicAsset = object(music.music_asset_info);
      var artist = object(originalSound.ig_artist);
      var safeMetadata = {
        aiLabel: clean(object(media.ai_label_info).label || object(media.ai_label_info).title || object(media.ai_label_info).text, 500),
        aiLabelPresent: Boolean(media.ai_label_info),
        clipsAttributionInfo: clean(object(media.clips_attribution_info).attribution_username, 500),
        commentingDisabled: typeof media.comments_disabled === 'boolean' ? media.comments_disabled : null,
        friendshipFollowing: typeof object(user.friendship_status).following === 'boolean' ? object(user.friendship_status).following : null,
        isSharedFromBasel: typeof media.is_shared_from_basel === 'boolean' ? media.is_shared_from_basel : null,
        likeAndViewCountsDisabled: typeof media.like_and_view_counts_disabled === 'boolean' ? media.like_and_view_counts_disabled : null,
        mediaType: safeNumber(media.media_type) || 0,
        showAccountTransparencyDetails: typeof user.show_account_transparency_details === 'boolean' ? user.show_account_transparency_details : null,
        wearableAttributionTitle: clean(object(media.wearable_attribution_info).attribution_title, 500)
      };
      return {
        mediaPk: mediaPk,
        mediaId: mediaId || mediaPk,
        code: code,
        permalink: 'https://www.instagram.com/reel/' + code + '/',
        authorId: clean(user.pk || user.id, 100) || '',
        authorUsername: clean(user.username, 100) || '',
        authorFullName: clean(user.full_name, 300),
        authorIsVerified: safeBoolean(user.is_verified),
        authorIsPrivate: safeBoolean(user.is_private),
        authorProfilePicUrl: httpsUrl(user.profile_pic_url),
        caption: cleanCaption(caption.text, 20000),
        takenAt: safeNumber(media.taken_at),
        mediaType: safeNumber(media.media_type) || 0,
        productType: clean(media.product_type, 100),
        inventorySource: clean(media.inventory_source, 300),
        originalWidth: safeNumber(media.original_width),
        originalHeight: safeNumber(media.original_height),
        durationMs: durationMs(media),
        likeCount: safeNumber(media.like_count),
        commentCount: safeNumber(media.comment_count),
        repostCount: safeNumber(media.media_repost_count),
        viewCount: safeNumber(media.view_count) !== null ? safeNumber(media.view_count) : safeNumber(media.play_count),
        fbLikeCount: safeNumber(media.fb_like_count),
        fbCommentCount: safeNumber(media.fb_comment_count),
        hasLiked: safeBoolean(media.has_liked),
        hasViewerSaved: safeBoolean(media.has_viewer_saved),
        canViewerReshare: safeBoolean(media.can_viewer_reshare),
        hasAudio: safeBoolean(media.has_audio),
        audioAssetId: clean(originalSound.audio_asset_id || musicAsset.audio_id, 200),
        audioTitle: clean(musicAsset.title || originalSound.original_audio_title, 500),
        audioArtistId: clean(artist.pk || artist.id, 100),
        audioArtistUsername: clean(musicAsset.display_artist || artist.username, 300),
        audioIsExplicit: safeBoolean(originalSound.is_explicit) || safeBoolean(musicAsset.is_explicit),
        coverCandidates: candidates(object(media.image_versions2).candidates, 4),
        videoCandidates: candidates(media.video_versions, 6),
        usertags: taggedUsers(object(media.usertags).in),
        coauthors: coauthors(media.coauthor_producers),
        locationJson: locationJson(media.location),
        safeMetadataJson: JSON.stringify(safeMetadata)
      };
    }

    function parseConnection(text, allowEmpty) {
      var payloads = parsePayloads(text);
      for (var payloadIndex = 0; payloadIndex < payloads.length; payloadIndex++) {
        var connection = findConnection(payloads[payloadIndex], 0, allowEmpty);
        if (!connection) continue;
        var pageInfo = object(connection.page_info);
        var reels = array(connection.edges).map(function (edge) {
          return normalizeMedia(object(object(object(edge).node).media));
        }).filter(Boolean);
        return {
          reels: reels,
          pageInfo: {
            endCursor: clean(pageInfo.end_cursor, 12000),
            hasNextPage: safeBoolean(pageInfo.has_next_page)
          }
        };
      }
      return null;
    }

    function requestInfo(url, method, body, headers, credentials) {
      return {
        url: String(url || ''),
        method: String(method || 'GET').toUpperCase(),
        body: typeof body === 'string' ? body : '',
        headers: headers || [],
        credentials: credentials || 'include'
      };
    }

    function isInstagramGraphQL(info) {
      if (!info || info.method !== 'POST' || !info.body) return false;
      try {
        var parsed = new URL(info.url, window.location.href);
        var instagramHost = parsed.hostname === 'instagram.com' || parsed.hostname.endsWith('.instagram.com');
        if (!instagramHost || parsed.pathname.indexOf('/graphql/query') === -1) return false;
        return hasReelsRequestHint(info) || /^\/reels(?:\/|$)/.test(window.location.pathname);
      } catch (_) {
        return false;
      }
    }

    function hasReelsRequestHint(info) {
      try {
        var params = new URLSearchParams(info.body);
        var friendly = params.get('fb_api_req_friendly_name') || '';
        var variables = JSON.parse(params.get('variables') || '{}');
        var container = clean(object(variables.data).container_module, 300) || '';
        return /clips|reels/i.test(friendly + ' ' + container);
      } catch (_) {
        return false;
      }
    }

    function looksLikeInstagramGraphQLRequest(input, init) {
      try {
        var method = String(
          (init && init.method) || (input && input.method) || 'GET'
        ).toUpperCase();
        if (method !== 'POST') return false;
        var value = typeof input === 'string' || input instanceof URL
          ? String(input)
          : (input && input.url ? String(input.url) : '');
        var parsed = new URL(value, window.location.href);
        var instagramHost = parsed.hostname === 'instagram.com' || parsed.hostname.endsWith('.instagram.com');
        return instagramHost && parsed.pathname.indexOf('/graphql/query') !== -1;
      } catch (_) {
        return false;
      }
    }

    function paginationCapable(info) {
      try {
        var params = new URLSearchParams(info.body);
        var variables = JSON.parse(params.get('variables') || '{}');
        var friendly = params.get('fb_api_req_friendly_name') || '';
        return Object.prototype.hasOwnProperty.call(variables, 'after') || /pagination/i.test(friendly);
      } catch (_) {
        return false;
      }
    }

    function captureTemplate(info) {
      state.template = {
        url: info.url,
        method: info.method,
        body: info.body,
        headers: info.headers,
        credentials: info.credentials || 'include'
      };
      state.templateReady = true;
    }

    function deliverPage(parsed, templateReady) {
      state.hasNextPage = parsed.pageInfo.hasNextPage;
      state.nextCursor = parsed.pageInfo.endCursor;
      var fresh = parsed.reels.filter(function (reel) {
        if (state.seenMedia.has(reel.mediaPk)) return false;
        state.seenMedia.add(reel.mediaPk);
        return true;
      });
      state.capturedCount += fresh.length;
      state.emptyPages = fresh.length ? 0 : state.emptyPages + 1;
      var batchId = Date.now().toString(36) + '-' + (++state.pageIndex).toString(36);
      post({
        type: 'page',
        batchId: batchId,
        pageIndex: state.pageIndex - 1,
        pageInfo: parsed.pageInfo,
        reels: fresh,
        templateReady: Boolean(templateReady)
      });
      return new Promise(function (resolve) {
        var timeout = window.setTimeout(function () {
          pendingAcks.delete(batchId);
          resolve({canContinue: false, stopReason: 'persistence-timeout'});
        }, 20000);
        pendingAcks.set(batchId, function (result) {
          window.clearTimeout(timeout);
          resolve(result || {canContinue: true, stopReason: null});
        });
      });
    }

    function queueDelivery(parsed, templateReady) {
      state.deliveryChain = state.deliveryChain.then(function (previous) {
        if (previous && previous.canContinue === false) return previous;
        return deliverPage(parsed, templateReady);
      });
      return state.deliveryChain;
    }

    function inspect(info, responseText) {
      if (!state.accepting) return;
      if (!isInstagramGraphQL(info)) return;
      var parsed = parseConnection(responseText, hasReelsRequestHint(info));
      if (!parsed) return;
      if (paginationCapable(info)) captureTemplate(info);
      queueDelivery(parsed, state.templateReady).then(function (acknowledgement) {
        if (!acknowledgement.canContinue) {
          state.running = false;
          var reason = acknowledgement.stopReason || 'storage-error';
          report(reason === 'low-storage' ? 'low-storage' : 'error', reason);
          return;
        }
        if (!state.running && !state.starting) {
          report(state.templateReady ? 'ready' : 'awaiting-pagination',
            state.templateReady ? null : 'Swipe once in Reels to prepare fetching.');
        }
      });
    }

    window.fetch = function (input, init) {
      if (!looksLikeInstagramGraphQLRequest(input, init)) {
        return originalFetch(input, init);
      }
      var request = null;
      try { request = new Request(input, init); } catch (_) {}
      var infoPromise = request ? request.clone().text().then(function (body) {
        var headers = [];
        request.headers.forEach(function (value, key) { headers.push([key, value]); });
        return requestInfo(request.url, request.method, body, headers, request.credentials);
      }).catch(function () { return null; }) : Promise.resolve(null);
      return originalFetch(input, init).then(function (response) {
        infoPromise.then(function (info) {
          if (!info || !isInstagramGraphQL(info)) return;
          response.clone().text().then(function (text) {
            inspect(info, text);
          }).catch(function () {});
        });
        return response;
      });
    };

    XMLHttpRequest.prototype.open = function (method, url) {
      this.__airplaneModeDoomscrollRequest = looksLikeInstagramGraphQLRequest(
        String(url || ''),
        {method: method}
      ) ? requestInfo(url, method, '', [], 'include') : null;
      return originalXhrOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
      if (this.__airplaneModeDoomscrollRequest) {
        this.__airplaneModeDoomscrollRequest.headers.push([String(name), String(value)]);
      }
      return originalXhrSetRequestHeader.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function (body) {
      var xhr = this;
      var info = xhr.__airplaneModeDoomscrollRequest;
      if (info) info.body = typeof body === 'string' ? body : '';
      xhr.addEventListener('loadend', function () {
        if (!info || !isInstagramGraphQL(info)) return;
        var text = '';
        try {
          text = xhr.responseType === 'json' ? JSON.stringify(xhr.response) : String(xhr.responseText || '');
        } catch (_) {}
        inspect(info, text);
      }, {once: true});
      return originalXhrSend.apply(this, arguments);
    };

    function paginationBody(cursor) {
      if (!state.template) return null;
      try {
        var params = new URLSearchParams(state.template.body);
        var variables = JSON.parse(params.get('variables') || '{}');
        variables.after = cursor;
        params.set('variables', JSON.stringify(variables));
        return params.toString();
      } catch (_) {
        return null;
      }
    }

    function delay(milliseconds, signal) {
      return new Promise(function (resolve, reject) {
        if (signal && signal.aborted) {
          reject(new DOMException('Stopped', 'AbortError'));
          return;
        }
        var timeout = window.setTimeout(function () {
          if (signal) signal.removeEventListener('abort', onAbort);
          resolve();
        }, milliseconds);
        function onAbort() {
          window.clearTimeout(timeout);
          if (signal) signal.removeEventListener('abort', onAbort);
          reject(new DOMException('Stopped', 'AbortError'));
        }
        if (signal) signal.addEventListener('abort', onAbort, {once: true});
      });
    }

    async function fetchNext(cursor) {
      var body = paginationBody(cursor);
      if (!body || !state.template) throw new Error('The captured pagination request is no longer usable.');
      var headers = new Headers();
      state.template.headers.forEach(function (pair) {
        try { headers.append(pair[0], pair[1]); } catch (_) {}
      });
      for (var attempt = 0; attempt < 4; attempt++) {
        if (!state.running) throw new DOMException('Stopped', 'AbortError');
        state.controller = new AbortController();
        var response = await originalFetch(state.template.url, {
          method: state.template.method,
          body: body,
          headers: headers,
          credentials: 'include',
          cache: 'no-store',
          signal: state.controller.signal
        });
        if (response.status === 401 || response.status === 403) {
          var authError = new Error('Instagram sign-in is required again.');
          authError.code = 'auth-required';
          throw authError;
        }
        if (response.status === 429 || response.status >= 500) {
          if (response.body && response.body.cancel) response.body.cancel().catch(function () {});
          if (attempt === 3) {
            var limited = new Error(response.status === 429 ? 'Instagram temporarily limited requests.' : 'Instagram is temporarily unavailable.');
            limited.code = response.status === 429 ? 'rate-limited' : 'server-error';
            throw limited;
          }
          var retryAfter = parseInt(response.headers.get('retry-after') || '0', 10);
          await delay(
            Math.min(retryAfter > 0 ? retryAfter * 1000 : Math.pow(2, attempt + 1) * 1000, 30000),
            state.controller.signal
          );
          continue;
        }
        if (!response.ok) throw new Error('Instagram returned HTTP ' + response.status + '.');
        return response.text();
      }
      throw new Error('Instagram pagination failed.');
    }

    async function start() {
      if (state.running || state.starting) return;
      if (!state.templateReady || !state.template) {
        report('awaiting-pagination', 'Swipe once in Reels to prepare fetching.');
        return;
      }
      state.starting = true;
      state.accepting = true;
      state.stopRequested = false;
      try {
        var pendingAcknowledgement = await state.deliveryChain;
        if (!pendingAcknowledgement.canContinue) {
          var pendingReason = pendingAcknowledgement.stopReason || 'storage-error';
          report(pendingReason === 'low-storage' ? 'low-storage' : 'error', pendingReason);
          return;
        }
        if (state.stopRequested) {
          report('stopped', 'Fetching stopped. Downloads will continue.');
          return;
        }
        if (!state.hasNextPage || !state.nextCursor) {
          report('complete', 'Instagram has no more Reels in this feed.');
          return;
        }
        state.starting = false;
        state.running = true;
        report('fetching', null);
        while (state.running && state.hasNextPage && state.nextCursor) {
          var cursor = state.nextCursor;
          if (state.seenCursors.has(cursor)) throw new Error('Instagram repeated the same page cursor.');
          var text = await fetchNext(cursor);
          if (/login_required|checkpoint_required|challenge_required/i.test(text)) {
            var loginError = new Error('Instagram sign-in is required again.');
            loginError.code = 'auth-required';
            throw loginError;
          }
          var parsed = parseConnection(text, true);
          if (!parsed) throw new Error('Instagram changed the Reels response format.');
          var acknowledgement = await queueDelivery(parsed, true);
          if (!acknowledgement.canContinue) {
            state.running = false;
            var reason = acknowledgement.stopReason || 'storage-error';
            report(reason === 'low-storage' ? 'low-storage' : 'error', reason);
            return;
          }
          state.seenCursors.add(cursor);
          if (state.stopRequested) break;
          if (state.emptyPages >= 2) throw new Error('Two pages contained no new Reels.');
          if (!state.hasNextPage || !state.nextCursor) break;
          await delay(1500, state.controller ? state.controller.signal : null);
        }
        state.running = false;
        report(
          state.stopRequested ? 'stopped' : 'complete',
          state.stopRequested
            ? 'Fetching stopped. Downloads will continue.'
            : 'All available Reels were captured.'
        );
      } catch (error) {
        state.running = false;
        if (error && error.name === 'AbortError') {
          report('stopped', 'Fetching stopped. Downloads will continue.');
          return;
        }
        var code = error && error.code ? error.code : 'capture-failed';
        var message = error && error.message ? error.message : 'Reels fetching stopped unexpectedly.';
        report(code === 'auth-required' ? 'auth-required' : (code === 'rate-limited' ? 'rate-limited' : 'error'), message);
        post({type: 'error', code: code, message: message, recoverable: code !== 'auth-required'});
      } finally {
        state.starting = false;
        state.controller = null;
      }
    }

    function stop() {
      state.accepting = false;
      if (!state.running && !state.starting) {
        report('stopped', 'Fetching stopped. Downloads will continue.');
        return;
      }
      report('stopping', null);
      state.stopRequested = true;
      state.running = false;
      if (state.controller) state.controller.abort();
    }

    function acknowledge(batchId, result) {
      var callback = pendingAcks.get(batchId);
      if (!callback) return;
      pendingAcks.delete(batchId);
      callback(result || {canContinue: true, stopReason: null});
    }

    window.__airplaneModeDoomscroll = {
      version: 2,
      start: start,
      stop: stop,
      ack: acknowledge,
      report: function () { report(); }
    };
    report('idle', null);
    return true;
  })();
`;
