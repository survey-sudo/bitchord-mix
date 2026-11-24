# ADR 0006: Persistent analysis store — Room database

## Context
Analysis lives in scattered caches (`AnalysisStore` JSON, `PhraseMaps`,
`StemCache`). Every queue edit risks losing work, and the preparer waits
for full analysis before planning.

## Decision
Introduce Room database `djmix.db` with `track_analysis`
(trackId + contentHash), `stem_index` (trackId + window + modelId),
`transition_history`, and `preference` tables. TrackAnalysis v3 blobs
carry model and schema versions; staleness is lazy and queue-first. Stems
are capped (500 MB default, LRU by `lastUsed`). WorkManager jobs fill the
next 5 tracks and next 2 pairs, plus a charging/idle library job.

## Consequences
Cold pairs mostly start READY. Old caches migrate once, then are deleted.
Background analysis is budgeted (≤3% battery/hour, thermal-gated).

