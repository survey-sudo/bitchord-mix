# Archive material

This folder holds the supporting material from the original `~/Bitchord` unified archive, kept here so one repo is enough to work from. It is separate from the Gradle source tree at the repo root to avoid name collisions with the source's own `docs/` and `tools/`.

| Folder | Files | Contents |
| --- | --- | --- |
| `docs/` | 80 | Handovers, diagnosis, build reports, release notes, agent instructions and Automix/watchdog research, including the 14 staged 2026-11-17/18/19 handovers and `STATUS_VC92.md`. |
| `patches/` | 47 | djmix / expanded / paused / graceful-exit patches, including `patch_djmix10_{a,b,c,d}.py` and `bitchord-djmix10.patch`. |
| `tools/` | 7 | Telemetry diff, dataviz, APK audit and device verification scripts. |
| `refs/` | 5 | Apple Music AutoMix research (md + pdf) and the engineering zip. |
| `screenshots/` | 5 | Device screenshots from the original archive. |

Total: 145 files including this README. `docs/` and `patches/` alone hold 127.

Related material outside `archive/`:

- [`addons/`](../addons/) — the standalone Cloudflare Worker add-ons (lossless
  locker, Monochrome adapter, artist signatures) and their offline tests. Their
  deployment notes and historical patch write-ups complement the handovers in
  `docs/`.
- [`tools/`](../tools/) — the *active* release helpers (`audit-apk.sh`,
  `vc92-build.sh`, `vc75-gate.sh`); the scripts here are the historical copies.

Notes:

- Some docs contain stale absolute paths such as `/home/hfzhkn/bitchord-device-evidence/...`; the files they refer to were reorganised in the original archive.
- Passwords embedded in one handover document were redacted in this copy. The real signing keystore and credentials are deliberately not in this repository.
- Some docs still mention `tools/vc75-gate.sh`; that script is still present and maintained at the repo root (it now checks versions dynamically rather than against a hard-coded vc83 stamp).
- Built APKs, device evidence dumps and session exports are deliberately not included — they are large generated artefacts, not source.
