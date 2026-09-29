import unittest
import numpy as np
from build_lcd_tv_model import build,audit,cube,SOCKETS,SCREEN
from render_rocket_arcade_preview import collect_quads


class LcdTvModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model=build();cls.report,cls.textures=audit(cls.model)

    def test_bounds_are_one_block_wide_and_thin(self):
        self.assertEqual([[0,0,5],[16,13,11]],self.report['bounds'])

    def test_screen_is_exact_four_by_three_north_quad(self):
        p=np.array(self.report['screen_quad']);np.testing.assert_allclose(p.min(0),[1,1.5,6]);np.testing.assert_allclose(p.max(0),[15,12,6])
        self.assertAlmostEqual(4/3,self.report['screen_aspect']);self.assertLess(np.cross(p[1]-p[0],p[2]-p[0])[2],0)

    def test_black_screen_and_original_solid_textures(self):
        self.assertTrue(np.all(self.textures['screen']==[0,0,0,255]));self.assertEqual(8,len(self.report['texture_sha256']))

    def test_ports_match_runtime_three_plug_centers(self):
        self.assertEqual(((5,4,8.23),(8,4,8.23),(11,4,8.23)),SOCKETS)
        self.assertEqual([0,0,1],self.report['rca_outward'])

    def test_ports_have_real_recess_and_no_rear_sheet_at_center(self):
        for x,y,z in SOCKETS:
            frontmost=[]
            for e in self.model['elements']:
                a,b=e['from'],e['to']
                if a[0]<x<b[0] and a[1]<y<b[1]:frontmost.append(b[2])
            self.assertAlmostEqual(7.68,max(frontmost));self.assertGreater(z-max(frontmost),.5)

    def test_stand_does_not_cover_screen_or_connector_centers(self):
        stand=[e for e in self.model['elements'] if e['name'] in ('窄后支柱','薄桌面底座')]
        self.assertTrue(all(e['to'][1]<4 for e in stand))
        self.assertLess(stand[-1]['to'][1],SCREEN[1])

    def test_all_cube_bounds_and_uvs_are_loader_legal(self):
        for e in self.model['elements']:
            a,b=np.array(e['from']),np.array(e['to']);self.assertTrue((a<b).all());self.assertGreaterEqual(a.min(),-16);self.assertLessEqual(b.max(),32)
            for f in e['faces'].values():self.assertEqual([0,0,16,16],f['uv']);self.assertIn(f['texture'][1:],self.textures)

    def test_builder_is_deterministic(self):
        self.assertEqual(self.model,build())


if __name__=='__main__':unittest.main()
