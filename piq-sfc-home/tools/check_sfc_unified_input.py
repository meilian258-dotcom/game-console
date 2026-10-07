"""Run the actual shared pure keyboard state and SFC wiring contracts, without Minecraft."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FC = ROOT.parent / "piq-fc-arcade"
JAVA = (java_home() / 'bin')


def run(command):
    result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=60)
    if result.returncode:
        raise AssertionError(result.stdout + "\n" + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        raise ValueError("Refusing to overwrite previous evidence")
    cache = (gradle_home() / 'caches/modules-2/files-2.1')
    deps = []
    for group, version in [("org.junit.platform", "1.13.4"), ("org.junit.jupiter", "5.13.4"),
                           ("org.opentest4j", "1.3.0"), ("org.apiguardian", "1.1.2")]:
        deps.extend(p for p in (cache / group).rglob("*.jar")
                    if version in p.parts and not any(x in p.name for x in ("-sources", "-javadoc")))
    pure = [FC / "src/main/java/cn/piq/retro/client" / (name + ".java")
            for name in ("KeyboardConfig", "KeyboardControlState")]
    pure.extend(ROOT / "src/main/java/cn/piq/sfchome/client" / (name + ".java")
                for name in ("SfcInputFocus", "SfcInputSendPolicy"))
    tests = ["client/SfcUnifiedKeyboardTest", "client/SfcInputFocusTest",
             "client/SfcInputSendPolicyTest", "server/SfcFcInteractionSourceTest"]
    test_sources = [ROOT / "src/test/java/cn/piq/sfchome" / (name + ".java") for name in tests]
    with tempfile.TemporaryDirectory(prefix="sfc-unified-input-") as folder:
        classes = Path(folder) / "classes"
        empty = Path(folder) / "empty"
        classes.mkdir(); empty.mkdir()
        cp = os.pathsep.join(map(str, [classes, *deps]))
        run([JAVA / "javac.exe", "-encoding", "UTF-8", "-proc:none", "-sourcepath", empty,
             "-cp", cp, "-d", classes, *pure, *test_sources, FC / "tools/qa/DeviceUiTestRunner.java"])
        result = json.loads(run([JAVA / "java.exe", "-cp", cp, "DeviceUiTestRunner",
                                 *["cn.piq.sfchome." + name.replace("/", ".") for name in tests]]))
        assert result["passed_tests"] == 25
    sources = pure + test_sources + [ROOT / "src/main/java/cn/piq/sfchome/client" / (name + ".java")
                                      for name in ("SfcHomeKeys", "SfcHomeClient")]
    report = {"ok": True, "tests": result["passed_tests"],
              "mode": "actual-pure-keyboard-state-and-sfc-source-contracts",
              "source_sha256": {str(p.relative_to(ROOT.parent)): hashlib.sha256(p.read_bytes()).hexdigest().upper()
                                for p in sources},
              "minecraft_started": False, "native_core_started": False,
              "limits": ["Pure Java production state executes; Minecraft host integration is checked by source contracts.",
                         "Not real keyboard/GLFW event injection, a live Minecraft client, multiplayer socket or gamepad hardware test."]}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
