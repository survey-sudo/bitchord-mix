package com.music.bitchord.playback.djmix.npu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM source-contract tests for Android-only discovery. No Android mock,
 * PackageManager, JNI library, or native QNN runtime is substituted here.
 *
 * These assert the real discovery boundary: unknown packages are never queried,
 * and callers cannot supply a library path to resolveAddon. They do not claim
 * that a forged public NpuAddon passed directly to open is authenticated, or
 * that dlopen rejects every absolute path. Those require a separate runtime
 * boundary check; checking bridgeLoaded() == false would not prove either.
 */
class AddonAllowlistSecurityTest {
    private val root: File by lazy {
        val start = File(System.getProperty("ae3.repoRoot", System.getProperty("user.dir"))).absoluteFile
        generateSequence(start) { it.parentFile }.firstOrNull {
            File(it, "app/src/main/java/com/music/bitchord/playback/djmix/npu/OrchardNpuBridge.kt").isFile
        } ?: error("Cannot locate repository root; set -Dae3.repoRoot=<checkout>")
    }
    private val bridge by lazy { source("OrchardNpuBridge.kt") }
    private val backend by lazy { source("InferenceBackend.kt") }

    private fun source(name: String) = File(root,
        "app/src/main/java/com/music/bitchord/playback/djmix/npu/$name").readText()

