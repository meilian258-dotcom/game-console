import copy
import unittest

import numpy as np

from check_rocket_coplanar_faces import coplanar_overlaps, intersect_convex, signed_area


def face(low, high, direction="east", rotation=None):
    result = {"from": low, "to": high,
              "faces": {direction: {"texture": "#0", "uv": [0, 0, 16, 16]}}}
    if rotation:
        result["rotation"] = rotation
    return result


def model(*elements):
    return {"textures": {"0": "piq_fc_arcade:block/rocket_arcade_skin"}, "elements": list(elements)}


class CoplanarFacesTest(unittest.TestCase):
    def test_partial_rectangle_overlap(self):
        result = coplanar_overlaps(model(face([0, 0, 0], [1, 4, 4]),
                                         face([0, 2, 2], [1, 6, 6])))
        self.assertEqual(1, len(result))
        self.assertAlmostEqual(4.0, result[0]["area_model_units_squared"])
        self.assertTrue(result[0]["same_facing"])
        self.assertFalse(result[0]["duplicate_geometry"])

    def test_duplicate_and_opposed_planes(self):
        a = face([0, 0, 0], [1, 2, 3])
        duplicate = coplanar_overlaps(model(a, copy.deepcopy(a)))[0]
        self.assertTrue(duplicate["duplicate_geometry"])
        opposed = coplanar_overlaps(model(a, face([1, 0, 0], [2, 2, 3], "west")))[0]
        self.assertFalse(opposed["same_facing"])
        self.assertAlmostEqual(6.0, opposed["area_model_units_squared"])

    def test_edge_contact_and_separated_planes_have_no_area(self):
        a = face([0, 0, 0], [1, 2, 3])
        self.assertEqual([], coplanar_overlaps(model(a, face([0, 2, 0], [1, 4, 3]))))
        self.assertEqual([], coplanar_overlaps(model(a, face([0, 0, 0], [1.001, 2, 3]))))

    def test_rotated_quads_do_not_use_bounding_box_area(self):
        rotation = {"axis": "x", "angle": 45, "origin": [1, 0, 0], "rescale": False}
        a = face([0, -1, -1], [1, 1, 1])
        b = face([0, -1, -1], [1, 1, 1], rotation=rotation)
        overlap = coplanar_overlaps(model(a, b))[0]
        self.assertAlmostEqual(8 * (np.sqrt(2) - 1), overlap["area_model_units_squared"])
        self.assertEqual(8, len(overlap["intersection_vertices"]))

    def test_oblique_planes_preserve_three_dimensional_area(self):
        rotation = {"axis": "z", "angle": 22.5, "origin": [0, 0, 0], "rescale": False}
        a = face([0, 0, 0], [1, 2, 3], rotation=rotation)
        overlap = coplanar_overlaps(model(a, copy.deepcopy(a)))[0]
        self.assertAlmostEqual(6.0, overlap["area_model_units_squared"])

    def test_polygon_winding_is_irrelevant_and_inputs_unchanged(self):
        a = np.array([[0, 0], [2, 0], [2, 2], [0, 2]], dtype=float)
        before = a.copy()
        self.assertAlmostEqual(4, abs(signed_area(intersect_convex(a, a[::-1]))))
        np.testing.assert_array_equal(before, a)


if __name__ == "__main__":
    unittest.main()
