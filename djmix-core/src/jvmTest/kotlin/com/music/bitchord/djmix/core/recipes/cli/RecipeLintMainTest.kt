package com.music.bitchord.djmix.core.recipes.cli

import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class RecipeLintMainTest {
    @Test
    fun allBundledRecipesPass() {
        val (status, stdout, stderr) = invoke("--all")

        assertEquals(0, status, stderr)
        assertTrue(stdout.contains("PASS: 26 RecipeV2"), stdout)
    }

    @Test
    fun allBundledRecipesPassFallbackChecks() {
        val (status, stdout, stderr) = invoke("--all", "--check-fallbacks")

        assertEquals(0, status, stderr)
        assertTrue(stdout.contains("optional capability fallback combinations"), stdout)
    }

    @Test
    fun mutatedRecipeJsonReturnsLintViolation() {
        val recipe = requireNotNull(RecipeRegistry.builtIn("S1"))
        val json = RecipeRegistry.json.encodeToString(RecipeV2.serializer(), recipe)
            .replace("Beat-matched equal-power blend with gentle low-end handoff", "")
        val file = Files.createTempFile("recipe-lint-mutated-", ".json")
        try {
            Files.writeString(file, json)
            val (status, _, stderr) = invoke(file.toString(), "--no-coverage")
            assertEquals(1, status, stderr)
            assertTrue(stderr.contains("BLANK_DESCRIPTION"), stderr)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun invalidUsageReturnsTwo() {
        val (status, _, stderr) = invoke("--not-a-real-option")

        assertEquals(2, status)
        assertTrue(stderr.contains("Unknown option"), stderr)
    }

    private fun invoke(vararg args: String): Triple<Int, String, String> {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val status = run(arrayOf<String>(*args), stdout, stderr)
        return Triple(status, stdout.toString(), stderr.toString())
    }
}
