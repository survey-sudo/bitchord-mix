# B12.12 archival record — 2026-11-25

These historical files are retained as evidence, not current AE3 build or
architecture instructions. The active references are
[`ARCHITECTURE.md`](../../docs/ARCHITECTURE.md),
[`AE3_BASELINE.md`](../../docs/AE3_BASELINE.md),
[`SYNC.md`](../../docs/SYNC.md), [`NPU.md`](../../docs/NPU.md), and
[`RELEASE.md`](../../docs/RELEASE.md).

The repository already has `archive/docs/`; no competing `docs/archive/` tree
was introduced. Every moved file is byte-identical to its original. The older
grokbox copy already in `archive/docs/tools/grokbox/` was not overwritten.

## Files archived

| Former active path | Archive path | Confirmed stale or contradictory material |
|---|---|---|
| `docs/REMAINING_WORK.md` | [`REMAINING_WORK.md`](REMAINING_WORK.md) | Lines 3–7 call vc78 the pending candidate; lines 193–202 name a November 15 build-host checkout as source of truth. Lines 279–305 describe the old QNN stack, and lines 363–364 say only bundled UMX is used. This cannot serve as the AE3 work queue or model architecture. |
| `docs/ci-parity-vc74.md` | [`ci-parity-vc74.md`](ci-parity-vc74.md) | Lines 68–92 certify vc74 / djmix32 and its old model payload; lines 164–166 generalize that frozen build into current CI/signing instructions. This is a historical artifact comparison, not AE3 release evidence. |
| `docs/tools/grokbox/README.md` | [`ae3-b12-12/grokbox/README.md`](ae3-b12-12/grokbox/README.md) | Lines 3–5 give the old build-host takeover objective; lines 32–38 describe the old prebuilt/Maven ORT setup, and lines 55–59 prescribe resuming the November 15 host state. |
| `docs/tools/grokbox/migrate-repo.md` | [`ae3-b12-12/grokbox/migrate-repo.md`](ae3-b12-12/grokbox/migrate-repo.md) | Lines 3–6 and 85 treat the old `bitchord-mix` build-host tree and remote as authoritative. |
| `docs/tools/grokbox/verify-build.md` | [`ae3-b12-12/grokbox/verify-build.md`](ae3-b12-12/grokbox/verify-build.md) | Lines 6–23 and 59 prescribe vc73/vc74 APK identities as the current build audit. |
| `docs/tools/grokbox/bootstrap.sh` | [`ae3-b12-12/grokbox/bootstrap.sh`](ae3-b12-12/grokbox/bootstrap.sh) | Companion executable moved with its obsolete migration/verification kit so the archived procedure remains self-contained. It was not executed or changed. |
| `docs/DJMIX52_BASELINE.md` | [`ae3-b12-12/DJMIX52_BASELINE.md`](ae3-b12-12/DJMIX52_BASELINE.md) | Prior vc93 measurements remain historical comparison evidence; `docs/AE3_BASELINE.md` now points to this archive. |
| `docs/AUTOMIX_ENGINE_REFACTOR.md` | [`ae3-b12-12/AUTOMIX_ENGINE_REFACTOR.md`](ae3-b12-12/AUTOMIX_ENGINE_REFACTOR.md) | Historical extraction plan; the hook manifest and upstream-sync playbook now reference its archived location. |
| `docs/LOOP_CHARACTER_RECOVERY_2026-11-19.md` | [`ae3-b12-12/LOOP_CHARACTER_RECOVERY_2026-11-19.md`](ae3-b12-12/LOOP_CHARACTER_RECOVERY_2026-11-19.md) | Dated recovery contract retained alongside the unchanged validation evidence. |
| `BUILD_IN_GITHUB_ACTIONS.md` | [`ae3-b12-12/BUILD_IN_GITHUB_ACTIONS.md`](ae3-b12-12/BUILD_IN_GITHUB_ACTIONS.md) | Guide names vc91/djmix49 as the checked-out base and vc92 as next; current release instructions remain in `docs/RELEASE.md`. |

## Reviewed and deliberately retained

| Active material | Reason to retain |
|---|---|
| `docs/UPSTREAM_SYNC_PLAYBOOK.md` | Protected `docs/SYNC.md:3–5` explicitly keeps it active until fully absorbed, and `docs/SYNC.md:72` still requires section 5. Its old version convention (lines 16–18), vc93 prerequisite (line 25), and djmix52 verification (lines 69–71) need revision when that absorption is authorized. |
| `docs/validation/loop-character/**` | Retained evidence for loop/handover behavior that `docs/ARCHITECTURE.md:143–151` explicitly preserves; the dated recovery narrative is archived above. |
| `docs/tools/apk-parity.py` | Generic artifact-comparison utility; no obsolete architecture mandate was established. |
| `docs/LYRICS_TRANSLATION.md`, `docs/web-app-build-prompt.md` | Separate lyrics/web topics; no B12 automix contradiction was established. |
| ADRs, ledger, and untangle records | Retained. This archival reconciliation only updates moved-path references in the active baseline, playbook, and upstream-sync manifests. |

## Root Markdown review and scope boundary

All nine root Markdown files were inventoried: `ADDITIONAL.md`,
`BUILD_IN_GITHUB_ACTIONS.md`, `CHANGELOG.md`, `CODE_OF_CONDUCT.md`,
`CONTRIBUTING.md`, `MAINTAINERS.md`, `PORT_LIST.md`, `README.md`, and
`THIRD_PARTY_NOTICES.md`.

- The archived `BUILD_IN_GITHUB_ACTIONS.md:9–23` contains a confirmed contradiction:
  it calls vc91/djmix49 the checked-out shipped base, calls vc92 next, and
  instructs agents not to build `master`. The later root-file archival pass moved
  it unchanged; it is no longer an active build guide.
- `README.md:97–119` describes the older automix implementation and bundled
  UMX as the feature explanation. It needs an AE3-aware editorial review, not
  wholesale archival of the repository README; the active NPU reference also
  retains UMX as a fallback, so mentioning UMX alone is not proof of a defect.
- `PORT_LIST.md` is a historical upstream-port ledger, and
  `THIRD_PARTY_NOTICES.md` contains license/asset attribution. Neither was
  treated as obsolete architecture solely because it mentions old models.
- The remaining root files have no confirmed B12 contradiction in this review;
  `CHANGELOG.md` was explicitly protected.

## Verification

- All ten archived payloads were compared byte-for-byte with their originals at
  `19da5aac`; their SHA-256 identities match. No historical payload was edited.
- Active baseline, playbook, hook-contract, and ownership references use the
  archived paths. Historical references inside archived payloads are preserved.
- The [archival appendix](ae3-b12-12/APPENDIX-tail.md) records the four later moves
  and their provenance. This final index supersedes its initial scope caveats.
- These archival checks do not establish compilation, signing, device behavior,
  or release acceptance.
