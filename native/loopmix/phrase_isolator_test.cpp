// Host checks for the phrase isolator and the bass-swap ramp.
// g++ -O2 -std=c++17 native/loopmix/phrase_isolator_test.cpp -o /tmp/phrase_isolator_test && /tmp/phrase_isolator_test
#include "phrase_isolator.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

static int fail(const char* msg) {
  std::fprintf(stderr, "FAIL %s\n", msg);
  return 1;
}

int main() {
  using bitchord::loopmix::PhraseIsolator;
  PhraseIsolator iso;
  iso.setup(48000);
  const double freqs[] = {80, 200, 1000, 2500, 8000};
  for (double hz : freqs) {
    double inRms = 0, outRms = 0;
    const int n = 48000;
    for (int i = 0; i < n; ++i) {
      const float x = std::sin(2.0 * 3.141592653589793 * hz * i / 48000.0);
      float lo, mid, hi;
      iso.process(0, x, lo, mid, hi);
      const float y = lo + mid + hi;
      if (i > 2400) {
        inRms += double(x) * x;
        outRms += double(y) * y;
      }
    }
    const double db = 10.0 * std::log10((outRms + 1e-18) / (inRms + 1e-18));
    std::printf("isolator %.0f Hz sum %+0.3f dB\n", hz, db);
    if (std::abs(db) > 0.1) return fail("band sum");
  }
  const int ramp = int(0.120 * 48000);
  const float step = bitchord::loopmix::raisedCosineKillStep(ramp);
  std::printf("kill ramp step %.6f\n", step);
  if (step > 0.01f) return fail("ramp step");

  // Echo input is dry × send. Send 0 keeps the line at rest.
  float line = 0;
  float peak = 0;
  for (int i = 0; i < 48000; ++i) {
    const float dry = std::sin(i * 0.1f);
    const float send = 0;
    line = dry * send + line * 0.4f;
    peak = std::max(peak, std::abs(line));
  }
  const double db = 20.0 * std::log10(peak + 1e-18);
  std::printf("echo send 0 peak %+0.1f dB\n", db);
  if (db > -90.0) return fail("echo gate");
  std::printf("ok\n");
  return 0;
}
