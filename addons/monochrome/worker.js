/**
 * BitChord <-> Monochrome (tracks API) adapter — v3
 *
 * BitChord speaks:  /manifest.json, /search?q=..., /stream/{id}
 * Monochrome speaks: GET /search/tracks?q=...&limit=..., GET /track/{id} (raw FLAC bytes)
 *
 * Backend: https://tracks.monochrome.st — the live API behind https://monochrome.st
 * (v2.10+). The old hifi-api v2.x instances (monochrome-api.samidy.com etc.)
 * all died in early Oct 2026 when Tidal revoked their OAuth tokens, so v3
 * speaks the new API dialect instead:
 *   - search param is `q` (not `s`), route is /search/tracks
 *   - track ids are numeric snowflakes, /track/{id} IS the FLAC stream
 *   - artwork ships inside search results (cached for /stream + /artwork)
 *
 * v3 keeps:
 *  - multi-upstream failover (429 / 5xx / 401 / 403 / timeouts -> next instance)
 *  - ?quality= negotiation (upstream serves one FLAC tier; all map to LOSSLESS)
 *  - cover art via Cache API (search-time) -> stream descriptor + /artwork/{id}
 *
 * Deploy: paste into the Cloudflare Worker (dashboard or `npx wrangler deploy`),
 * or PUT via the API. Then paste the Worker URL into BitChord > Sources.
 *
 * Optional Worker variable: UPSTREAMS = comma-separated instance URLs.
 */

const DEFAULT_UPSTREAMS = [
  "https://tracks.monochrome.st",
];

// Statuses worth failing over to the next upstream for.
// 404 is NOT here: it means "not found", which is terminal.
const FAILOVER_STATUSES = new Set([400, 401, 402, 403, 408, 409, 425, 429, 500, 502, 503, 504]);

const QUALITY_ALIASES = {
  hires: "LOSSLESS",
  "hi-res": "LOSSLESS",
  hireslossless: "LOSSLESS",
  hi_res_lossless: "LOSSLESS",
  "24bit": "LOSSLESS",
  "24-bit": "LOSSLESS",
  lossless: "LOSSLESS",
  flac: "LOSSLESS",
  cd: "LOSSLESS",
  "16bit": "LOSSLESS",
  high: "LOSSLESS",
  aac: "LOSSLESS",
  320: "LOSSLESS",
  low: "LOSSLESS",
  96: "LOSSLESS",
};

