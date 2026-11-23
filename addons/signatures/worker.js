/**
 * BitChord artist-signature API example.
 *
 * Implements the contract BitChord's ArtistSignatures.kt expects from a
 * user-configured signature provider:
 *
 *   GET /v1/health                    -> 2xx (the settings screen saves the
 *                                          base URL only when this answers 2xx)
 *   GET /v1/signatures?artist={name}  -> {"signatures":[{...}]}  (BitChord
 *                                          ranks them itself; empty array
 *                                          when nothing is known)
 *
 * Each signature object is read tolerantly by the app; the fields below are
 * the ones it actually parses:
 *   asset: { url, format, width, height }   (or top-level width/height)
 *   license: { status: "known" | "requires_review" | ... }
 *   verification: "artist_verified" | "verified" | "unverified"
 *
 * Backend: Wikimedia Commons file search (intitle:signature "Artist Name")
 * plus a manual OVERRIDES map for artists Commons doesn't cover.
 * Commons files are freely licensed -> license.status "known".
 *
 * Deploy: new Cloudflare Worker, paste this file (or `wrangler deploy`
 * with the bundled wrangler.toml), then paste the Worker URL into
 * BitChord > Settings > Artist signatures > "tap to set the API URL".
 */

const COMMONS_API = "https://commons.wikimedia.org/w/api.php";
const UA = "bitchord-signatures/1.0 (BitChord signature API example)";

// In-memory cache: normalized artist -> { at, signatures }
const CACHE = new Map();
const CACHE_TTL_MS = 6 * 60 * 60 * 1000;

/**
 * Manual entries for artists with no Commons coverage.
 * Key = normalized artist name (see norm()). Value = array of signature
 * objects in the same shape the Commons backend returns.
 *
 * Example:
 *   "close your eyes": [
 *     {
 *       asset: { url: "https://example.com/sample-signature.svg", format: "svg", width: 1200, height: 300 },
 *       width: 1200, height: 300,
 *       license: { status: "known" },
 *       verification: "unverified",
 *       source: "manual",
 *     },
 *   ],
 */
const OVERRIDES = {
  // e.g. "manuel turizo": [ ... ],
};

function norm(s) {
  return String(s || "")
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function formatOf(url, fallback = "") {
  const fromUrl = String(url || "").split("?")[0].split(".").pop() || "";
  const f = (fallback || fromUrl).toLowerCase();
  return ["svg", "png", "jpg", "jpeg", "webp", "gif"].includes(f) ? f : f;
}

// Same ranking order as BitChord's own picker, so the first entry is the
// best even for a client that doesn't rank: known license, SVG, verified,
// panoramic (wide signature strips read best small and centered).
function rankSig(s) {
  const status = (s.license && s.license.status) || "";
  const licenseScore = status === "known" ? 2 : status === "requires_review" ? 1 : 0;
  const fmt = (s.asset && s.asset.format) || s.format || "";
  const formatScore = fmt === "svg" ? 2 : fmt === "png" ? 1 : 0;
  const ver = (s.verification || "").toLowerCase();
  const verScore = ver === "artist_verified" ? 3 : ver === "verified" ? 2 : ver === "unverified" ? 1 : 0;
  const w = s.width || (s.asset && s.asset.width) || 0;
  const h = s.height || (s.asset && s.asset.height) || 0;
  const panoramic = w > 0 && h > 0 && w / h >= 1.4 ? 1 : 0;
  return { score: licenseScore * 1000 + formatScore * 100 + verScore * 10 + panoramic, area: w * h };
}

function bestFirst(sigs) {
  return sigs
    .map((s) => ({ s, ...rankSig(s) }))
    .sort((a, b) => b.score - a.score || b.area - a.area)
    .map((x) => x.s);
}

async function commonsLookup(artist) {
  const params = new URLSearchParams({
    action: "query",
    format: "json",
    generator: "search",
    gsrsearch: `intitle:signature "${artist}"`,
    gsrnamespace: "6",
    gsrlimit: "20",
    prop: "imageinfo",
    iiprop: "url|size",
  });
  const res = await fetch(`${COMMONS_API}?${params.toString()}`, {
    headers: { "user-agent": UA, accept: "application/json" },
  });
  if (!res.ok) throw new Error(`commons http ${res.status}`);
  const data = await res.json();
  const pages = (data.query && data.query.pages) || {};
  const needle = norm(artist).replace(/ /g, "");
  const out = [];
  for (const page of Object.values(pages)) {
    const info = (page.imageinfo && page.imageinfo[0]) || {};
    const url = info.url || "";
    if (!/^https:\/\//.test(url)) continue;
    // Precision guard: the file title must actually name the artist
    // (the quoted search is usually enough, but short names like
    // "TWS"/"Yves" deserve the extra check).
    const hay = norm(page.title || "").replace(/ /g, "");
    if (needle.length >= 3 && !hay.includes(needle)) continue;
    const fmt = formatOf(url, info.ext || "");
    out.push({
      asset: { url, format: fmt, width: info.width || 0, height: info.height || 0 },
      width: info.width || 0,
      height: info.height || 0,
      license: { status: "known" },
      verification: "unverified",
      source: "wikimedia-commons",
      title: page.title || "",
    });
  }
  return bestFirst(out);
}

async function signaturesFor(artist) {
  const key = norm(artist);
  if (!key) return [];
  const now = Date.now();
  const hit = CACHE.get(key);
  if (hit && now - hit.at < CACHE_TTL_MS) return hit.signatures;

  let sigs = [];
  if (OVERRIDES[key]) {
    sigs = bestFirst(OVERRIDES[key]);
  } else {
    try {
      sigs = await commonsLookup(artist);
    } catch (e) {
      sigs = [];
    }
  }
  CACHE.set(key, { at: now, signatures: sigs });
  // keep the map small
  if (CACHE.size > 500) {
    const oldest = [...CACHE.entries()].sort((a, b) => a[1].at - b[1].at)[0];
    if (oldest) CACHE.delete(oldest[0]);
  }
  return sigs;
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "access-control-allow-origin": "*",
    },
  });
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
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

    // The settings screen's save-gate: must answer 2xx.
    if (path === "/v1/health") {
      return json({ ok: true, service: "bitchord-signatures", source: "wikimedia-commons", overrides: Object.keys(OVERRIDES).length });
    }

    if (path === "/v1/signatures") {
      const artist = (url.searchParams.get("artist") || "").trim();
      if (!artist) return json({ signatures: [] });
      const signatures = await signaturesFor(artist);
      return json({ signatures });
    }

    if (path === "/" || path === "") {
      return json({
        name: "BitChord artist-signature API (example)",
        usage: "Paste this URL into BitChord > Settings > Artist signatures > set the API URL",
        contract: "GET /v1/health -> 2xx; GET /v1/signatures?artist=NAME -> {signatures:[...]}",
        endpoints: ["/v1/health", "/v1/signatures?artist=..."],
      });
    }

    return new Response("Not found", { status: 404 });
  },
};
