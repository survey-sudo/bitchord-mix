#!/usr/bin/env bash
# Bootstrap a BitChord build box.
#
# Mirrors the toolchain observed on bk-android on 2026-11-15:
#   Debian 13 (trixie) | 4 vCPU | 7.8 GiB RAM
#   OpenJDK 21.0.12.1   | /usr/lib/jvm/java-21-openjdk-amd64
#   Android SDK 5.5 G   | /opt/android-sdk
#   NDK 27.2.12479018   | cmake 3.22.1 | build-tools 36.0.0 | platform android-36
#   Gradle 8.13 via wrapper (no global Gradle needed)
#
# Usage:
#   ./bootstrap.sh --check    # report only, install nothing
#   sudo ./bootstrap.sh       # provision
#
# Idempotent: every step re-checks before acting.

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
JAVA_HOME_TARGET="/usr/lib/jvm/java-21-openjdk-amd64"

# Exact versions, taken from the working bk-android box. Do not float these.
PLATFORM="android-36"
BUILD_TOOLS="36.0.0"
NDK="27.2.12479018"
CMAKE="3.22.1"
PLATFORM_TOOLS="platform-tools"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$*"; }
bad()  { printf '  \033[31mMISS\033[0m %s\n' "$*"; }
note() { printf '  \033[33mNOTE\033[0m %s\n' "$*"; }

need_sudo() {
  if [[ $EUID -ne 0 ]]; then
    if sudo -n true 2>/dev/null; then SUDO="sudo"; else
      echo "This script needs root or passwordless sudo." >&2; exit 1
    fi
  else SUDO=""; fi
}

# ---------------------------------------------------------------- check mode
if [[ $CHECK_ONLY -eq 1 ]]; then
  say "host"
  printf '  %s\n' "$(uname -srm)"
  printf '  nproc=%s  mem=%s\n' "$(nproc)" "$(awk '/MemTotal/{printf "%.1f GiB",$2/1048576}' /proc/meminfo)"
  df -h / | tail -1 | sed 's/^/  /'

  say "java"
  if command -v java >/dev/null; then
    java -version 2>&1 | head -1 | sed 's/^/  /'
    printf '  JAVA_HOME=%s\n' "${JAVA_HOME:-<unset>}"
    [[ -d "$JAVA_HOME_TARGET" ]] && ok "target JDK dir present" || bad "$JAVA_HOME_TARGET"
  else bad "java not found"; fi

  say "android sdk ($ANDROID_HOME)"
  if [[ -d "$ANDROID_HOME" ]]; then
    ok "sdk root"
    for d in "platforms/$PLATFORM" "build-tools/$BUILD_TOOLS" "ndk/$NDK" "cmake/$CMAKE" "$PLATFORM_TOOLS"; do
      [[ -d "$ANDROID_HOME/$d" ]] && ok "$d" || bad "$d"
    done
    du -sh "$ANDROID_HOME" 2>/dev/null | sed 's/^/  /'
  else bad "sdk root missing"; fi

  say "gradle cache"
  if [[ -d "$HOME/.gradle" ]]; then
    du -sh "$HOME/.gradle" 2>/dev/null | sed 's/^/  /'
    ls "$HOME/.gradle/wrapper/dists" 2>/dev/null | sed 's/^/  /'
  else note "no ~/.gradle (cold first build, expect +15-25 min)"; fi

  say "disk"
  df -h / /data 2>/dev/null | sed 's/^/  /'
  note "need >= 8 GiB free for SDK + Gradle + build output"
  exit 0
fi

# ---------------------------------------------------------------- provision
need_sudo

say "packages"
if ! command -v java >/dev/null || ! java -version 2>&1 | grep -q '21\.'; then
  $SUDO apt-get update -qq
  $SUDO apt-get install -y openjdk-21-jdk-headless unzip curl git python3 rsync
else
  ok "openjdk 21 already present"
fi
$SUDO apt-get install -y unzip curl git python3 rsync >/dev/null
ok "base packages"

say "android cmdline-tools"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  $SUDO mkdir -p "$ANDROID_HOME/cmdline-tools"
  TMP=$(mktemp -d)
  curl -fsSL -o "$TMP/tools.zip" \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$TMP/tools.zip" -d "$TMP"
  $SUDO rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  $SUDO mv "$TMP/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$TMP"
  ok "cmdline-tools installed"
else
  ok "cmdline-tools already present"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
export JAVA_HOME="$JAVA_HOME_TARGET"
export ANDROID_HOME

say "licenses"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
ok "licenses accepted"

say "sdk packages (exact versions)"
# shellcheck disable=SC2086
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "$PLATFORM_TOOLS" \
  "platforms;$PLATFORM" \
  "build-tools;$BUILD_TOOLS" \
  "ndk;$NDK" \
  "cmake;$CMAKE" 2>&1 | tail -5
ok "sdk packages installed"

say "env file"
ENV_FILE=/etc/profile.d/bitchord-android.sh
if [[ ! -f "$ENV_FILE" ]]; then
  $SUDO tee "$ENV_FILE" >/dev/null <<EOF
export ANDROID_HOME=$ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=$JAVA_HOME_TARGET
export PATH=\$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin
EOF
  ok "wrote $ENV_FILE"
else
  ok "$ENV_FILE already present"
fi

say "verify"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --version
java -version 2>&1 | head -1
ls "$ANDROID_HOME/ndk"
ls "$ANDROID_HOME/cmake"
du -sh "$ANDROID_HOME"

cat <<'EOF'

Next:
  1. rsync the repo            -> migrate-repo.md
  2. copy the two secret files -> migrate-repo.md (out of band, never via git)
  3. build and compare the APK -> verify-build.md
EOF
