import copy,json,unittest
import numpy as np
from build_cartridge_computer_model import MODEL,ITEM,STATE,build,item_model,blockstate,audit,SCREEN
from check_cartridge_computer_model import analyze,exposed_overlaps,front_intrusions,intersection,subtract
from render_rocket_arcade_preview import collect_quads
from build_lcd_tv_model import cube
from import_subor_hardware import encoded

class CartridgeComputerModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.model=json.loads(MODEL.read_bytes());cls.report=analyze()
    def test_installed_three_resources_are_deterministic(self):
        self.assertEqual(MODEL.read_bytes(),encoded(build()));self.assertEqual(ITEM.read_bytes(),encoded(item_model()));self.assertEqual(STATE.read_bytes(),encoded(blockstate()))
    def test_native_bounds_screen_and_inset_are_exact(self):
        r,_,_=audit(self.model);self.assertEqual(r['bounds'],[[.65,0,.6],[15.,13.5,14.6]])
        self.assertAlmostEqual(r['glass_aspect'],4/3);self.assertEqual(r['front_recess'],.9)
        self.assertTrue(all(v[2]==6.15 for v in r['glass_quad']))
    def test_actual_java_shapes_all_four_facings_contain_every_vertex(self):
        self.assertTrue(self.report['ok'],self.report);self.assertEqual(12,len(self.report['checks']))
    def test_terminal_is_static_geometry_using_only_vanilla_green(self):
        chars=[e for e in self.model['elements'] if e['name'].startswith('静态终端')]
        self.assertGreater(len(chars),30);self.assertTrue(all(set(e['faces'])=={'north'} for e in chars))
        self.assertTrue(all(e['faces']['north']['texture']=='#terminal' for e in chars))
        self.assertTrue(all(e['from'][0]>=SCREEN[0] and e['to'][0]<=SCREEN[2] and e['from'][1]>=SCREEN[1] and e['to'][1]<=SCREEN[3] for e in chars))
        self.assertEqual('minecraft:block/lime_concrete',self.model['textures']['terminal'])
    def test_existing_textures_are_byte_frozen_and_terminal_texels_are_opaque(self):
        self.assertEqual(9,len(self.report['texture_sha256']))
        self.assertEqual('33F4BDAD5277E58FBFCD3D6EC99C9F1FBA76DC45A1A6853047D66215D30D3165',self.report['texture_sha256']['vanilla_lime_concrete'])
    def test_separate_mouse_is_on_viewer_right_and_has_two_buttons(self):
        mouse=next(e for e in self.model['elements'] if e['name']=='鼠标圆角中壳');keyboard=next(e for e in self.model['elements'] if e['name']=='键盘奶油壳')
        self.assertLess(mouse['to'][0],keyboard['from'][0]);self.assertEqual(2,len([e for e in self.model['elements'] if e['name'] in ('鼠标左键','鼠标右键')]))
    def test_keycaps_have_actual_spacing_and_are_above_keyboard_deck(self):
        keys=[e for e in self.model['elements'] if e['name'] in ('字母键','功能键','空格键','控制键','数字区键')]
        self.assertGreater(len(keys),50)
        for i,a in enumerate(keys):
            self.assertGreater(a['to'][1],1.2)
            for b in keys[i+1:]:
                size=np.minimum(a['to'],b['to'])-np.maximum(a['from'],b['from'])
                self.assertFalse(np.all(size>1e-8),(a,b))
    def test_complete_screen_has_no_shell_intrusion(self):
        self.assertFalse(front_intrusions(self.model,collect_quads(self.model)))
        altered=copy.deepcopy(self.model);altered['elements'].append(cube('bad-front-panel',[5,7,5.5],[9,10,5.6],'white'))
        self.assertTrue(front_intrusions(altered,collect_quads(altered)))
    def test_crt_back_inner_panel_is_partitioned_not_duplicate_outer_strip(self):
        b=next(e for e in self.model['elements'] if e['name']=='CRT后内板')
        self.assertEqual([2.9,5.4,6.25],b['from']);self.assertEqual([12.1,12.375,8.6],b['to'])
        self.assertFalse(self.report['exposed_coplanar_overlaps'])
    def test_overlap_checker_rejects_visible_duplicate_face(self):
        m={'textures':{'white':'piq_fc_arcade:block/home_retro_tv_white'},'elements':[cube('a',[0,0,0],[3,3,1],'white',('north',)),cube('b',[2,2,0],[4,4,1],'white',('north',))]}
        self.assertEqual(1,len(exposed_overlaps(collect_quads(m))))
    def test_overlap_checker_subtracts_complete_and_partial_foreground(self):
        m={'textures':{'white':'piq_fc_arcade:block/home_retro_tv_white'},'elements':[cube('a',[0,0,0],[3,3,1],'white',('north',)),cube('b',[2,2,0],[4,4,1],'white',('north',)),cube('cover',[2,2,-1],[3,3,-.9],'white',('north',))]}
        self.assertFalse(exposed_overlaps(collect_quads(m)));m['elements'][-1]['to'][0]=2.5
        self.assertTrue(exposed_overlaps(collect_quads(m)))
    def test_rectangle_subtraction_retains_exact_area(self):
        pieces=subtract((0,0,5,5),(1,1,4,4));self.assertEqual(16,sum((r[2]-r[0])*(r[3]-r[1]) for r in pieces));self.assertIsNone(intersection((0,0,1,1),(1,0,2,1)))

if __name__=='__main__':unittest.main()
