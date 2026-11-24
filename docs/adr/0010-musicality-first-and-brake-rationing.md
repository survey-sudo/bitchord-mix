# ADR 0010: Musicality first and brake rationing

## Context
Owner feedback: brake/vinyl disc effects fire sloppily and too often on
blendable pairs. Mixes must sound natural and musical across Subtle, Club,
and Festival ("best of both worlds"), not punchy by default.

## Decision
Graceful blending wins whenever tracks can blend cleanly: blends, stem
swaps, filter sweeps, loops, tempo-synced risers/phasers/echo. Brake (F2)
and Spinback (F7/F9) are Festival-only, never a low-confidence fallback,
hard-gated to |ΔBPM| > 10% or (|ΔBPM| > 8% + genre class change) at clean
phrase ends with an 8-transition cooldown; rewritten in M4 with
pre-stretch turntable physics + LPF roll-off.

## Consequences
Candidate scoring in `:djmix-core/planner` enforces these hard gates and
cooldowns; low-confidence Festival falls back to F5 Tunnel or C2 Filter
wash + echo. Low-confidence pairs never hear brake/spinback.
