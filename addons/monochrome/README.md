# BitChord ↔ Monochrome adapter (v3)

Cloudflare Worker that makes Monochrome's tracks API speak BitChord's
addon protocol:

- `GET /manifest.json` → addon description
- `GET /search?q=...&limit=25` → `{ tracks: [...] }`
- `GET /stream/{id}` → playable FLAC URL + codec metadata
- `GET /artwork/{id}` → 302 redirect to the track's cover art

Backend: `https://tracks.monochrome.st` — the live API behind
https://monochrome.st. (The old hifi-api v2.x instances all died in Oct 2026
when Tidal revoked their tokens; v3 speaks the new dialect: `q` param,
`/search/tracks`, `/track/{id}` is the raw FLAC stream.)

## Deploy (dashboard, ~30 seconds)

1. Open <https://dash.cloudflare.com> → **Workers & Pages** → **Create** → **Create Worker**.
2. Delete the template, paste the entire contents of `worker.js`, **Deploy**.
3. Copy the `*.workers.dev` URL and paste it into **BitChord → Sources → add addon**.

## Deploy (CLI)

```bash
npx wrangler deploy   # from this folder
```

## Configuration

Optional Worker variable `UPSTREAMS`: comma-separated tracks-API instance
URLs. Default: `https://tracks.monochrome.st` (with round-robin + failover
ready if more instances appear).

## Behavior notes (v3)

- **Failover:** network errors, timeouts, and HTTP 400/401/403/408/409/425/429/5xx
  move to the next upstream. 404 is terminal (not found).
- **Quality:** the tracks API serves a single FLAC tier; `/stream/{id}` hands
  back the direct audio URL (`/track/{id}` is the FLAC itself — verified `fLaC`
  magic bytes over the wire). `?quality=` is accepted and maps to `LOSSLESS`.
- **Cover art:** ships inside search results; the worker caches id → artwork
  (Cache API) so `/stream/{id}` and `/artwork/{id}` can serve it.

## Tests

Offline, no network: `node test_worker.mjs` (25 assertions; stubs `fetch` and
the Cache API; covers manifest, search failover, new response shape, stream
descriptor, artwork cache).
