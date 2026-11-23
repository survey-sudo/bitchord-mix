# Verifying a build box migration

A build box is only migrated when **the same source produces the same bytes**.
A green `BUILD SUCCESSFUL` is not the test.

Reference build: `vc73` on `bk-android`.

| Property | Value |
|---|---|
| APK | `app-prod-arm64-v8a-release.apk` |
| Size | `67,430,938` B |
| SHA-256 | `e76cb3ce0d1e1e8704077ec13d069e78534f7dec39abd94158e477a24492cfc0` |
| `versionCode` | `73` |
| `versionName` | `2.2.3-djmix31` |
| Signing cert SHA-256 | `20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c` |
| `libonnxruntime.so` | exactly **1**, `34,208,672` B |
| QNN / HTP `.so` | **0** |
| `libbitchord_analysis.so` | `486,288` B |
| `libbitchord_loopmix.so` | `438,616` B |

The current tree is `vc74` (`2.2.3-djmix32`), so the hash will differ — compare
against whatever `bk-android` produces for **the same commit**, not against the
`vc73` numbers above. Use the table as the shape of the audit.

---

## 1. Reproducibility check

Build the same commit on both boxes with the same command and compare:

```bash
sha256sum app/build/outputs/apk/prod/release/*.apk
```

Byte-identical is the strong pass. If they differ, the usual causes are, in
order of likelihood:

1. **Different JDK** — confirm `java -version` matches on both.
2. **Different NDK** — `ls $ANDROID_HOME/ndk`; must be `27.2.12479018`.
3. **Different CMake** — must be `3.22.1`.
4. **`--offline` vs online** resolving a different dependency version.
5. **Timestamp/zip ordering** — rare for AGP, but check with
   `unzip -v` entry ordering before chasing anything else.

APK builds are not guaranteed bit-reproducible; if only the signature block or
zip ordering differs, record that and fall back to the structural audit below.

---

## 2. Structural audit (always run)

```bash
APK=app/build/outputs/apk/prod/release/app-prod-arm64-v8a-release.apk

stat -c '%s bytes' "$APK"
sha256sum "$APK"

"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK" | head -3
# expect versionCode='74' versionName='2.2.3-djmix32'

"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$APK" \
  | grep -i 'sha-256'
# expect 20c66ad6ab1a1d1f14f4b31966e8cb0cc591e39356687006989a6f2172e3fe4c

unzip -l "$APK" | grep -c 'libonnxruntime.so'          # expect 1
unzip -l "$APK" | grep 'libonnxruntime.so'             # expect 34208672
unzip -l "$APK" | grep -icE 'libQnn|libHtp'            # expect 0
unzip -l "$APK" | grep 'libbitchord_'                  # analysis + loopmix
```

---

## 3. Invariants

```bash
cd /data/workspace/bitchord-task2/work/BitChord
git rev-parse HEAD
git remote -v
git status --porcelain | wc -l
test -d npu-addon/prebuilt && echo "prebuilt present (not a build dep)"
test -f /data/workspace/bitchord-task2/baseline-vc69.apk && echo "baseline present"
```

---

## 4. Cleanup between builds

Disk is the usual failure mode: `bk-android` sits at 89 % on `/data` before a
release build. Before each `assembleProdRelease`, remove only regenerables:

```bash
./gradlew --stop 2>/dev/null
rm -rf app/build/intermediates app/build/tmp app/build/outputs/mapping
# keep app/build/test-results and app/build/reports
df -h /data   # want >= 3.2 G free
```

Never `gradle clean` and never delete `npu-addon/prebuilt/`.
