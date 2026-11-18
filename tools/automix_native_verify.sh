#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MUSIC=${MUSIC_DIR:-/home/hfzhkn/Music}
BIN=${LOOPMIX_AB:-/tmp/loopmix_ab_automix_verify}
OUT=${AUTOMIX_VERIFY_DIR:-/tmp/opencode/automix-real}
mkdir -p "$OUT"

command -v ffmpeg >/dev/null
command -v python3 >/dev/null
[[ -x "$BIN" ]] || {
  g++ -std=c++17 -O2 -I "$ROOT/native" -I "$ROOT/native/third_party" \
    "$ROOT/native/loopmix/loop_mix_renderer.cpp" \
    "$ROOT/native/loopmix/loop_mix_renderer_ab_main.cpp" -o "$BIN"
}

decode() {
  local input=$1 output=$2
  ffmpeg -hide_banner -loglevel error -y -i "$input" -ar 48000 -ac 2 -c:a pcm_s16le "$output"
}

zero_vocal() {
  local output=$1
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i anullsrc=r=48000:cl=stereo \
    -t 6 -c:a pcm_s16le "$output"
}

run_pair() {
  local name=$1 out_track=$2 in_track=$3
  local a="$OUT/${name}-out.wav" b="$OUT/${name}-in.wav" z="$OUT/${name}-zero-voc.wav"
  local prefix="$OUT/${name}"
  decode "$MUSIC/$out_track" "$a"
  decode "$MUSIC/$in_track" "$b"
  zero_vocal "$z"
  echo "pair=$name stem-off=true renderer-quality=efficient(highQuality=false)"
  "$BIN" --out-bed "$a" --out-voc "$z" --in-bed "$b" --in-voc "$z" \
    --out-prefix "$prefix" --clashE 0.18
  for wav in "$prefix"_off.wav "$prefix"_fixed015.wav "$prefix"_duckgain.wav; do
    echo "ffmpeg ebur128/astats $wav"
    ffmpeg -hide_banner -i "$wav" -af ebur128=peak=true,astats=metadata=1 -f null - 2>&1 |
      grep -E 'Integrated loudness|Peak:|Overall|RMS level dB|Peak level dB' || true
    python3 "$ROOT/tools/automix_seam_metrics.py" "$wav" --boundary-sec 3.0
  done
}

run_pair "calvin-onekiss" \
  "Calvin Harris & Rihanna - This Is What You Came For.mp3" \
  "Calvin Harris, Dua Lipa - One Kiss.mp3"
run_pair "aa-control" \
  "Calvin Harris, Dua Lipa - One Kiss.mp3" \
  "Calvin Harris, Dua Lipa - One Kiss.mp3"
run_pair "enhypen-meovv" \
  "ENHYPEN - Bloody Paradise.flac" \
  "MEOVV - BURNING UP.mp3"
echo "automix native real-audio verification complete: output=$OUT stem-off=true"
