// Offline tests for locker worker.js — no network (no fetch needed).
import worker from "./worker.js";

let failures = 0;
function check(name, cond, extra = "") {
  if (cond) console.log(`ok   ${name}`);
  else { failures++; console.log(`FAIL ${name} ${extra}`); }
}

const call = (path) => worker.fetch(new Request(`https://locker.test${path}`), {});

// manifest
{
  const r = await call("/manifest.json");
  const j = await r.json();
  check("manifest", r.status === 200 && j.id === "dev.bitchord.lossless-locker" && j.version === "1.2.0");
  check("manifest hires", j.qualities.includes("HI_RES_LOSSLESS"));
}

// search: exact-ish match
{
  const j = await (await call("/search?q=Slowly%20Bleed")).json();
  check("search finds slowly bleed", j.tracks.length === 1 && j.tracks[0].id === "sample-004", JSON.stringify(j).slice(0, 80));
  check("search hires flag", j.tracks[0].audioQuality === "HI_RES_LOSSLESS");
  check("search cover", (j.tracks[0].cover || "").endsWith("cover.jpg"));
}

// search: artist query matches all 7
{
  const j = await (await call("/search?q=Close%20Your%20Eyes&limit=50")).json();
  check("search artist 31 tracks", j.tracks.length === 31, j.tracks.length);
}

// search: junk query -> []
{
  const j = await (await call("/search?q=Thriller%20Michael%20Jackson")).json();
  check("search junk empty", j.tracks.length === 0, JSON.stringify(j).slice(0, 80));
  const j2 = await (await call("/search?q=")).json();
  check("search empty q", j2.tracks.length === 0);
}

// stream descriptor
{
  const r = await call("/stream/sample-004");
  const j = await r.json();
  check("stream 200", r.status === 200, r.status);
  check("stream url", j.url === "https://lossless.example.com/lossless/sample-001/04-slowly-bleed.flac", j.url);
  check("stream hires label", j.audioQuality === "HI_RES_LOSSLESS" && j.quality === "Hi-Res · 24-bit / 96 kHz", j.quality);
  check("stream specs", j.sampleRate === 96000 && j.bitDepth === 24 && j.codec === "flac");
  const r2 = await call("/stream/nope");
  check("stream 404", r2.status === 404, r2.status);
}

// artwork
{
  const r = await call("/artwork/sample-004");
  check("artwork 302", r.status === 302 && (r.headers.get("location") || "").endsWith("cover.jpg"));
  check("artwork 404", (await call("/artwork/nope")).status === 404);
}

// health
{
  const j = await (await call("/healthz")).json();
  check("healthz", j.ok === true && j.tracks === 31);
}

console.log(failures ? `\n${failures} FAILURES` : "\nALL PASS");
process.exit(failures ? 1 : 0);
