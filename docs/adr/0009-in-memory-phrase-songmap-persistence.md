# ADR 0009: Ephemeral PhraseMaps / SongMaps fold into TrackAnalysis v3

## Context
Part B3.1 (`ADR 0006`) schedules migrating existing caches (`AnalysisStore` JSON, `PhraseMaps`, `StemCache`) into Room `djmix.db`. Wave 1B revealed that `PhraseMaps` (`app/src/main/java/com/music/bitchord/playback/smart/PhraseMaps.kt:9-19`, a 24-entry `ConcurrentHashMap` that calls `clear()` when `size > 24`) and `SongMaps` (`shared/.../smart/SongMaps.kt:7`) are purely in-memory with zero disk persistence; only `AnalysisStore` (`SCHEMA_VERSION = 5`) and `StemCache` exist on disk.

## Decision
Do not build a disk migration adapter for `PhraseMaps` or `SongMaps`. Migrate only `AnalysisStore` v5 JSON and `StemCache` files into `djmix.db`, fold persisted phrase/structure/map summaries directly into `TrackAnalysis v3` (`track_analysis` table) as new M2 schema fields, and delete `PhraseMaps` and `SongMaps`'s mutable caches once `TrackAnalysis v3` lands.

## Consequences
Avoids throwaway migration code for non-persistent caches and removes the 24-track `PhraseMaps` clear cliff plus duplicate on-the-fly `SongMaps.fromAnalysis` calls in `CrossfadeController` and `PairPreparer`.
