package com.music.bitchord.djmix.render.cli

import com.music.bitchord.djmix.render.*
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.StemKind
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

private const val HELP = """djmix-render: frozen compiler replay and explicit host rendering
Usage: djmix-render replay --bundle <zip> [--out <trace.json>]
       djmix-render replay <zip> --out <render.wav> --renderer-bin <native-executable>
         --deck-a-pcm <stereo.f32> --deck-b-pcm <stereo.f32> [--verify-sha256 <PCM-hash>] [--json]
         [--stem-pcm OUT:VOCALS=/absolute/region.f32 ...]
       djmix-render --help

The --bundle form emits a JSON decision/compiler trace. The positional form requires
explicit host PCM and an executable supporting --render SCRIPT --out WAV.
Flag rings omit full reducer inputs: reducer equivalence is unavailable, not a match.
Repeat --stem-pcm DECK:KIND=/absolute/region.f32 for exactly the captured stem refs.
DECK is OUT/IN; KIND is VOCALS/DRUMS/BASS/OTHER (case-sensitive). OTHER-only refuses.
PCM must be finite raw interleaved stereo f32le at the captured rate/region/frame count.
Owner overrides and input hashes are reported; captured channel hashes are checked when present.
No device, decoder or bit-exact parity is claimed.
Exit codes: 0 success/help; 1 invalid bundle/core/render/PCM-hash failure; 2 usage/I/O error.
"""

fun main(args: Array<String>) { exitProcess(ReplayCli.run(args)) }

