// Host-only TransitionScript renderer installation and WAV execution.
// Canonical --render executes only complete retained geometry. Legacy draft
// JSON may be inspected with --inspect-script; it is never inferred into audio.
// Implementation header included once inside the host CLI anonymous namespace.
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#pragma once

namespace render_script {

// Matches the existing canonical device adapter's typed setters. Geometry and
// automation are already frozen: no wet lanes, beat clocks or recipes are made.
void installCanonicalFx(Renderer& r, const JsonVal& root) {
  const JsonVal empty;
  const JsonVal& fx = root.get("fxParams") ? *root.get("fxParams") : empty;
  for (const auto& q : {std::pair<const char*,int>("outSvfQ",0),{"inSvfQ",1}}) {
    if (fx.hasNum(q.first)) r.setSvfQ(q.second,float(fx.numField(q.first)));
  }
  auto active = [&](const char* name) -> const JsonVal* {
    const JsonVal* p = fx.get(name);
    return p && p->kind == JsonVal::J_OBJ && p->boolField("enabled") ? p : nullptr;
  };
  auto deck = [](const JsonVal* p,int def=0) { return canonicalDeckIndex(p->get("deck"),def); };
  if (const JsonVal* p = active("loop")) r.setLoopSeamMs(0,p->numField("seamCrossfadeMs",8));
  if (const JsonVal* p = active("roll")) {
    std::vector<double> schedule{4,2,1,.5,.25};
    if (const JsonVal* v = p->get("scheduleBeats")) {
      schedule.clear(); for (const auto& point : v->arr) schedule.push_back(point.num);
    }
    r.setLoopRollEx(deck(p),true,p->numField("startMixSec"),p->numField("beatSec",.5),
        p->numField("releaseMixSec"),p->numField("totalBeats",8),float(p->numField("hpfStartHz",200)),
        float(p->numField("hpfEndHz",2000)),float(p->numField("boostDb",2)),p->boolField("stopOnDownbeat",true),
        schedule.data(),int(schedule.size()));
  }
  for (const char* name : {"sweep","incomingSweep"}) if (const JsonVal* p = active(name)) {
    r.setSweep(deck(p),p->boolField("isHighPass",true),float(p->numField("startHz",20)),
        float(p->numField("endHz",1500)),float(p->numField("qStart",.707)),float(p->numField("qEnd",1.6)),
        p->numField("startMixSec"),p->numField("endMixSec"),p->boolField("exponential",true));
  }
  if (const JsonVal* p = active("echo")) {
    r.setEchoEx(deck(p),p->numField("delaySec"),float(p->numField("feedback",.6)),float(p->numField("wet",.55)),
        float(p->numField("wetHpfHz",250)),float(p->numField("wetLpfHz",6000)),p->boolField("pingPong",true));
  }
  if (const JsonVal* p = active("reverb")) r.setReverb(deck(p),float(p->numField("roomSize",2.2)),float(p->numField("damping",.55)),float(p->numField("wet",.35)));
  if (const JsonVal* p = active("flanger")) {
    r.setFlangerEx(deck(p),p->numField("lfoHz"),p->numField("depthSec",.003),float(p->numField("feedback",.64)),
        float(p->numField("wetPeak",.6)),float(p->numField("stereoOffsetDeg",90)));
  }
  if (const JsonVal* p = active("phaser")) {
    r.setPhaserEx(deck(p),p->numField("rateBeats",4),float(p->numField("depth",.7)),
        float(p->numField("feedback",.6)),int(p->numField("stages",6)),float(p->numField("stereoOffsetDeg",90)));
  }
  if (const JsonVal* p = active("riser")) {
    r.setRiserEx(deck(p),p->numField("beatSec",.5),p->numField("lengthBeats",16),
        float(p->numField("noiseSweepStartHz",300)),float(p->numField("noiseSweepEndHz",8000)),
        float(p->numField("noiseLevelPeak",.7)),float(p->numField("uplifterOctaves",1)),
        p->boolField("includeSnareRoll",true),p->numField("reverseReverbTailBeats",1),0.0);
  }
  if (const JsonVal* p = active("impact")) r.setImpact(deck(p,1),p->numField("triggerMixSec"),float(p->numField("peakDbfs",-6)),float(p->numField("lowThumpHz",55)),p->numField("noiseBurstMs",180));
  if (const JsonVal* p = active("gate")) {
    const float division=float(p->numField("divisionStepsPerBeat",4));
    r.setGate(deck(p),p->numField("beatSec",.5),p->boolField("isStutter") ? -division : division,float(p->numField("swing")));
  }
  if (const JsonVal* p = active("pump")) r.setPump(deck(p),p->numField("beatSec",.5),p->boolField("fullBand"));
  if (const JsonVal* p = active("brake")) r.setBrakeEx(deck(p),p->numField("startMixSec"),p->numField("lengthSec",.5),float(p->numField("endRate",.05)),float(p->numField("lpfStartHz",18000)),float(p->numField("lpfEndHz",800)),p->numField("endFadeMs",10));
  if (const JsonVal* p = active("tapeStop")) r.setTapeStop(deck(p),p->numField("startMixSec"),p->numField("lengthSec",1),float(p->numField("wowDepth",.03)),float(p->numField("wowRateHz",5)),float(p->numField("lpfStartHz",18000)),float(p->numField("lpfEndHz",600)),p->numField("endFadeMs",10));
  // Canonical compiled spinback gap: prefer silenceGapSec (frozen compiler output).
  // Legacy fallback: silenceGapBeats only when silenceGapSec is absent (backward compat);
  // host-side validation rejects enabled spinback missing compiled silenceGapSec.
  if (const JsonVal* p = active("spinback")) {
    const double gapSec = p->hasNum("silenceGapSec")
        ? p->numField("silenceGapSec")
        : p->numField("silenceGapBeats", 1);
    r.setSpinback(deck(p), p->numField("startMixSec"), p->numField("lengthSec", .25),
        p->numField("peakReverseSpeed", 3), gapSec);
  }
  if (fx.boolField("isolate")) { r.setIsolate(0,true); r.setIsolate(1,true); }
  const JsonVal& loud = root.get("loudness") ? *root.get("loudness") : empty;
  r.setLoudnessMatch(loud.boolField("enabled",true),float(loud.numField("targetLufs",-14)));
  if (const JsonVal* w = root.get("dropWindowMs"); w && w->kind == JsonVal::J_OBJ) {
    r.setLoudnessDropWindow(w->numField("startMs")*.001,w->numField("endMs")*.001,float(loud.numField("dropBumpLu")));
  }
  r.setTruePeak(loud.boolField("truePeakLimiter",true));
  r.setTruePeakCeilingDb(float(loud.numField("truePeakCeilingDbtp",-1)));
}

int runRenderMode(const std::string& scriptPath, const std::string& outWavPath, bool inspectOnly=false) {
  std::ifstream file(scriptPath,std::ios::binary);
  if (!file) { std::fprintf(stderr,"error: cannot open script %s\n",scriptPath.c_str()); return 1; }
  const std::string raw((std::istreambuf_iterator<char>(file)),std::istreambuf_iterator<char>());
  JsonVal root;
  std::string err;
  JsonParser parser(raw);
  if (!parser.parse(root,err) || root.kind != JsonVal::J_OBJ) {
    std::fprintf(stderr,"error: invalid script JSON (%s): %s\n",scriptPath.c_str(),err.empty()?"root must be object":err.c_str()); return 2;
  }
  const JsonVal* geometry = root.get("renderGeometry");
  if (inspectOnly) {
    std::printf("inspected script JSON schema=%.0f canonicalGeometry=%s (no execution)\n",root.numField("schema",-1),
        geometry && geometry->kind == JsonVal::J_OBJ ? "present" : "missing");
    return 0;
  }
  if (!geometry || geometry->kind != JsonVal::J_OBJ) {
    std::fprintf(stderr,"error: canonical render_geometry_missing; --render requires complete renderGeometry; use --inspect-script for draft inspection\n"); return 2;
  }
  double rate=0;
  if (!canonicalNumber(root.get("sampleRateHz"),rate) || std::floor(rate) != rate || rate < 16000 || rate > 192000) {
    std::fprintf(stderr,"error: canonical sampleRateHz must be an integer in [16000, 192000]\n"); return 2;
  }
  const int sr=int(rate);
  CanonicalDeckSetup out,in;
  if (!validateCanonicalGeometry(root,sr,out,in,err) || !validateCanonicalContent(root,sr,out,in,err)) {
    std::fprintf(stderr,"error: invalid canonical script: %s\n",err.c_str()); return 2;
  }
  double duration=std::max(out.stopAtMixSec,in.stopAtMixSec);
  duration=std::max(duration,std::max(out.knotFrames.back(),in.knotFrames.back())/sr);
  for (const char* key : {"durationSec","totalFrames"}) if (const JsonVal* v = root.get(key)) {
    double value=0;
    if (!canonicalNumber(v,value) || value <= 0 || (std::strcmp(key,"totalFrames") == 0 && std::floor(value) != value)) {
      std::fprintf(stderr,"error: invalid canonical %s\n",key); return 2;
    }
    duration=std::max(duration,std::strcmp(key,"totalFrames") == 0 ? value/sr : value);
  }
  if (!std::isfinite(duration) || duration <= 0 || duration*sr > (std::numeric_limits<uint32_t>::max()-36u)/4u) {
    std::fprintf(stderr,"error: canonical duration exceeds PCM16 WAV limits\n"); return 2;
  }
  const int64_t totalFrames=int64_t(std::llround(duration*sr));
  Renderer r(sr);
  r.setHighQuality(geometry->boolField("highQuality"));
  for (int index=0;index<2;++index) {
    const auto& setup=index==0?out:in;
    auto& d=r.deck(index);
    const std::string path=root.get(index==0?"outRegion":"inRegion")->strField("pcmPath");
    if (!loadCanonicalAudio(path,sr,setup.frameCount,d.left,d.right,err)) {
      std::fprintf(stderr,"error: canonical deck %d: %s\n",index,err.c_str()); return 2;
    }
    d.regionStart=setup.regionStartSec; d.loopStart=setup.loopStartFrame; d.loopEnd=setup.loopEndFrame;
    d.releaseAt=setup.releaseAtFrame; d.knotTimes=setup.knotFrames; d.knotPositions=setup.knotPositions;
    d.runOn=setup.runOn; d.stopAt=setup.stopAtMixSec; d.preroll=setup.prerollSec; d.level=setup.levelGain;
  }
  if (const JsonVal* stems=root.get("stems")) for (const auto& s:stems->arr) {
    const int deck=canonicalDeckIndex(s.get("deck")),kind=parseStemKind(s.get("stem"));
    std::vector<float> left,right;
    const int frames=deck==0?out.frameCount:in.frameCount;
    if (!loadCanonicalAudio(s.strField("pcmPath"),sr,frames,left,right,err) ||
        !r.setDeckStem(deck,kind,left.data(),right.data(),frames)) {
      std::fprintf(stderr,"error: canonical stem deck=%d kind=%d: %s\n",deck,kind,err.c_str()); return 2;
    }
  }
  for (const auto& lane:root.get("envelopes")->arr) {
    auto* slot=envelopeSlot(r.deck(canonicalDeckIndex(lane.get("deck"))),parseEnvelopeKind(lane.get("kind")));
    for (const auto& t:lane.get("timesSec")->arr) slot->times.push_back(t.num);
    for (const auto& v:lane.get("values")->arr) slot->values.push_back(float(v.num));
    const int kind=parseEnvelopeKind(lane.get("kind"));
    slot->exponential=lane.boolField("exponential",kind==2||kind==3);
  }
  installCanonicalFx(r,root);
  r.prepare();
  if (const JsonVal* stems=root.get("stems")) for (const auto& s:stems->arr) {
    if (!r.deck(canonicalDeckIndex(s.get("deck"))).v2.stemsOn) {
      std::fprintf(stderr,"error: canonical stems did not activate (OTHER is a native residual and needs a separated base stem)\n");
      return 2;
    }
  }
  // prepare resets fade state. Retain the exact physical track fade afterwards.
  for (int index=0;index<2;++index) {
    const auto& setup=index==0?out:in;
    if (setup.fadeOutTrackSec>=0) r.deck(index).fadeOutAt.store(
        r.deck(index).releasedPositionOf(setup.fadeOutTrackSec,sr),std::memory_order_relaxed);
  }
  std::vector<float> samples(size_t(totalFrames)*2,0.f),scratch(2048,0.f);
  for (int64_t frame=r.firstFrame();frame<totalFrames;) {
    const int chunk=int(std::min<int64_t>(1024,totalFrames-frame));
    r.render(frame,scratch.data(),chunk);
    for (int i=0;i<chunk;++i) {
      const float l=scratch[size_t(i)*2],rr=scratch[size_t(i)*2+1];
      if (!std::isfinite(l)||!std::isfinite(rr)) { std::fprintf(stderr,"error: canonical renderer produced non-finite audio\n"); return 2; }
      const int64_t dest=frame+i;
      if (dest>=0) { samples[size_t(dest)*2]=l; samples[size_t(dest)*2+1]=rr; }
    }
    frame+=chunk;
  }
  if (!writeWavStereo16(outWavPath,samples,size_t(totalFrames),sr,err)) {
    std::fprintf(stderr,"error: %s\n",err.c_str()); return 1;
  }
  std::printf("rendered %s frames=%lld sr=%d violations=%d\n",outWavPath.c_str(),
      static_cast<long long>(totalFrames),sr,r.exitGateViolations());
  return 0;
}

}  // namespace render_script
