// Host-only JSON, audio adapters, and exact canonical geometry.
// Extracted from loop_mix_renderer_ab_main.cpp; canonical input checks live here.
// Implementation header: included once inside that host CLI anonymous namespace.
// This preserves the existing two-source host build and does not enter Android builds.
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#pragma once

// ---------------------------------------------------------------------------
// Standalone --render <script.json> --out <file.wav> mode (TransitionScriptV1)
// ---------------------------------------------------------------------------

namespace render_script {

struct JsonVal {
  enum Kind { J_NULL, J_BOOL, J_NUM, J_STR, J_ARR, J_OBJ };
  Kind kind = J_NULL;
  bool b = false;
  double num = 0.0;
  std::string str;
  std::vector<JsonVal> arr;
  std::vector<std::pair<std::string, JsonVal>> obj;

  const JsonVal* get(const char* key) const {
    if (kind != J_OBJ) return nullptr;
    for (const auto& kv : obj) {
      if (kv.first == key) return &kv.second;
    }
    return nullptr;
  }
  bool asBool(bool def = false) const {
    if (kind == J_BOOL) return b;
    if (kind == J_NUM) return num != 0.0;
    return def;
  }
  double asNum(double def = 0.0) const {
    return (kind == J_NUM && std::isfinite(num)) ? num : def;
  }
  std::string asStr(const std::string& def = "") const {
    return kind == J_STR ? str : def;
  }
  bool boolField(const char* key, bool def = false) const {
    const JsonVal* v = get(key);
    return (v && v->kind != J_NULL) ? v->asBool(def) : def;
  }
  double numField(const char* key, double def = 0.0) const {
    const JsonVal* v = get(key);
    return (v && v->kind != J_NULL) ? v->asNum(def) : def;
  }
  bool hasNum(const char* key) const {
    const JsonVal* v = get(key);
    return v != nullptr && v->kind == J_NUM && std::isfinite(v->num);
  }
  std::string strField(const char* key, const std::string& def = "") const {
    const JsonVal* v = get(key);
    return (v && v->kind != J_NULL) ? v->asStr(def) : def;
  }
};

class JsonParser {
 public:
  explicit JsonParser(const std::string& src) : s_(src) {}

  bool parse(JsonVal& out, std::string& err) {
    skipWs();
    if (!parseValue(out, 0, err)) return false;
    skipWs();
    if (pos_ != s_.size()) {
      err = "trailing bytes at offset " + std::to_string(pos_);
      return false;
    }
    return true;
  }

 private:
  const std::string& s_;
  size_t pos_ = 0;

  void skipWs() {
    while (pos_ < s_.size()) {
      const unsigned char c = static_cast<unsigned char>(s_[pos_]);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') ++pos_;
      else break;
    }
  }

  bool matchLit(const char* lit) {
    const size_t n = std::strlen(lit);
    if (pos_ + n > s_.size()) return false;
    if (std::memcmp(s_.data() + pos_, lit, n) != 0) return false;
    pos_ += n;
    return true;
  }

