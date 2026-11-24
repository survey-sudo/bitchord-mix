# Freeze lossless renderer geometry in TransitionScript v1

Status: accepted for implementation before the first accepted AE3 release.
Context: draft schema 1 cannot represent the live renderer's two exact deck setups.
Decision: complete unreleased schema 1; retain AE 3.0.0 and release 1.10-djmix53/versionCode 95.
Add nullable `renderGeometry` solely to preserve legacy decoding and inspection.
Canonical compilation, preparation, export, rendering and replay require complete geometry or typed refusal.
Store exact per-deck frame arrays, loop/release, stop, preroll, gain and physical-track fade values.
Freeze handover, incoming-player start and quality values without recomputation or host-only defaults.
Canonical scripts clear legacy knot/loop fields; remaining region timing fields are checked compatibility projections.
`RecipeInterpreter` remains the sole compiler and receives the generation's detached frozen `RecipeV2` value.
Represent base SVF Q explicitly; preserve S3 envelope lanes and constant Q=0.9 behavior.
Device and host validate exact assets and install the same complete script, preserving existing setter order.
Gate runtime cutover on setter parity, existing transport/loop/pause invariants and real PCM hash fixtures.
Incomplete draft scripts remain inspectable, but are not execution-compatible; no previously accepted AE3 baseline is withdrawn.
After the first accepted release, apply B16 schema and engine-major rules to every incompatible contract change.
