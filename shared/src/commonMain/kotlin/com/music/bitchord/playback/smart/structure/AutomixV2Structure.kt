package com.music.bitchord.playback.smart.structure

/**
 * AUTOMIX-V2(D): switches for the structure v2 pass.
 *
 * Every failure falls back to the vc80 [com.music.bitchord.playback.smart.StructureAnalyzer]
 * result: v2 never removes a map, it only replaces labels when it is confident
 * and always adds its events (drops/builds/breakdowns/chorus repeats).
 */
object AutomixV2Structure {
    /** Master switch. False = byte-for-byte vc80 structure path. */
    var enabled: Boolean = true

    /**
     * Estimate beat phase and bar phase from the PCM when the caller passes no
     * downbeats. Phase 0 (the vc80 grid) is kept unless another phase scores
     * [StructureTuning.GRID_MARGIN] better, so a track that already starts on
     * the one is never moved.
     */
    var estimateGrid: Boolean = true

    /** v2 section labels replace the vc80 labels only at or above both gates. */
    var minLabelConfidence: Double = 0.45
    var minPhraseConfidence: Double = 0.35

    /** Optional learned model (All-In-One-style head on stems). Null = DSP only. */
    var model: StructureModel? = null

    const val VERSION: Int = 2
}
