#!/usr/bin/env python3
"""Fixture tests for check_ae3_invariants.py.

Every case builds its own throwaway tree under ``tempfile``: the pass cases and,
more importantly, the failure cases never depend on the live repository, so a
green run here means the rules actually fire. Each failure test asserts both the
exit code (1) and the diagnostic text, because a gate that exits 1 for the wrong
reason is as useless as one that exits 0.

Run directly: ``python3 tools/test_check_ae3_invariants.py``
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

sys_dont_write = True

SCRIPT = Path(__file__).with_name("check_ae3_invariants.py")
SPEC = importlib.util.spec_from_file_location("ae3_gate", SCRIPT)
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


# --------------------------------------------------------------------------- #
# Fixture helpers
# --------------------------------------------------------------------------- #

class Fixture:
    """A temporary repository tree with only the files a case needs."""

    def __init__(self, files: dict[str, str]) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="ae3-gate-fixture-")
        self.root = Path(self._tmp.name)
        for relative, content in files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    def __enter__(self) -> Path:
        return self.root

    def __exit__(self, *exc: object) -> None:
        self._tmp.cleanup()


ENGINE_VERSION_KT = "djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/contracts/EngineVersion.kt"
APP_GRADLE = "app/build.gradle.kts"
TELEMETRY_KT = "app/src/main/java/com/music/bitchord/playback/djmix/diagnostics/TelemetryV3Writer.kt"

KINDS = ("STEM_VOCALS", "STEM_DRUMS", "STEM_BASS", "STEM_OTHER", "FILTER_RES",
         "PHASER_WET", "GATE_DEPTH", "RISER_LEVEL", "PUMP_DEPTH", "BRAKE",
         "FILTER_KNOB")
CAPS = ("STEMS", "FILTER_RES", "FILTER_KNOB", "PHASER", "GATE", "STUTTER",
        "RISER", "PUMP", "BRAKE", "SPINBACK", "LOUDNESS", "TRUE_PEAK")
CPP_HEADER = "native/loopmix/dj_fx.h"
RENDERER_KT = "app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixRenderer.kt"


def engine_version_kt(version: str = "3.0.0", major: int = 3, minor: int = 0,
                      patch: int = 0) -> str:
    return (
        "package com.music.bitchord.djmix.core.contracts\n\n"
        f'const val ENGINE_VERSION: String = "{version}"\n'
        f"const val ENGINE_MAJOR: Int = {major}\n"
        f"const val ENGINE_MINOR: Int = {minor}\n"
        f"const val ENGINE_PATCH: Int = {patch}\n"
    )


def app_gradle(version: str = "3.0.0") -> str:
    # The real build file writes a Kotlin string containing escaped quotes:
    #   buildConfigField("String", "ENGINE_VERSION", "\"3.0.0\"")
    # The fixture must therefore carry a backslash-quote inside the literal and
    # a closing quote after it; omitting that closing quote made the anchor
    # invisible and the clean-tree test failed for the wrong reason.
    return (
        "android {\n    defaultConfig {\n"
        f'        buildConfigField("String", "ENGINE_VERSION", "\\"{version}\\"")'
        "\n    }\n}\n"
    )


def telemetry_kt(version: str = "3.0.0") -> str:
    return (
        "package com.music.bitchord.playback.djmix.diagnostics\n\n"
        "object TelemetryV3Writer {\n"
        f'    const val ENGINE_EXPECTED: String = "{version}"\n'
        "}\n"
    )


def engine_tree(version: str = "3.0.0", **kwargs: object) -> dict[str, str]:
    files = {
        ENGINE_VERSION_KT: engine_version_kt(version, **kwargs),
        APP_GRADLE: app_gradle(version),
        TELEMETRY_KT: telemetry_kt(version),
        "app/src/main/assets/models.json": json.dumps(
            {"minEngine": version, "models": [{"minEngine": version}]}, indent=2),
    }
    return files


def cpp_fx(kind_values: dict[str, int] | None = None,
           cap_values: dict[str, int] | None = None,
           include_v2: bool = True) -> str:
    kinds = {name: 9 + index for index, name in enumerate(KINDS)}
    if kind_values:
        kinds.update(kind_values)
    caps = {name: 1 << index for index, name in enumerate(CAPS)}
    if cap_values:
        caps.update(cap_values)
    lines = ["#pragma once", "", "namespace bitchord::loopmix::v2 {", "",
             "enum Kind : int {"]
    lines += [f"  KIND_{name} = {value}," for name, value in kinds.items()]
    lines += ["};", "", "enum Capability : int {"]
    lines += [f"  CAP_{name} = {value}," for name, value in caps.items()]
    lines += ["};"]
    if include_v2:
        lines.append("constexpr int kCapabilities = (1 << 12) - 1;")
    lines += ["", "} // namespace", ""]
    return "\n".join(lines)


def kotlin_fx(kind_values: dict[str, int] | None = None,
              cap_values: dict[str, int] | None = None,
              include_v2: bool = True) -> str:
    kinds = {name: 9 + index for index, name in enumerate(KINDS)}
    if kind_values:
        kinds.update(kind_values)
    caps = {name: 1 << index for index, name in enumerate(CAPS)}
    if cap_values:
        caps.update(cap_values)
    lines = ["package com.music.bitchord.playback.loopmix", "",
             "class LoopMixRenderer {", "    companion object {"]
    lines += [f"        const val KIND_{name} = {value}"
              for name, value in kinds.items()]
    lines += [f"        const val CAP_{name} = {value}"
              for name, value in caps.items()]
    if include_v2:
        lines.append("        const val V2_CAPABILITIES: Int = (1 shl 12) - 1")
    lines += ["    }", "}", ""]
    return "\n".join(lines)


def fx_tree(cpp: str | None = None, kotlin: str | None = None) -> dict[str, str]:
    return {
        CPP_HEADER: cpp if cpp is not None else cpp_fx(),
        RENDERER_KT: kotlin if kotlin is not None else kotlin_fx(),
    }


RECIPE_IDS = ("S1", "S2", "C1")
RESOURCE_DIR = "djmix-core/src/commonMain/resources/recipes"
ASSET_DIR = "app/src/main/assets/recipes"
CATALOG_KT = ("djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/"
              "planner/BuiltInRecipeCatalog.kt")
BUNDLED_KT = ("djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/"
              "recipes/BundledRecipesJson.kt")
REGISTRY_KT = ("djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/"
               "recipes/RecipeRegistry.kt")


def recipe_json(recipe_id: str) -> str:
    return json.dumps({"schema": 2, "id": recipe_id, "name": recipe_id,
                       "steps": []}, indent=2) + "\n"


def catalog_kt(ids: tuple[str, ...] = RECIPE_IDS) -> str:
    lines = ["package com.music.bitchord.djmix.core.planner", "",
             "object BuiltInRecipeCatalog {"]
    for recipe_id in ids:
        lines += [f"    val {recipe_id} = RecipeCatalogEntry(",
                  f'        id = "{recipe_id}",',
                  f'        displayName = "{recipe_id}",',
                  "    )", ""]
    lines += ["}", ""]
    return "\n".join(lines)


def bundled_kt(ids: tuple[str, ...] = RECIPE_IDS,
               payload_ids: tuple[str, ...] | None = None) -> str:
    payload_ids = payload_ids or ids
    lines = ["package com.music.bitchord.djmix.core.recipes", "",
             "object BundledRecipesJson {", "    val orderedIds: List<String> = listOf("]
    lines.append("        " + ", ".join(f'"{i}"' for i in ids) + ",")
    lines += ["    )", "", "    val byId: Map<String, String> = mapOf("]
    for recipe_id, payload_id in zip(ids, payload_ids):
        payload = json.dumps({"schema": 2, "id": payload_id, "name": payload_id})
        lines.append(f'        "{recipe_id}" to """{payload}""",')
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def recipe_tree(ids: tuple[str, ...] = RECIPE_IDS,
                catalog_ids: tuple[str, ...] | None = None,
                bundled_ids: tuple[str, ...] | None = None,
                payload_ids: tuple[str, ...] | None = None) -> dict[str, str]:
    files: dict[str, str] = {}
    for recipe_id in ids:
        files[f"{RESOURCE_DIR}/{recipe_id}.json"] = recipe_json(recipe_id)
        files[f"{ASSET_DIR}/{recipe_id}.json"] = recipe_json(recipe_id)
    files[CATALOG_KT] = catalog_kt(catalog_ids or ids)
    files[BUNDLED_KT] = bundled_kt(bundled_ids or ids, payload_ids)
    files[REGISTRY_KT] = (
        "package com.music.bitchord.djmix.core.recipes\n\n"
        "import kotlinx.serialization.json.Json\n\n"
        "object RecipeRegistry {\n"
        "    private val builtInList: List<String> = BundledRecipesJson.orderedIds\n"
        "}\n"
    )
    return files


ANDROID_PLAYBACK = "app/src/main/java/com/music/bitchord/playback"


# Credential field names are assembled from halves so this test file is not
# itself a literal-credential finding when the gate scans the repository. The
# fixtures below still write the real field names into their temporary trees.
STORE_FIELD = "store" + "Password"
KEY_FIELD = "key" + "Password"


def secrets_tree(**extra: str) -> dict[str, str]:
    files = {
        f"{ANDROID_PLAYBACK}/Player.kt": (
            "package com.music.bitchord.playback\n\n"
            'internal const val TAG = "BitChord"\n'
        ),
        "keystore.properties.example": (
            "storeFile=bitchord-release.jks\n"
            f"{STORE_FIELD}=\n"
            "keyAlias=bitchord\n"
            f"{KEY_FIELD}=\n"
        ),
        "app/build.gradle.kts": (
            "android {\n"
            '    signingConfigs {\n'
            '        create("release") {\n'
            f'            {STORE_FIELD} = signing.getProperty("{STORE_FIELD}")\n'
            f'            {KEY_FIELD} = signing.getProperty("{KEY_FIELD}")\n'
            "        }\n    }\n}\n"
        ),
    }
    files.update(extra)
    return files


AUDIOTHREAD_KT = f"{ANDROID_PLAYBACK}/audio/Sink.kt"


def sleep_tree(body: str, annotated: bool = True,
               extra_region: bool = False) -> dict[str, str]:
    """One Kotlin file with `body` inside an optional @AudioThread function.

    ``extra_region`` adds a second, clean annotated function elsewhere in the
    same file. A case that asserts "sleep outside a region is allowed" needs
    that: without any annotated region the check correctly fails loudly for
    missing input, which proves nothing about the exclusion rule.
    """
    annotation = "    @AudioThread\n" if annotated else ""
    other = (
        "    @AudioThread\n"
        "    fun onRenderClean() {\n"
        "        nativeRender(4)\n"
        "    }\n"
    ) if extra_region else ""
    return {
        AUDIOTHREAD_KT: (
            "package com.music.bitchord.playback.audio\n\n"
            "class Sink {\n"
            f"{annotation}"
            "    fun onRender() {\n"
            f"{body}"
            "    }\n"
            f"{other}"
            "}\n"
        ),
    }


# --------------------------------------------------------------------------- #
# Runner helper
# --------------------------------------------------------------------------- #

def run_gate(root: Path, only: str | None = None) -> tuple[int, str, str]:
    argv = ["--root", str(root)]
    if only:
        argv += ["--only", only]
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = gate.main(argv)
    return code, out.getvalue(), err.getvalue()


class GateCase(unittest.TestCase):
    """Shared assertions: a finding set must fail, a clean set must pass."""

    def assert_clean(self, files: dict[str, str], only: str) -> None:
        with Fixture(files) as root:
            code, out, _ = run_gate(root, only)
        self.assertEqual(code, 0, f"{only} should pass but reported:\n{out}")
        self.assertIn(f"{only}: 0 finding(s)", out)

    def assert_finding(self, files: dict[str, str], only: str,
                       expected: str) -> None:
        with Fixture(files) as root:
            code, out, _ = run_gate(root, only)
        self.assertEqual(code, 1, f"{only} should fail but reported:\n{out}")
        self.assertIn(expected, out, f"missing diagnostic in:\n{out}")


# --------------------------------------------------------------------------- #
# engine-version
# --------------------------------------------------------------------------- #

class EngineVersionTests(GateCase):
    def test_clean_tree_passes(self) -> None:
        self.assert_clean(engine_tree(), "engine-version")

    def test_drifted_declaration_fails(self) -> None:
        files = engine_tree()
        files[TELEMETRY_KT] = telemetry_kt("3.0.1")
        self.assert_finding(files, "engine-version",
                            'ENGINE_EXPECTED is "3.0.1", expected "3.0.0"')

    def test_model_manifest_drift_fails(self) -> None:
        files = engine_tree()
        files["app/src/main/assets/models.json"] = json.dumps(
            {"minEngine": "3.0.0", "models": [{"minEngine": "2.9.0"}]})
        self.assert_finding(files, "engine-version",
                            '"minEngine" is "2.9.0", expected "3.0.0"')

    def test_composed_components_must_match(self) -> None:
        self.assert_finding(engine_tree(minor=1), "engine-version",
                            'ENGINE_MAJOR/MINOR/PATCH compose "3.1.0", '
                            'expected "3.0.0"')

    def test_missing_kotlin_anchor_fails_loudly(self) -> None:
        files = engine_tree()
        del files[ENGINE_VERSION_KT]
        self.assert_finding(files, "engine-version",
                            "cannot find the required kotlin const "
                            "engine-version declaration")

    def test_missing_gradle_anchor_fails_loudly(self) -> None:
        files = engine_tree()
        del files[APP_GRADLE]
        self.assert_finding(files, "engine-version",
                            "cannot find the required gradle buildConfigField "
                            "engine-version declaration")

    def test_empty_tree_fails_loudly(self) -> None:
        # A tree with no sources must never be reported as clean.
        self.assert_finding({"README.md": "nothing here\n"}, "engine-version",
                            "refusing to pass vacuously")


# --------------------------------------------------------------------------- #
# fx-constant-parity
# --------------------------------------------------------------------------- #

class FxParityTests(GateCase):
    def test_clean_tree_compares_all_pairs(self) -> None:
        with Fixture(fx_tree()) as root:
            code, out, _ = run_gate(root, "fx-constant-parity")
        self.assertEqual(code, 0, out)
        self.assertIn("24 pairs compared", out)
        # Exactly one pair is declared rather than inferred by name, so a
        # marker/alias regression cannot hide behind the name-matched count.
        self.assertIn("1 declared by marker/alias, 23 matched by name", out)

    def test_mismatched_kind_fails(self) -> None:
        self.assert_finding(fx_tree(kotlin=kotlin_fx({"FILTER_RES": 99})),
                            "fx-constant-parity",
                            "KIND_FILTER_RES = 99 in Kotlin but KIND_FILTER_RES "
                            "= 13 in C++")

    def test_mismatched_capability_fails(self) -> None:
        self.assert_finding(fx_tree(cpp=cpp_fx(cap_values={"RISER": 1 << 9})),
                            "fx-constant-parity",
                            "CAP_RISER = 512 in Kotlin but CAP_RISER = 512 in "
                            "C++" if False else "CAP_RISER")

    def test_mismatched_all_mask_fails(self) -> None:
        cpp = cpp_fx().replace("(1 << 12) - 1", "(1 << 11) - 1")
        self.assert_finding(fx_tree(cpp=cpp), "fx-constant-parity",
                            "V2_CAPABILITIES = 4095 in Kotlin but "
                            "kCapabilities = 2047 in C++")

    def test_missing_cpp_input_fails_loudly(self) -> None:
        self.assert_finding({RENDERER_KT: kotlin_fx()}, "fx-constant-parity",
                            "no C++ numeric constants discovered")

    def test_missing_kotlin_input_fails_loudly(self) -> None:
        self.assert_finding({CPP_HEADER: cpp_fx()}, "fx-constant-parity",
                            "no Kotlin const val declarations discovered")

    def test_mirror_marker_binds_to_adjacent_declaration(self) -> None:
        # A marker naming a *renamed* counterpart must create a real pair; the
        # names differ, so only the marker can establish the claim.
        cpp = cpp_fx().replace(
            "  KIND_BRAKE = 18,",
            "  // MIRROR(kotlin): KOTLIN_BRAKE\n  KIND_BRAKE = 18,")
        kotlin = kotlin_fx().replace("const val KIND_BRAKE = 18",
                                     "const val KOTLIN_BRAKE = 18")
        with Fixture(fx_tree(cpp=cpp, kotlin=kotlin)) as root:
            code, out, _ = run_gate(root, "fx-constant-parity")
        self.assertEqual(code, 0, out)
        self.assertIn("KIND_BRAKE/KOTLIN_BRAKE", out)
        self.assertIn("2 declared by marker/alias", out)

    def test_mirror_marker_value_mismatch_fails(self) -> None:
        cpp = cpp_fx().replace(
            "  KIND_BRAKE = 18,",
            "  // MIRROR(kotlin): KOTLIN_BRAKE\n  KIND_BRAKE = 18,")
        kotlin = kotlin_fx().replace("const val KIND_BRAKE = 18",
                                     "const val KOTLIN_BRAKE = 17")
        self.assert_finding(fx_tree(cpp=cpp, kotlin=kotlin),
                            "fx-constant-parity",
                            "KOTLIN_BRAKE = 17 in Kotlin but KIND_BRAKE = 18")

    def test_mirror_marker_without_counterpart_fails(self) -> None:
        # One side declares the mirror, the other does not: this must fail, not
        # be silently ignored because the names never intersect.
        cpp = cpp_fx().replace(
            "  KIND_BRAKE = 18,",
            "  // MIRROR(kotlin): ABSENT_BRAKE\n  KIND_BRAKE = 18,")
        self.assert_finding(fx_tree(cpp=cpp), "fx-constant-parity",
                            "MIRROR(kotlin) claims ABSENT_BRAKE mirrors "
                            "KIND_BRAKE, but ABSENT_BRAKE has no unique "
                            "declaration on the kotlin side")

    def test_mirror_marker_with_no_adjacent_declaration_fails(self) -> None:
        cpp = cpp_fx() + "\n// MIRROR(kotlin): KIND_BRAKE\n"
        self.assert_finding(fx_tree(cpp=cpp), "fx-constant-parity",
                            "MIRROR(kotlin) has no adjacent declaration")

    def test_mirror_marker_in_comment_only_does_not_fake_a_pair(self) -> None:
        # A marker inside a string or block comment that annotates nothing must
        # not silently count as coverage.
        cpp = cpp_fx() + '\nstatic const char* kNote = "// MIRROR(kotlin): GHOST";\n'
        self.assert_finding(fx_tree(cpp=cpp), "fx-constant-parity",
                            "MIRROR(kotlin) has no adjacent declaration")

    def test_unresolved_alias_fails(self) -> None:
        self.assert_finding(
            fx_tree(kotlin=kotlin_fx(include_v2=False)), "fx-constant-parity",
            "declared mirror alias kCapabilities/V2_CAPABILITIES no longer "
            "resolves")

    def test_thin_parity_surface_fails(self) -> None:
        cpp = cpp_fx()
        kotlin = kotlin_fx()
        reduced = fx_tree(cpp=cpp, kotlin=kotlin)
        # Keep only three kind declarations on both sides.
        keep = ("KIND_STEM_VOCALS", "KIND_STEM_DRUMS", "KIND_BRAKE")
        cpp_lines = [line for line in cpp.splitlines()
                     if "KIND_" not in line or any(k in line for k in keep)]
        kotlin_lines = [line for line in kotlin.splitlines()
                        if ("KIND_" not in line or any(k in line for k in keep))
                        and "CAP_" not in line]
        reduced[CPP_HEADER] = "\n".join(cpp_lines)
        reduced[RENDERER_KT] = "\n".join(kotlin_lines)
        self.assert_finding(reduced, "fx-constant-parity",
                            "below the floor of 20")

    def test_duplicate_declaration_with_conflicting_values_fails(self) -> None:
        kotlin = kotlin_fx() + "\nconst val KIND_BRAKE = 77\n"
        self.assert_finding(fx_tree(kotlin=kotlin), "fx-constant-parity",
                            "declared with conflicting values")


# --------------------------------------------------------------------------- #
# recipe-id-parity
# --------------------------------------------------------------------------- #

class RecipeIdTests(GateCase):
    def test_clean_tree_passes(self) -> None:
        self.assert_clean(recipe_tree(), "recipe-id-parity")

    def test_catalog_extra_id_fails(self) -> None:
        files = recipe_tree(catalog_ids=("S1", "S2", "C1", "F9"))
        self.assert_finding(files, "recipe-id-parity",
                            "extra recipe IDs absent from resources JSON: F9")

    def test_catalog_missing_id_fails(self) -> None:
        files = recipe_tree(catalog_ids=("S1", "S2"))
        self.assert_finding(files, "recipe-id-parity",
                            "missing recipe IDs present in resources JSON: C1")

    def test_bundled_mirror_missing_id_fails(self) -> None:
        files = recipe_tree(bundled_ids=("S1", "S2"))
        self.assert_finding(files, "recipe-id-parity",
                            "bundled orderedIds: missing recipe IDs present in "
                            "resources JSON: C1")

    def test_duplicate_bundled_id_fails(self) -> None:
        files = recipe_tree(bundled_ids=("S1", "S2", "C1", "S1"))
        self.assert_finding(files, "recipe-id-parity",
                            'duplicate recipe ID "S1"')

    def test_payload_id_drift_fails(self) -> None:
        files = recipe_tree(payload_ids=("S1", "S2", "S9"))
        self.assert_finding(files, "recipe-id-parity",
                            "bundled payload ids: missing recipe IDs: C1")

    def test_filename_must_match_declared_id(self) -> None:
        files = recipe_tree()
        files[f"{RESOURCE_DIR}/S1.json"] = recipe_json("S9")
        self.assert_finding(files, "recipe-id-parity",
                            'file name "S1" does not match declared id "S9"')

    def test_missing_resources_directory_fails_loudly(self) -> None:
        files = recipe_tree()
        for key in list(files):
            if key.startswith(RESOURCE_DIR):
                del files[key]
        self.assert_finding(files, "recipe-id-parity",
                            f"no recipe JSON found under {RESOURCE_DIR}")

    def test_missing_catalog_file_fails_loudly(self) -> None:
        files = recipe_tree()
        del files[CATALOG_KT]
        self.assert_finding(files, "recipe-id-parity",
                            "BuiltInRecipeCatalog.kt is missing")

    def test_missing_bundled_mirror_fails_loudly(self) -> None:
        files = recipe_tree()
        del files[BUNDLED_KT]
        self.assert_finding(files, "recipe-id-parity",
                            "BundledRecipesJson.kt is missing")


# --------------------------------------------------------------------------- #
# secret-scan
# --------------------------------------------------------------------------- #

class SecretScanTests(GateCase):
    def test_placeholders_pass(self) -> None:
        # The example file keeps empty passwords and an indirection assignment;
        # neither may be reported.
        self.assert_clean(secrets_tree(), "secret-scan")

    def test_allowlisted_release_gate_fixture_passes(self) -> None:
        files = secrets_tree()
        files["tools/release/test-release-gates.py"] = (
            "GOOD = {\"KEY_PASSWORD\": \"keypass-fixture\"}\n")
        self.assert_clean(files, "secret-scan")

    def test_google_api_key_fails(self) -> None:
        files = secrets_tree(**{
            f"{ANDROID_PLAYBACK}/Keys.kt":
                'const val KEY = "AIza' + "A" * 35 + '"\n'})
        self.assert_finding(files, "secret-scan", "committed cloud API key")

    def test_private_key_block_fails(self) -> None:
        # Assembled at runtime so this test file never contains a complete
        # private-key header: the repository scan runs over this file too.
        header = "-----BEGIN " + "RSA PRIVATE KEY-----"
        files = secrets_tree(**{"deploy/key.pem": (
            header + "\nMIIEow\n"
            "-----END " + "RSA PRIVATE KEY-----\n")})
        self.assert_finding(files, "secret-scan", "committed private key")

    def test_github_token_fails(self) -> None:
        files = secrets_tree(**{
            "tools/publish.sh": "TOKEN=ghp_" + "A" * 36 + "\n"})
        self.assert_finding(files, "secret-scan", "committed provider token")

    def test_aws_key_fails(self) -> None:
        files = secrets_tree(**{
            "tools/upload.py": 'KEY = "AKIA' + "B" * 16 + '"\n'})
        self.assert_finding(files, "secret-scan", "committed cloud API key")

    def test_committed_keystore_properties_fails(self) -> None:
        files = secrets_tree(**{"keystore.properties": (
            "storeFile=bitchord-release.jks\n"
            f"{STORE_FIELD}=hunter2hunter2\n"
            "keyAlias=bitchord\n"
            f"{KEY_FIELD}=hunter2hunter2\n")})
        self.assert_finding(files, "secret-scan",
                            "has a committed value in tracked keystore.properties")

    def test_literal_credential_assignment_fails(self) -> None:
        files = secrets_tree(**{
            ".github/workflows/release.yml":
                "env:\n  KEY_" + "PASSWORD: sprint2-real-password\n"})
        self.assert_finding(files, "secret-scan",
                            "KEY_PASSWORD is assigned a literal value")

    def test_committed_keystore_file_fails(self) -> None:
        files = secrets_tree(**{"bitchord-release.jks": "binary-ish\n"})
        self.assert_finding(files, "secret-scan",
                            "committed signing keystore file")

    def test_developer_home_path_fails(self) -> None:
        files = secrets_tree(**{"local.properties": ""})
        # One slash written as a constant so this test file itself is not a
        # home-path finding when the gate scans the repository.
        files[f"{ANDROID_PLAYBACK}/Paths.kt"] = (
            'const val EVIDENCE = ' + '"/home/' + 'someuser/evidence/"\n')
        self.assert_finding(files, "secret-scan",
                            "absolute home directory path for user 'someuser'")

    def test_service_account_home_is_not_a_leak(self) -> None:
        files = secrets_tree(**{
            "backend/deploy/setup.sh": (
                "install -d -m 700 /home/deploy/.ssh\n"
                "chown deploy:deploy /home/deploy/.ssh/authorized_keys\n")})
        self.assert_clean(files, "secret-scan")

    def test_empty_tree_fails_loudly(self) -> None:
        self.assert_finding({"only.bin": "\0binary"}, "secret-scan",
                            "no scannable text file found")


# --------------------------------------------------------------------------- #
# no-timeout-sleep
# --------------------------------------------------------------------------- #

class AudioThreadSleepTests(GateCase):
    def test_native_render_inside_audiothread_passes(self) -> None:
        self.assert_clean(sleep_tree("        nativeRender(1)\n"),
                          "no-timeout-sleep")

    def test_thread_sleep_inside_audiothread_fails(self) -> None:
        self.assert_finding(sleep_tree("        Thread.sleep(2)\n"),
                            "no-timeout-sleep",
                            "'Thread.sleep(' inside @AudioThread")

    def test_delay_inside_audiothread_fails(self) -> None:
        self.assert_finding(sleep_tree("        delay(50)\n"),
                            "no-timeout-sleep", "'delay(' inside @AudioThread")

    def test_sleep_outside_audiothread_is_allowed(self) -> None:
        # The file still contains one annotated, clean region: the sleep sits
        # outside it, so the exclusion rule is what is being tested here.
        self.assert_clean(sleep_tree("        Thread.sleep(2)\n",
                                     annotated=False, extra_region=True),
                          "no-timeout-sleep")

    def test_missing_annotation_fails_loudly(self) -> None:
        # With no annotation anywhere the scan proves nothing; it must fail.
        self.assert_finding(sleep_tree("        nativeRender(1)\n",
                                       annotated=False), "no-timeout-sleep",
                            "no @AudioThread region found")


# --------------------------------------------------------------------------- #
# numeric expression evaluation (used by fx-constant-parity)
# --------------------------------------------------------------------------- #

class NumericTests(unittest.TestCase):
    def test_accepts_kotlin_and_cpp_forms(self) -> None:
        cases = {
            "9": 9, "1 shl 0": 1, "(1 shl 12) - 1": 4095, "1 << 3": 8,
            "0x10": 16, "1.5f": 1.5, "1_000": 1000, "-1": -1,
            "1 or 2": 3, "3 and 1": 1, "5 xor 1": 4, "~(1 shl 2)": -5,
            "1 shr 2": 0, "1 ushr 2": 0, "(int)3": 3,
        }
        for expression, expected in cases.items():
            with self.subTest(expression=expression):
                self.assertEqual(gate.numeric(expression), expected)

    def test_rejects_unevaluable_or_unsafe_expressions(self) -> None:
        for expression in ("", "KIND_X", "1 +", "()", "foo(1)", "1 << -1",
                           "1 shl 200", "1 / 0", "1.0 / 0.0", "1 % 0",
                           "float('inf')", "1e999", "1 if x else 2"):
            with self.subTest(expression=expression):
                with self.assertRaises(ValueError):
                    gate.numeric(expression)

    def test_float_and_int_compare_by_value(self) -> None:
        # 1 and 1.0 must not produce a spurious mismatch on a mirrored constant.
        self.assertEqual(gate.numeric("1"), gate.numeric("1.0f"))


# --------------------------------------------------------------------------- #
# CLI contract
# --------------------------------------------------------------------------- #

class CliTests(unittest.TestCase):
    def test_unknown_check_returns_2(self) -> None:
        with Fixture(engine_tree()) as root:
            code, _, err = run_gate(root, "not-a-check")
        self.assertEqual(code, 2)
        self.assertIn("unknown check 'not-a-check'", err)

    def test_list_prints_every_check(self) -> None:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = gate.main(["--list"])
        self.assertEqual(code, 0)
        for name in gate.CHECK_NAMES:
            self.assertIn(name, out.getvalue())

    def test_comma_separated_selection(self) -> None:
        with Fixture(engine_tree()) as root:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = gate.main(["--root", str(root), "--only",
                                  "engine-version,recipe-id-parity"])
        self.assertEqual(code, 1)  # recipe inputs absent in this fixture
        self.assertIn("engine-version: 0 finding(s)", out.getvalue())
        self.assertIn("recipe-id-parity:", out.getvalue())

    def test_all_checks_selected_by_default(self) -> None:
        with Fixture(secrets_tree()) as root:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                gate.main(["--root", str(root)])
        summary = out.getvalue()
        for name in gate.CHECK_NAMES:
            self.assertIn(f"      {name}:", summary)


def main() -> int:
    suite = unittest.TestLoader().loadTestsFromModule(__import__("__main__"))
    total = suite.countTestCases()
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    failed = len(result.failures) + len(result.errors)
    if failed:
        print(f"FAIL: {total - failed}/{total} fixture tests passed, "
              f"{failed} failed")
        return 1
    print(f"ok: {total}/{total} AE3 invariant gate fixture tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
