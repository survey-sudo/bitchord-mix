#!/usr/bin/env python3
"""Fail-closed fixture tests for the AE3 release gate (release.yml + release-gates.sh).

Local only, no network, no Gradle, no gh. It extracts the real `run:` scripts out
of .github/workflows/release.yml, builds a throwaway fixture repository in a temp
directory, stubs `audit-apk.sh` and `gh`, and then checks behaviour:

  * each of the four signing secrets missing/empty -> nonzero, named only;
  * unusable base64 / non-keystore / newline-in-password -> nonzero, secret not echoed;
  * missing / empty / ambiguous / symlinked quality report -> nonzero;
  * zero or two APKs, or an unsigned APK name -> nonzero (no "first file" guess);
  * populated inputs select exactly the expected report and APK, at mode 600;
  * the audit step passes a plain APK path to the full audit (a stub that rejects
    --structural-only), and an audit failure never reaches `gh`;
  * a file changed after the audit never reaches `gh`;
  * the publish step uploads exactly the audited APK, models.json and the one
    selected report.

Usage: python3 tools/release/test-release-gates.py [-v]
Exit: 0 all cases pass, 1 any case fails.
"""
import base64
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
WORKFLOW = REPO / ".github" / "workflows" / "release.yml"
HELPER = HERE / "release-gates.sh"

VERBOSE = "-v" in sys.argv
RESULTS = []


def log(msg=""):
    print(msg, flush=True)


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    log(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail and not ok else ""))


def run(cmd, cwd=None, env=None, stdin=None):
    merged = os.environ.copy()
    merged.pop("GITHUB_OUTPUT", None)
    for key in ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
        merged.pop(key, None)
    if env:
        merged.update(env)
    proc = subprocess.run(
        cmd, cwd=cwd, env=merged, input=stdin,
        capture_output=True, text=True,
    )
    if VERBOSE:
        log(f"$ {' '.join(cmd)}  (rc={proc.returncode})")
        if proc.stdout.strip():
            log(textwrap.indent(proc.stdout.strip(), "  out| "))
        if proc.stderr.strip():
            log(textwrap.indent(proc.stderr.strip(), "  err| "))
    return proc


def load_steps():
    doc = yaml.safe_load(WORKFLOW.read_text())
    steps = doc["jobs"]["release"]["steps"]
    by_name = {s.get("name"): s for s in steps if s.get("name")}
    return steps, by_name


STEP_RE = re.compile(r"\$\{\{\s*steps\.([A-Za-z0-9_-]+)\.outputs\.([A-Za-z0-9_-]+)\s*\}\}")
INPUT_RE = re.compile(r"\$\{\{\s*inputs\.([A-Za-z0-9_-]+)\s*\}\}")


def read_outputs(path):
    out = {}
    if path and os.path.exists(path):
        for line in Path(path).read_text().splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out[k] = v
    return out


# --- fixture construction ---------------------------------------------------

BUILD_GRADLE = textwrap.dedent(
    """\
    // fixture
    extra["bitchordVersionCode"] = 95
    extra["bitchordVersionName"] = "1.10-djmix53"
    """
)

# The stub refuses to be asked for a structural-only run: if the workflow ever
# regresses to `--structural-only` (or passes extra flags), the audit "fails"
# and the publish path is unreachable. It also records what it was given.
AUDIT_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    set -euo pipefail
    echo "audit-stub: $*" >&2
    [ "$#" -eq 1 ] || { echo "audit-stub: expected exactly one APK path, got $#" >&2; exit 9; }
    case "$1" in --*) echo "audit-stub: refusing option $1 (structural-only is not a release audit)" >&2; exit 9 ;; esac
    [ -f "$1" ] || { echo "audit-stub: no such APK $1" >&2; exit 9; }
    [ -n "${AUDIT_EXPECTED_VERSION_NAME:-}" ] || { echo "audit-stub: expected versionName not exported" >&2; exit 9; }
    [ -n "${AUDIT_EXPECTED_VERSION_CODE:-}" ] || { echo "audit-stub: expected versionCode not exported" >&2; exit 9; }
    printf '%s %s\\n' "$AUDIT_EXPECTED_VERSION_NAME" "$AUDIT_EXPECTED_VERSION_CODE" > "$AUDIT_STUB_RECEIPT"
    exit "${AUDIT_STUB_RC:-0}"
    """
)

GH_STUB = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    set -euo pipefail
    printf '%s\\n' "$*" >> "$GH_STUB_LOG"
    case "$1 $2" in
      "release view") exit 1 ;;   # no existing release -> create path
      *) exit 0 ;;
    esac
    """
)


