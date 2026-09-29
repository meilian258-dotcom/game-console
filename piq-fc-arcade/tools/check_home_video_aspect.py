"""Run the same actual-MC probe against frozen alpha13 and current/new packaged consumer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DELIVERY = ROOT.parent / "制作Mod/03-街机模拟/PIQ-FC街机"
OLD = DELIVERY / "piq_fc_arcade-0.31.0-alpha.13.jar"
OLD_SHA = "681D1BE79DD87B67D64034FAE18CC022E942F6C6887E335A5CB70C05C87D2342"
JAVA = Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")
MC = Path("C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar")
JOML = Path("C:/Users/13498/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.5/22566d58af70ad3d72308bab63b8339906deb649/joml-1.10.5.jar")
PROBE = ROOT / "tools/qa/HomeVideoAspectProbe.java"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def run(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, timeout=60)
    stdout = result.stdout.decode("utf-8", errors="replace")
    stderr = result.stderr.decode("utf-8", errors="replace")
    if result.returncode:
        raise AssertionError(stdout + stderr)
    return stdout.strip()

def parse(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)

def check(jar=None, expected=None):
    assert sha(OLD) == OLD_SHA, "Frozen alpha13 jar changed"
    if jar:
        assert expected and sha(jar) == expected.upper(), "Candidate must be hash-pinned"
    with tempfile.TemporaryDirectory(prefix="piq-home-aspect-") as temp:
        temp = Path(temp)
        qa, active, empty = temp / "qa", temp / "active", temp / "empty"
        for path in (qa, active, empty): path.mkdir()
        dependencies = os.pathsep.join(map(str, (MC, JOML)))
        run([JAVA / "javac.exe", "-encoding", "UTF-8", "-proc:none", "-sourcepath", empty,
             "-cp", dependencies, "-d", qa, PROBE])
        assert not (qa / "cn/piq/fcarcade/client/CrtScanlineVertexConsumer.class").exists()
        sources = [ROOT / "src/main/java/cn/piq/fcarcade/client" / name for name in
                   ("CrtScanlinePattern.java", "CrtScanlineVertexConsumer.java")]
        source_shas = {str(path.relative_to(ROOT)): sha(path) for path in sources}
        if not jar:
            run([JAVA / "javac.exe", "-encoding", "UTF-8", "-proc:none", "-sourcepath", empty,
                 "-cp", dependencies, "-d", active, *sources])
        old_out = run([JAVA / "java.exe", "-cp", os.pathsep.join(map(str, (OLD, qa, MC, JOML))),
                       "cn.piq.fcarcade.client.HomeVideoAspectProbe", "--legacy-only"])
        new_out = run([JAVA / "java.exe", "-cp", os.pathsep.join(map(str, (jar or active, qa, MC, JOML))),
                       "cn.piq.fcarcade.client.HomeVideoAspectProbe"])
        baseline, candidate = parse(old_out), parse(new_out)
        assert baseline["LEGACY_CASES"] == candidate["LEGACY_CASES"] == "72"
        assert baseline["LEGACY_SHA256"] == candidate["LEGACY_SHA256"], "Legacy default output changed"
        assert int(candidate["ASPECT_CASES"]) == 145
        assert int(candidate["ASSERTIONS"]) > 100000
        assert sha(OLD) == OLD_SHA
        if jar: assert sha(jar) == expected.upper()
        elif source_shas != {str(path.relative_to(ROOT)): sha(path) for path in sources}:
            raise AssertionError("Active sources changed during audit")
        return {"status": "passed", "mode": "actual-final-jar" if jar else "direct-compiled-production-source",
                "old_jar": str(OLD), "old_jar_sha256": OLD_SHA,
                "candidate_jar": str(jar) if jar else None, "candidate_sha256": expected,
                "production_source_sha256": None if jar else source_shas,
                "actual_mc_api_sha256": sha(MC), "actual_joml_sha256": sha(JOML),
                "probe_sha256": sha(PROBE), "legacy": baseline, "candidate": candidate,
                "boundary": "Actual MC/JOML geometry, attributes and frozen default byte comparison; no Minecraft screenshot or live-world test."}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--jar-sha256")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = check(args.jar, args.jar_sha256)
    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        assert not args.report.exists(), "Refusing to overwrite a historical report"
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x", encoding="utf-8") as stream: stream.write(output + "\n")
    print(output)

if __name__ == "__main__": main()