object ReplayCli {
    fun run(args: Array<String>, out: (String) -> Unit = { print(it) }, err: (String) -> Unit = { System.err.println(it) },
        injectedBackend: RenderBackend? = null): Int {
        if (args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("replay", "--help"))) { out(HELP); return 0 }
        try {
            if (args.firstOrNull() != "replay") return usage(err, "Expected replay subcommand")
            val traceMode = args.getOrNull(1) == "--bundle"
            val options = linkedMapOf<String, String>()
            val stemOptions = mutableListOf<String>()
            val flags = hashSetOf<String>()
            var index = if (traceMode) 1 else 2
            val allowed = if (traceMode) setOf("--bundle", "--out") else
                setOf("--out", "--renderer-bin", "--deck-a-pcm", "--deck-b-pcm", "--stem-pcm", "--verify-sha256")
            if (!traceMode && (args.size < 2 || args[1].startsWith("--"))) return usage(err, "--bundle or a positional bundle path is required")
            while (index < args.size) {
                val key = args[index]
                if (!traceMode && key == "--json" && flags.add(key)) { index++; continue }
                if (key !in allowed || key in options || index + 1 >= args.size || args[index + 1].startsWith("--") || args[index + 1].isBlank()) {
                    return usage(err, "Unknown, duplicate or incomplete option: $key")
                }
                if (key == "--stem-pcm") {
                    stemOptions += args[index + 1]
                    if (stemOptions.size > 8) return usage(err, "At most eight distinct deck/stem mappings are supported")
                } else options[key] = args[index + 1]
                index += 2
            }
            val bundle = Path.of(if (traceMode) options.getValue("--bundle") else args[1])
            val output = options["--out"]?.let { Path.of(it) }
            if (output != null && (output.toAbsolutePath().normalize() == bundle.toAbsolutePath().normalize() ||
                (Files.exists(output) && Files.exists(bundle) && Files.isSameFile(bundle, output)))) return usage(err, "--out must not overwrite the bundle")
            val engine = BundleReplayEngine()
            if (traceMode) {
                if (output != null && !output.fileName.toString().endsWith(".json", ignoreCase = true)) return usage(err, "Trace --out must end in .json; it never writes WAV")
                val trace = engine.encodeTrace(engine.replay(bundle))
                if (output == null) out(trace) else writeOutput(output, trace)
                return 0
            }
            val target = output ?: return usage(err, "WAV --out is required")
            if (!target.fileName.toString().endsWith(".wav", ignoreCase = true)) return usage(err, "Audio --out must end in .wav")
            val a = options["--deck-a-pcm"]?.let { Path.of(it) } ?: return usage(err, "--deck-a-pcm is required")
            val b = options["--deck-b-pcm"]?.let { Path.of(it) } ?: return usage(err, "--deck-b-pcm is required")
            val stems = stemOptions.map { value ->
                val parts = Regex("(OUT|IN):(VOCALS|DRUMS|BASS|OTHER)=(/.+)").matchEntire(value)
                    ?: return usage(err, "--stem-pcm requires DECK:KIND=/absolute/region.f32")
                StemPcmInput(DeckTarget.valueOf(parts.groupValues[1]), StemKind.valueOf(parts.groupValues[2]), Path.of(parts.groupValues[3]))
            }
            val backend = injectedBackend ?: options["--renderer-bin"]?.let { NativeProcessRenderBackend(Path.of(it)) }
                ?: return usage(err, "--renderer-bin is required for WAV output")
            val expected = options["--verify-sha256"]
            if (expected != null && !expected.matches(Regex("[a-fA-F0-9]{64}"))) return usage(err, "--verify-sha256 requires 64 hex digits")
            val result = engine.render(bundle, target, backend, a, b, expected, stems)
            if ("--json" in flags) out(renderJson(result)) else out(
                "rendered=${result.hashMatched != false} output=$target pcmSha256=${result.render.pcmSha256} samplePeak=${result.render.samplePeak}\n" +
                    "localPcmOverrides=${result.localPcmOverrides} inputPcm=" +
                    NativeScriptJsonCodec.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ReplayPcmInput.serializer()), result.inputPcm) + "\n" +
                    "unverifiedCapturedAssetHashKeys=${result.unverifiedCapturedAssetHashKeys}\n" +
                    "reducerReplayComplete=false reducerEquivalent=unavailable bitExactParity=not_established\n")
            return if (result.hashMatched == false) 1 else 0
        } catch (error: BundleReplayException) {
            err("${error.code}: ${error.message}")
            return if (error.code == ReplayErrorCode.INPUT) 2 else 1
        } catch (error: InvalidPathException) {
            return usage(err, error.message ?: "Invalid path")
        } catch (error: IOException) {
            err("INPUT: ${error.message}"); return 2
        } catch (error: SecurityException) {
            err("INPUT: ${error.message}"); return 2
        }
    }

    private fun renderJson(value: RenderReplayResult): String = buildJsonObject {
        put("schema", 1); put("operation", "render"); put("engine", value.decisionTrace.engine)
        put("planId", value.decisionTrace.planId); put("generation", value.decisionTrace.generation)
        put("rendered", value.hashMatched != false); put("outputWav", value.render.outputWav.toString())
        put("reducerReplayComplete", false); put("reducerEquivalent", JsonNull)
        put("bitExactParity", "not_established"); put("pcmSha256", value.render.pcmSha256)
        put("sampleRateHz", value.render.sampleRateHz); put("sampleCount", value.render.sampleCount); put("samplePeak", value.render.samplePeak)
        put("expectedSha256", value.expectedSha256?.let { JsonPrimitive(it) } ?: JsonNull)
        put("hashMatched", value.hashMatched?.let { JsonPrimitive(it) } ?: JsonNull)
        put("localPcmOverrides", value.localPcmOverrides)
        put("inputPcm", JsonArray(value.inputPcm.map { NativeScriptJsonCodec.json.encodeToJsonElement(ReplayPcmInput.serializer(), it) }))
        put("unverifiedCapturedAssetHashKeys", JsonArray(value.unverifiedCapturedAssetHashKeys.map { JsonPrimitive(it) }))
    }.toString() + "\n"

    private fun writeOutput(destination: Path, text: String) {
        val target = destination.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".djmix-trace-", ".part")
        try {
            Files.writeString(temporary, text, Charsets.UTF_8)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun usage(err: (String) -> Unit, message: String): Int { err("$message\n$HELP"); return 2 }
}
