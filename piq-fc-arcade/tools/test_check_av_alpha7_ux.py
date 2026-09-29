import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

from check_av_alpha7_ux import inspect, run_probe
from verify_home_fc_final_jar import DELIVERY


class IndependentAvAlpha7UxTest(unittest.TestCase):
    def test_frozen_alpha7_geometry_keeps_its_original_table_and_unequal_route_contract(self):
        report = inspect(DELIVERY/"piq_fc_arcade-0.31.0-alpha.7.jar",
                         "B2DAF27EA28F33A7DFA5138E6D324BE15BECDE140305C6FD280B3542F3FA52A4")
        self.assertTrue(report["ok"], report["failures"])
        self.assertEqual(864, report["same_level_cases"])
        self.assertEqual(192, report["unchanged_unequal_height_legacy_routes"])
        self.assertEqual([], report["changed_unequal_height_legacy_routes"])
        self.assertGreaterEqual(report["minimum_table_contact_fraction"], .5)
        self.assertGreaterEqual(report["minimum_actual_y"], 0)
        self.assertLessEqual(report["max_quads"], 6000)
        self.assertEqual("final_jar", report["mode"])

    def test_final_jar_wrong_hash_is_rejected_before_geometry_execution(self):
        with patch("check_av_alpha7_ux.file_sha", return_value="A"*64), patch("check_av_alpha7_ux.subprocess.run") as run:
            with self.assertRaises(ValueError): inspect(Path("unused.jar"), "B"*64)
            run.assert_not_called()

    def test_missing_expected_jar_hash_is_rejected_without_even_reading_candidate(self):
        with patch("check_av_alpha7_ux.file_sha") as digest:
            with self.assertRaises(ValueError): inspect(Path("unused.jar"))
            digest.assert_not_called()

    def test_changed_historical_jar_is_not_accepted_as_different_height_reference(self):
        with patch("check_av_alpha7_ux.file_sha", return_value="A"*64), patch("check_av_alpha7_ux.subprocess.run") as run:
            with self.assertRaises(ValueError): inspect()
            run.assert_not_called()

    def test_probe_launch_errors_cannot_be_reported_as_geometry_pass(self):
        for code, text in ((2, ""), (0, "unrelated tool output")):
            with patch("check_av_alpha7_ux.subprocess.run", return_value=subprocess.CompletedProcess([], code, text, "launch failed")):
                with self.assertRaises(RuntimeError): run_probe("unused")

    def test_probe_records_numeric_failures_without_treating_exit_one_as_launch_error(self):
        result = {"ok": False, "failures": ["actual vertex below tabletop"]}
        with patch("check_av_alpha7_ux.subprocess.run", return_value=subprocess.CompletedProcess([], 1, json.dumps(result), "")):
            self.assertEqual(result, run_probe("unused"))


if __name__ == "__main__": unittest.main()
