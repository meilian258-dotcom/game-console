import copy
import json
import unittest

from prepare_handheld_model import BODY, DECAL, remove_front_decal


class HandheldBrandingTest(unittest.TestCase):
    def test_removes_only_exact_front_decal_and_is_idempotent(self):
        body = {'name': 'body', 'from': [0, 0, 0], 'to': [1, 1, 1]}
        decal = {'name': DECAL, 'from': [7.49, 1.288, 6.21], 'to': [8.51, 1.297, 6.48], 'faces': {'up': {'texture': '#0'}}}
        model = {'elements': [body, decal], 'textures': {'0': 'original'}}
        self.assertTrue(remove_front_decal(model))
        self.assertEqual(model, {'elements': [body], 'textures': {'0': 'original'}})
        self.assertFalse(remove_front_decal(model))

    def test_changed_geometry_requires_review(self):
        model = {'elements': [{'name': DECAL, 'from': [0, 0, 0], 'to': [1, 1, 1], 'faces': {}}]}
        original = copy.deepcopy(model)
        with self.assertRaises(ValueError):
            remove_front_decal(model)
        self.assertEqual(model, original)

    def test_production_has_only_authorized_one_face_removed(self):
        model = json.loads(BODY.read_text(encoding='utf-8'))
        self.assertEqual(len(model['elements']), 532)
        self.assertFalse(any(element.get('name') == DECAL for element in model['elements']))


if __name__ == '__main__':
    unittest.main()
