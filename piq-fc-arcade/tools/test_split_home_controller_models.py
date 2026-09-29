import copy
import json
import unittest
from collections import Counter

import numpy as np

from split_home_controller_models import ASSETS, SOURCE, PREFIXES, collect, held_model, partition
from render_rocket_arcade_preview import collect_quads


class ControllerModelPartitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = json.loads(SOURCE.read_bytes())
        cls.outputs = collect()

    def test_exact_disjoint_multiset_partition_preserves_every_original_element(self):
        body, groups = partition(self.source)
        encode = lambda e: json.dumps(e, ensure_ascii=False, sort_keys=True)
        self.assertEqual(Counter(map(encode, self.source["elements"])),
                         Counter(map(encode, body + groups[0] + groups[1])))
        self.assertEqual([101, 47, 60], [len(body), len(groups[0]), len(groups[1])])

    def test_docks_and_mechanical_body_do_not_disappear_with_borrowed_controllers(self):
        body, groups = partition(self.source)
        self.assertEqual(2, sum("手柄收纳底槽" in e["name"] for e in body))
        for port, group in enumerate(groups):
            self.assertTrue(all(e["name"].startswith(PREFIXES[port]) for e in group))
            self.assertEqual(7, sum(e["name"].startswith(PREFIXES[port][1]) for e in group))

    def test_held_controllers_omit_original_docked_cords_and_keep_uvs(self):
        _, groups = partition(self.source)
        for port, group in enumerate(groups):
            held, center = held_model(self.source, group, PREFIXES[port][0])
            original = [e for e in group if e["name"].startswith(PREFIXES[port][0])]
            self.assertEqual([40, 53][port], len(held["elements"]))
            for old, new in zip(original, held["elements"]):
                self.assertEqual(old["faces"], new["faces"])
                self.assertEqual(old.get("rotation", {}).get("angle"), new.get("rotation", {}).get("angle"))
            before = dict(self.source, elements=original)
            for old, new in zip(collect_quads(before), collect_quads(held)):
                np.testing.assert_allclose((old.vertices - center) / .6 + 8, new.vertices, atol=1e-8)
                np.testing.assert_array_equal(old.uv, new.uv)

    def test_actual_generated_assets_match_reviewed_partition(self):
        for name, value in self.outputs.items():
            self.assertEqual(value, json.loads((ASSETS / name).read_bytes()))

    def test_positive_camera_z_faces_are_readable_and_dpad_is_left_of_a(self):
        _, groups = partition(self.source)
        for port, group in enumerate(groups):
            held, _ = held_model(self.source, group, PREFIXES[port][0])
            by_name = {e["name"]: e for e in held["elements"]}
            prefix = PREFIXES[port][0]
            center = lambda e: (np.array(e["from"]) + e["to"]) / 2 - 8
            pad = center(by_name[prefix + "十字键横"])
            button = center(by_name[prefix + "A黑色圆钮·横芯"])
            # Render rotates P1 east to south (-90Y), P2 west to south (+90Y).
            horizontal = lambda v: -v[2] if port == 0 else v[2]
            self.assertLess(horizontal(pad), horizontal(button))

    def test_item_does_not_add_a_free_creative_controller_or_use_stock_lead_texture(self):
        model = self.outputs["models/item/fc_controller.json"]
        self.assertEqual("builtin/entity", model["parent"])
        self.assertEqual("piq_fc_arcade:block/home_famicom_console", model["textures"]["particle"])
        for context in ("gui", "ground", "fixed", "firstperson_righthand", "firstperson_lefthand",
                        "thirdperson_righthand", "thirdperson_lefthand"):
            self.assertIn(context, model["display"])


if __name__ == "__main__":
    unittest.main()
