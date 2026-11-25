package com.music.bitchord.playback.smart

/**
 * Where a [TrackAnalysis]'s provenance metadata came from. Provenance is
 * carried alongside the exact analysis instance it describes, so a later
 * store-row rewrite or model-registry change cannot silently re-label an old
 * result. This is declaration metadata, not proof of what executed.
 */
enum class AnalysisProvenanceOrigin {
    /** Nothing was recorded: legacy rows, in-memory failures, undecodable tracks. */
    UNKNOWN,

    /** Captured by the analysis producer at generation/persist time. */
    PRODUCER_CAPTURED,

    /** Read back from the stored V3 row during restoration. */
    STORE_DECLARED,
}

/**
 * What the analysis `contentHash` actually digests. The app analysis bridge
 * hashes a source/rendition *metadata fingerprint* (path/size/mtime or cache
 * key), never PCM audio bytes. Rows written by other producers (for example
 * the offline analysis pipeline) may carry a PCM digest instead, so a
 * restored legacy row's role is [UNKNOWN] rather than guessed.
 */
enum class AnalysisSourceHashRole {
    UNKNOWN,

    /** SHA-256 of a source/rendition metadata fingerprint, not audio bytes. */
    SOURCE_METADATA_FINGERPRINT,

    /** SHA-256 of decoded mono PCM bytes. */
    PCM_DIGEST,

    /** SHA-256 of prepared/exported planar PCM samples (asset identity). */
    PREPARED_PCM,
}

/**
 * Provenance frozen with one actual analysis instance.
 *
 * Truthfulness rules:
 * - [sourceHash] is labelled by [sourceHashRole]; a metadata fingerprint is
 *   never presented as a PCM digest.
 * - [declaredModelVersions] are the constants the producer/row *declared* at
 *   write time. They are not measured runtime/model identity.
 * - [measuredModelRuntimeIdentity] and [pcmDigest] stay null when the producer
 *   did not measure them; the app analysis path cannot, so they remain null.
 * - Missing legacy declarations stay null/empty and unverified, never
 *   backfilled from current models. Origin identifies the metadata boundary,
 *   not completeness or verification.
 */
