"""Compile only an independent probe; inspect real final-JAR AV geometry without Minecraft.

Source mode is explicit development verification; final mode requires the exact JAR hash.
No existing JAR, asset or prior report is modified. Generated probe classes are temporary.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
from verify_home_fc_final_jar import ROOT, DELIVERY, ALPHA6_JAR_SHA, file_sha

JDK = Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")


def run_probe(classpath, reference=False):
    result = subprocess.run([str(JDK/"java.exe"), "-cp", classpath, "AvCableAlpha7Probe"]
                            + (["--unequal-only"] if reference else []), capture_output=True, text=True, check=False)
    if result.returncode not in (0, 1) or not result.stdout.startswith("{"):
        raise RuntimeError("AV probe could not run: " + result.stderr)
    return json.loads(result.stdout)


def inspect(jar=None, expected_sha=None):
    if jar and (not expected_sha or file_sha(jar) != expected_sha.upper()):
        raise ValueError("Final JAR SHA must be supplied and match before geometry execution")
    baseline = DELIVERY / "piq_fc_arcade-0.31.0-alpha.6.jar"
    if file_sha(baseline) != ALPHA6_JAR_SHA:
        raise ValueError("Immutable alpha.6 baseline JAR changed")
    with tempfile.TemporaryDirectory(prefix="piq-av-alpha7-probe-") as scratch:
        compile_args = [str(JDK/"javac.exe"), "-encoding", "UTF-8", "-d", scratch]
        if jar:
            compile_args += ["-cp", str(jar)]
        else:
            java = ROOT / "src/main/java/cn/piq/fcarcade"
            compile_args += [str(java/path) for path in (
                "home/HomeHardwareScale.java", "home/HomeConsoleLayout.java", "client/HomeHardwareRenderLayout.java",
                "client/HomeAvCableLayout.java", "client/HomeAvCableMesh.java",
                "layout/RocketArcadeGeometry.java", "home/LargeLcdTvLayout.java", "home/VintageTvLayout.java")]
        compile_args.append(str(ROOT/"tools/AvCableAlpha7Probe.java"))
        subprocess.run(compile_args, check=True, capture_output=True, text=True)
        report = run_probe(scratch + (";"+str(jar) if jar else ""))
        # Put the old JAR before source-generated classes so unequal-height comparison
        # invokes the original Java implementation, not copied/rewritten formulae.
        prior = run_probe(str(baseline)+";"+scratch, reference=True)
    changed = [key for key,value in prior["unequal_route_hashes"].items() if report["unequal_route_hashes"].get(key) != value]
    report.update(mode="final_jar" if jar else "explicit_source", jar_sha256=file_sha(jar) if jar else None,
                  prior_jar_sha256=ALPHA6_JAR_SHA, unchanged_unequal_height_legacy_routes=len(prior["unequal_route_hashes"])-len(changed),
                  changed_unequal_height_legacy_routes=changed)
    if changed:
        report["ok"] = False
        report["failures"].append("Unequal-height old FC/Subor routes no longer preserve the reviewed conservative behavior")
    report["limits"] = ["Only equal base heights imply a common tabletop; no arbitrary world-block terrain sampling is claimed.",
                        "Unequal heights retain prior routing for unchanged hardware; they are not certified to follow stepped tables.",
                        "This is real generated geometry executed offline, not a Minecraft screenshot or gameplay test."]
    if jar and file_sha(jar) != expected_sha.upper():
        raise ValueError("JAR changed during read-only geometry inspection")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--source", action="store_true")
    mode.add_argument("--jar", type=Path)
    parser.add_argument("--jar-sha256")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.report and (args.report.parent.name != "家用FC-0.31.0-alpha.7-模型预览" or args.report.exists()):
        parser.error("Report must be new and inside the alpha.7 preview folder")
    result = inspect(args.jar, args.jar_sha256)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        print("REPORT_SHA256="+file_sha(args.report))
    print(json.dumps({key:value for key,value in result.items() if key!="unequal_route_hashes"},ensure_ascii=False,indent=2))
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__": main()
