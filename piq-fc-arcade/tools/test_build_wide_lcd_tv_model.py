import unittest
import numpy as np
from build_wide_lcd_tv_model import build,audit,SOCKETS
from build_lcd_tv_model import build as legacy
from import_subor_hardware import ASSETS,encoded,sha

class WideLcdModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.model=build();cls.report,cls.textures=audit(cls.model)
    def test_actual_bounds_and_legacy_unchanged(self):
        self.assertEqual([[0,0,5],[24,15,11]],self.report['bounds'])
        self.assertEqual(sha(encoded(legacy())),sha((ASSETS/'models/block/home_lcd_tv.json').read_bytes()))
    def test_physical_screen_is_sixteen_nine_not_stretched_game(self):
        e=self.model['elements'][4]
        self.assertEqual(['north'],list(e['faces']))
        self.assertAlmostEqual(16/9,(e['to'][0]-e['from'][0])/(e['to'][1]-e['from'][1]))
        self.assertTrue(np.all(self.textures[self.model['textures']['screen']]==[0,0,0,255]))
    def test_socket_centers_have_recess(self):
        for x,y,z in SOCKETS:
            fronts=[e['to'][2] for e in self.model['elements'] if e['from'][0]<x<e['to'][0] and e['from'][1]<y<e['to'][1]]
            self.assertAlmostEqual(7.68,max(fronts));self.assertGreater(z-max(fronts),.5)
    def test_stand_keeps_original_size_only_moves_to_new_center(self):
        for name in ('窄后支柱','薄桌面底座'):
            a=next(e for e in legacy()['elements'] if e['name']==name)
            b=next(e for e in self.model['elements'] if e['name']==name)
            np.testing.assert_allclose(np.array(a['to'])-a['from'],np.array(b['to'])-b['from'])
            np.testing.assert_allclose(np.array(b['from'])-a['from'],[4,0,0])
    def test_texture_hashes_and_all_bounds_still_loader_legal(self):
        self.assertEqual(8,len(self.report['texture_sha256']))
        for e in self.model['elements']:
            self.assertTrue(np.all(np.array(e['to'])>e['from']))
            self.assertGreaterEqual(min(e['from']),-16);self.assertLessEqual(max(e['to']),32)
    def test_installed_model_exactly_matches_builder(self):
        self.assertEqual(sha(encoded(self.model)),sha((ASSETS/'models/block/home_wide_lcd_tv.json').read_bytes()))
        self.assertEqual(self.model,build())

if __name__=='__main__':unittest.main()
