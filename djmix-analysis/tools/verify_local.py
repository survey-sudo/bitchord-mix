#!/usr/bin/env python3
"""Offline, no-Gradle Kotlin/JNI/JUnit check using existing cache artifacts only.

This is a bounded developer check, not the registered-module Gradle gate.
It deliberately records the exact cached dependency classpath and commands.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("/tmp/opencode/ae3-analysis-check"))
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    repo = root.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    records = []

    def artifact(group, name, version, extension="jar"):
        matches = sorted((args.cache / group / name / version).rglob(f"*.{extension}"))
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one cached {group}:{name}:{version} {extension}; found {len(matches)}")
        return matches[0]

    def run(label, command):
        command = list(map(str, command))
        (output / f"{label}.command.txt").write_text(shlex.join(command) + "\n")
        env = dict(os.environ, JAVA_HOME=str(args.jdk.resolve()))
        completed = subprocess.run(command, cwd=repo, env=env, text=True, capture_output=True)
        (output / f"{label}.log").write_text(completed.stdout + completed.stderr)
        record = {"stage": label, "command": command, "rc": completed.returncode}
        records.append(record)
        (output / "commands.json").write_text(json.dumps(records, indent=2) + "\n")
        print(f"{label}: RC={completed.returncode}", flush=True)
        if completed.returncode:
            print(completed.stdout + completed.stderr)
            raise SystemExit(completed.returncode)

    compiler = [artifact("org.jetbrains.kotlin", name, "2.3.20") for name in
                ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
    compiler += [artifact("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                 artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"), artifact("org.jetbrains", "annotations", "13.0")]
    # Coroutines 1.11.0 and stdlib 2.4.10 are the installed cache; record this
    # local check's variance from the module's declared 1.10.2 / plugin 2.3.20.
    dependencies = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"),
                    artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.11.0"),
                    artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0"),
                    artifact("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.11.0"),
                    artifact("com.microsoft.onnxruntime", "onnxruntime", "1.31.0"), artifact("org.jetbrains", "annotations", "23.0.0")]
    with zipfile.ZipFile(artifact("androidx.media3", "media3-common", "1.11.0", "aar")) as aar:
        media3 = output / "media3-common.jar"
        media3.write_bytes(aar.read("classes.jar"))
    dependencies.append(media3)
    production = [repo / line for raw in (root / "playback-sources.txt").read_text().splitlines()
                  if (line := raw.strip()) and not line.startswith("#")]
    sources = sorted((root / "src/main/kotlin").rglob("*.kt")) + production
    sources += sorted((repo / "djmix-core/src/commonMain/kotlin").rglob("*.kt"))
    tests = sorted((root / "src/test/kotlin").rglob("*.kt"))
    identities = {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources + tests}
    identities["local-dependency-identities"] = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in dependencies}
    (output / "identities.json").write_text(json.dumps(identities, indent=2) + "\n")
    base = [args.jdk / "bin/java", "-Xmx1500m", "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xskip-metadata-version-check",
            "-Xplugin=" + str(artifact("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.3.20"))]
    cp = os.pathsep.join(map(str, dependencies))
    run("main-compile", base + ["-classpath", cp, "-d", output / "main.jar"] + sources)
    run("native-configure", ["cmake", "-S", root / "native", "-B", output / "native", "-DCMAKE_BUILD_TYPE=Release"])
    run("native-build", ["cmake", "--build", output / "native", "--parallel", "2"])
    test_dependencies = [artifact("org.jetbrains.kotlin", name, "2.3.20") for name in ("kotlin-test", "kotlin-test-junit")]
    test_dependencies += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core", "1.3")]
    test_cp = cp + os.pathsep + os.pathsep.join(map(str, [output / "main.jar"] + test_dependencies))
    run("test-compile", base + ["-classpath", test_cp, "-Xfriend-paths=" + str(output / "main.jar"), "-d", output / "test.jar"] + tests)
    names = ["com.music.bitchord.djmix.analysis." + name for name in
             ("AnalysisPipelineDeterminismTest", "StemSeparatorJvmTest", "TrackAnalysisExporterTest", "cli.AuditionPlanDriverTest")]
    run("junit", [args.jdk / "bin/java", "-Xmx1200m", "-Djava.library.path=" + str(output / "native"), "-cp",
                  test_cp + os.pathsep + str(output / "test.jar"), "org.junit.runner.JUnitCore"] + names)
    (output / "runtime-classpath.txt").write_text(str(output / "main.jar") + os.pathsep + cp)
    print((output / "junit.log").read_text())
    return 0


if __name__ == "__main__":
    sys.exit(main())