  bool parseString(std::string& out, std::string& err) {
    if (pos_ >= s_.size() || s_[pos_] != '"') {
      err = "expected '\"' at offset " + std::to_string(pos_);
      return false;
    }
    ++pos_;
    out.clear();
    while (pos_ < s_.size()) {
      const char c = s_[pos_++];
      if (c == '"') return true;
      if (c == '\\') {
        if (pos_ >= s_.size()) { err = "unterminated escape"; return false; }
        const char e = s_[pos_++];
        switch (e) {
          case '"': out.push_back('"'); break;
          case '\\': out.push_back('\\'); break;
          case '/': out.push_back('/'); break;
          case 'b': out.push_back('\b'); break;
          case 'f': out.push_back('\f'); break;
          case 'n': out.push_back('\n'); break;
          case 'r': out.push_back('\r'); break;
          case 't': out.push_back('\t'); break;
          case 'u': {
            if (pos_ + 4 > s_.size()) { err = "short \\u escape"; return false; }
            uint32_t cp = 0;
            for (int k = 0; k < 4; ++k) {
              const char h = s_[pos_++];
              cp <<= 4;
              if (h >= '0' && h <= '9') cp |= uint32_t(h - '0');
              else if (h >= 'a' && h <= 'f') cp |= uint32_t(h - 'a' + 10);
              else if (h >= 'A' && h <= 'F') cp |= uint32_t(h - 'A' + 10);
              else { err = "bad hex in \\u escape"; return false; }
            }
            if (cp < 0x80) {
              out.push_back(static_cast<char>(cp));
            } else if (cp < 0x800) {
              out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
              out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else {
              out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
              out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
              out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            }
            break;
          }
          default:
            err = "unknown JSON escape";
            return false;
        }
      } else {
        if (static_cast<unsigned char>(c) < 0x20) {
          err = "unescaped control character in JSON string";
          return false;
        }
        out.push_back(c);
      }
    }
    err = "unterminated string";
    return false;
  }

  bool parseNumber(double& out, std::string& err) {
    // strtod alone accepts non-JSON forms such as 01 and 0x1. Check the JSON
    // number grammar first and require it to consume precisely that token.
    const size_t start = pos_;
    if (pos_ < s_.size() && s_[pos_] == '-') ++pos_;
    if (pos_ >= s_.size()) { err = "incomplete JSON number"; return false; }
    if (s_[pos_] == '0') ++pos_;
    else if (s_[pos_] >= '1' && s_[pos_] <= '9') {
      while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') ++pos_;
    } else { err = "invalid JSON number"; return false; }
    if (pos_ < s_.size() && s_[pos_] == '.') {
      const size_t digits = ++pos_;
      while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') ++pos_;
      if (digits == pos_) { err = "missing fractional digits"; return false; }
    }
    if (pos_ < s_.size() && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
      ++pos_;
      if (pos_ < s_.size() && (s_[pos_] == '+' || s_[pos_] == '-')) ++pos_;
      const size_t digits = pos_;
      while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') ++pos_;
      if (digits == pos_) { err = "missing exponent digits"; return false; }
    }
    const char* begin = s_.c_str() + start;
    char* end = nullptr;
    const double val = std::strtod(begin, &end);
    if (end != s_.c_str() + pos_ || !std::isfinite(val)) {
      err = "invalid number at offset " + std::to_string(pos_);
      return false;
    }
    out = val;
    return true;
  }

  bool parseValue(JsonVal& v, int depth, std::string& err) {
    if (depth > 64) { err = "JSON nesting too deep"; return false; }
    skipWs();
    if (pos_ >= s_.size()) { err = "unexpected end of JSON"; return false; }
    const char c = s_[pos_];
    if (c == 'n') {
      if (!matchLit("null")) { err = "expected null"; return false; }
      v = JsonVal();
      v.kind = JsonVal::J_NULL;
      return true;
    }
    if (c == 't') {
      if (!matchLit("true")) { err = "expected true"; return false; }
      v = JsonVal();
      v.kind = JsonVal::J_BOOL;
      v.b = true;
      return true;
    }
    if (c == 'f') {
      if (!matchLit("false")) { err = "expected false"; return false; }
      v = JsonVal();
      v.kind = JsonVal::J_BOOL;
      v.b = false;
      return true;
    }
    if (c == '"') {
      v = JsonVal();
      v.kind = JsonVal::J_STR;
      return parseString(v.str, err);
    }
    if (c == '[') {
      ++pos_;
      v = JsonVal();
      v.kind = JsonVal::J_ARR;
      skipWs();
      if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }
      while (true) {
        JsonVal item;
        if (!parseValue(item, depth + 1, err)) return false;
        v.arr.push_back(std::move(item));
        skipWs();
        if (pos_ >= s_.size()) { err = "unterminated array"; return false; }
        if (s_[pos_] == ']') { ++pos_; return true; }
        if (s_[pos_] != ',') { err = "expected ',' or ']' in array"; return false; }
        ++pos_;
      }
    }
    if (c == '{') {
      ++pos_;
      v = JsonVal();
      v.kind = JsonVal::J_OBJ;
      skipWs();
      if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
      while (true) {
        skipWs();
        std::string key;
        if (!parseString(key, err)) return false;
        skipWs();
        if (pos_ >= s_.size() || s_[pos_] != ':') { err = "expected ':'"; return false; }
        ++pos_;
        JsonVal val;
        if (!parseValue(val, depth + 1, err)) return false;
        if (v.get(key.c_str())) { err = "duplicate JSON object key: " + key; return false; }
        v.obj.emplace_back(std::move(key), std::move(val));
        skipWs();
        if (pos_ >= s_.size()) { err = "unterminated object"; return false; }
        if (s_[pos_] == '}') { ++pos_; return true; }
        if (s_[pos_] != ',') { err = "expected ',' or '}' in object"; return false; }
        ++pos_;
      }
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
      v = JsonVal();
      v.kind = JsonVal::J_NUM;
      return parseNumber(v.num, err);
    }
    err = std::string("unexpected char '") + c + "' at offset " + std::to_string(pos_);
    return false;
  }
};

int parseEnvelopeKind(const JsonVal* v) {
  if (!v || v->kind == JsonVal::J_NULL) return -1;
  if (v->kind != JsonVal::J_STR) return -1;
  const std::string& u = v->str;
  if (u == "GAIN") return 0;
  if (u == "LOW_DB") return 1;
  if (u == "HIGH_PASS_HZ") return 2;
  if (u == "LOW_PASS_HZ") return 3;
  if (u == "ECHO_SEND") return 4;
  if (u == "MID_DB") return 5;
  if (u == "HIGH_DB") return 6;
  if (u == "FLANGER_WET") return 7;
  if (u == "REVERB_SEND") return 8;
  if (u == "STEM_VOCALS") return 9;
  if (u == "STEM_DRUMS") return 10;
  if (u == "STEM_BASS") return 11;
  if (u == "STEM_OTHER") return 12;
  if (u == "FILTER_RES") return 13;
  if (u == "PHASER_WET") return 14;
  if (u == "GATE_DEPTH") return 15;
  if (u == "RISER_LEVEL") return 16;
  if (u == "PUMP_DEPTH") return 17;
  if (u == "BRAKE") return 18;
  if (u == "FILTER_KNOB") return 19;
  return -1;
}

int parseStemKind(const JsonVal* v) {
  if (!v || v->kind == JsonVal::J_NULL) return -1;
  if (v->kind != JsonVal::J_STR) return -1;
  const std::string& u = v->str;
  if (u == "VOCALS") return 0;
  if (u == "DRUMS") return 1;
  if (u == "BASS") return 2;
  if (u == "OTHER") return 3;
  return -1;
}

bitchord::loopmix::Envelope* envelopeSlot(bitchord::loopmix::Deck& d, int kind) {
  switch (kind) {
    case 0: return &d.gain;
    case 1: return &d.lowDb;
    case 2: return &d.highPassHz;
    case 3: return &d.lowPassHz;
    case 4: return &d.echoSend;
    case 5: return &d.midDb;
    case 6: return &d.highDb;
    case 7: return &d.flangerWet;
    case 8: return &d.reverbSend;
    default: return bitchord::loopmix::v2Envelope(d, kind);
  }
}

std::string resolveAudioPath(const std::string& rawPath, const std::string& scriptDir) {
  if (rawPath.empty()) return "";
  std::FILE* f = std::fopen(rawPath.c_str(), "rb");
  if (f) { std::fclose(f); return rawPath; }
  if (!scriptDir.empty() && rawPath[0] != '/') {
    const std::string joined = scriptDir + "/" + rawPath;
    std::FILE* f2 = std::fopen(joined.c_str(), "rb");
    if (f2) { std::fclose(f2); return joined; }
  }
  return rawPath;
}

bool loadAudioAuto(const std::string& rawPath, const std::string& scriptDir, int srcRateHint,
                   int targetRate, std::vector<float>& outL, std::vector<float>& outR,
                   std::string& err) {
  const std::string path = resolveAudioPath(rawPath, scriptDir);
  std::FILE* f = std::fopen(path.c_str(), "rb");
  if (!f) {
    err = "cannot open audio file: " + path;
    return false;
  }
  std::fseek(f, 0, SEEK_END);
  const long sz = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  if (sz <= 0) {
    std::fclose(f);
    err = "empty audio file: " + path;
    return false;
  }
  uint8_t hdr[12] = {};
  const size_t nHdr = std::fread(hdr, 1, std::min<size_t>(12, size_t(sz)), f);
  std::fclose(f);
  if (nHdr == 12 && std::memcmp(hdr, "RIFF", 4) == 0 && std::memcmp(hdr + 8, "WAVE", 4) == 0) {
    return readWavAtRate(path, outL, outR, targetRate, err);
  }
  // Headerless interleaved float32 stereo PCM (.f32 / .pcm).
  if (sz % 8 != 0) {
    err = "audio file is neither WAVE nor stereo float32 interleaved (size=" +
          std::to_string(sz) + "): " + path;
    return false;
  }
  const size_t srcFrames = size_t(sz) / 8;
  std::vector<float> inter(srcFrames * 2);
  std::FILE* fr = std::fopen(path.c_str(), "rb");
  if (!fr || std::fread(inter.data(), sizeof(float), inter.size(), fr) != inter.size()) {
    if (fr) std::fclose(fr);
    err = "short read on float32 file: " + path;
    return false;
  }
  std::fclose(fr);
  const int srcRate = srcRateHint > 0 ? srcRateHint : targetRate;
  std::vector<float> srcL(srcFrames), srcR(srcFrames);
  for (size_t i = 0; i < srcFrames; ++i) {
    srcL[i] = inter[i * 2];
    srcR[i] = inter[i * 2 + 1];
  }
  if (srcRate == targetRate || srcFrames == 0) {
    outL = std::move(srcL);
    outR = std::move(srcR);
    return true;
  }
  const size_t outFrames = static_cast<size_t>(std::llround(
      double(srcFrames) * double(targetRate) / double(srcRate)));
  outL.assign(outFrames, 0.f);
  outR.assign(outFrames, 0.f);
  const double step = double(srcRate) / double(targetRate);
  for (size_t i = 0; i < outFrames; ++i) {
    const double pos = double(i) * step;
    const size_t i0 = std::min(srcFrames - 1, size_t(std::floor(pos)));
    const size_t i1 = std::min(srcFrames - 1, i0 + 1);
    const float f = float(pos - double(i0));
    outL[i] = srcL[i0] * (1.f - f) + srcL[i1] * f;
    outR[i] = srcR[i0] * (1.f - f) + srcR[i1] * f;
  }
  return true;
}

struct CanonicalDeckSetup {
  int frameCount = 0;
  int64_t loopStartFrame = 0;
  int64_t loopEndFrame = 0;
  double releaseAtFrame = 1e18;
  std::vector<double> knotFrames;
  std::vector<double> knotPositions;
  bool runOn = false;
  double stopAtMixSec = 1e18;
  double prerollSec = 0.4;
  float levelGain = 1.f;
  double regionStartSec = 0.0;
  double fadeOutTrackSec = -1.0;
};

bool canonicalNumber(const JsonVal* value, double& out) {
  if (!value || value->kind != JsonVal::J_NUM || !std::isfinite(value->num)) return false;
  out = value->num;
  return true;
}

bool canonicalInteger(const JsonVal* value, int64_t& out) {
  double number = 0.0;
  if (!canonicalNumber(value, number) || std::floor(number) != number ||
      number < static_cast<double>(std::numeric_limits<int64_t>::min()) ||
      number >= static_cast<double>(std::numeric_limits<int64_t>::max())) return false;
  out = static_cast<int64_t>(number);
  return true;
}

bool parseCanonicalDeck(const JsonVal* geometry, const char* name,
                        CanonicalDeckSetup& setup, std::string& err) {
  const JsonVal* value = geometry ? geometry->get(name) : nullptr;
  if (!value || value->kind != JsonVal::J_OBJ) {
    err = std::string("renderGeometry.") + name + " must be an object";
    return false;
  }
  double n = 0.0;
  int64_t integer = 0;
  auto number = [&](const char* field, double& target) {
    if (canonicalNumber(value->get(field), target)) return true;
    err = std::string("renderGeometry.") + name + "." + field + " must be a finite number";
    return false;
  };
  auto whole = [&](const char* field, int64_t& target) {
    if (canonicalInteger(value->get(field), target)) return true;
    err = std::string("renderGeometry.") + name + "." + field + " must be an integer";
    return false;
  };
  if (!whole("frameCount", integer) || integer <= 0 || integer > std::numeric_limits<int>::max()) {
    if (err.empty()) err = std::string("renderGeometry.") + name + ".frameCount must be a positive Int";
    return false;
  }
  setup.frameCount = static_cast<int>(integer);
  if (!whole("loopStartFrame", setup.loopStartFrame) ||
      !whole("loopEndFrame", setup.loopEndFrame)) return false;
  if (setup.loopStartFrame < 0 || setup.loopEndFrame < setup.loopStartFrame ||
      setup.loopEndFrame > setup.frameCount) {
    err = std::string("renderGeometry.") + name + " loop bounds are outside its region samples";
    return false;
  }
  const JsonVal* release = value->get("releaseAtFrame");
  if (!release) {
    err = std::string("renderGeometry.") + name + ".releaseAtFrame is required (null means never release)";
    return false;
  }
  if (release->kind != JsonVal::J_NULL) {
    if (!canonicalNumber(release, setup.releaseAtFrame) || setup.releaseAtFrame < setup.loopEndFrame ||
        std::abs(setup.releaseAtFrame) > 9.0e15) {
      err = std::string("renderGeometry.") + name +
          ".releaseAtFrame must be null or a finite position at/after loopEndFrame";
      return false;
    }
  }
  const JsonVal* knotFrames = value->get("knotFrames");
  const JsonVal* knotPositions = value->get("knotPositions");
  if (!knotFrames || knotFrames->kind != JsonVal::J_ARR ||
      !knotPositions || knotPositions->kind != JsonVal::J_ARR ||
      knotFrames->arr.size() < 2 || knotFrames->arr.size() != knotPositions->arr.size()) {
    err = std::string("renderGeometry.") + name + " requires equal knotFrames/knotPositions arrays with at least two points";
    return false;
  }
  setup.knotFrames.reserve(knotFrames->arr.size());
  setup.knotPositions.reserve(knotPositions->arr.size());
  for (size_t i = 0; i < knotFrames->arr.size(); ++i) {
    double frame = 0.0, position = 0.0;
    if (!canonicalNumber(&knotFrames->arr[i], frame) ||
        !canonicalNumber(&knotPositions->arr[i], position)) {
      err = std::string("renderGeometry.") + name + " knot values must all be finite numbers";
      return false;
    }
    if (i > 0 && !(frame > setup.knotFrames.back())) {
      err = std::string("renderGeometry.") + name + " knotFrames must be strictly increasing";
      return false;
    }
    if (std::abs(frame) > 9.0e15 || std::abs(position) > 9.0e15 || position < 0.0 ||
        (i > 0 && position < setup.knotPositions.back())) {
      err = std::string("renderGeometry.") + name +
          " knot frame/position is outside safe range or positions are not non-negative/non-decreasing";
      return false;
    }
    setup.knotFrames.push_back(frame);
    setup.knotPositions.push_back(position);
  }
  if (setup.knotFrames.front() != 0.0 || setup.knotPositions.front() >= setup.frameCount) {
    err = std::string("renderGeometry.") + name + " knot map must start at mix frame 0 inside the decoded region";
    return false;
  }
  bool advancing = false;
  for (size_t i = 1; i < setup.knotFrames.size(); ++i) {
    const double dt = setup.knotFrames[i] - setup.knotFrames[i - 1];
    const double dp = setup.knotPositions[i] - setup.knotPositions[i - 1];
    if (dp == 0.0 && std::strcmp(name, "inbound") == 0 && !advancing) continue;
    const double rate = dp / dt;
    if (!(dp > 0.0) || (!advancing && setup.knotPositions[i] - setup.knotPositions.front() <= 0.5) ||
        !std::isfinite(rate) || rate < 0.75 - 1e-9 || rate > 1.35 + 1e-9) {
      err = std::string("renderGeometry.") + name + " knot map has a non-advancing or unsupported source rate";
      return false;
    }
    advancing = true;
  }
  if (!advancing) {
    err = std::string("renderGeometry.") + name + " knot map has no advancing segment";
    return false;
  }
  const JsonVal* runOn = value->get("runOn");
  if (!runOn || runOn->kind != JsonVal::J_BOOL) {
    err = std::string("renderGeometry.") + name + ".runOn must be a boolean";
    return false;
  }
  setup.runOn = runOn->b;
  if (!number("stopAtMixSec", setup.stopAtMixSec) || setup.stopAtMixSec <= 0.0 ||
      !number("prerollSec", setup.prerollSec) || setup.prerollSec < 0.0 || setup.prerollSec > 4.0 ||
      !number("levelGain", n) || n < 0.0 || n > 4.0 ||
      !number("regionStartSec", setup.regionStartSec)) {
    if (err.empty()) err = std::string("renderGeometry.") + name + " has out-of-range deck setup values";
    return false;
  }
  if (setup.regionStartSec < 0.0) {
    err = std::string("renderGeometry.") + name + ".regionStartSec must be non-negative";
    return false;
  }
  const double frameLimit = static_cast<double>(std::numeric_limits<int64_t>::max() / 2);
  for (double seconds : {setup.regionStartSec, setup.stopAtMixSec, setup.prerollSec}) {
    if (seconds >= frameLimit / 192000.0) {
      err = std::string("renderGeometry.") + name + " seconds exceed native frame range";
      return false;
    }
  }
  setup.levelGain = static_cast<float>(n);
  const JsonVal* fade = value->get("fadeOutTrackSec");
  if (!fade) {
    err = std::string("renderGeometry.") + name + ".fadeOutTrackSec is required (null means no fade)";
    return false;
  }
  if (fade->kind != JsonVal::J_NULL &&
      (!canonicalNumber(fade, setup.fadeOutTrackSec) || setup.fadeOutTrackSec < 0.0)) {
    err = std::string("renderGeometry.") + name + ".fadeOutTrackSec must be null or a finite non-negative number";
    return false;
  }
  return true;
}

bool validCanonicalRegion(const JsonVal* region, const char* name, const char* expectedDeck,
                          int sampleRate, std::string& err) {
  if (!region || region->kind != JsonVal::J_OBJ) {
    err = std::string("canonical ") + name + " must be an object";
    return false;
  }
  const std::string trackId = region->strField("trackId");
  if (region->strField("deck") != expectedDeck || trackId.find_first_not_of(" \t\r\n") == std::string::npos ||
      trackId.find('\0') != std::string::npos) {
    err = std::string("canonical ") + name + " has missing trackId or incorrect deck";
    return false;
  }
  double start = 0.0, end = 0.0, rate = 0.0;
  int64_t startMs = 0, endMs = 0;
  if (!canonicalInteger(region->get("startMs"), startMs) || !canonicalInteger(region->get("endMs"), endMs)) {
    err = std::string("canonical ") + name + " startMs/endMs must be integer milliseconds";
    return false;
  }
  start = static_cast<double>(startMs);
  end = static_cast<double>(endMs);
  if (start < 0.0 || end < start) {
    err = std::string("canonical ") + name + " requires finite non-negative startMs/endMs with endMs >= startMs";
    return false;
  }
  const JsonVal* sampleRateValue = region->get("sampleRateHz");
  if (!sampleRateValue) rate = 48000.0;
  else if (!canonicalNumber(sampleRateValue, rate)) {
    err = std::string("canonical ") + name + " sampleRateHz must be an integer matching script sampleRateHz";
    return false;
  }
  if (std::floor(rate) != rate || rate != sampleRate) {
    err = std::string("canonical ") + name + " sampleRateHz must match script sampleRateHz";
    return false;
  }
  const std::string pcmPath = region->strField("pcmPath");
  if (pcmPath.empty() || pcmPath[0] != '/' || pcmPath.find('\0') != std::string::npos) {
    err = std::string("canonical ") + name + ".pcmPath must be an absolute path";
    return false;
  }
  return true;
}

double canonicalPositionAt(const CanonicalDeckSetup& setup, double frame) {
  if (frame >= setup.knotFrames.back()) {
    const size_t last = setup.knotFrames.size() - 1;
    // Match Deck::knotRate(lastKnot - 1), including a sub-frame final segment.
    const double probe = setup.knotFrames.back() - 1.0;
    const auto upper = std::upper_bound(setup.knotFrames.begin(), setup.knotFrames.end(), probe);
    const size_t hi = std::clamp<size_t>(static_cast<size_t>(upper - setup.knotFrames.begin()), 1, last);
    const double rate = setup.runOn ? 1.0 : std::clamp(
        (setup.knotPositions[hi] - setup.knotPositions[hi - 1]) /
        (setup.knotFrames[hi] - setup.knotFrames[hi - 1]), 0.85, 1.15);
    return setup.knotPositions.back() + (frame - setup.knotFrames.back()) * rate;
  }
  const auto upper = std::upper_bound(setup.knotFrames.begin(), setup.knotFrames.end(), frame);
  const size_t hi = static_cast<size_t>(upper - setup.knotFrames.begin());
  const size_t lo = hi == 0 ? 0 : hi - 1;
  const size_t next = std::min(lo + 1, setup.knotFrames.size() - 1);
  const double f = (frame - setup.knotFrames[lo]) /
      (setup.knotFrames[next] - setup.knotFrames[lo]);
  return setup.knotPositions[lo] +
      (setup.knotPositions[next] - setup.knotPositions[lo]) * f;
}

double canonicalPhysicalPosition(const CanonicalDeckSetup& setup, double position) {
  const double loopLength = static_cast<double>(setup.loopEndFrame - setup.loopStartFrame);
  if (loopLength <= 0.0 || position < setup.loopEndFrame) return position;
  if (position < setup.releaseAtFrame) {
    return setup.loopStartFrame + std::fmod(position - setup.loopStartFrame, loopLength);
  }
  return setup.loopEndFrame + position - setup.releaseAtFrame;
}

bool validateCanonicalGeometry(const JsonVal& root, int sampleRate,
                               CanonicalDeckSetup& out, CanonicalDeckSetup& in,
                               std::string& err) {
  const JsonVal* geometry = root.get("renderGeometry");
  if (!geometry || geometry->kind != JsonVal::J_OBJ) {
    err = "renderGeometry must be an object";
    return false;
  }
  int64_t schema = 0, generation = 0;
  const JsonVal* planId = root.get("planId");
  if (!canonicalInteger(root.get("schema"), schema) || schema != 1 ||
      !canonicalInteger(root.get("generation"), generation) || generation < 0 ||
      !planId || planId->kind != JsonVal::J_STR ||
      planId->str.find_first_not_of(" \t\r\n") == std::string::npos || planId->str.find('\0') != std::string::npos) {
    err = "canonical script requires schema=1, non-negative generation, and non-empty planId";
    return false;
  }
  // TransitionScriptV1 itself has no engine field. If an exporting envelope
  // supplies one, never silently execute a different engine's payload.
  for (const char* key : {"engine", "engineVersion"}) {
    if (const JsonVal* engine = root.get(key); engine &&
        (engine->kind != JsonVal::J_STR || engine->str != "3.0.0")) {
      err = "unsupported canonical engine (expected 3.0.0)";
      return false;
    }
  }
  double value = 0.0;
  for (const char* key : {"handoverMixSec", "handoverTrackSec", "incomingStartMixSec", "incomingStartTrackSec"}) {
    if (!canonicalNumber(geometry->get(key), value) || value < 0.0) {
      err = std::string("renderGeometry.") + key + " must be a finite non-negative number";
      return false;
    }
  }
  const JsonVal* highQuality = geometry->get("highQuality");
  if (!highQuality || highQuality->kind != JsonVal::J_BOOL) {
    err = "renderGeometry.highQuality must be a boolean";
    return false;
  }
  if (!validCanonicalRegion(root.get("outRegion"), "outRegion", "OUT", sampleRate, err) ||
      !validCanonicalRegion(root.get("inRegion"), "inRegion", "IN", sampleRate, err) ||
      !parseCanonicalDeck(geometry, "out", out, err) ||
      !parseCanonicalDeck(geometry, "inbound", in, err)) return false;
  for (const auto& pair : {std::pair<const char*, const CanonicalDeckSetup*>("outRegion", &out),
                           std::pair<const char*, const CanonicalDeckSetup*>("inRegion", &in)}) {
    const JsonVal* region = root.get(pair.first);
    const double regionStartSec = region->numField("startMs") * 0.001;
    const double expectedEndMs = (pair.second->regionStartSec + pair.second->frameCount / static_cast<double>(sampleRate)) * 1000.0;
    const double actualEndMs = region->numField("endMs", -1.0);
    const double expectedStartMs = std::round(pair.second->regionStartSec * 1000.0);
    const double expectedPrerollMs = std::round(pair.second->prerollSec * 1000.0);
    const double expectedStopMs = std::round(pair.second->stopAtMixSec * 1000.0);
    const JsonVal* prerollMs = region->get("prerollMs");
    int64_t actualPrerollValue = 400;
    if (prerollMs && !canonicalInteger(prerollMs, actualPrerollValue)) {
      err = std::string("canonical ") + pair.first + ".prerollMs must be an integer";
      return false;
    }
    const double actualPrerollMs = static_cast<double>(actualPrerollValue);
    const JsonVal* stopAtMixMs = region->get("stopAtMixMs");
    int64_t actualStopValue = 0;
    if (!canonicalInteger(stopAtMixMs, actualStopValue)) {
      err = std::string("canonical ") + pair.first + ".stopAtMixMs must be an integer";
      return false;
    }
    const double actualStopMs = static_cast<double>(actualStopValue);
    const JsonVal* fadeOutTrackMs = region->get("fadeOutTrackMs");
    const double expectedFadeMs = pair.second->fadeOutTrackSec >= 0.0 ?
        std::round(pair.second->fadeOutTrackSec * 1000.0) : -1.0;
    int64_t actualFadeValue = 0;
    if (fadeOutTrackMs && fadeOutTrackMs->kind != JsonVal::J_NULL &&
        !canonicalInteger(fadeOutTrackMs, actualFadeValue)) {
      err = std::string("canonical ") + pair.first + ".fadeOutTrackMs must be null or an integer";
      return false;
    }
    const double actualFadeMs = fadeOutTrackMs && fadeOutTrackMs->kind != JsonVal::J_NULL ?
        static_cast<double>(actualFadeValue) : -1.0;
    double actualLevel = 1.0;
    if (region->get("levelGain") && !canonicalNumber(region->get("levelGain"),actualLevel)) {
      err = "canonical region levelGain must be a finite number";
      return false;
    }
    const JsonVal* runOn = region->get("runOn");
    if (std::abs(regionStartSec - pair.second->regionStartSec) > 0.000500001 ||
        std::abs(region->numField("startMs") - expectedStartMs) > 0.5 ||
        std::abs(actualEndMs - expectedEndMs) > 0.500001 ||
        std::abs(actualPrerollMs - expectedPrerollMs) > 0.5 ||
        std::abs(actualStopMs - expectedStopMs) > 0.5 ||
        std::abs(actualFadeMs - expectedFadeMs) > 0.5 ||
        static_cast<float>(actualLevel) != pair.second->levelGain ||
        !runOn || runOn->kind != JsonVal::J_BOOL || runOn->b != pair.second->runOn) {
      err = std::string("renderGeometry.") + (pair.first[0] == 'o' ? "out" : "inbound") +
          " deck setup does not match its AudioRegionRef projections";
      return false;
    }
  }
  if (out.frameCount <= 0 || in.frameCount <= 0) {
    err = "canonical renderGeometry requires positive deck frame counts";
    return false;
  }
  double handoverMixSec = geometry->numField("handoverMixSec", -1.0);
  double handoverTrackSec = geometry->numField("handoverTrackSec", -1.0);
  double incomingStartMixSec = geometry->numField("incomingStartMixSec", -1.0);
  double incomingStartTrackSec = geometry->numField("incomingStartTrackSec", -1.0);
  if (!(handoverMixSec > 0.0) || incomingStartMixSec < 0.0 || incomingStartMixSec >= handoverMixSec ||
      handoverTrackSec < 0.0 || incomingStartTrackSec < 0.0 || out.fadeOutTrackSec >= 0.0 ||
      in.fadeOutTrackSec < 0.0 || std::abs(in.fadeOutTrackSec - handoverTrackSec) > 1e-9 || !in.runOn ||
      in.stopAtMixSec + 1e-9 < handoverMixSec + 0.025 ||
      std::abs(in.knotFrames.back() - handoverMixSec * sampleRate) > 1e-6) {
    err = "canonical handover, incoming fade, and terminal knot geometry are inconsistent";
    return false;
  }
  const double handoverPosition = canonicalPositionAt(in, handoverMixSec * sampleRate);
  const double handoverTrack = in.regionStartSec +
      canonicalPhysicalPosition(in, handoverPosition) / sampleRate;
  const double expectedIncomingStart = std::max(0.0, handoverTrack - (handoverMixSec - incomingStartMixSec));
  const double incomingFadeEndPosition = canonicalPhysicalPosition(
      in, canonicalPositionAt(in, (handoverMixSec + 0.025) * sampleRate));
  if ((in.loopEndFrame > in.loopStartFrame &&
       (in.releaseAtFrame >= 1e15 || handoverPosition < in.releaseAtFrame)) ||
      std::abs(handoverTrack - handoverTrackSec) > 0.5 / sampleRate ||
      std::abs(expectedIncomingStart - incomingStartTrackSec) > 0.5 / sampleRate ||
      in.frameCount + 1.0 < incomingFadeEndPosition) {
    err = "canonical incoming source map does not cover handover and physical fade geometry";
    return false;
  }
  if (const JsonVal* knots = root.get("knots"); knots && knots->kind != JsonVal::J_NULL &&
      !(knots->kind == JsonVal::J_ARR && knots->arr.empty())) {
    err = "canonical scripts must keep executable knots in renderGeometry, not legacy knots";
    return false;
  }
  if (const JsonVal* loops = root.get("loopBounds"); loops && loops->kind != JsonVal::J_NULL) {
    err = "canonical scripts must keep loop setup in renderGeometry, not loopBounds";
    return false;
  }
  if (root.get("inKnots") || root.get("outKnots") || root.get("audition")) {
    err = "canonical scripts cannot include legacy knot or audition overlays";
    return false;
  }
  return true;
}

bool canonicalText(const JsonVal* v) {
  return v && v->kind == JsonVal::J_STR && v->str.find('\0') == std::string::npos &&
      v->str.find_first_not_of(" \t\r\n") != std::string::npos;
}

int canonicalDeckIndex(const JsonVal* v, int def = -1) {
  if (!v) return def;
  if (v->kind != JsonVal::J_STR) return -1;
  return v->str == "OUT" ? 0 : v->str == "IN" ? 1 : -1;
}

// Optional scalar fields use the contract's serialized defaults, never a
// host-inferred beat clock, lane, placement or replacement recipe.
bool canonicalScalar(const JsonVal& obj, const char* key, double def, double low,
                     double high, std::string& err, bool integer = false) {
  double n = def;
  if ((obj.get(key) && !canonicalNumber(obj.get(key), n)) || n < low || n > high ||
      !std::isfinite(n) || (integer && std::floor(n) != n)) {
    err = std::string(key) + " has an invalid type or value";
    return false;
  }
  return true;
}

bool canonicalBoolean(const JsonVal& obj, const char* key, std::string& err) {
  const JsonVal* v = obj.get(key);
  if (!v || v->kind == JsonVal::J_BOOL) return true;
  err = std::string(key) + " must be boolean";
  return false;
}

bool validateCanonicalFx(const JsonVal& root, int sr, const CanonicalDeckSetup& out,
                         const CanonicalDeckSetup& in, std::string& err) {
  const JsonVal* fx = root.get("fxParams");
  if (!fx) return true;
  if (fx->kind != JsonVal::J_OBJ) { err = "fxParams must be an object"; return false; }
  if (!canonicalBoolean(*fx, "isolate", err)) return false;
  if (fx->get("svfQ")) { err = "use canonical outSvfQ/inSvfQ, not legacy svfQ"; return false; }
  for (const char* key : {"outSvfQ", "inSvfQ"}) {
    const JsonVal* q = fx->get(key);
    if (q && q->kind != JsonVal::J_NULL && !canonicalScalar(*fx, key, .707, .5, 1.92, err)) return false;
  }
  int brakes = 0;
  for (const char* name : {"loop", "roll", "sweep", "incomingSweep", "echo", "reverb", "flanger",
                           "phaser", "riser", "impact", "gate", "pump", "brake", "tapeStop", "spinback"}) {
    const JsonVal* p = fx->get(name);
    if (!p || p->kind == JsonVal::J_NULL) continue;
    if (p->kind != JsonVal::J_OBJ) { err = std::string(name) + " must be an object or null"; return false; }
    for (const auto& kv : p->obj) {
      if (kv.second.kind == JsonVal::J_NUM && std::abs(kv.second.num) > std::numeric_limits<float>::max()) {
        err = std::string(name) + "." + kv.first + " exceeds native scalar range";
        return false;
      }
    }
    for (const char* key : {"enabled", "isHighPass", "exponential", "pingPong", "includeSnareRoll",
                             "stopOnDownbeat", "isStutter", "fullBand"}) {
      if (!canonicalBoolean(*p, key, err)) return false;
    }
    const std::string key(name);
    const int deck = canonicalDeckIndex(p->get("deck"), key == "impact" ? 1 : 0);
    if (deck < 0) { err = key + ".deck must be OUT or IN"; return false; }
    const double horizon = deck == 0 ? out.stopAtMixSec : root.get("renderGeometry")->numField("handoverMixSec");
    const CanonicalDeckSetup& setup = deck == 0 ? out : in;
    auto n = [&](const char* field, double def, double low, double high, bool integer = false) {
      return canonicalScalar(*p, field, def, low, high, err, integer);
    };
    auto interval = [&](double start, double end) {
      if (std::isfinite(start) && std::isfinite(end) && start >= 0 && end > start && end <= horizon) return true;
      err = key + " requires a resolved interval inside its deck horizon";
      return false;
    };
    const bool enabled = p->boolField("enabled");
    if (key == "loop") {
      if (!n("bars", 4, 1, std::numeric_limits<int>::max(), true) ||
          !n("holdPhrases", 0, 0, std::numeric_limits<int>::max(), true) ||
          !n("seamCrossfadeMs", 8, .5, 8)) return false;
      if (enabled && out.loopEndFrame <= out.loopStartFrame) { err = "enabled loop has no OUT loop geometry"; return false; }
    } else if (key == "sweep" || key == "incomingSweep") {
      if (deck != (key == "sweep" ? 0 : 1)) { err = "sweep deck does not match typed slot"; return false; }
      if (!n("startHz", 20, 20, sr*.45) || !n("endHz", 1500, 20, sr*.45) ||
          !n("qStart", .707, .5, 1.92) || !n("qEnd", 1.6, .5, 1.92) ||
          !n("bars", 8, 1, std::numeric_limits<int>::max(), true)) return false;
      for (const char* t : {"startMixSec", "endMixSec"}) {
        if (const JsonVal* v = p->get(t); v && v->kind != JsonVal::J_NULL &&
            !n(t, 0, 0, horizon)) return false;
      }
      if (enabled && (!p->hasNum("startMixSec") || !p->hasNum("endMixSec") ||
          !interval(p->numField("startMixSec"), p->numField("endMixSec")))) {
        err = "enabled sweep requires resolved startMixSec/endMixSec"; return false;
      }
    } else if (key == "roll") {
      if (!n("startMixSec", 0, 0, horizon) || !n("releaseMixSec", 0, 0, horizon) ||
          !n("beatSec", .5, 1e-9, 1e6) || !n("totalBeats", 8, 1e-9, 1e6) ||
          !n("hpfStartHz", 200, 20, 8000) || !n("hpfEndHz", 2000, p->numField("hpfStartHz",200), 16000) ||
          !n("boostDb", 2, -6, 6) || !interval(p->numField("startMixSec"), p->numField("releaseMixSec")) ||
          setup.loopEndFrame <= setup.loopStartFrame) { if (err.empty()) err = "roll requires loop geometry"; return false; }
      if (const JsonVal* schedule = p->get("scheduleBeats")) {
        if (schedule->kind != JsonVal::J_ARR || schedule->arr.empty()) { err = "roll scheduleBeats must be a nonempty array"; return false; }
        for (const auto& v : schedule->arr) {
          if (v.kind != JsonVal::J_NUM || v.num < .0625 || v.num > 32) { err = "invalid roll scheduleBeats"; return false; }
        }
      }
    } else if (key == "echo") {
      if (!n("delayBeats", .5, 1e-9, 1e6) || !n("delaySec", 0, .010000001, 2) ||
          !n("feedback", .6, 0, .85) || !n("wet", .55, 1e-9, 1.2) ||
          !n("wetHpfHz", 250, 40, 4000) || !n("wetLpfHz", 6000, 500, sr*.45)) return false;
    } else if (key == "reverb") {
      if (!n("roomSize", 2.2, .5, 6) || !n("damping", .55, 0, 1) || !n("wet", .35, 1e-9, 1.2)) return false;
    } else if (key == "flanger") {
      if (!n("lfoCyclesPerBars", 2, 1e-9, 1e6) || !n("lfoHz", 0, 1e-9, 1e6) ||
          !n("depthSec", .003, .0002, .0045) || !n("feedback", .64, -.78, .78) ||
          !n("wetPeak", .6, 1e-9, 1) || !n("stereoOffsetDeg",90,-1e9,1e9)) return false;
    } else if (key == "phaser") {
      if (!n("rateBeats", 4, .0625, 64) || !n("depth", .7, 0, 1) || !n("feedback", .6, -.85, .85) ||
          !n("wetPeak", .7, 0, 1.2) || !n("stages", 6, 4, 8, true) ||
          !n("stereoOffsetDeg",90,-1e9,1e9) || int(p->numField("stages",6)) % 2 != 0) {
        if (err.empty()) err = "phaser stages must be even";
        return false;
      }
    } else if (key == "riser") {
      if (!n("beatSec", .5, .100000001, 3.999999999) || !n("lengthBeats",16,.25,256) ||
          !n("noiseSweepStartHz",300,60,12000) ||
          !n("noiseSweepEndHz",8000,p->numField("noiseSweepStartHz",300)+50,sr*.42) ||
          !n("noiseLevelPeak",.7,0,1.5) || !n("uplifterOctaves",1,.25,3) || !n("reverseReverbTailBeats",1,0,2)) return false;
      if (p->get("startMixSec")) { err = "riser start is determined by its canonical lane, not a host overlay"; return false; }
    } else if (key == "impact") {
      if (!n("triggerMixSec",0,0,horizon) || !n("peakDbfs",-6,-40,-6) ||
          !n("lowThumpHz",55,28,140) || !n("noiseBurstMs",180,30,1500)) return false;
    } else if (key == "gate") {
      if (!n("beatSec",.5,.100000001,3.999999999) || !n("divisionStepsPerBeat",4,-16,16) ||
          !n("swing",0,0,.33) || !n("depthPeak",1,0,1) || !n("rampBars",2,1e-9,1e6)) return false;
      const double div = p->numField("divisionStepsPerBeat",4);
      if (std::abs(div) < .0625 || (div < 0 && !p->boolField("isStutter"))) { err = "invalid gate division"; return false; }
      for (const char* overlay : {"divisionEndStepsPerBeat", "startMixSec", "endMixSec"}) {
        if (p->get(overlay)) { err = "gate timing must come from canonical automation, not host overlays"; return false; }
      }
    } else if (key == "pump") {
      if (!n("beatSec",.5,.100000001,3.999999999)) return false;
    } else if (key == "spinback") {
      const double gapBeats = p->numField("silenceGapBeats", 1.0);
      const bool hasCompiledGapSec = p->hasNum("silenceGapSec");
      const double gapSecRaw = hasCompiledGapSec ? p->numField("silenceGapSec") : 0.0;
      // Legacy sentinel: silenceGapSec defaults to 0.0 when absent in older scripts.
      // If beats source intent is present, compiled seconds must be authoritative.
      const bool missingCompiledGapSec = gapBeats > 0.0 && (!hasCompiledGapSec || gapSecRaw <= 0.0);
      if (enabled && missingCompiledGapSec) {
        err = "enabled spinback requires compiled silenceGapSec";
        return false;
      }
      const double gapSec = missingCompiledGapSec ? 0.0 : gapSecRaw;
      if (!n("startMixSec",0,0,horizon) || !n("lengthSec",.25,.02,3) ||
          !n("peakReverseSpeed",3,1,6) || !n("silenceGapBeats",1,0,1e6) ||
          !(std::isfinite(gapSecRaw) && gapSecRaw >= 0.0) ||
          (!missingCompiledGapSec && !(std::isfinite(gapSec) && gapSec >= 0.0 && gapSec <= 4.0)) ||
          !interval(p->numField("startMixSec"),p->numField("startMixSec")+p->numField("lengthSec",.25))) return false;
      if (enabled) ++brakes;
    } else {
      const double length = p->numField("lengthSec",key == "brake" ? .5 : 1);
      if (!n("startMixSec",0,0,horizon) || !n("lengthSec",key == "brake" ? .5 : 1,.02,8) ||
          !n("lpfStartHz",18000,2000,sr*.45) || !n("lpfEndHz",key == "brake" ? 800 : 600,100,p->numField("lpfStartHz",18000)) ||
          !n("endFadeMs",10,1,length*500) ||
          !interval(p->numField("startMixSec"),p->numField("startMixSec")+length)) return false;
      if (key == "brake" && !n("endRate",.05,.01,.45)) return false;
      if (key == "tapeStop" && (!n("wowDepth",.03,0,.12) || !n("wowRateHz",5,.5,15))) return false;
      if (enabled) ++brakes;
    }
  }
  if (brakes > 1) { err = "conflicting brake/tapeStop/spinback processors"; return false; }
  return true;
}

bool validateCanonicalContent(const JsonVal& root, int sr, const CanonicalDeckSetup& out,
                              const CanonicalDeckSetup& in, std::string& err) {
  bool stems[2][4] = {};
  const JsonVal* stemList = root.get("stems");
  if (stemList && stemList->kind != JsonVal::J_ARR) { err = "stems must be an array"; return false; }
  if (stemList) for (const auto& s : stemList->arr) {
    const int deck = canonicalDeckIndex(s.get("deck"));
    const int kind = parseStemKind(s.get("stem"));
    if (s.kind != JsonVal::J_OBJ || deck < 0 || kind < 0 || !s.get("stem") ||
        s.get("stem")->kind != JsonVal::J_STR || stems[deck][kind]) {
      err = "canonical stems require unique, typed deck/stem identities"; return false;
    }
    stems[deck][kind] = true;
    const JsonVal& region = *root.get(deck == 0 ? "outRegion" : "inRegion");
    int64_t start = 0, end = 0;
    if (s.strField("trackId") != region.strField("trackId") ||
        !canonicalInteger(s.get("startMs"), start) || !canonicalInteger(s.get("endMs"), end) ||
        start != region.numField("startMs") || end != region.numField("endMs") ||
        !canonicalScalar(s,"sampleRateHz",48000,sr,sr,err,true) ||
        !canonicalText(s.get("pcmPath")) || s.strField("pcmPath")[0] != '/') {
      err = "canonical stem track, region, sample rate or PCM path is misaligned"; return false;
    }
  }
  bitchord::loopmix::Envelope lanes[2][20];
  bool seen[2][20] = {};
  const JsonVal* envs = root.get("envelopes");
  if (!envs || envs->kind != JsonVal::J_ARR) { err = "canonical envelopes must be an array with both GAIN lanes"; return false; }
  const double handover = root.get("renderGeometry")->numField("handoverMixSec");
  for (const auto& lane : envs->arr) {
    const int deck = canonicalDeckIndex(lane.get("deck"));
    const int kind = parseEnvelopeKind(lane.get("kind"));
    if (lane.kind != JsonVal::J_OBJ || deck < 0 || kind < 0 || !lane.get("kind") ||
        lane.get("kind")->kind != JsonVal::J_STR || seen[deck][kind]) {
      err = "canonical envelopes require unique, typed deck/kind identities"; return false;
    }
    seen[deck][kind] = true;
    const JsonVal* times = lane.get("timesSec");
    const JsonVal* values = lane.get("values");
    if (!times || !values || times->kind != JsonVal::J_ARR || values->kind != JsonVal::J_ARR ||
        times->arr.empty() || times->arr.size() != values->arr.size() ||
        !canonicalBoolean(lane,"exponential",err)) { err = "malformed canonical envelope arrays/curve"; return false; }
    auto& e = lanes[deck][kind];
    e.exponential = lane.boolField("exponential",kind == 2 || kind == 3);
    const auto& setup = deck == 0 ? out : in;
    for (size_t i = 0; i < times->arr.size(); ++i) {
      double t = 0, v = 0;
      if (!canonicalNumber(&times->arr[i],t) || !canonicalNumber(&values->arr[i],v) ||
          std::abs(v) > std::numeric_limits<float>::max() || t < -setup.prerollSec ||
          t > setup.stopAtMixSec || (i > 0 && t <= e.times.back())) {
        err = "envelope values must fit native floats; times must increase inside deck bounds"; return false;
      }
      bool valid = true;
      if (kind == 0 || (kind >= 9 && kind <= 12)) valid = v >= 0;
      else if (kind == 2 || kind == 3) valid = v > 0;
      else if (kind == 13) valid = v >= .25 && v <= 6;
      else if (kind == 16) valid = v >= 0 && v <= 2;
      else if (kind == 19) valid = v >= -1 && v <= 1;
      else if (kind == 4 || kind == 7 || kind == 8 || (kind >= 14 && kind <= 18)) valid = v >= 0 && v <= 1;
      if (!valid) { err = "canonical envelope value is outside lane bounds"; return false; }
      e.times.push_back(t); e.values.push_back(static_cast<float>(v));
    }
    if (kind >= 9 && kind <= 12 && !stems[deck][kind-9]) { err = "canonical stem lane has no aligned stem asset"; return false; }
  }
  if (!seen[0][0] || !seen[1][0]) { err = "canonical script requires both OUT/IN GAIN lanes"; return false; }
  // ChoreoGuard.isNeutral tolerances; do not invent tighter host-only gates.
  auto neutral = [](int kind,float v) {
    if (!std::isfinite(v)) return false;
    if (kind == 0 || (kind >= 9 && kind <= 12)) return std::abs(v-1.f) <= .02f;
    if (kind == 1 || kind == 5 || kind == 6) return std::abs(v) <= .25f;
    if (kind == 2) return v <= 25.f;
    if (kind == 3) return v >= 19000.f;
    if (kind == 13) return std::abs(v-1.f) <= .05f;
    if (kind == 19) return std::abs(v) <= .02f;
    return v >= -1e-4f && v <= .02f;
  };
  const JsonVal* fx = root.get("fxParams");
  auto configured = [&](const char* key,int deck) {
    const JsonVal* p = fx ? fx->get(key) : nullptr;
    return p && p->kind == JsonVal::J_OBJ && p->boolField("enabled") &&
        canonicalDeckIndex(p->get("deck"),std::strcmp(key,"impact") == 0 ? 1 : 0) == deck;
  };
  for (int deck = 0; deck < 2; ++deck) for (int k = 0; k < 20; ++k) if (seen[deck][k]) {
    const auto& e = lanes[deck][k];
    if (deck == 0 && !neutral(k,e.at(0))) { err = "OUT lane is not neutral at splice"; return false; }
    if (deck == 1 && (!neutral(k,e.at(handover)) ||
        !neutral(k,e.values.back()) || e.times.back() > handover + 1e-6)) {
      err = "IN lane is not neutral by canonical handover"; return false;
    }
    const char* processor = k == 4 ? "echo" : k == 7 ? "flanger" : k == 8 ? "reverb" :
        k == 14 ? "phaser" : k == 15 ? "gate" : k == 16 ? "riser" : k == 17 ? "pump" : nullptr;
    if (processor && !configured(processor,deck)) { err = "canonical wet lane has no configured processor"; return false; }
    if (k == 18 && !configured("brake",deck) && !configured("tapeStop",deck) && !configured("spinback",deck)) {
      err = "canonical BRAKE lane has no configured processor"; return false;
    }
    if (deck == 0 && (k == 4 || k == 7 || k == 8 || (k >= 14 && k <= 17)) &&
        (e.at(out.stopAtMixSec) > .02 || e.values.back() > .02)) { err = "OUT wet lane is not retired by stop"; return false; }
  }
  if (lanes[0][0].at(handover) > .001 || lanes[0][0].values.back() > .001 || lanes[1][0].at(0) > .001) {
    err = "canonical GAIN splice/handover boundaries are invalid"; return false;
  }
  for (const auto& item : {std::pair<const char*,int>("phaser",14),{"gate",15},{"riser",16},{"pump",17}}) {
    for (int deck = 0; deck < 2; ++deck) if (configured(item.first,deck) && !seen[deck][item.second]) {
      err = "enabled canonical processor is missing its automation lane"; return false;
    }
  }
  double dryEnd = 0;
  for (size_t i = 0; i < lanes[0][0].times.size(); ++i) {
    if (lanes[0][0].values[i] > .001 || (i > 0 && lanes[0][0].values[i-1] > .001)) dryEnd = lanes[0][0].times[i];
  }
  auto covered = [&](const CanonicalDeckSetup& d, double endPosition, bool incoming) {
    double required = std::max(canonicalPhysicalPosition(d,d.knotPositions.front()),canonicalPhysicalPosition(d,endPosition));
    if (d.loopEndFrame > d.loopStartFrame) required = std::max(required,d.loopEndFrame + .008*sr);
    if (incoming) required = std::max(required,(in.fadeOutTrackSec+.025-d.regionStartSec)*sr);
    return std::isfinite(required) && required <= d.frameCount+1.0;
  };
  if (!covered(out,canonicalPositionAt(out,dryEnd*sr),false) || !covered(in,in.knotPositions.back(),true)) {
    err = "canonical physical PCM does not cover dry playback, loop seam or incoming fade"; return false;
  }
  const JsonVal* loud = root.get("loudness");
  if (loud && (loud->kind != JsonVal::J_OBJ || !canonicalBoolean(*loud,"enabled",err) ||
      !canonicalBoolean(*loud,"truePeakLimiter",err) || !canonicalScalar(*loud,"targetLufs",-14,-59.999999,-3.000001,err) ||
      !canonicalScalar(*loud,"truePeakCeilingDbtp",-1,-12,-.1,err) || !canonicalScalar(*loud,"dropBumpLu",0,0,6,err))) {
    if (err.empty()) err = "invalid canonical loudness settings";
    return false;
  }
  if (const JsonVal* window = root.get("dropWindowMs"); window && window->kind != JsonVal::J_NULL) {
    int64_t start = 0, end = 0;
    if (window->kind != JsonVal::J_OBJ || !canonicalInteger(window->get("startMs"),start) ||
        !canonicalInteger(window->get("endMs"),end) || start < 0 || end <= start) { err = "invalid canonical drop window"; return false; }
  }
  return validateCanonicalFx(root,sr,out,in,err);
}

bool loadCanonicalAudio(const std::string& path, int sr, int frames,
                        std::vector<float>& left, std::vector<float>& right, std::string& err) {
  // Detect and reject a WAV's actual channel/rate mismatch before the legacy
  // inspection loader could downmix or resample it into a plausible frame count.
  std::ifstream file(path,std::ios::binary);
  if (!file) { err = "cannot open canonical PCM: " + path; return false; }
  file.seekg(0,std::ios::end);
  const auto size = file.tellg();
  if (size <= 0) { err = "empty or unreadable canonical PCM"; return false; }
  file.seekg(0);
  uint8_t hdr[12] = {};
  file.read(reinterpret_cast<char*>(hdr),12);
  const bool wave = file.gcount() == 12 && std::memcmp(hdr,"RIFF",4) == 0 && std::memcmp(hdr+8,"WAVE",4) == 0;
  if (wave) {
    bool haveFmt = false, haveData = false;
    uint32_t dataBytes = 0;
    int bytesPerFrame = 0;
    uint64_t off = 12;
    while (off+8 <= static_cast<uint64_t>(size)) {
      uint8_t chunk[8]; file.seekg(static_cast<std::streamoff>(off)); file.read(reinterpret_cast<char*>(chunk),8);
      const uint32_t count = readU32(chunk+4);
      if (off+8+count > static_cast<uint64_t>(size)) { err = "truncated canonical WAV chunk"; return false; }
      if (std::memcmp(chunk,"fmt ",4) == 0) {
        uint8_t fmt[16] = {};
        if (count < 16) { err = "short canonical WAV format"; return false; }
        file.read(reinterpret_cast<char*>(fmt),16);
        if (readU16(fmt+2) != 2 || readU32(fmt+4) != static_cast<uint32_t>(sr)) {
          err = "canonical WAV must already be stereo at script sampleRateHz"; return false;
        }
        bytesPerFrame = readU16(fmt+12);
        if (bytesPerFrame != 4 && bytesPerFrame != 8) { err = "invalid canonical WAV block alignment"; return false; }
        haveFmt = true;
      } else if (std::memcmp(chunk,"data",4) == 0) {
        dataBytes = count;
        haveData = true;
      }
      off += 8+static_cast<uint64_t>(count)+(count&1u);
    }
    if (!haveFmt || !haveData || uint64_t(frames)*bytesPerFrame != dataBytes) {
      err = "canonical WAV frame count must match renderGeometry before decoding"; return false;
    }
  } else if (size != static_cast<std::streamoff>(frames)*8) {
    err = "canonical PCM must be exact-length interleaved stereo f32"; return false;
  }
  if (!loadAudioAuto(path,"",sr,sr,left,right,err)) return false;
  if (left.size() != static_cast<size_t>(frames) || right.size() != static_cast<size_t>(frames)) {
    err = "canonical PCM frame count does not match renderGeometry"; return false;
  }
  for (size_t i = 0; i < left.size(); ++i) if (!std::isfinite(left[i]) || !std::isfinite(right[i])) {
    err = "canonical PCM contains non-finite samples"; return false;
  }
  return true;
}

}  // namespace render_script
