/**
 * BitChord Lossless Locker — example addon source (v1)
 *
 * Serves an example hi-res FLAC library as a BitChord addon:
 *   /manifest.json, /search?q=..., /stream/{id}, /artwork/{id}
 *
 * Files live on an example host (lossless.example.com); this worker is just the
 * catalog + protocol adapter. To add more albums, append entries to CATALOG.
 *
 * Deploy: new Cloudflare Worker, paste this file, deploy, add the Worker URL
 * in BitChord > Sources.
 */

const BASE = "https://lossless.example.com/lossless";

// id, file, title, artist, album, duration (s), bitDepth, sampleRate
const CATALOG = [
  { id: "sample-001", file: "sample-001/01-open-your-eyes.flac", title: "Open Your Eyes", artist: "Close Your Eyes", album: "256th Note", duration: 179, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-002", file: "sample-001/02-drop-that.flac", title: "Drop That", artist: "Close Your Eyes", album: "256th Note", duration: 183, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-003", file: "sample-001/03-vibe-check.flac", title: "Vibe Check", artist: "Close Your Eyes", album: "256th Note", duration: 180, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-004", file: "sample-001/04-slowly-bleed.flac", title: "Slowly Bleed", artist: "Close Your Eyes", album: "256th Note", duration: 213, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-005", file: "sample-001/05-fool-for-you.flac", title: "Fool For You", artist: "Close Your Eyes", album: "256th Note", duration: 199, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-006", file: "sample-001/06-baby-bossa-nova.flac", title: "Baby Bossa Nova", artist: "Close Your Eyes", album: "256th Note", duration: 137, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-007", file: "sample-001/07-eternity.flac", title: "Eternity", artist: "Close Your Eyes", album: "256th Note", duration: 190, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-008", file: "sample-002/01-close-your-eyes.flac", title: "Close Your Eyes", artist: "Close Your Eyes", album: "ETERNALT", duration: 173, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-009", file: "sample-002/02-subtitled.flac", title: "Subtitled", artist: "Close Your Eyes", album: "ETERNALT", duration: 208, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-010", file: "sample-002/03-all-my-poetry.flac", title: "All My Poetry", artist: "Close Your Eyes", album: "ETERNALT", duration: 216, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-011", file: "sample-002/04-how-to-dance.flac", title: "How To Dance", artist: "Close Your Eyes", album: "ETERNALT", duration: 189, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-012", file: "sample-002/05-laid-back.flac", title: "Laid Back", artist: "Close Your Eyes", album: "ETERNALT", duration: 164, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-013", file: "sample-002/06-to-the-woods.flac", title: "To The Woods", artist: "Close Your Eyes", album: "ETERNALT", duration: 210, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-014", file: "sample-002/07-stay-4-good.flac", title: "Stay 4 Good", artist: "Close Your Eyes", album: "ETERNALT", duration: 189, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-015", file: "sample-002/08-upside-down.flac", title: "Upside Down", artist: "Close Your Eyes", album: "ETERNALT", duration: 188, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-016", file: "sample-003/01-x.flac", title: "X", artist: "Close Your Eyes", album: "blackout", duration: 195, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-017", file: "sample-003/02-sob.flac", title: "SOB", artist: "Close Your Eyes", album: "blackout", duration: 134, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-018", file: "sample-003/03-chic.flac", title: "CHIC", artist: "Close Your Eyes", album: "blackout", duration: 185, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-019", file: "sample-003/04-2-0.flac", title: "2.0", artist: "Close Your Eyes", album: "blackout", duration: 202, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-020", file: "sample-003/05-whos-dat.flac", title: "Who's Dat?", artist: "Close Your Eyes", album: "blackout", duration: 175, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-021", file: "sample-003/06-x-english-ver.flac", title: "X (English Ver.)", artist: "Close Your Eyes", album: "blackout", duration: 195, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-022", file: "sample-004/01-open-your-eyes.flac", title: "Open Your Eyes", artist: "Close Your Eyes", album: "Open Your Eyes", duration: 179, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-023", file: "sample-005/01-pose-spanish-ver.flac", title: "POSE (Spanish Ver.)", artist: "Close Your Eyes", album: "POSE (Spanish Ver.)", duration: 150, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-024", file: "sample-006/01-pose.flac", title: "POSE", artist: "Close Your Eyes", album: "OVEREXPOSED", duration: 150, bitDepth: 24, sampleRate: 48000 },
  { id: "sample-025", file: "sample-006/02-what-if-i-miss-love.flac", title: "What If I Miss Love?", artist: "Close Your Eyes", album: "OVEREXPOSED", duration: 189, bitDepth: 24, sampleRate: 48000 },
  { id: "sample-026", file: "sample-007/01-i-got-it.flac", title: "I GOT IT", artist: "Close Your Eyes", album: "The Judge Returns OST Part.1", duration: 150, bitDepth: 16, sampleRate: 44100 },
  { id: "sample-027", file: "sample-007/02-i-got-it-inst.flac", title: "I GOT IT (Inst.)", artist: "Close Your Eyes", album: "The Judge Returns OST Part.1", duration: 150, bitDepth: 16, sampleRate: 44100 },
  { id: "sample-028", file: "sample-008/01-paint-candy.flac", title: "Paint Candy", artist: "Close Your Eyes", album: "Snowy Summer", duration: 180, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-029", file: "sample-008/02-snowy-summer.flac", title: "Snowy Summer", artist: "Close Your Eyes", album: "Snowy Summer", duration: 177, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-030", file: "sample-008/03-yu.flac", title: "You", artist: "Close Your Eyes", album: "Snowy Summer", duration: 170, bitDepth: 24, sampleRate: 96000 },
  { id: "sample-031", file: "sample-008/04-bubble-tea.flac", title: "Bubble Tea", artist: "Close Your Eyes", album: "Snowy Summer", duration: 163, bitDepth: 24, sampleRate: 96000 },
];

const COVERS = {
  "sample-001": `${BASE}/sample-001/cover.jpg`,
  "sample-002": `${BASE}/sample-002/cover.jpg`,
  "sample-003": `${BASE}/sample-003/cover.jpg`,
  "sample-004": `${BASE}/sample-004/cover.jpg`,
  "sample-005": `${BASE}/sample-005/cover.jpg`,
  "sample-006": `${BASE}/sample-006/cover.jpg`,
  "sample-007": `${BASE}/sample-007/cover.jpg`,
  "sample-008": `${BASE}/sample-008/cover.jpg`,
};

function qualityFor(t) {
  return t.sampleRate >= 88200 ? "HI_RES_LOSSLESS" : "LOSSLESS";
}

function coverFor(t) {
  const dir = t.file.split("/")[0];
  return COVERS[dir] || null;
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

// Same relevance gate as the Monochrome worker: only claim tracks that
// actually match, return [] otherwise so BitChord falls back.
function relevanceOk(query, title, artist) {
  const tokens = normText(query).split(" ").filter((w) => w.length > 2);
  if (!tokens.length) return true;
  const words = new Set((normText(title) + " " + normText(artist)).split(" "));
  const hits = tokens.filter((t) => words.has(t)).length;
  return hits / tokens.length >= 0.6;
}

function toSearchItem(t) {
  return {
    id: t.id,
    title: t.title,
    artist: t.artist,
    album: t.album,
    duration: t.duration,
    cover: coverFor(t),
    format: "flac",
    audioQuality: qualityFor(t),
  };
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
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

    if (path === "/manifest.json") {
      return json({
        id: "dev.bitchord.lossless-locker",
        name: "BitChord Lossless Locker",
        version: "1.2.0",
        resources: ["search", "stream"],
        qualities: ["HI_RES_LOSSLESS", "LOSSLESS"],
        allowDownloads: 0,
        checkValidLossless: 0,
      });
    }

    if (path === "/healthz" || path === "/ping") {
      return json({ ok: true, tracks: CATALOG.length });
    }
    if (path === "/" || path === "") {
      return json({
        name: "BitChord Lossless Locker (example addon)",
        usage: "Paste this URL into BitChord > Sources",
        endpoints: ["/manifest.json", "/search?q=...", "/stream/{id}", "/artwork/{id}"],
      });
    }

    if (path === "/search") {
      const q = url.searchParams.get("q")?.trim();
      if (!q) return json({ tracks: [] });
      const limit = Math.min(Math.max(parseInt(url.searchParams.get("limit") || "25", 10) || 25, 1), 50);
      const items = CATALOG.filter((t) => relevanceOk(q, t.title, t.artist))
        .slice(0, limit)
        .map(toSearchItem);
      return json({ tracks: items });
    }

    if (path.startsWith("/artwork/")) {
      const id = decodeURIComponent(path.substring("/artwork/".length)).trim();
      const t = CATALOG.find((x) => x.id === id);
      const cover = t && coverFor(t);
      if (!cover) return new Response("No artwork", { status: 404 });
      return Response.redirect(cover, 302);
    }

    if (path.startsWith("/stream/")) {
      const id = decodeURIComponent(path.substring("/stream/".length)).trim();
      const t = CATALOG.find((x) => x.id === id);
      if (!t) return new Response("Not found", { status: 404 });
      const khz = (t.sampleRate / 1000).toFixed(1).replace(/\.0$/, "");
      const qLabel = qualityFor(t) === "HI_RES_LOSSLESS" ? "Hi-Res" : "Lossless";
      return json({
        url: `${BASE}/${t.file}`,
        codec: "flac",
        container: "flac",
        manifest: "none",
        encrypted: false,
        audioQuality: qualityFor(t),
        format: "flac",
        quality: `${qLabel} · ${t.bitDepth}-bit / ${khz} kHz`,
        artwork: coverFor(t),
        title: t.title,
        artist: t.artist,
        album: t.album,
        duration: t.duration,
        sampleRate: t.sampleRate,
        bitDepth: t.bitDepth,
      });
    }

    return new Response("Not found", { status: 404 });
  },
};
