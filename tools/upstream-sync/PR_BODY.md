Automatic merge of upstream BitChord **{{TAG}}** (previous base **{{BASE}}**).

Fork-owned paths were kept as-is; upstream changes inside them are listed in the port list below.
CI (android-ci) runs on this PR. Before merging, the agent or maintainer completes:

- [ ] Every port-list row ported or marked "not applicable" with a reason
- [ ] `bash tools/upstream-sync/hooks-check.sh` passes
- [ ] Native gates + `:shared:jvmTest` + `:app:testProdDebugUnitTest` + `assembleProdRelease` green
- [ ] `adb install -r` on the phone; versionCode went up; no data loss
- [ ] Smoke: playback, queue, lyrics, the release's headline features, Settings → Automix rows present
- [ ] Automix: 3+ natural transitions, `BitChordChoreo` move lines in logcat, no silence or cut-offs
- [ ] User listened and approved
