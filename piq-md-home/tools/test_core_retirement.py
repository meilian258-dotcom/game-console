"""Tool entrypoints reject retired work before creating outputs or invoking native tools."""
from pathlib import Path
import subprocess
import sys
import unittest

HERE = Path(__file__).resolve().parent


class CoreRetirementToolsTest(unittest.TestCase):
    def rejected(self, script, arguments, message):
        result = subprocess.run([sys.executable, "-X", "utf8", "-B", str(HERE / script), *arguments],
                                capture_output=True, encoding="utf-8", timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(message, result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_historical_builder_requires_explicit_acknowledgement(self):
        self.rejected("build_core.py", [], "BlastEm 已退役")

    def test_historical_builder_never_writes_current_resources(self):
        resources = HERE.parent / "src/main/resources/core/windows-x64"
        self.rejected("build_core.py", ["--historical-only", "missing-source", "missing-toolchain", str(resources)],
                      "不得输出到当前 MD 资源目录")

    def test_retired_native_probe_fails_before_output_creation(self):
        # An empty fresh-name is intentionally invalid: the explicit retirement must be the first result.
        self.rejected("run_native_probe.py", ["", "JNI_TRIAL", "missing-fc.jar", "missing-md.jar", "BLASTEM"],
                      "BlastEm 已退役")

    def test_current_probe_requires_explicit_packages(self):
        self.rejected("run_native_probe.py", ["unused", "JNI_TRIAL"], "current explicit package paths are required")


if __name__ == "__main__":
    unittest.main()
