import json
import unittest
import numpy as np
from build_vintage_tv_model import build
from vintage_tv_alpha10_archive import release,MODEL_SHA
from import_subor_hardware import ASSETS,encoded,sha
from check_controller_pose_pipeline import display_matrix,translation,points
from render_rocket_arcade_preview import collect_quads

class VintageTvModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.released=release();cls.model=cls.released['model'];cls.textures=cls.released['textures'];cls.quads=collect_quads(cls.model)

    def test_frozen_alpha10_mirrored_model_matches_original_builder(self):
        self.assertEqual(MODEL_SHA,sha(self.released['model_bytes']))
        self.assertEqual(self.released['model_bytes'],encoded(self.model));self.assertEqual(self.model,build())

    def test_physical_four_three_screen_is_viewer_left_of_knobs(self):
        screen=next(e for e in self.model['elements'] if e['name']=='完整4比3黑屏')
        np.testing.assert_allclose(screen['from'],[4.6,2.1,2.28]);np.testing.assert_allclose(screen['to'],[14.6,9.6,2.30])
        self.assertEqual(['north'],list(screen['faces']))
        for name in ('频道黑旋钮中心','音量黑旋钮中心'):
            knob=next(e for e in self.model['elements'] if e['name']==name)
            self.assertAlmostEqual(2.38,(knob['from'][0]+knob['to'][0])/2)
            self.assertLess(knob['to'][0],screen['from'][0])
        self.assertTrue(np.all(self.textures[self.model['textures']['screen']]==[0,0,0,255]))

    def test_all_actual_rotated_vertices_fit_one_cell_and_layout_bounds(self):
        p=np.concatenate([q.vertices for q in self.quads])
        self.assertTrue(np.all(p.min(0)>=[.2,0,1.8]));self.assertTrue(np.all(p.max(0)<=[15.8,14.3,14.2]))
        for e in self.model['elements']:
            self.assertGreaterEqual(min(e['from']),-16);self.assertLessEqual(max(e['to']),32)
            self.assertTrue(np.all(np.array(e['to'])>e['from']))

    def test_three_mirrored_socket_centers_have_recessed_rear_surfaces(self):
        for channel,color in enumerate(('yellow','white','red')):
            x,y,z=9.5-channel*2,3.1,14.04
            rings=[e for e in self.model['elements'] if e['name'].startswith(color+'AV接口')]
            self.assertEqual(4,len(rings))
            vertices=np.array([p for e in rings for p in (e['from'],e['to'])])
            np.testing.assert_allclose((vertices.min(0)+vertices.max(0))[:2]/2,[x,y])
            self.assertAlmostEqual(z,vertices[:,2].max())
            faces=[e['to'][2] for e in self.model['elements'] if e['from'][0]<x<e['to'][0] and e['from'][1]<y<e['to'][1] and 'rotation' not in e]
            self.assertLess(max(faces),z-.15)

    def test_true_vanilla_gui_transform_fits_entire_model_without_refitting(self):
        item=self.released['item']
        m=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)
        p=points(np.concatenate([q.vertices for q in self.quads])/16,m)
        pixels=p[:,:2]*[16,-16]+8
        self.assertGreaterEqual(pixels.min(),0);self.assertLessEqual(pixels.max(),16)

    def test_existing_eight_solid_color_textures_remain_frozen(self):
        self.assertEqual(8,len(self.released['texture_sha256']))
        self.assertEqual('AF00B00AD7E928A32320856F79610E33B0308A56666577D4FACA7C26C38BE9FD',self.released['old_models']['home_lcd_tv.json'])
        self.assertEqual('D1DE1CA2233E7295B56A94D2DF15BD970F0783522B050F892FD8D5F904800528',self.released['old_models']['home_wide_lcd_tv.json'])

if __name__=='__main__':unittest.main()
