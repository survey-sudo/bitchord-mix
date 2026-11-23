# Archive material

This folder holds the supporting material from the original `~/Bitchord` unified archive, kept here so one repo is enough to work from. It is separate from the Gradle source tree at the repo root to avoid name collisions with the source's own `docs/` and `tools/`.

| Folder | Contents |
| --- | --- |
| `docs/` | Handovers, diagnosis, build reports, release notes and agent instructions (66 files) |
| `patches/` | djmix / expanded / paused / graceful-exit patches (43 files) |
| `tools/` | Telemetry diff, dataviz, APK audit and device verification scripts |
| `refs/` | Apple Music AutoMix research (md + pdf) and the engineering zip |
| `screenshots/` | Device screenshots from the original archive |

Notes:

- Some docs contain stale absolute paths such as `/home/hfzhkn/bitchord-device-evidence/...`; the files they refer to were reorganised in the original archive.
- Passwords embedded in one handover document were redacted in this copy. The real signing keystore and credentials are deliberately not in this repository.
- Built APKs, device evidence dumps and session exports are deliberately not included — they are large generated artefacts, not source.
