"""Six actual-asset regressions; baseline is the retained, SHA-checked beta.2 JAR."""
import json
import unittest

from audit_rocket_geometry_update import audit, load_baseline, load_textures
from render_rocket_arcade_preview import DEFAULT_MODEL


class RocketGeometryUpdateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, before = load_baseline()
        after = json.loads(DEFAULT_MODEL.read_text(encoding="utf-8-sig"))
        textures, _ = load_textures(after)
        cls.report = audit(before, after, textures)

    def test_only_approved_twenty_five_element_micro_adjustments(self):
        self.assertTrue(self.report["checks"]["only_approved_geometry_changes"])
        self.assertTrue(self.report["checks"]["element_count_unchanged_155"])
        self.assertEqual(25, len(self.report["changed_element_indices"]))

    def test_all_918_surviving_faces_retain_exact_uv_rotation_and_texture(self):
        self.assertEqual(920, self.report["faces_before"])
        self.assertEqual(918, self.report["faces_after"])
        self.assertTrue(self.report["checks"]["all_surviving_face_uv_rotation_texture_unchanged"])

    def test_entire_screen_element_and_four_corners_remain_byte_semantically_exact(self):
        self.assertTrue(self.report["checks"]["screen_47_entire_element_and_four_corners_exact"])
        self.assertEqual(self.report["screen_vertices_before"], self.report["screen_vertices_after"])

    def test_raw_and_rotated_global_bounds_are_exactly_unchanged(self):
        self.assertTrue(self.report["checks"]["unrotated_bounds_exact"])
        self.assertTrue(self.report["checks"]["rotated_bounds_exact"])

    def test_two_deleted_caps_are_strictly_hidden_and_screen_corner_joins_have_no_gap(self):
        self.assertTrue(self.report["checks"]["only_two_deleted_caps_with_strict_opaque_cover"])
        self.assertTrue(self.report["checks"]["horizontal_screen_frames_join_vertical_frames_without_gap"])
        for proof in self.report["removed_face_proofs"]:
            self.assertIn(49, proof["opaque_covering_elements"])

    def test_every_remaining_coplanar_overlap_has_an_actual_opaque_internal_proof(self):
        self.assertEqual(19, len(self.report["remaining_overlaps"]))
        self.assertTrue(self.report["checks"]["all_remaining_overlaps_have_opaque_internal_proof"])
        self.assertTrue(self.report["ok"])


if __name__ == "__main__":
    unittest.main()