function getUpstreams(env) {
  const raw = (env && env.UPSTREAMS) || "";
  const list = raw
    .split(",")
    .map((s) => s.trim().replace(/\/+$/, ""))
    .filter((s) => /^https?:\/\//i.test(s));
  return list.length ? list : DEFAULT_UPSTREAMS.slice();
}

// Round-robin the starting upstream so load spreads across instances.
let rrCounter = 0;
function rotated(list) {
  if (list.length <= 1) return list.slice();
  const start = (rrCounter = (rrCounter + 1) % list.length);
  return list.slice(start).concat(list.slice(0, start));
}

/**
 * GET `path` from the first upstream that answers successfully.
 * Returns { ok:true, res, upstream } on 2xx,
 *         { ok:false, status:404 } when every upstream 404s,
 *         { ok:false, status:502 } when all fail otherwise.
 */
async function upstreamGet(upstreams, path, { timeoutMs = 9000 } = {}) {
  let notFound = 0;
  let lastStatus = 0;
  for (const base of rotated(upstreams)) {
    let res;
    try {
      res = await fetch(base + path, {
        signal: AbortSignal.timeout(timeoutMs),
        headers: {
          accept: "application/json",
          "user-agent": "bitchord-monochrome-addon/3.0",
        },
      });
    } catch {
      continue; // DNS / timeout / connection reset -> next upstream
    }
    if (res.status === 404) {
      notFound++;
      continue;
    }
    if (FAILOVER_STATUSES.has(res.status)) {
      lastStatus = res.status;
      try { await res.arrayBuffer(); } catch { /* drain */ }
      continue;
    }
    if (res.ok) return { ok: true, res, upstream: base };
    lastStatus = res.status;
    try { await res.arrayBuffer(); } catch { /* drain */ }
  }
  if (notFound === upstreams.length) return { ok: false, status: 404 };
  return { ok: false, status: 502, upstreamStatus: lastStatus };
}

function normalizeQuality(v) {
  if (!v) return null;
  const key = String(v).trim().toLowerCase().replace(/[\s_]+/g, "");
  return QUALITY_ALIASES[key] || null;
}

function msToSec(ms) {
  if (ms == null) return undefined;
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return undefined;
  return n > 1000 ? Math.round(n / 1000) : Math.round(n);
}

function normText(s) {
  return String(s || "")
    .toLowerCase()
    .replace(/\([^)]*\)/g, " ")
    .replace(/[’‘`]/g, "")
    .replace(/[^a-z0-9 ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// The upstream search is loose/fuzzy and happily returns unrelated tracks
// (e.g. "Thriller" for "Close Your Eyes Slowly Bleed"). BitChord treats a
// non-empty result as authoritative and stops falling back to its other
// sources, so junk results actively break playback of tracks the backend
// doesn't have. Gate: keep only results sharing at least half the query's
// significant words with title/artist; return [] otherwise so BitChord
// falls back.
function relevanceOk(query, title, artist) {
  const tokens = normText(query).split(" ").filter((w) => w.length > 2);
  if (!tokens.length) return true; // can't judge a tiny query; keep
  const words = new Set((normText(title) + " " + normText(artist)).split(" "));
  const hits = tokens.filter((t) => words.has(t)).length;
  // Word (not substring) match, bar at 0.6: "bleed" must not match
  // "bleeding"/"Wait and Bleed". Failure mode is safe: [] lets BitChord
  // fall back to its other sources.
  return hits / tokens.length >= 0.6;
}

function mapSearchTrack(t) {
  const artistNames = Array.isArray(t?.artistNames)
    ? t.artistNames.filter(Boolean)
    : [];
  return {
    id: String(t.trackId || t.id || ""),
    title: t.title ?? "",
    artist: artistNames.join(", "),
    album: t.albumTitle || t.releaseTitle || "",
    duration: msToSec(t.duration),
    isrc: t.isrc || undefined,
    cover: t.artwork || undefined,
    format: "flac",
    audioQuality: "LOSSLESS",
  };
}

// ---- artwork cache (populated at search time, read at stream/artwork time) ----
function artworkCacheKey(id) {
  return `https://bitchord-monochrome.internal/artwork/${encodeURIComponent(id)}`;
}

async function cacheArtwork(id, info) {
  try {
    const cache = caches.default;
    await cache.put(
      new Request(artworkCacheKey(id)),
      new Response(JSON.stringify(info), {
        headers: {
          "content-type": "application/json",
          "cache-control": "public, max-age=86400",
        },
      })
    );
  } catch { /* cache unavailable (e.g. unit tests) -> ignore */ }
}

async function cachedArtwork(id) {
  try {
    const cache = caches.default;
    const res = await cache.match(new Request(artworkCacheKey(id)));
    if (!res) return null;
    return await res.json();
  } catch {
    return null;
  }
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      ...extraHeaders,
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const upstreams = getUpstreams(env);
    const path = url.pathname;

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET, OPTIONS",
          "access-control-allow-headers": "*",
        },
      });
    }

    // ---- manifest ----
    if (path === "/manifest.json") {
      return json({
        id: "dev.monochrome.bitchord",
        name: "Monochrome Lossless",
        version: "3.0.0",
        resources: ["search", "stream"],
        qualities: ["LOSSLESS"],
        allowDownloads: 0,
        checkValidLossless: 0,
      });
    }

    // ---- health / root ----
    if (path === "/healthz" || path === "/ping") {
      return json({ ok: true, upstreams: upstreams.length });
    }
    if (path === "/" || path === "") {
      return json({
        name: "Monochrome Lossless (BitChord addon)",
        usage: "Paste this URL into BitChord > Sources",
        endpoints: ["/manifest.json", "/search?q=...", "/stream/{id}?quality=lossless", "/artwork/{id}"],
      });
    }

    // ---- search ----
    if (path === "/search") {
      const q = url.searchParams.get("q")?.trim();
      if (!q) return json({ tracks: [] });
      const limit = Math.min(Math.max(parseInt(url.searchParams.get("limit") || "25", 10) || 25, 1), 50);

      const r = await upstreamGet(upstreams, `/search/tracks?q=${encodeURIComponent(q)}&limit=${limit}`);
      if (!r.ok) {
        return new Response(r.status === 404 ? "Not found" : "Upstream search failed", { status: r.status });
      }
      let body;
      try {
        body = await r.res.json();
      } catch {
        return new Response("Upstream search failed", { status: 502 });
      }
      const items = (body?.tracks ?? [])
        .filter((t) => t && t.playable !== false)
        .map(mapSearchTrack)
        .filter((t) => t.id)
        .filter((t) => relevanceOk(q, t.title, t.artist)); // drop junk matches; [] lets BitChord fall back
      // Remember artwork for /stream/{id} and /artwork/{id}.
      await Promise.all(
        items.slice(0, 50).map((t) =>
          t.cover ? cacheArtwork(t.id, { artwork: t.cover, title: t.title, artist: t.artist }) : null
        )
      );
      return json({ tracks: items });
    }

    // ---- artwork redirect ----
    if (path.startsWith("/artwork/")) {
      const id = decodeURIComponent(path.substring("/artwork/".length)).trim();
      if (!id) return new Response("Not found", { status: 404 });
      const info = await cachedArtwork(id);
      if (info?.artwork) return Response.redirect(info.artwork, 302);
      return new Response("No artwork", { status: 404 });
    }

    // ---- stream ----
    if (path.startsWith("/stream/")) {
      const id = decodeURIComponent(path.substring("/stream/".length)).trim();
      if (!id) return new Response("Not found", { status: 404 });

      // The tracks API serves a single FLAC tier; every quality alias maps to it.
      normalizeQuality(url.searchParams.get("quality") || url.searchParams.get("q") || url.searchParams.get("tier"));

      // /track/{id} IS the audio (verified: fLaC magic bytes, octet-stream).
      // The official web client hands this URL to the player directly, so we
      // do the same — no manifest decoding needed on the new backend.
      const upstream = rotated(upstreams)[0];
      const streamUrl = `${upstream}/track/${encodeURIComponent(id)}`;
      const info = await cachedArtwork(id);

      return json({
        url: streamUrl,
        codec: "flac",
        container: "flac",
        manifest: "none",
        encrypted: false,
        audioQuality: "LOSSLESS",
        format: "flac",
        quality: "Lossless",
        artwork: info?.artwork,
        title: info?.title,
        artist: info?.artist,
      });
    }

    return new Response("Not found", { status: 404 });
  },
};
