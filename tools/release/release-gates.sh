#!/usr/bin/env bash
# Release-gate glue for .github/workflows/release.yml (fork-owned, Part B11.1 / C3).
#
# The release job is fail-closed. This helper owns the checks that must be
# testable outside Actions:
#
#   validate-tag       TAG=<tag>              tag is a safe `ae3-*` ref
#   require-signing    KEY* env vars          all four secrets present, key
#                                             decodes to a real keystore,
#                                             keystore + properties are 0600
#   require-quality                           docs/quality/AE3-<build>.html
#                                             exists, is a regular non-empty
#                                             file, and is unambiguous
#   select-artifacts                          require-quality + exactly one
#                                             prod release APK, with hashes
#   verify-sha <file> <sha256> [label]        file still has the audited hash
#
# Any missing/empty input, malformed key material, or ambiguous/empty artifact
# is a nonzero exit. Secrets are never echoed: errors name the variable only.
#
# Contract: selected values are printed to stdout as `key=value` lines (and
# appended to $GITHUB_OUTPUT when set), so a caller can read them in the same
# step; human-facing notes go to stderr. Nothing else goes to stdout.
#
# This script is not the quality gate itself: a present report is transported,
# not re-judged. mixq/audition/perf/soak gates stay on the devbox (docs/QUALITY.md).
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
BUILD_GRADLE="${BUILD_GRADLE:-$REPO_ROOT/build.gradle.kts}"
QUALITY_DIR="${QUALITY_DIR:-$REPO_ROOT/docs/quality}"
APK_DIR="${APK_DIR:-$REPO_ROOT/app/build/outputs/apk/prod/release}"
KEYSTORE_OUT="${KEYSTORE_OUT:-$REPO_ROOT/bitchord-loopfix.jks}"
KEYSTORE_PROPS="${KEYSTORE_PROPS:-$REPO_ROOT/keystore.properties}"