    // Keep offsets so balanced-body reads are unaffected by braces/comments in
    // log strings. A small lexer avoids regex stripping // inside string values.
    private fun code(text: String): String {
        val result = text.toCharArray()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                text.startsWith("/*", i) -> {
                    i += 2
                    var depth = 1
                    while (i < text.length && depth > 0) {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) text.length else end + 3
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i++]
                    while (i < text.length) {
                        if (text[i] == '\\') i = (i + 2).coerceAtMost(text.length)
                        else if (text[i++] == quote) break
                    }
                }
                else -> { i++; continue }
            }
            for (n in start until i) if (result[n] != '\n' && result[n] != '\r') result[n] = ' '
        }
        return result.concatToString()
    }

    private fun body(text: String, name: String): String {
        val clean = code(text)
        val declaration = Regex("\\bfun\\s+" + Regex.escape(name) + "\\s*\\(").find(clean)
            ?: error("Missing function $name")
        val start = clean.indexOf('{', declaration.range.last + 1)
        check(start >= 0) { "Missing body for $name" }
        var depth = 1
        var end = start + 1
        while (end < clean.length && depth > 0) {
            if (clean[end] == '{') depth++
            if (clean[end] == '}') depth--
            end++
        }
        check(depth == 0) { "Unbalanced function $name" }
        return clean.substring(start + 1, end - 1)
    }

    private fun trustedPackages(): List<String> {
        val match = Regex("val\\s+TRUSTED_PACKAGES\\s*:\\s*List<String>\\s*=\\s*listOf\\(([^)]*)\\)",
            RegexOption.DOT_MATCHES_ALL).find(bridge) ?: error("Missing explicit trusted package list")
        return Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    @Test
    fun ourKnownPackagePrecedesOrchardWithoutWildcardOrPrefixTrust() {
        assertEquals(listOf("com.music.bitchord.loopfix.npu.v73", "dev.sfg.orchard.npu.v73"), trustedPackages())
        assertTrue(trustedPackages().first().startsWith("com.music.bitchord.loopfix.npu."))
        assertTrue(trustedPackages().last().startsWith("dev.sfg.orchard.npu."))
        assertFalse(trustedPackages().any { '*' in it || it.endsWith('.') })
        assertTrue(Regex("for\\s*\\(\\s*pkg\\s+in\\s+TRUSTED_PACKAGES\\s*\\)").containsMatchIn(body(bridge, "resolveAddon")))
    }

    @Test
    fun arbitraryAndLookalikePackagesHaveNoDiscoveryEntry() {
        val unknown = listOf("", "com.attacker.npu", "com.music.bitchord.loopfix.npu",
            "com.music.bitchord.loopfix.npu.v74", "com.music.bitchord.loopfix.npu.v73.evil",
            "dev.sfg.orchard.npu.v73.evil", "dev.sfg.orchard.npu.v73/../../evil", "COM.MUSIC.BITCHORD.LOOPFIX.NPU.V73")
        for (name in unknown) assertFalse("Unexpected trust for $name", name in trustedPackages())
        val resolver = body(bridge, "resolveAddon")
        assertEquals(1, Regex("\\bgetPackageInfo\\s*\\(").findAll(resolver).count())
        assertTrue(Regex("pm\\.getPackageInfo\\s*\\(\\s*pkg\\s*,\\s*0\\s*\\)").containsMatchIn(resolver))
        assertFalse(Regex("\\b(?:getInstalledPackages|getInstalledApplications|queryIntentActivities)\\s*\\(").containsMatchIn(resolver))
        assertTrue(Regex("fun\\s+resolveAddon\\s*\\(\\s*context\\s*:\\s*Context\\s*\\)").containsMatchIn(code(bridge)))
    }

    @Test
    fun missingPackagesAndMissingRuntimeDoNotCreateAnAddon() {
        val resolver = body(bridge, "resolveAddon")
        assertTrue(Regex("getOrNull\\s*\\(\\s*\\)\\s*\\?:\\s*continue").containsMatchIn(resolver))
        assertTrue(Regex("info\\.applicationInfo\\s*\\?:\\s*continue").containsMatchIn(resolver))
        assertTrue(Regex("if\\s*\\(\\s*!File\\(libDir,\\s*RUNTIME_SO\\)\\.isFile\\s*\\)\\s*continue").containsMatchIn(resolver))
        assertTrue(resolver.trimEnd().endsWith("return null"))
    }

    @Test
    fun discoveryAcceptsNoArbitraryOrPersistedLibraryPath() {
        val resolver = body(bridge, "resolveAddon")
        assertTrue(Regex("val\\s+pm\\s*=\\s*context\\.packageManager").containsMatchIn(resolver))
        assertTrue(Regex("val\\s+appInfo\\s*=\\s*info\\.applicationInfo").containsMatchIn(resolver))
        assertTrue(Regex("val\\s+libDir\\s*=\\s*appInfo\\.nativeLibraryDir\\?\\.takeIf").containsMatchIn(resolver))
        assertTrue(Regex("it\\.isNotBlank\\(\\)\\s*}\\s*\\?:\\s*continue").containsMatchIn(resolver))
        assertTrue(Regex("return\\s+NpuAddon\\(pkg,\\s*code,\\s*libDir\\)").containsMatchIn(resolver))
        for (untrustedInput in listOf("getSharedPreferences", "getString", "getExternalFilesDir", "getenv", "getProperty", "Uri.parse")) {
            assertFalse("Path provenance escaped through $untrustedInput", untrustedInput in resolver)
        }
        assertEquals(1, Regex("\\bNpuAddon\\s*\\(").findAll(resolver).count())
    }

    @Test
    fun backendLibraryNameIsFixedRatherThanTakenFromModelsOrPreferences() {
        assertTrue(Regex("const\\s+val\\s+RUNTIME_SO\\s*=\\s*\"liborchard_qnn\\.so\"").containsMatchIn(bridge))
        assertTrue(Regex("const\\s+val\\s+BACKEND_SO_NAME\\s*=\\s*RUNTIME_SO").containsMatchIn(code(bridge)))
        val open = body(bridge, "open")
        assertTrue(Regex("nativeOpen\\(addon\\.nativeLibraryDir,\\s*modelFile\\.absolutePath,\\s*cache\\.absolutePath,\\s*BACKEND_SO_NAME\\)").containsMatchIn(open))
        // System.load(path) must never replace the fixed JNI shim loadLibrary.
        assertFalse(Regex("System\\.load\\s*\\(").containsMatchIn(code(bridge)))
        val spec = Regex("data\\s+class\\s+ModelSpec\\s*\\((.*?)\\n\\)", RegexOption.DOT_MATCHES_ALL)
            .find(code(backend))?.groupValues?.get(1) ?: error("Missing ModelSpec")
        assertFalse(Regex("(?i)nativeLibraryDir|libraryPath|backendSoName").containsMatchIn(spec))
    }

    @Test
    fun qnnBackendUsesAddonProvenanceAndOnlyKnownStagedGraphs() {
        val qnn = backend.substring(backend.indexOf("class QnnAddonBackend"))
        val open = body(qnn, "open")
        assertTrue(Regex("val\\s+current\\s*=\\s*addon\\(\\)\\s*\\?:\\s*throw").containsMatchIn(open))
        assertTrue("OrchardNpuBridge.stageQnnModels(context, current)" in open)
        assertTrue("OrchardNpuBridge.open(current, graph, OrchardNpuBridge.cacheDir(context))" in open)
        assertFalse("model.file.absolutePath" in open)
        assertFalse("System.load" in open)
        assertTrue(Regex("\"nirovi-qnn\"\\s*->\\s*staged\\.first").containsMatchIn(qnn))
        assertTrue(Regex("\"syvenu-qnn\"\\s*->\\s*staged\\.second").containsMatchIn(qnn))
        assertTrue(Regex("else\\s*->\\s*throw\\s+IllegalArgumentException").containsMatchIn(open))
    }

    @Test
    fun nativePathContractRejectsEmptyRelativeAndTraversalInputsBeforeDlopen() {
        val native = File(root, "app/src/main/cpp/jni/npu_bridge.cpp").readText()
        val start = native.indexOf("bool isSafeAbsoluteDir(")
        assertTrue("Native path predicate missing", start >= 0)
        val predicate = native.substring(start, native.indexOf("\n}", start) + 2)
        assertTrue("Empty and relative paths must be rejected",
            Regex("if\\s*\\(dir\\.empty\\(\\)\\s*\\|\\|\\s*dir\\[0]\\s*!=\\s*'/'\\)\\s*return false").containsMatchIn(predicate))
        assertTrue("Interior traversal must be rejected",
            Regex("if\\s*\\(dir\\.find\\(\"/\\.\\./\"\\)\\s*!=\\s*std::string::npos\\)\\s*return false").containsMatchIn(predicate))
        assertTrue("Trailing traversal must be rejected",
            Regex("dir\\.compare\\(dir\\.size\\(\\)\\s*-\\s*3,\\s*3,\\s*\"/\\.\\.\"\\)\\s*==\\s*0\\)\\s*return false").containsMatchIn(predicate))
        val guard = native.indexOf("if (!isSafeAbsoluteDir(nativeLibDir))")
        val load = native.indexOf("dlopen(full.c_str()")
        assertTrue("Path guard must precede dlopen", guard >= 0 && load > guard)
        assertTrue("Runtime path must have a fixed soname suffix",
            Regex("const\\s+std::string\\s+full\\s*=\\s*nativeLibDir\\s*\\+\\s*\"/\"\\s*\\+\\s*kRuntimeSoName").containsMatchIn(native))
        assertTrue("Alternate backends must be rejected before loading",
            "!soName.empty() && soName != kRuntimeSoName" in native)
    }
}
