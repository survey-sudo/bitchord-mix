package com.music.bitchord.djmix.render

import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** Uses canonical serializers and the authoritative core execution guard, without repairs. */
object NativeScriptJsonCodec {
    internal val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun decode(text: String): TransitionScriptV1 =
        decodeComplete(TransitionScriptV1.serializer(), text, "script.json").also(::checkExecutable)

    fun encode(script: TransitionScriptV1): String {
        checkExecutable(script)
        return try {
            json.encodeToString(TransitionScriptV1.serializer(), script)
        } catch (error: IllegalArgumentException) {
            throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "script.json: ${error.message}", error)
        }
    }

    private fun checkExecutable(script: TransitionScriptV1) {
        when (val result = ScriptExecutionGuard.validate(script)) {
            is MixOutcome.Ok -> Unit
            is MixOutcome.Refused -> throw BundleReplayException(ReplayErrorCode.CORE_REFUSAL, "script.json: ${result.reason}: ${result.detail}")
            is MixOutcome.Failed -> throw BundleReplayException(ReplayErrorCode.CORE_REFUSAL, "script.json: ${result.kind}: ${result.message}")
        }
    }

    /** The real writer uses encodeDefaults=true. Missing serialized fields are errors,
     * except debug-event identities intentionally omitted by JSONObject.put(null).
     */
    internal fun <T> decodeComplete(serializer: KSerializer<T>, text: String, name: String): T =
        decodeValue(serializer, text, name, complete = true)

    internal fun <T> decodeValue(serializer: KSerializer<T>, text: String, name: String, complete: Boolean = false): T = try {
        StrictJson.check(text, name)
        val element = json.parseToJsonElement(text)
        val value = json.decodeFromJsonElement(serializer, element)
        if (complete) requireFields(element, json.encodeToJsonElement(serializer, value), name)
        value
    } catch (error: BundleReplayException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$name: ${error.message}", error)
    }

    internal fun objectValue(text: String, name: String): JsonObject = try {
        StrictJson.check(text, name)
        json.parseToJsonElement(text) as? JsonObject
            ?: throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$name must be an object")
    } catch (error: IllegalArgumentException) {
        throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$name: ${error.message}", error)
    }

    private fun requireFields(actual: JsonElement, encoded: JsonElement, path: String) {
        if (actual is JsonPrimitive && encoded is JsonPrimitive && actual != JsonNull && encoded != JsonNull) {
            val wrongString = actual.isString != encoded.isString
            val wrongBoolean = (actual.booleanOrNull != null) != (encoded.booleanOrNull != null)
            if (wrongString || wrongBoolean) throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$path has the wrong JSON primitive type")
        }
        if (actual is JsonObject && encoded is JsonObject) {
            for ((key, value) in encoded) {
                val supplied = actual[key] ?: throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$path.$key is required (no replay defaults)")
                requireFields(supplied, value, "$path.$key")
            }
        } else if (actual is JsonArray && encoded is JsonArray) {
            if (actual.size != encoded.size) throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$path contains duplicate/inconsistent collection values")
            actual.indices.forEach { requireFields(actual[it], encoded[it], "$path[$it]") }
        }
    }
}
