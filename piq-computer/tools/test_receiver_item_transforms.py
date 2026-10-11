"""Deterministic item-transform regression; does not launch Minecraft or edit assets."""
import copy
import json
import unittest
from inventory7_assets import root, receiver_model, gui


class ReceiverItemTransformsTest(unittest.TestCase):
    def setUp(self):
        self.block=json.loads((root/'models/block/wireless_receiver.json').read_text('utf8'))
        self.item=json.loads((root/'models/item/connector.json').read_text('utf8'))

    def test_authored_item_matches_generator_without_changing_placed_geometry(self):
        original=copy.deepcopy(self.block)
        self.assertEqual(self.item,receiver_model(self.block))
        self.assertEqual(original,self.block)
        self.assertEqual(['receiver_usb','receiver_body'],[e['name'] for e in self.item['elements']])
        for item,placed in zip(self.item['elements'],self.block['elements']):
            self.assertEqual(placed['faces'],item['faces'])
        self.assertEqual(self.block['textures'],self.item['textures'])

    def test_gui_remains_original_framing(self):
        self.assertEqual(gui(self.item,[25,110,0]),self.item['display']['gui'])
        self.assertAlmostEqual(1.0826482542486753,self.item['display']['gui']['scale'][0])

    def test_all_four_hands_are_small_and_centered(self):
        points=[e[k] for e in self.item['elements'] for k in ('from','to')]
        extents=[max(p[i] for p in points)-min(p[i] for p in points) for i in range(3)]
        for view in ('firstperson','thirdperson'):
            right=self.item['display'][view+'_righthand']
            self.assertEqual(right,self.item['display'][view+'_lefthand'])
            self.assertEqual([.25]*3,right['scale'])
            self.assertEqual([0,0,0],right['rotation'])
            self.assertEqual([0,1,0],right['translation'])
            self.assertAlmostEqual(2.75,max(a*b for a,b in zip(extents,right['scale'])))
            for axis in (0,2):
                lo=min(p[axis] for p in points);hi=max(p[axis] for p in points)
                self.assertAlmostEqual(8,(lo+hi)/2)


if __name__=='__main__':unittest.main(verbosity=2)
