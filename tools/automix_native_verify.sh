#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MUSIC=${MUSIC_DIR:-/home/hfzhkn/Music}
BIN=${LOOPMIX_AB:-/tmp/loopmix_ab_automix_verify}
OUT=${AUTOMIX_VERIFY_DIR:-/tmp/opencode/automix-real}
OUT_TRACK=""
IN_TRACK=""
CASE_NAME="cli-pair"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --renderer) BIN="$2"; shift 2 ;;
    --out-track) OUT_TRACK="$2"; shift 2 ;;
    --in-track) IN_TRACK="$2"; shift 2 ;;
    --output-dir) OUT="$2"; shift 2 ;;
    --name) CASE_NAME="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

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

resolve_track() {
  local p=$1
  if [[ -f "$p" ]]; then echo "$p"; else echo "$MUSIC/$p"; fi
}

run_pair() {
  local name=$1
  local out_src in_src
  out_src=$(resolve_track "$2")
  in_src=$(resolve_track "$3")
  test -f "$out_src" || { echo "Missing out track: $out_src" >&2; exit 2; }
  test -f "$in_src" || { echo "Missing in track: $in_src" >&2; exit 2; }
  local a="$OUT/${name}-out.wav" b="$OUT/${name}-in.wav" z="$OUT/${name}-zero-voc.wav"
  local prefix="$OUT/${name}"
  decode "$out_src" "$a"
  decode "$in_src" "$b"
  zero_vocal "$z"
  echo "pair=$name stem-off=true renderer-quality=efficient(highQuality=false)"
  "$BIN" --out-bed "$a" --out-voc "$z" --in-bed "$b" --in-voc "$z" \
    --out-prefix "$prefix" --clashE 0.18
  for wav in "$prefix"_off.wav "$prefix"_fixed015.wav "$prefix"_duckgain.wav; do
    echo "ffmpeg ebur128/astats $wav"
    ffmpeg -nostdin -hide_banner -i "$wav" -af 'ebur128=peak=true,astats=metadata=1:reset=0' -f null - >"$wav.meters.log" 2>&1
    grep -E 'Integrated loudness|Peak:|RMS level dB|Peak level dB' "$wav.meters.log"
    python3 "$ROOT/tools/automix_seam_metrics.py" "$wav" --reference "$prefix"_off.wav --boundary-sec 3.0
  done
}

if [[ -n "$OUT_TRACK" && -n "$IN_TRACK" ]]; then
  run_pair "$CASE_NAME" "$OUT_TRACK" "$IN_TRACK"
else
  run_pair "calvin-onekiss" \
    "Calvin Harris & Rihanna - This Is What You Came For.mp3" \
    "Calvin Harris, Dua Lipa - One Kiss.mp3"
  run_pair "aa-control" \
    "Calvin Harris, Dua Lipa - One Kiss.mp3" \
    "Calvin Harris, Dua Lipa - One Kiss.mp3"
  run_pair "enhypen-meovv" \
    "ENHYPEN - Bloody Paradise.flac" \
    "MEOVV - BURNING UP.mp3"
fi
echo "automix native real-audio verification complete: output=$OUT stem-off=true"