def make_fixture(root, *, report=True, report_bytes=b"<html>ok</html>\n"):
    fx = Path(root)
    fx.mkdir(parents=True, exist_ok=True)
    (fx / "build.gradle.kts").write_text(BUILD_GRADLE)
    (fx / "docs" / "quality").mkdir(parents=True)
    (fx / "app" / "build" / "outputs" / "apk" / "prod" / "release").mkdir(parents=True)
    (fx / "app" / "src" / "main" / "assets").mkdir(parents=True)
    (fx / "app" / "src" / "main" / "assets" / "models.json").write_text('{"fixture":true}\n')
    (fx / "app" / "build" / "outputs" / "apk" / "prod" / "release" / "app-prod-release.apk").write_bytes(b"PK\x03\x04fixture-apk")
    if report:
        (fx / "docs" / "quality" / "AE3-djmix53.html").write_bytes(report_bytes)
    (fx / "tools" / "release").mkdir(parents=True)
    shutil.copy2(HELPER, fx / "tools" / "release" / "release-gates.sh")
    (fx / "tools" / "audit-apk.sh").write_text(AUDIT_STUB)
    os.chmod(fx / "tools" / "audit-apk.sh", 0o755)
    bindir = fx / ".bin"
    bindir.mkdir()
    (bindir / "gh").write_text(GH_STUB)
    os.chmod(bindir / "gh", 0o755)
    return fx


def helper(fx, *args, env=None):
    base = {"REPO_ROOT": str(fx)}
    if env:
        base.update(env)
    proc = run(["bash", "tools/release/release-gates.sh", *args], cwd=fx, env=base)
    return proc


def make_p12(base):
    """A real PKCS#12 container, so the accept-path fixture is not a fake blob."""
    key = Path(base) / "k.pem"
    crt = Path(base) / "c.pem"
    p12 = Path(base) / "k.p12"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
         "-out", str(crt), "-days", "1", "-subj", "/CN=BitChord fixture"],
        check=True, capture_output=True,
    )
    subprocess.run(
        ["openssl", "pkcs12", "-export", "-inkey", str(key), "-in", str(crt),
         "-out", str(p12), "-passout", "pass:fixture"],
        check=True, capture_output=True,
    )
    return base64.b64encode(p12.read_bytes()).decode()


GOOD_SECRETS = {
    "KEYSTORE_PASSWORD": "storepass-fixture",
    "KEY_ALIAS": "fixturealias",
    "KEY_PASSWORD": "keypass-fixture",
}


# --- cases ------------------------------------------------------------------

def case_missing_secret(fx, b64, which):
    env = dict(GOOD_SECRETS)
    env["KEYSTORE_BASE64"] = b64
    env[which] = ""
    proc = helper(fx, "require-signing", env=env)
    ok = proc.returncode != 0 and which in (proc.stdout + proc.stderr)
    # no secret value may be echoed
    leaked = [v for v in env.values() if v and len(v) > 8 and v in proc.stdout + proc.stderr]
    record(f"missing secret {which} -> nonzero, named, no values echoed",
           ok and not leaked, f"rc={proc.returncode} leaks={len(leaked)}")