data class TrackAnalysisProvenance(
    val origin: AnalysisProvenanceOrigin = AnalysisProvenanceOrigin.UNKNOWN,
    /** Declared source/rendition hash (see [sourceHashRole]); null when absent. */
    val sourceHash: String? = null,
    val sourceHashRole: AnalysisSourceHashRole = AnalysisSourceHashRole.UNKNOWN,
    /** Declared model-version constants at write time; null when the row had none. */
    val declaredModelVersions: Map<String, String>? = null,
    /** Declared analysis schema of the source V3 row; null when unknown. */
    val declaredSchemaVersion: Int? = null,
    /**
     * Measured per-inference runtime/model identity. Unavailable on the app
     * analysis path, so always null there; never substitute declared constants.
     */
    val measuredModelRuntimeIdentity: String? = null,
    /** Measured PCM digest, when a producer actually computed one. Never inferred. */
    val pcmDigest: String? = null,
    /** All current app evidence is declared/unverified, including matching fingerprints. */
    val status: String = "unknown_unverified",
    val hashCompatibility: String = "unknown",
) {
    val isKnown: Boolean get() = origin != AnalysisProvenanceOrigin.UNKNOWN

    /** True when the declared constants must not be read as executed runtime identity. */
    val modelIdentityIsDeclaredOnly: Boolean
        get() = measuredModelRuntimeIdentity == null && !declaredModelVersions.isNullOrEmpty()

    /** Freeze maps at the analysis boundary; later row/registry changes cannot replace them. */
    fun detached(): TrackAnalysisProvenance = copy(declaredModelVersions = declaredModelVersions?.toMap())

    /**
     * V3 has no separate provenance slot. Reserved, explicitly labelled metadata
     * travels in its existing string map, without changing the public/SQLite schema.
     * Consumers comparing model declarations must use [declaredModels].
     */
    fun toModelVersions(): Map<String, String> = buildMap {
        putAll(declaredModelVersions.orEmpty())
        put("${PREFIX}format", "1")
        put("${PREFIX}origin", origin.name)
        put("${PREFIX}status", status)
        put("${PREFIX}hash_role", sourceHashRole.name)
        put("${PREFIX}hash_compatibility", hashCompatibility)
        put("${PREFIX}model_identity", when {
            measuredModelRuntimeIdentity != null -> "producer_measured"
            declaredModelVersions.isNullOrEmpty() -> "unknown"
            else -> "declared_only_runtime_unknown"
        })
        sourceHash?.let { put("${PREFIX}source_hash", it) }
        declaredSchemaVersion?.let { put("${PREFIX}source_schema", it.toString()) }
        measuredModelRuntimeIdentity?.let { put("${PREFIX}measured_model_runtime_identity", it) }
        pcmDigest?.let { put("${PREFIX}pcm_digest", it) }
    }

    companion object {
        private const val PREFIX = "__analysis_provenance."

        fun declaredModels(modelVersions: Map<String, String>): Map<String, String> =
            modelVersions.filterKeys { !it.startsWith(PREFIX) }

        /** No provenance was recorded; callers must treat it as unverified legacy. */
        val UNKNOWN = TrackAnalysisProvenance()

        /**
         * Provenance captured by the app producer when persisting a fresh
         * analysis: its source/rendition fingerprint hash plus declared model
         * constants. No runtime identity or PCM digest is invented.
         */
        fun producerCaptured(
            sourceFingerprintHash: String?,
            declaredModelVersions: Map<String, String>?,
            schemaVersion: Int = 3,
        ): TrackAnalysisProvenance = TrackAnalysisProvenance(
            origin = AnalysisProvenanceOrigin.PRODUCER_CAPTURED,
            sourceHash = sourceFingerprintHash?.takeIf { it.isNotBlank() },
            sourceHashRole = AnalysisSourceHashRole.SOURCE_METADATA_FINGERPRINT,
            declaredModelVersions = declaredModelVersions?.takeIf { it.isNotEmpty() }?.toMap(),
            declaredSchemaVersion = schemaVersion,
            status = "producer_declared_unverified",
            hashCompatibility = "not_checked",
        )

        /**
         * Provenance read from a V3 row. Explicit labels from new producers
         * survive; an unlabelled legacy hash keeps its unknown role because
         * metadata versus PCM cannot be established from the digest alone.
         * Model constants remain declarations only.
         */
        fun storeDeclared(
            contentHash: String?,
            modelVersions: Map<String, String>?,
            schemaVersion: Int?,
        ): TrackAnalysisProvenance {
            val metadata = modelVersions.orEmpty()
            val labelled = metadata["${PREFIX}format"] == "1"
            val hash = contentHash?.takeIf { it.isNotBlank() }
            return TrackAnalysisProvenance(
                origin = if (labelled) AnalysisProvenanceOrigin.values().firstOrNull {
                    it.name == metadata["${PREFIX}origin"]
                } ?: AnalysisProvenanceOrigin.STORE_DECLARED else AnalysisProvenanceOrigin.STORE_DECLARED,
                sourceHash = hash,
                sourceHashRole = if (labelled && hash != null) AnalysisSourceHashRole.values().firstOrNull {
                    it.name == metadata["${PREFIX}hash_role"]
                } ?: AnalysisSourceHashRole.UNKNOWN else AnalysisSourceHashRole.UNKNOWN,
                declaredModelVersions = declaredModels(metadata).takeIf { it.isNotEmpty() },
                declaredSchemaVersion = schemaVersion,
                measuredModelRuntimeIdentity = if (labelled) metadata["${PREFIX}measured_model_runtime_identity"] else null,
                pcmDigest = if (labelled) metadata["${PREFIX}pcm_digest"] else null,
                status = if (labelled) metadata["${PREFIX}status"] ?: "stored_declared_unverified" else "legacy_declared_unverified",
                hashCompatibility = if (labelled) metadata["${PREFIX}hash_compatibility"] ?: "unknown" else "unknown",
            )
        }
    }
}
