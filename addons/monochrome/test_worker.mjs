// Offline tests for worker.js v3 — stubs global fetch + Cache API, no network.
import worker from "./worker.js";

let failures = 0;
function check(name, cond, extra = "") {
  if (cond) console.log(`ok   ${name}`);
  else { failures++; console.log(`FAIL ${name} ${extra}`); }
}

// --- stub Cache API (in-memory) ------------------------------------------
const cacheStore = new Map();
globalThis.caches = {
  default: {
    async put(req, res) { cacheStore.set(String(req.url), await res.text()); },
    async match(req) {
      const v = cacheStore.get(String(req.url));
      return v === undefined ? undefined : new Response(v);
    },
  },
};

// --- stub upstream ---------------------------------------------------------
const routes = {
  // search: first upstream 429s, second answers with new tracks-api shape
  "https://u1.test/search/tracks?q=Deja%20Vu&limit=25": { status: 429 },
  "https://u2.test/search/tracks?q=Deja%20Vu&limit=25": {
    status: 200,
    json: { tracks: [
      { trackId: "154067222933803008", id: "154067222933803008", title: "Deja Vu",
        artistIds: ["1"], artistNames: ["TOMORROW X TOGETHER"],
        releaseId: "9", albumTitle: "minisode 3: TOMORROW",
        artwork: "https://img.test/cover.jpg", explicit: false, playable: true,
        duration: 215506, isrc: "USA2P2408081" },
      { trackId: "2", title: "Deja Vu (Sped Up)", artistNames: ["A", "B"], duration: 200000, playable: true },
      { trackId: "3", title: "Blocked", artistNames: ["X"], duration: 1000, playable: false },
    ] },
  },
  "https://u1.test/search/tracks?q=zzz&limit=25": { status: 500 },
  "https://u2.test/search/tracks?q=zzz&limit=25": { status: 500 },
  // search: upstream returns junk that doesn't match the query -> filtered to []
  "https://u1.test/search/tracks?q=Slowly%20Bleed&limit=25": {
    status: 200,
    json: { tracks: [
      { trackId: "1", title: "X", artistNames: ["CLOSE YOUR EYES"], playable: true, duration: 200000 },
      { trackId: "2", title: "Thriller", artistNames: ["Michael Jackson"], playable: true, duration: 300000 },
      { trackId: "3", title: "Internal Bleeding", artistNames: ["Obituary"], playable: true, duration: 182000 },
      { trackId: "4", title: "Wait and Bleed", artistNames: ["Slipknot"], playable: true, duration: 167000 },
    ] },
  },
  "https://u2.test/search/tracks?q=Slowly%20Bleed&limit=25": { status: 500 },
};

globalThis.fetch = async (target) => {
  const hit = routes[String(target)];
  if (!hit) return new Response("no route", { status: 500 });
  if (hit.json) return new Response(JSON.stringify(hit.json), { status: hit.status });
  return new Response("err", { status: hit.status });
};

const ENV = { UPSTREAMS: "https://u1.test,https://u2.test" };
const call = (path) => worker.fetch(new Request(`https://addon.test${path}`), ENV);

// 1. manifest v3
{
  const r = await call("/manifest.json");
  const j = await r.json();
  check("manifest 200 + id", r.status === 200 && j.id === "dev.monochrome.bitchord" && j.version === "3.0.0", r.status);
  check("manifest qualities", Array.isArray(j.qualities) && j.qualities.includes("LOSSLESS"));
}

// 2. search with failover (u1 429 -> u2 ok), new shape mapping
{
  const r = await call("/search?q=Deja%20Vu");
  const j = await r.json();
  check("search 200", r.status === 200, r.status);
  check("search 2 tracks (unplayable filtered)", j.tracks?.length === 2, JSON.stringify(j).slice(0, 160));
  const t = j.tracks[0];
  check("search id/title/artist", t.id === "154067222933803008" && t.title === "Deja Vu" && t.artist === "TOMORROW X TOGETHER",
    `${t.id} ${t.title} ${t.artist}`);
  check("search album+duration s+isrc+cover",
    t.album === "minisode 3: TOMORROW" && t.duration === 216 && t.isrc === "USA2P2408081" && t.cover === "https://img.test/cover.jpg",
    JSON.stringify(t).slice(0, 200));
  check("search artist join", j.tracks[1].artist === "A, B", j.tracks[1].artist);
  check("search lossless flag", t.audioQuality === "LOSSLESS" && t.format === "flac");
  check("search empty q", (await (await call("/search?q=")).json()).tracks.length === 0);
}

// 3. search all upstreams fail -> 502
{
  const r = await call("/search?q=zzz");
  check("search upstream dead 502", r.status === 502, r.status);
}

// 3b. search junk results filtered -> [] so BitChord falls back
{
  const r = await call("/search?q=Slowly%20Bleed");
  const j = await r.json();
  check("search junk filtered to empty", r.status === 200 && j.tracks.length === 0,
    `${r.status} ${JSON.stringify(j).slice(0, 100)}`);
}

// 4. stream descriptor: direct FLAC url, artwork from search cache
{
  const r = await call("/stream/154067222933803008");
  const j = await r.json();
  check("stream 200", r.status === 200, r.status);
  check("stream direct url", j.url === "https://u1.test/track/154067222933803008" || j.url === "https://u2.test/track/154067222933803008", j.url);
  check("stream flac", j.codec === "flac" && j.container === "flac" && j.encrypted === false);
  check("stream lossless", j.audioQuality === "LOSSLESS" && j.quality === "Lossless");
  check("stream artwork from cache", j.artwork === "https://img.test/cover.jpg", String(j.artwork));
  check("stream title/artist from cache", j.title === "Deja Vu" && j.artist === "TOMORROW X TOGETHER");
}

// 5. stream quality param accepted (maps to single FLAC tier)
{
  const r = await call("/stream/154067222933803008?quality=hires");
  const j = await r.json();
  check("quality param ok", r.status === 200 && j.audioQuality === "LOSSLESS", `${r.status} ${j.audioQuality}`);
}

// 6. stream unknown id (no cached artwork) still works, artwork undefined
{
  const r = await call("/stream/999");
  const j = await r.json();
  check("stream uncached id 200", r.status === 200 && typeof j.url === "string", r.status);
  check("stream uncached no artwork", j.artwork === undefined, String(j.artwork));
}

// 7. artwork redirect from cache
{
  const r = await call("/artwork/154067222933803008");
  check("artwork 302", r.status === 302 && r.headers.get("location") === "https://img.test/cover.jpg",
    `${r.status} ${r.headers.get("location")}`);
  const r2 = await call("/artwork/999");
  check("artwork missing 404", r2.status === 404, r2.status);
}

// 8. health + unknown route
{
  const h = await (await call("/healthz")).json();
  check("healthz", h.ok === true && h.upstreams === 2, JSON.stringify(h));
  const r = await call("/nope");
  check("unknown 404", r.status === 404, r.status);
}

console.log(failures ? `\n${failures} FAILURES` : "\nALL PASS");
process.exit(failures ? 1 : 0);