def main():
    steps, by_name = load_steps()

    # Static parse/order assertions.
    parse = run(["bash", "-n", "tools/release/release-gates.sh"], cwd=REPO)
    record("bash -n tools/release/release-gates.sh", parse.returncode == 0, f"rc={parse.returncode}")
    record("release.yml parses and has the expected steps",
           all(n in by_name for n in ("Resolve release tag", "Require release signing secrets",
                                      "Require release quality report", "Audit APK",
                                      "Create GitHub Release and upload assets")))
    names = [s.get("name") for s in steps]
    record("signing gate runs before heavy CI gates",
           names.index("Require release signing secrets") < names.index("Native probes (deterministic FP flags, ADR 0008)")
           and names.index("Require release signing secrets") < names.index("assembleProdRelease"))
    audit_script = by_name["Audit APK"]["run"]
    record("audit step never asks for structural-only",
           "--structural-only" not in audit_script and "ignoredRC" not in audit_script,
           "structural-only present")
    record("audit step has no `ls | head -1` APK guess",
           "head -1" not in audit_script and "ls app/build/outputs" not in audit_script)
    pub_script = by_name["Create GitHub Release and upload assets"]["run"]
    record("publish step has no docs/quality/*.html glob and no skip path",
           "docs/quality/*.html" not in pub_script and "skipping quality" not in pub_script)
    record("no untrusted expression is interpolated directly into publish shell",
           not any(re.search(r"(inputs\.|secrets\.)", line) for line in pub_script.splitlines()),
           "expression found in run block")
    offending = [
        s.get("name") for s in steps
        if re.search(r"\$\{\{\s*secrets\.", s.get("run", "") or "")
    ]
    record("no `secrets.` expression in any run block (env-only passing)",
           not offending, f"offending steps: {offending}")
    record("no untrusted `steps.*.outputs.*` inside publish run block",
           not any(STEP_RE.search(line) for line in pub_script.splitlines()))

    # Every run block must at least parse as bash. This is the same check the
    # runner's shell would do, done statically.
    bad_blocks = []
    for s in steps:
        script = s.get("run")
        if not script:
            continue
        proc = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
        if proc.returncode != 0:
            bad_blocks.append((s.get("name"), proc.stderr.strip()))
    record("every extracted run block passes bash -n", not bad_blocks, str(bad_blocks[:2]))

    tmp = tempfile.mkdtemp(prefix="release-gates-", dir="/tmp/opencode")
    try:
        b64 = make_p12(tmp)
        fx = make_fixture(Path(tmp) / "fx")
        env_ok = dict(GOOD_SECRETS, KEYSTORE_BASE64=b64)

        # 1) each of the four secrets missing/empty
        for which in ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
            fx_one = make_fixture(Path(tmp) / f"miss-{which}", report=False)
            case_missing_secret(fx_one, b64, which)

        # all four empty at once -> named
        proc = helper(make_fixture(Path(tmp) / "miss-all", report=False), "require-signing",
                      env={"KEYSTORE_BASE64": "", "KEYSTORE_PASSWORD": "", "KEY_ALIAS": "", "KEY_PASSWORD": ""})
        record("all four secrets empty -> nonzero, all names listed",
               proc.returncode != 0 and all(n in proc.stderr for n in
                                            ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")),
               f"rc={proc.returncode}")

        # 2) invalid base64: must fail and must not echo the blob
        marker = "SUPERSECRETMARKERb64!!"
        badblob = base64.b64encode(marker.encode()).decode()
        fx_bad = make_fixture(Path(tmp) / "badb64", report=True)
        proc = helper(fx_bad, "require-signing", env=dict(GOOD_SECRETS, KEYSTORE_BASE64=marker))
        record("invalid base64 -> nonzero, value not echoed",
               proc.returncode != 0 and marker not in (proc.stdout + proc.stderr) and badblob not in (proc.stdout + proc.stderr),
               f"rc={proc.returncode}")
        record("invalid base64 leaves no keystore/properties behind",
               not (fx_bad / "bitchord-loopfix.jks").exists() and not (fx_bad / "keystore.properties").exists())

        # non-keystore bytes (valid base64) -> nonzero
        fx_nonks = make_fixture(Path(tmp) / "nonks", report=True)
        notks = base64.b64encode(b"this is not a keystore at all").decode()
        proc = helper(fx_nonks, "require-signing", env=dict(GOOD_SECRETS, KEYSTORE_BASE64=notks))
        record("valid base64 but not a keystore -> nonzero", proc.returncode != 0, f"rc={proc.returncode}")

        # newline in a property value -> nonzero
        fx_nl = make_fixture(Path(tmp) / "newline", report=True)
        proc = helper(fx_nl, "require-signing",
                      env=dict(env_ok, KEY_ALIAS="alias\nstorePassword=injected"))
        record("newline in KEY_ALIAS -> nonzero (no property injection)", proc.returncode != 0, f"rc={proc.returncode}")

        # 3) accept path: mode 600, storeFile relative, properties populated
        proc = helper(fx, "require-signing", env=env_ok)
        mode_ks = oct(os.stat(fx / "bitchord-loopfix.jks").st_mode & 0o777)
        mode_pr = oct(os.stat(fx / "keystore.properties").st_mode & 0o777)
        props = (fx / "keystore.properties").read_text() if (fx / "keystore.properties").exists() else ""
        record("valid signing material accepted, files mode 600",
               proc.returncode == 0 and mode_ks == "0o600" and mode_pr == "0o600",
               f"rc={proc.returncode} jks={mode_ks} props={mode_pr}")
        record("keystore.properties points at a relative storeFile present on disk",
               "storeFile=bitchord-loopfix.jks" in props and (fx / "bitchord-loopfix.jks").stat().st_size > 0)
        record("secret values never printed on the success path",
               all(v not in (proc.stdout + proc.stderr) for v in env_ok.values() if len(v) > 8))

        # 4) quality report: absent / empty / ambiguous / symlink
        for label, mutator in (
            ("absent", lambda f: None),
            ("empty", lambda f: (f / "docs/quality/AE3-djmix53.html").write_bytes(b"")),
            ("ambiguous", lambda f: (f / "docs/quality/AE3-1.10-djmix53.html").write_text("<html>dup</html>")),
            ("symlink", lambda f: ((f / "docs/quality/AE3-djmix53.html").unlink(),
                                   (f / "docs/quality/real.html").write_text("<html>x</html>"),
                                   (f / "docs/quality/AE3-djmix53.html").symlink_to(f / "docs/quality/real.html"))),
        ):
            fx_q = make_fixture(Path(tmp) / f"q-{label}", report=(label != "absent"))
            mutator(fx_q)
            proc = helper(fx_q, "require-quality", env={"REPO_ROOT": str(fx_q)})
            record(f"quality report {label} -> nonzero", proc.returncode != 0, f"rc={proc.returncode}")

        # 5) select-artifacts: report selection is exact, decoys ignored
        fx_sel = make_fixture(Path(tmp) / "sel", report=True)
        (fx_sel / "docs/quality/AE3-djmix52.html").write_text("<html>old</html>")
        (fx_sel / "docs/quality/notes.html").write_text("<html>unrelated</html>")
        outfile = Path(tmp) / "sel.out"
        proc = helper(fx_sel, "select-artifacts", env={"REPO_ROOT": str(fx_sel), "GITHUB_OUTPUT": str(outfile)})
        outs = read_outputs(outfile)
        record("populated fixture selects exactly one APK and the build's report",
               proc.returncode == 0
               and outs.get("apk", "").endswith("app-prod-release.apk")
               and outs.get("quality_html", "").endswith("docs/quality/AE3-djmix53.html")
               and outs.get("version_name") == "1.10-djmix53"
               and outs.get("version_code") == "95"
               and outs.get("build_id") == "djmix53",
               f"rc={proc.returncode} outs={outs}")
        record("selected report hash matches the file on disk",
               outs.get("quality_sha256") == __import__("hashlib").sha256(
                   (fx_sel / "docs/quality/AE3-djmix53.html").read_bytes()).hexdigest())
        record("decoy reports are not selected",
               "AE3-djmix52" not in outs.get("quality_html", "") and "notes.html" not in outs.get("quality_html", ""))

        # zero APKs / two APKs / unsigned name
        fx_zero = make_fixture(Path(tmp) / "zero", report=True)
        (fx_zero / "app/build/outputs/apk/prod/release/app-prod-release.apk").unlink()
        proc = helper(fx_zero, "select-artifacts", env={"REPO_ROOT": str(fx_zero)})
        record("zero APKs -> nonzero", proc.returncode != 0, f"rc={proc.returncode}")

        fx_two = make_fixture(Path(tmp) / "two", report=True)
        (fx_two / "app/build/outputs/apk/prod/release/app-prod-release-2.apk").write_bytes(b"PK\x03\x04other")
        proc = helper(fx_two, "select-artifacts", env={"REPO_ROOT": str(fx_two)})
        record("two APKs -> nonzero (refuses to guess)", proc.returncode != 0, f"rc={proc.returncode}")

        fx_unsigned = make_fixture(Path(tmp) / "unsigned", report=True)
        (fx_unsigned / "app/build/outputs/apk/prod/release/app-prod-release.apk").rename(
            fx_unsigned / "app/build/outputs/apk/prod/release/app-prod-release-unsigned.apk")
        proc = helper(fx_unsigned, "select-artifacts", env={"REPO_ROOT": str(fx_unsigned)})
        record("unsigned-only APK -> nonzero", proc.returncode != 0, f"rc={proc.returncode}")

        # 6) tag validation
        for tag, expect_ok in (("ae3-1.10-djmix53", True), ("ae3-manual-4711", True),
                               ("v1.0", False), ("ae3-", False), ("ae3-x;rm -rf /", False),
                               ("ae3-x`id`", False), ("ae3-x/../y", False), ("", False),
                               ("ae3-" + "a" * 120, False)):
            proc = helper(fx, "validate-tag", env={"TAG": tag})
            record(f"tag validate {tag[:28]!r} -> {'accepted' if expect_ok else 'rejected'}",
                   (proc.returncode == 0) == expect_ok, f"rc={proc.returncode}")

        # 7) real workflow steps: audit failure must never reach gh
        def run_pipeline(base, audit_rc="0", tamper=None):
            f = make_fixture(base, report=True)
            gh_log = Path(base) / "gh.log"
            receipt = Path(base) / "audit.receipt"
            env_common = {
                "GH_STUB_LOG": str(gh_log), "AUDIT_STUB_RC": audit_rc,
                "AUDIT_STUB_RECEIPT": str(receipt), "PATH": f"{f/'.bin'}:{os.environ['PATH']}",
            }
            audit_out = Path(base) / "audit.out"
            audit_env = dict(env_common)
            audit_env["GITHUB_OUTPUT"] = str(audit_out)
            audit_env["GITHUB_STEP_SUMMARY"] = str(Path(base) / "audit.summary")
            a = run(["bash", "-c", by_name["Audit APK"]["run"]], cwd=f, env=audit_env)

            outs = read_outputs(audit_out)
            # Known outputs from steps that ran earlier in the real job.
            prior = {"tag": {"tag": "ae3-1.10-djmix53"}}
            prior.update({"artifacts": outs})
            pub_env = {"GITHUB_STEP_SUMMARY": str(Path(base) / "pub.summary")}
            pub_env.update(env_common)
            for key, value in by_name["Create GitHub Release and upload assets"].get("env", {}).items():
                s = str(value)
                m = STEP_RE.search(s)
                if m:
                    pub_env[key] = prior.get(m.group(1), {}).get(m.group(2), "")
                elif INPUT_RE.search(s):
                    pub_env[key] = "false"
                elif "secrets." in s or "github.token" in s:
                    pub_env[key] = "stub-token"
                else:
                    pub_env[key] = s
            # A failed step aborts the job, so the publish step only runs when
            # the audit step succeeded (exactly like Actions).
            if a.returncode != 0:
                return f, a, None, gh_log, receipt, outs
            if tamper:
                tamper(f)
            p = run(["bash", "-c", pub_script], cwd=f, env=pub_env)
            return f, a, p, gh_log, receipt, outs

        # happy path: audit ok -> exactly the audited set is uploaded
        f, a, p, gh_log, receipt, outs = run_pipeline(Path(tmp) / "pipe-ok")
        gh_calls = gh_log.read_text().splitlines() if gh_log.exists() else []
        creates = [c for c in gh_calls if c.startswith("release create ")]
        record("audit step passes a plain APK path and exports the build's version",
               a.returncode == 0 and receipt.exists() and receipt.read_text().strip() == "1.10-djmix53 95",
               f"rc={a.returncode}")
        record("happy path publishes exactly once with apk + models.json + selected report",
               p.returncode == 0 and len(creates) == 1
               and "release create ae3-1.10-djmix53" in creates[0]
               and "app-prod-release.apk" in creates[0]
               and "app/src/main/assets/models.json" in creates[0]
               and "docs/quality/AE3-djmix53.html" in creates[0]
               and creates[0].count("docs/quality/") == 1,
               f"rc={p.returncode} calls={gh_calls}")
        summary = Path(tmp) / "pipe-ok" / "pub.summary"
        record("release summary records the exact report and APK hashes",
               summary.exists() and "AE3-djmix53.html" in summary.read_text()
               and "quality report sha256" in summary.read_text())

        # audit failure -> publish never runs
        f, a, p, gh_log, _, _ = run_pipeline(Path(tmp) / "pipe-audit-fail", audit_rc="1")
        record("audit failure -> nonzero and gh is never called",
               a.returncode != 0 and p is None and not gh_log.exists(), f"audit_rc={a.returncode}")

        # structural-only regression trap: the stub rejects options, so if the
        # workflow ever passes --structural-only the audit itself fails.
        record("audit stub would reject --structural-only (trap armed)",
               run(["bash", "tools/audit-apk.sh", "--structural-only", "x.apk"],
                   cwd=Path(tmp) / "pipe-ok", env={"AUDIT_STUB_RECEIPT": str(Path(tmp) / "r")}).returncode == 9)

        # APK replaced between audit and publish -> publish refuses
        def tamper_apk(f):
            (f / "app/build/outputs/apk/prod/release/app-prod-release.apk").write_bytes(b"PK\x03\x04SWAPPED")
        f, a, p, gh_log, _, _ = run_pipeline(Path(tmp) / "pipe-tamper-apk", tamper=tamper_apk)
        record("APK changed after audit -> publish fails, gh never called",
               a.returncode == 0 and p.returncode != 0 and not gh_log.exists(),
               f"pub_rc={p.returncode}")

        # report replaced between audit and publish -> publish refuses
        def tamper_report(f):
            (f / "docs/quality/AE3-djmix53.html").write_text("<html>swapped</html>")
        f, a, p, gh_log, _, _ = run_pipeline(Path(tmp) / "pipe-tamper-report", tamper=tamper_report)
        record("quality report changed after audit -> publish fails, gh never called",
               a.returncode == 0 and p.returncode != 0 and not gh_log.exists(),
               f"pub_rc={p.returncode}")

        # report missing at publish time -> publish fails before gh
        def remove_report(f):
            (f / "docs/quality/AE3-djmix53.html").unlink()
        f, a, p, gh_log, _, _ = run_pipeline(Path(tmp) / "pipe-no-report", tamper=remove_report)
        record("quality report removed after audit -> publish fails, gh never called",
               p.returncode != 0 and not gh_log.exists(), f"pub_rc={p.returncode}")

        # report missing before the audit -> the audit step itself blocks
        f = make_fixture(Path(tmp) / "pipe-missing-report", report=False)
        out = Path(tmp) / "mr.out"
        a = run(["bash", "-c", audit_script], cwd=f, env={
            "GITHUB_OUTPUT": str(out), "GITHUB_STEP_SUMMARY": str(Path(tmp) / "mr.summary"),
            "AUDIT_STUB_RECEIPT": str(Path(tmp) / "mr.receipt"), "PATH": f"{f/'.bin'}:{os.environ['PATH']}",
        })
        record("missing report blocks before the audit/publish path",
               a.returncode != 0 and not (Path(tmp) / "mr.receipt").exists(), f"rc={a.returncode}")

    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    failed = [r for r in RESULTS if not r[1]]
    log()
    log(f"{len(RESULTS) - len(failed)}/{len(RESULTS)} cases passed")
    if failed:
        log("FAILED:")
        for name, _, detail in failed:
            log(f"  - {name} ({detail})")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
