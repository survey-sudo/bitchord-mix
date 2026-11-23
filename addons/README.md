# Standalone BitChord add-ons (Cloudflare Workers)

Three self-contained Cloudflare Workers live here. They are **not** part of the
Gradle build: deploy one to Cloudflare, then paste its `https://…workers.dev`
URL into BitChord (Sources → *add addon*, or Settings → Artist signatures for
`signatures`). Each folder has its own `wrangler.toml`; tests are offline and
need only Node.js (no Wrangler login, no network).

| Add-on | Protocol | What it does |
| --- | --- | --- |
| [`locker/`](locker/) | BitChord addon v1 | Example hi-res FLAC catalog: `/manifest.json`, `/search?q=…`, `/stream/{id}`, `/artwork/{id}`. The catalog and cover host in `worker.js` are deliberately `example.com` placeholders — edit `CATALOG`/`BASE` before using it. `search-filler-fix.patch.md` is a historical note about the relevance gate of an older deployed v2 worker, **not** a patch to this v1 source. |
| [`monochrome/`](monochrome/) | BitChord addon v3 | Adapter for the Monochrome tracks API (`https://tracks.monochrome.st`): manifest, search with upstream failover, direct-FLAC `/stream/{id}`, and cached `/artwork/{id}`. See [`monochrome/README.md`](monochrome/README.md) for the v3 dialect and deploy notes. |
| [`signatures/`](signatures/) | Artist-signature provider | `/v1/health` plus `/v1/signatures?artist=…`, backed by Wikimedia Commons search with an `OVERRIDES` map. The app sets the base URL under Settings → Artist signatures; it saves the URL only when `/v1/health` returns 2xx. `bench/` contains offline benchmark scripts, `live_smoke.mjs` is an optional network smoke test. |

## Configure and deploy

1. `cd` into the add-on folder.
2. Paste `worker.js` into a new Worker in the Cloudflare dashboard, **or** run
   `npx wrangler deploy` to use the bundled `wrangler.toml`.
3. Copy the deployed `*.workers.dev` URL into the corresponding BitChord
   settings screen. No shared secrets or API tokens are stored in this repo;
   any deployment token is supplied transiently by its maintainer.

Optional Worker variables:

- `monochrome`: `UPSTREAMS` — comma-separated tracks-API instance URLs
  (default `https://tracks.monochrome.st`; see the commented `[vars]` block in
  `monochrome/wrangler.toml`).
- `locker`: edit `CATALOG`, `COVERS` and `BASE` in `worker.js` to point at your
  own FLAC files.
- `signatures`: edit `OVERRIDES` in `worker.js` to add manual entries.

## Test

Every add-on has an offline test that stubs `fetch` (and the Cache API where
used), so it runs without a Cloudflare account or network access:

```bash
cd addons/locker      && node test_worker.mjs
cd addons/monochrome  && node test_worker.mjs
cd addons/signatures  && node test_worker.mjs
```

A plain `node --check worker.js` is also enough for a syntax-only check.
`signatures/live_smoke.mjs` is the only script that talks to a live endpoint;
run it against your own deployment, not as part of the offline suite.

These workers are archived reference/deployment material. Related historical
notes live in [`archive/docs/`](../archive/docs/), and other legacy scripts in
[`archive/tools/`](../archive/tools/).