note() { printf '%s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

emit() {
  local key="$1" value="$2"
  printf '%s=%s\n' "$key" "$value"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s=%s\n' "$key" "$value" >> "$GITHUB_OUTPUT"
  fi
}

cmd_validate_tag() {
  local tag="${TAG:-}"
  [ -n "$tag" ] || die "release tag is empty"
  [ "${#tag}" -le 100 ] || die "release tag is longer than 100 characters"
  # A tag becomes a git ref, a gh release argument and part of a grep pattern.
  # Restrict it to characters that are inert in all three: no spaces, quotes,
  # '$', backticks or path separators. The prefix also keeps the changelog's
  # `git tag --list 'ae3-*'` range selection meaningful.
  if ! grep -qE '^ae3-[A-Za-z0-9][A-Za-z0-9._-]*$' <<< "$tag"; then
    die "release tag must match ^ae3-[A-Za-z0-9][A-Za-z0-9._-]*\$ (got: $tag)"
  fi
  emit tag "$tag"
  note "release tag ok: $tag"
}

cmd_require_signing() {
  local missing=() name
  for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
    if [ -z "${!name:-}" ]; then missing+=("$name"); fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    note "ERROR: release signing secret(s) missing or empty: ${missing[*]}"
    note "A publish-capable release needs all four (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)."
    note "There is no unsigned release path: configure the secrets before releasing."
    return 1
  fi

  # keystore.properties is line-oriented: a newline in a value would inject
  # another property. Reject rather than escape, so a bad secret fails loudly.
  for name in KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
    case "${!name}" in
      *$'\n'*|*$'\r'*) die "$name contains a newline; refusing to write keystore.properties" ;;
    esac
  done

  umask 077
  mkdir -p -- "$(dirname -- "$KEYSTORE_OUT")" "$(dirname -- "$KEYSTORE_PROPS")"
  local ks_tmp props_tmp
  ks_tmp=$(mktemp "${KEYSTORE_OUT}.XXXXXX")
  if ! printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$ks_tmp" 2>/dev/null; then
    rm -f -- "$ks_tmp"
    die "KEYSTORE_BASE64 is not valid base64 (value not shown)"
  fi
  if [ ! -s "$ks_tmp" ]; then
    rm -f -- "$ks_tmp"
    die "KEYSTORE_BASE64 decoded to an empty keystore"
  fi
  # JKS (FE ED FE ED), JCEKS (CE CE CE CE) or PKCS#12 (DER SEQUENCE 30 82).
  # A keystore that does not even start like one is wrong material, and Gradle
  # would otherwise only discover that after the long build.
  local magic
  magic=$(od -An -tx1 -N4 -- "$ks_tmp" | tr -d ' \n')
  case "$magic" in
    feedfeed|cececece|3082*) ;;
    *)
      rm -f -- "$ks_tmp"
      die "KEYSTORE_BASE64 is not a JKS/JCEKS/PKCS12 keystore (magic: $magic)"
      ;;
  esac
  chmod 600 "$ks_tmp"
  mv -f -- "$ks_tmp" "$KEYSTORE_OUT"

  local store_file
  case "$KEYSTORE_OUT" in
    "$REPO_ROOT"/*) store_file="${KEYSTORE_OUT#"$REPO_ROOT"/}" ;;
    *) store_file="$KEYSTORE_OUT" ;;
  esac
  props_tmp=$(mktemp "${KEYSTORE_PROPS}.XXXXXX")
  {
    printf 'storeFile=%s\n' "$store_file"
    printf 'storePassword=%s\n' "$KEYSTORE_PASSWORD"
    printf 'keyAlias=%s\n' "$KEY_ALIAS"
    printf 'keyPassword=%s\n' "$KEY_PASSWORD"
  } > "$props_tmp"
  chmod 600 "$props_tmp"
  mv -f -- "$props_tmp" "$KEYSTORE_PROPS"
  note "signing material ready: $(basename "$KEYSTORE_OUT") and $(basename "$KEYSTORE_PROPS") (mode 600)"
}

# Sets VERSION_NAME, VERSION_CODE, BUILD_ID, QUALITY_HTML, QUALITY_BYTES,
# QUALITY_SHA256.
# Deterministic convention from docs/QUALITY.md: docs/quality/AE3-<build>.html,
# where <build> is the djmixNN suffix of bitchordVersionName (e.g. djmix53), the
# same build label used for the audition folder. The full versionName is
# accepted as the one alternative, so `AE3-1.10-djmix53.html` also works; both
# at once is ambiguous and fails rather than guessing.
resolve_quality() {
  [ -f "$BUILD_GRADLE" ] || die "cannot find $BUILD_GRADLE"
  # No `sed | head` here: under `pipefail` head closing the pipe early can turn
  # a successful match into a SIGPIPE failure (the trap audit-apk.sh documents).
  local raw
  raw=$(sed -n 's/.*extra\["bitchordVersionName"\][[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$BUILD_GRADLE")
  VERSION_NAME="${raw%%$'\n'*}"
  [ -n "${VERSION_NAME:-}" ] || die "cannot read bitchordVersionName from $BUILD_GRADLE"
  raw=$(sed -n 's/.*extra\["bitchordVersionCode"\][[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$BUILD_GRADLE")
  VERSION_CODE="${raw%%$'\n'*}"
  [ -n "${VERSION_CODE:-}" ] || die "cannot read bitchordVersionCode from $BUILD_GRADLE"
  BUILD_ID=$(grep -oE 'djmix[0-9]+$' <<< "$VERSION_NAME" || true)
  [ -n "${BUILD_ID:-}" ] || die "bitchordVersionName '$VERSION_NAME' has no djmixNN suffix"

  local primary="$QUALITY_DIR/AE3-${BUILD_ID}.html"
  local alternate="$QUALITY_DIR/AE3-${VERSION_NAME}.html"
  local found=() candidate
  for candidate in "$primary" "$alternate"; do
    if [ -L "$candidate" ]; then
      die "quality report must be a regular file, not a symlink: $candidate"
    fi
    if [ -e "$candidate" ]; then
      [ -f "$candidate" ] || die "quality report is not a regular file: $candidate"
      found+=("$candidate")
    fi
  done

  if [ "${#found[@]}" -eq 0 ]; then
    note "ERROR: required quality report missing. Expected one of:"
    note "  $primary"
    note "  $alternate"
    note "Generate it upstream with: python3 tools/mixq/mixq.py report results.json --out $primary"
    note "This workflow never creates or fakes a report: the release stays blocked until the devbox report is committed."
    return 1
  fi
  if [ "${#found[@]}" -gt 1 ]; then
    note "ERROR: ambiguous quality report; both exist:"
    note "  ${found[*]}"
    note "Keep exactly one per release build so the uploaded report is unambiguous."
    return 1
  fi

  QUALITY_HTML="${found[0]}"
  [ -s "$QUALITY_HTML" ] || die "quality report is empty: $QUALITY_HTML"
  QUALITY_BYTES=$(stat -c %s -- "$QUALITY_HTML")
  QUALITY_SHA256=$(sha256sum -- "$QUALITY_HTML" | cut -d' ' -f1)
}

cmd_require_quality() {
  resolve_quality
  emit version_name "$VERSION_NAME"
  emit version_code "$VERSION_CODE"
  emit build_id "$BUILD_ID"
  emit quality_html "$QUALITY_HTML"
  emit quality_sha256 "$QUALITY_SHA256"
  emit quality_bytes "$QUALITY_BYTES"
  note "quality report: $QUALITY_HTML ($QUALITY_BYTES bytes, sha256 $QUALITY_SHA256)"
}

cmd_select_artifacts() {
  [ -d "$APK_DIR" ] || die "APK output directory not found: $APK_DIR"
  local apks=() f
  while IFS= read -r f; do apks+=("$f"); done < <(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' -print | sort)
  if [ "${#apks[@]}" -eq 0 ]; then
    die "no APK under $APK_DIR"
  fi
  if [ "${#apks[@]}" -ne 1 ]; then
    note "ERROR: expected exactly one release APK under $APK_DIR, found ${#apks[@]}:"
    note "  $(printf '%s ' "${apks[@]}")"
    note "Refusing to guess which one was audited."
    return 1
  fi
  APK="${apks[0]}"
  case "$(basename -- "$APK")" in
    *unsigned*) die "the only APK is unsigned ($APK); signing did not happen" ;;
  esac
  APK_BYTES=$(stat -c %s -- "$APK")
  APK_SHA256=$(sha256sum -- "$APK" | cut -d' ' -f1)

  resolve_quality

  emit apk "$APK"
  emit apk_sha256 "$APK_SHA256"
  emit apk_bytes "$APK_BYTES"
  emit version_name "$VERSION_NAME"
  emit version_code "$VERSION_CODE"
  emit build_id "$BUILD_ID"
  emit quality_html "$QUALITY_HTML"
  emit quality_sha256 "$QUALITY_SHA256"
  emit quality_bytes "$QUALITY_BYTES"
  note "selected apk: $APK ($APK_BYTES bytes, sha256 $APK_SHA256)"
  note "selected quality report: $QUALITY_HTML ($QUALITY_BYTES bytes, sha256 $QUALITY_SHA256)"
}

cmd_verify_sha() {
  local file="${1:-}" expected="${2:-}" label="${3:-artifact}"
  [ -n "$file" ] || die "verify-sha: missing file argument"
  [ -n "$expected" ] || die "verify-sha: missing expected sha256 for $label"
  [ -f "$file" ] || die "$label not found: $file"
  local got
  got=$(sha256sum -- "$file" | cut -d' ' -f1)
  if [ "$got" != "$expected" ]; then
    die "$label changed after audit: sha256 $got != $expected; refusing to publish"
  fi
  note "verified $label sha256 $got"
}

case "${1:-}" in
  validate-tag) shift; cmd_validate_tag "$@" ;;
  require-signing) shift; cmd_require_signing "$@" ;;
  require-quality) shift; cmd_require_quality "$@" ;;
  select-artifacts) shift; cmd_select_artifacts "$@" ;;
  verify-sha) shift; cmd_verify_sha "$@" ;;
  *)
    printf 'usage: %s <validate-tag|require-signing|require-quality|select-artifacts|verify-sha> ...\n' "$0" >&2
    exit 2
    ;;
esac
