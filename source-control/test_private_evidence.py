import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from private_evidence import ENVIRONMENT_VARIABLE, private_evidence


class PrivateEvidenceTests(unittest.TestCase):
    def test_unconfigured_does_not_fall_back_to_repository(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, ENVIRONMENT_VARIABLE):
                private_evidence("component/design/report.json")

    def test_missing_evidence_is_an_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(os.environ, {ENVIRONMENT_VARIABLE: temporary}):
                with self.assertRaisesRegex(FileNotFoundError, "restore the original"):
                    private_evidence("component/design/report.json")

    def test_preserves_repository_layout_and_exact_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "component/design/report.json"
            report.parent.mkdir(parents=True)
            report.write_bytes(b'{"fixture": true}\n')
            with patch.dict(os.environ, {ENVIRONMENT_VARIABLE: temporary}):
                self.assertEqual(private_evidence("component/design/report.json"), report)
                self.assertEqual(report.read_bytes(), b'{"fixture": true}\n')

    def test_rejects_unsafe_relative_paths(self):
        for path in ("", "/report.json", "C:/report.json", "a/../report.json", "a\\report.json", "a//report.json", "./report.json"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                private_evidence(path)

    def test_rejects_relative_root(self):
        with patch.dict(os.environ, {ENVIRONMENT_VARIABLE: "relative-root"}):
            with self.assertRaisesRegex(ValueError, "absolute"):
                private_evidence("component/design/report.json")

    def test_rejects_directory_as_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            (Path(temporary) / "report.json").mkdir()
            with patch.dict(os.environ, {ENVIRONMENT_VARIABLE: temporary}):
                with self.assertRaises(FileNotFoundError):
                    private_evidence("report.json")


if __name__ == "__main__":
    unittest.main()
