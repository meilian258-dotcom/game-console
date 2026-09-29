"""Pure in-memory projection/UV tests plus read-only final-asset validation."""
import unittest

import numpy as np

import render_rocket_arcade_preview as preview


class RocketPreviewTest(unittest.TestCase):
    def test_north_face_matches_minecraft_vertex_order(self):
        element = {"from": [1, 2, 3], "to": [4, 5, 6]}
        np.testing.assert_array_equal(preview.vertices_for(element, "north"),
                                      [[4, 5, 3], [4, 2, 3], [1, 2, 3], [1, 5, 3]])

    def test_rotated_element_uses_origin_and_positive_right_hand_x(self):
        points = np.array([[8, 11, 4]], dtype=float)
        rotation = {"axis": "x", "angle": 45, "origin": [8, 10, 4]}
        np.testing.assert_allclose(preview.rotated(points, rotation),
                                   [[8, 10+2**-0.5, 4+2**-0.5]], atol=1e-10)

    def test_rescale_is_applied_after_rotation_on_perpendicular_axes(self):
        points = np.array([[8, 11, 4]], dtype=float)
        rotation = {"axis": "x", "angle": 22.5, "origin": [8, 10, 4], "rescale": True}
        np.testing.assert_allclose(preview.rotated(points, rotation),
                                   [[8, 11, 4+np.tan(np.pi/8)]], atol=1e-10)

    def test_uv_rotation_matches_block_face_shifted_index(self):
        np.testing.assert_array_equal(preview.uv_for({"uv": [1, 2, 3, 4], "rotation": 90}),
                                      [[1, 4], [3, 4], [3, 2], [1, 2]])

    @staticmethod
    def face(canvas, depth, z, texture):
        vertices = np.array([[0, 0, z], [0, 8, z], [8, 8, z], [8, 0, z]], dtype=float)
        uv = preview.uv_for({"uv": [0, 0, 16, 16]})
        for selected in ([0, 1, 2], [0, 2, 3]):
            preview.raster_triangle(canvas, depth, vertices[selected], uv[selected], texture)

    def test_textured_face_orientation_preserves_all_four_corner_colors(self):
        texture = np.array([[[255, 0, 0, 255], [0, 255, 0, 255]],
                            [[0, 0, 255, 255], [255, 255, 0, 255]]], dtype=np.uint8)
        canvas = np.zeros((8, 8, 4), dtype=np.uint8)
        depth = np.full((8, 8), -np.inf)
        self.face(canvas, depth, 1, texture)
        np.testing.assert_array_equal(canvas[1, 1], texture[0, 0])
        np.testing.assert_array_equal(canvas[1, 6], texture[0, 1])
        np.testing.assert_array_equal(canvas[6, 1], texture[1, 0])
        np.testing.assert_array_equal(canvas[6, 6], texture[1, 1])

    def test_depth_buffer_is_draw_order_independent_for_occluding_faces(self):
        near = np.array([[[255, 0, 0, 255]]], dtype=np.uint8)
        far = np.array([[[0, 0, 255, 255]]], dtype=np.uint8)
        results = []
        for layers in (((2, near), (1, far)), ((1, far), (2, near))):
            canvas = np.zeros((8, 8, 4), dtype=np.uint8)
            depth = np.full((8, 8), -np.inf)
            for z, texture in layers:
                self.face(canvas, depth, z, texture)
            results.append(canvas)
        np.testing.assert_array_equal(results[0], results[1])
        np.testing.assert_array_equal(results[0][4, 4], near[0, 0])

    def test_transparent_texels_do_not_occlude_geometry_behind_them(self):
        clear = np.array([[[255, 255, 255, 0]]], dtype=np.uint8)
        opaque = np.array([[[20, 30, 40, 255]]], dtype=np.uint8)
        canvas = np.zeros((8, 8, 4), dtype=np.uint8)
        depth = np.full((8, 8), -np.inf)
        self.face(canvas, depth, 2, clear)
        self.face(canvas, depth, 1, opaque)
        np.testing.assert_array_equal(canvas[4, 4], opaque[0, 0])

    def test_texture_namespace_alias_cycles_and_traversal(self):
        self.assertEqual(preview.resolve_texture({"0": "piq_fc_arcade:block/rocket_arcade_skin",
                                                   "particle": "#0"}, "#particle"),
                         "piq_fc_arcade:block/rocket_arcade_skin")
        with self.assertRaises(ValueError):
            preview.resolve_texture({"0": "#1", "1": "#0"}, "#0")
        with self.assertRaises(ValueError):
            preview.resolve_texture({}, "piq_fc_arcade:../private")

    def test_cutout_shader_alpha_threshold_discards_25_but_preserves_26(self):
        texture = np.array([[[70, 80, 90, 25], [20, 30, 40, 26]]], dtype=np.uint8)
        original = texture.copy()
        canvas = np.zeros((8, 8, 4), dtype=np.uint8)
        depth = np.full((8, 8), -np.inf)
        self.face(canvas, depth, 1, texture)
        self.assertEqual(0, int(canvas[4, 1, 3]))
        np.testing.assert_array_equal(canvas[4, 6], [20, 30, 40, 255])
        np.testing.assert_array_equal(texture, original)

    def test_actual_final_assets_are_valid_and_have_one_rotated_four_three_display(self):
        _, _, quads, report = preview.inspect_assets(preview.DEFAULT_MODEL, preview.ASSETS)
        self.assertTrue(report["ok"], report["errors"])
        self.assertEqual(155, report["elements"])
        self.assertEqual(1, len(report["textures"]))
        self.assertEqual([2048, 2048], report["textures"][0]["size"])
        self.assertGreater(len(quads), 700)
        self.assertEqual(1, len(report["four_three_screens"]))
        screen = report["four_three_screens"][0]
        self.assertAlmostEqual(10.52, screen["width"])
        self.assertAlmostEqual(7.89, screen["height"])
        self.assertAlmostEqual(4/3, screen["ratio"])
        self.assertEqual("x", screen["rotation"]["axis"])
        self.assertEqual(22.5, screen["rotation"]["angle"])


if __name__ == "__main__":
    unittest.main()
