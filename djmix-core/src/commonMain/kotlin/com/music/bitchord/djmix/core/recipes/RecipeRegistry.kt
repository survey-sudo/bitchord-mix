package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.djmix.core.planner.RecipeCatalogEntry
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Pure-KMP registry of AE3 [RecipeV2] definitions (`S1..S6`, `C1..C9`, `F1..F11`).
 *
 * Parses bundled `schema = 2` definitions without filesystem I/O and allows validated
 * JSON overrides via [overrideFromJson], mapped to planner catalog entries via [toCatalogEntries].
 */
object RecipeRegistry {

    /** Shared forward-compatible JSON codec for [RecipeV2] documents. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val builtInList: List<RecipeV2> = BundledRecipesJson.orderedIds.map { id ->
        val payload = BundledRecipesJson.byId[id]
            ?: error("Missing bundled RecipeV2 JSON for '$id'")
        json.decodeFromString(RecipeV2.serializer(), payload)
    }

    private val builtInById: Map<String, RecipeV2> = builtInList.associateBy { it.id }

    @Volatile
    private var overridesById: Map<String, RecipeV2> = emptyMap()

    /** Returns all active recipes (`S1..S6`, `C1..C9`, `F1..F11`, plus any custom IDs) in canonical order. */
    fun all(): List<RecipeV2> {
        val current = overridesById
        if (current.isEmpty()) return builtInList
        val merged = ArrayList<RecipeV2>(builtInList.size + current.size)
        val seen = HashSet<String>(builtInList.size + current.size)
        for (recipe in builtInList) {
            seen += recipe.id
            merged += (current[recipe.id] ?: recipe)
        }
        for ((id, extra) in current) {
            if (id !in seen) merged += extra
        }
        return merged
    }

    /** Returns all enabled active recipes allowed at [level] (`level.atLeast(recipe.level)`). */
    fun forLevel(level: DjLevel): List<RecipeV2> =
        all().filter { it.enabled && level.atLeast(it.level) }

    /** Looks up an active recipe by [id] (`S1..F11` or custom override). */
    fun byId(id: String): RecipeV2? = overridesById[id] ?: builtInById[id]

    /** Returns the original bundled built-in recipe for [id], ignoring overrides. */
    fun builtIn(id: String): RecipeV2? = builtInById[id]

    /** Decodes [jsonText] into a [RecipeV2] without installing an override. */
    fun parseJson(jsonText: String): MixOutcome<RecipeV2> {
        return try {
            MixOutcome.Ok(json.decodeFromString(RecipeV2.serializer(), jsonText))
        } catch (t: Throwable) {
            MixOutcome.Failed(
                kind = FailureKind.DECODE_ERROR,
                message = "Invalid RecipeV2 JSON: ${t.message ?: t::class.simpleName}",
                recoverable = true,
            )
        }
    }

    /**
     * Parses, lints, and installs a JSON [RecipeV2] override under [id].
     * Rejects mismatched IDs, non-v2 schemas, or linter errors without altering registry state.
     */
    fun overrideFromJson(id: String, jsonText: String): MixOutcome<RecipeV2> {
        if (id.isBlank()) {
            return MixOutcome.Failed(
                kind = FailureKind.INTERNAL_INVARIANT,
                message = "overrideFromJson requires a non-blank recipe id",
                recoverable = true,
            )
        }
        val parsed = when (val decoded = parseJson(jsonText)) {
            is MixOutcome.Ok -> decoded.value
            is MixOutcome.Refused -> return decoded
            is MixOutcome.Failed -> return decoded
        }
        if (parsed.id != id) {
            return MixOutcome.Failed(
                kind = FailureKind.INTERNAL_INVARIANT,
                message = "Recipe JSON id '${parsed.id}' does not match target id '$id'",
                recoverable = true,
            )
        }
        val lint = RecipeLinter.lintRecipe(parsed)
        if (!lint.passed) {
            return MixOutcome.Failed(
                kind = FailureKind.INTERNAL_INVARIANT,
                message = "Recipe '$id' failed lint: ${lint.errors.first().message}",
                recoverable = true,
            )
        }
        val next = LinkedHashMap(overridesById)
        next[id] = parsed
        overridesById = next
        return MixOutcome.Ok(parsed)
    }

    /** Removes a runtime override for [id], restoring the bundled recipe if built-in. */
    fun clearOverride(id: String) {
        if (overridesById.containsKey(id)) {
            val next = LinkedHashMap(overridesById)
            next.remove(id)
            overridesById = next
        }
    }

    /** Removes all runtime overrides, restoring all 26 bundled built-in definitions. */
    fun resetOverrides() {
        overridesById = emptyMap()
    }

    /** Converts active [RecipeV2] definitions into planner [RecipeCatalogEntry] descriptors. */
    fun toCatalogEntries(): List<RecipeCatalogEntry> = BuiltInRecipeCatalog.mergeOverrides(all())
}
