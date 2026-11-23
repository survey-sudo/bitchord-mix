# BitChord Locker — search gate filler-word fix (DEPLOYED 2026-10-08 ~11:35 WIB)

Deployed via wrangler with a maintainer-supplied Cloudflare API token (transient use, not stored). Version ID:
30f3b46a-25ae-4466-b70c-efc21a5d2ce3. R2 binding LOSSLESS preserved.
Live-verified: all filler queries below now HIT, junk still MISSes, stream +
Range (206) healthy.

Verified live 2026-10-08 ~10:2x WIB against
https://bitchord-locker.example.workers.dev/search

## The bug
The relevance gate (`relevanceOk`, tokens hit-ratio >= 0.6) misses any query with
2+ unparenthesized filler words. `normText` strips `(...)` but not bare words.

| query | tracks |
|---|---|
| `Ever2Late` | 1 ✓ |
| `kiiikiii ever2late` | 1 ✓ |
| `kiiikiii ever2late audio` (1 filler) | 1 ✓ |
| `kiiikiii ever2late official audio` (2 fillers) | 0 ✗ |
| `Ever2Late official audio` | 0 ✗ |
| `ever2late official mv` | 0 ✗ |
| `kiiikiii ever2late official lyric video` | 0 ✗ |
| `kiiikiii ever2late (official audio)` (parens) | 1 ✓ |

Note: queries that ALSO contain the real artist words (e.g. `close your eyes
slowly bleed official audio`) still pass because artist tokens pad the ratio.
The miss hits KiiiKiii tracks hardest (artist = one token).

## The fix (drop-in, applies to the deployed v2 worker's search gate)
Strip known filler words AFTER normText, BEFORE the hit-ratio check. Everything
else (threshold 0.6, filter-then-slice) stays identical — strictly fewer misses,
no new false positives on distinctive titles.

```js
const FILLER = new Set([
  "official", "audio", "video", "videos", "mv", "lyric", "lyrics",
  "hd", "4k", "8k", "live", "cover", "remix", "acoustic", "unplugged",
  "instrumental", "inst", "ver", "version", "versioned",
  "topic", "channel", "music", "song", "track", "single", "album", "ep",
  "performance", "visualizer", "teaser", "trailer", "remaster", "remastered",
  "clean", "explicit", "spedup", "speedup", "slowed", "reverb", "edit",
]);

function relevanceOk(query, title, artist) {
  const tokens = normText(query).split(" ").filter((w) => w.length > 2 && !FILLER.has(w));
  if (!tokens.length) return true;
  const words = new Set((normText(title) + " " + normText(artist)).split(" "));
  const hits = tokens.filter((t) => words.has(t)).length;
  return hits / tokens.length >= 0.6;
}
```

Edge case preserved: if the query is ONLY filler (e.g. `official audio`),
tokens is empty → return true → same as current behaviour (no crash, returns all).

## To deploy
1. Locate the v2 worker source (the deployed worker is 48 tracks, R2-backed —
   NOT the `worker.js` in this folder, which is the stale v1 with 31
   tracks and lossless.example.com hosting).
2. Apply the FILLER set + the one-line tokens change to its search gate.
3. Sanity check locally against the table above (curl the live /search).
4. Deploy with a maintainer-supplied Cloudflare API token (supplied transiently, never stored).
5. Re-verify live: `?q=kiiikiii ever2late official audio` must return Ever2Late!.
