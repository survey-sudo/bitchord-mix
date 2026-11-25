package com.music.bitchord.djmix.analysis.cli

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val auditionJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

internal fun cliOptions(args: Array<String>, flags: Set<String> = emptySet()): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val name = args[index++]
        require(name.startsWith("--") && name.length > 2) { "Expected an option, got '$name'" }
        val key = name.removePrefix("--")
        require(key !in result) { "Duplicate option $name" }
        result[key] = if (key in flags) "true" else {
            require(index < args.size && !args[index].startsWith("--")) { "$name requires a value" }
            args[index++]
        }
    }
    return result
}

internal fun Map<String, String>.required(name: String): String =
    this[name]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("--$name required")

internal fun writeJsonAtomically(file: File, text: String) {
    val target = file.absoluteFile
    target.parentFile.mkdirs()
    val temporary = Files.createTempFile(target.parentFile.toPath(), ".${target.name}.", ".tmp")
    try {
        Files.writeString(temporary, text)
        Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
