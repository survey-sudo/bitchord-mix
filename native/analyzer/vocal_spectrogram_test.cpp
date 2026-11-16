// Host checks for SeparateVocalWindow: the successful path, not just the
// fallback. A null-write bug shipped in vc73/vc74 because nothing ever called
// this with a valid magnitude tensor and checked the outputs came back sized.
//
//   g++ -O2 -std=c++17 -I native/analyzer native/analyzer/vocal_spectrogram_test.cpp \
//       native/analyzer/vocal_spectrogram.cpp -o /tmp/vocal_spectrogram_test \
//       && /tmp/vocal_spectrogram_test
//
// Exit 0 means every check passed. A segmentation fault is the failure the
// vc74 crash presented as, so a crash here is a legible result: the test does
// not need to catch it to report it.
#include "vocal_spectrogram.h"

#include <cmath>
#include <cstdio>
#include <vector>

using bitchord::smart::SeparateVocalWindow;
using bitchord::smart::kVocalSpectrogramBins;
using bitchord::smart::kVocalSpectrogramChannels;
using bitchord::smart::kVocalSpectrogramFft;
using bitchord::smart::kVocalSpectrogramHop;
using bitchord::smart::kVocalSpectrogramSampleRate;

static int failures = 0;

static void check(bool cond, const char* what) {
  if (cond) {
    std::printf("ok   %s\n", what);
  } else {
    std::printf("FAIL %s\n", what);
    ++failures;
  }
}

// Number of frames the implementation derives from a padded input of this
// length, matching the caller's own computation so target_frames agrees.
static size_t frames_for(size_t input_length) {
  const size_t pad = kVocalSpectrogramFft / 2;
  const size_t padded = input_length + 2 * pad;
  return (padded - kVocalSpectrogramFft) / kVocalSpectrogramHop + 1;
}

static std::vector<float> sine(size_t n, double phase) {
  std::vector<float> v(n);
  for (size_t i = 0; i < n; ++i) {
    v[i] = 0.3f * static_cast<float>(std::sin(i * 0.01 + phase));
  }
  return v;
}

static std::vector<float> magnitude(size_t frames, float value) {
  return std::vector<float>(
    kVocalSpectrogramChannels * kVocalSpectrogramBins * frames, value);
}

static double peak_of(const std::vector<float>& v) {
  double peak = 0.0;
  for (float s : v) peak = std::fmax(peak, std::fabs(static_cast<double>(s)));
  return peak;
}

int main() {
  // Half a second of stereo at the expected rate: the shape the app sends on a
  // real transition window.
  const size_t n = 22050;
  const std::vector<float> left = sine(n, 0.0);
  const std::vector<float> right = sine(n, 0.4);
  const size_t frames = frames_for(n);
  const std::vector<float> mag = magnitude(frames, 0.05f);

  // 1. The success path. This is the check that did not exist in vc73/vc74:
  //    on a true return both outputs must be exactly input_length long, because
  //    the overlap-add loop writes them by index from 0 to input_length-1.
  {
    std::vector<float> out_left, out_right;
    const bool ok = SeparateVocalWindow(
      left, right, 44100.0, mag, frames, true, out_left, out_right);
    check(ok, "valid input returns true");
    check(out_left.size() == n, "out_left is input_length");
    check(out_right.size() == n, "out_right is input_length");
    // A zero-length output would have crashed above; a silent one would mean the
    // mask path degenerated. The value is not pinned, only that signal exists.
    check(peak_of(out_left) > 0.0, "out_left carries signal");
    check(peak_of(out_right) > 0.0, "out_right carries signal");
  }

  // 2. keep_vocals == false runs the complementary mask through the same write
  //    loop, so it must produce sized output too.
  {
    std::vector<float> out_left, out_right;
    const bool ok = SeparateVocalWindow(
      left, right, 44100.0, mag, frames, false, out_left, out_right);
    check(ok, "keep_vocals=false returns true");
    check(out_left.size() == n, "keep_vocals=false out_left is input_length");
    check(out_right.size() == n, "keep_vocals=false out_right is input_length");
  }

  // 3. A shorter window than the minimum must be rejected, not crash. This is
  //    the early return that must stay ABOVE the sizing of the outputs.
  {
    std::vector<float> short_in = sine(64, 0.0);
    const size_t short_frames = frames_for(64);
    std::vector<float> short_mag = magnitude(short_frames, 0.05f);
    std::vector<float> out_left, out_right;
    const bool ok = SeparateVocalWindow(
      short_in, short_in, 44100.0, short_mag, short_frames, true, out_left, out_right);
    check(!ok, "input at or below pad+1 returns false");
    check(out_left.empty() && out_right.empty(), "rejected call leaves outputs empty");
  }

  // 4. Sample-rate mismatch returns false before any sizing.
  {
    std::vector<float> out_left, out_right;
    const bool ok = SeparateVocalWindow(
      left, right, 48000.0, mag, frames, true, out_left, out_right);
    check(!ok, "sample-rate mismatch returns false");
    check(out_left.empty() && out_right.empty(), "rate mismatch leaves outputs empty");
  }

  // 5. Magnitude length mismatch returns false rather than writing.
  {
    std::vector<float> out_left, out_right;
    const std::vector<float> truncated(mag.begin(), mag.begin() + mag.size() / 2);
    const bool ok = SeparateVocalWindow(
      left, right, 44100.0, truncated, frames, true, out_left, out_right);
    check(!ok, "magnitude length mismatch returns false");
    check(out_left.empty() && out_right.empty(), "magnitude mismatch leaves outputs empty");
  }

  // 6. target_frames == 0 returns false: the JVM fallback contract, which is
  //    the ONLY case the pre-vc75 tests covered.
  {
    std::vector<float> out_left, out_right;
    const bool ok = SeparateVocalWindow(
      left, right, 44100.0, mag, 0, true, out_left, out_right);
    check(!ok, "target_frames=0 returns false");
  }

  // 7. Channel imbalance: a non-square stereo input must be rejected, since the
  //    implementation assumes both channels share input_length.
  {
    std::vector<float> out_left, out_right;
    const std::vector<float> shorter(n / 2);
    const bool ok = SeparateVocalWindow(
      left, shorter, 44100.0, mag, frames, true, out_left, out_right);
    check(!ok, "mismatched channel lengths return false");
  }

  if (failures != 0) {
    std::printf("%d check(s) failed\n", failures);
    return 1;
  }
  std::printf("all checks passed\n");
  return 0;
}
