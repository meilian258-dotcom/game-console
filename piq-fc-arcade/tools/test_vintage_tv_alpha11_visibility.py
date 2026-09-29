import json
import unittest
import numpy as np
from check_vintage_tv_alpha11 import MODEL,analyze,front_occluders
from render_rocket_arcade_preview import Quad

def quad(x1,y1,x2,y2,z):return Quad(np.array([[x1,y1,z],[x2,y1,z],[x2,y2,z],[x1,y2,z]]),np.zeros((4,2)),'test',5,'north')
class InsetScreenVisibilityTest(unittest.TestCase):
    def test_actual_glass_behind_dynamic_plane_does_not_occlude(self):self.assertEqual([],front_occluders([quad(4.35,2,14.55,9.65,3.35)]))
    def test_solid_front_wall_covering_recess_is_rejected(self):self.assertTrue(front_occluders([quad(1,1,15,11,2.5)]))
    def test_tiny_corner_cut_in_complete_source_rectangle_is_rejected(self):self.assertTrue(front_occluders([quad(4.34,1.99,4.37,2.02,2.5)]))
    def test_edge_frame_outside_window_is_allowed(self):self.assertEqual([],front_occluders([quad(4.1,2,4.35,9.65,2.5)]))
    def test_triangle_crossing_dynamic_depth_is_clipped_before_visibility(self):
        q=quad(5,3,6,4,3.5);q.vertices[0,2]=3.0
        self.assertTrue(front_occluders([q]))

class InstalledInsetScreenTest(unittest.TestCase):
    def test_recess_top_and_bottom_join_outer_mouth_without_hairline_gap(self):
        e={part['name']:part for part in json.loads(MODEL.read_bytes())['elements']}
        self.assertAlmostEqual(e['mouthB']['to'][1],e['recess-wallB']['from'][1],places=12)
        self.assertAlmostEqual(e['mouthT']['from'][1],e['recess-wallT']['to'][1],places=12)
        for side in ('B','T'):
            self.assertAlmostEqual(e['mouth'+side]['to'][2],e['recess-wall'+side]['from'][2],places=12)

    def test_all_four_inner_shadow_walls_close_recess_and_reach_native_glass_depth(self):
        e={part['name']:part for part in json.loads(MODEL.read_bytes())['elements']}
        glass=e['完整4比3黑屏']['from'][2]
        for side in ('L','R','B','T'):
            self.assertAlmostEqual(glass,e['inner-shadow'+side]['to'][2],places=12)
            self.assertAlmostEqual(e['recess-wall'+side]['to'][2],e['inner-shadow'+side]['from'][2],places=12)
        # The vertical ring thickness is intentionally .14, not the horizontal
        # .13: otherwise its outer top/bottom leave the v2 .01 aperture.
        for side in ('B','T'):
            self.assertAlmostEqual(.14,e['inner-shadow'+side]['to'][1]-e['inner-shadow'+side]['from'][1],places=12)
        self.assertAlmostEqual(e['recess-wallB']['to'][1],e['inner-shadowB']['from'][1],places=12)
        self.assertAlmostEqual(e['recess-wallT']['from'][1],e['inner-shadowT']['to'][1],places=12)
        self.assertAlmostEqual(e['recess-wallL']['to'][0],e['inner-shadowL']['from'][0],places=12)
        self.assertAlmostEqual(e['recess-wallR']['from'][0],e['inner-shadowR']['to'][0],places=12)

    def test_installed_model_and_actual_java_presentation_pass_every_contract(self):
        report,*_=analyze()
        self.assertTrue(report['ok'],[c for c in report['checks'] if not c['ok']])
        self.assertGreaterEqual(len(report['checks']),14);self.assertEqual([],report['occluding_faces'])
if __name__=='__main__':unittest.main()
