package com.music.bitchord.djmix.render

import kotlinx.serialization.json.JsonPrimitive

/** Framing checks only. kotlinx.serialization owns JSON syntax and canonical types. */
internal object StrictJson {
    private data class Frame(val objectFrame: Boolean, val keys: MutableSet<String> = hashSetOf(), var keyNext: Boolean = true)

    fun check(text: String, name: String) {
        val stack = ArrayList<Frame>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    if (stack.size >= 128) fail(name, "JSON nesting exceeds 128")
                    stack += Frame(text[index] == '{')
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                ',' -> stack.lastOrNull()?.takeIf { it.objectFrame }?.keyNext = true
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    if (index >= text.length) fail(name, "Unterminated JSON string")
                    val frame = stack.lastOrNull()
                    if (frame?.objectFrame == true && frame.keyNext) {
                        val key = (NativeScriptJsonCodec.json.parseToJsonElement(text.substring(start, index + 1)) as JsonPrimitive).content
                        if (!frame.keys.add(key)) fail(name, "Duplicate JSON key: $key")
                        frame.keyNext = false
                    }
                }
            }
            index++
        }
    }

    private fun fail(name: String, message: String): Nothing =
        throw BundleReplayException(ReplayErrorCode.INVALID_JSON, "$name: $message")
}
