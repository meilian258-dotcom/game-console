"""Bounded hosted-build failure summaries; no native compiler or core is run."""
import tempfile
from pathlib import Path
import unittest

from build_candidate import failure_excerpt


class BuildDiagnosticsTest(unittest.TestCase):
    def excerpt(self, content):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "build.log"
            path.write_bytes(content)
            return failure_excerpt(path)

    def test_first_error_survives_long_parallel_tail(self):
        report = self.excerpt(
            b"Compiling input_common.cpp...\n"
            b"input_common.cpp:35:10: fatal error: SDL2/SDL.h: No such file\n"
            b"  #include <SDL2/SDL.h>\n"
            + b"Compiling other.cpp...\n" * 3000
            + b"make: Error 2\n")
        self.assertIn("First compiler error context:", report)
        self.assertIn("SDL2/SDL.h: No such file", report)
        self.assertIn("make: Error 2", report)
        self.assertLess(len(report), 21000)

    def test_no_error_marker_still_reports_tail(self):
        report = self.excerpt(b"working\n" * 1000 + b"make: Error 2\n")
        self.assertNotIn("First compiler error context:", report)
        self.assertLessEqual(len(report.splitlines()), 61)
        self.assertIn("make: Error 2", report)

    def test_oversized_line_and_invalid_utf8_are_bounded(self):
        report = self.excerpt(b"x" * (9 * 1024**2) + b"\xff: fatal error: late\n")
        self.assertNotIn("First compiler error context:", report)
        self.assertIn("fatal error: late", report)
        self.assertLess(len(report), 21000)

    def test_error_line_is_not_lost_to_long_preceding_context(self):
        report = self.excerpt(b"x" * 12000 + b"\nsource: error: primary\nfollowup\n")
        self.assertIn("source: error: primary", report.split("Build log tail:")[0])
        self.assertLess(len(report), 21000)

    def test_link_error_and_empty_log(self):
        self.assertIn("First compiler error context:",
                      self.excerpt(b"obj: undefined reference to 'symbol'\n"))
        self.assertEqual("Build log tail:\n", self.excerpt(b""))

    def test_error_near_end_of_long_line_keeps_marker(self):
        report = self.excerpt(b"x" * 3000 + b": error: visible\n" + b"later\n" * 5000)
        self.assertIn(": error: visible", report.split("Build log tail:")[0])

    def test_error_marker_crossing_read_boundary_is_found(self):
        report = self.excerpt(b"x" * 4092 + b": fatal error: visible\n" + b"later\n" * 5000)
        self.assertIn(": fatal error: visible", report.split("Build log tail:")[0])


if __name__ == "__main__":
    unittest.main()
