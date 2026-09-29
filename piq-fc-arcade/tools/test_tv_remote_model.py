import copy,json,unittest,zipfile
import numpy as np
from build_tv_remote_model import MODEL,SOURCES,build,audit,displays,first_matrix,third_matrix,load_textures
from check_controller_pose_pipeline import points,projected_bounds,display_matrix,translation
from render_rocket_arcade_preview import collect_quads
from check_cartridge_computer_model import exposed_overlaps
from import_subor_hardware import encoded,sha

class TvRemoteModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw=MODEL.read_bytes();cls.model=json.loads(cls.raw);cls.report,cls.textures,cls.gui=audit(cls.model);cls.vertices=np.concatenate([q.vertices for q in collect_quads(cls.model)])
    def test_installed_native_model_matches_generator_and_frozen_hash(self):
        self.assertEqual(self.raw,encoded(build()));self.assertEqual('6620E178C8C03A2826B980F4F6BB0E9C41342D01CAF9140C6859146B45F27FD7',sha(self.raw));self.assertNotIn('parent',self.model)
    def test_complete_geometry_is_small_three_dimensional_remote(self):
        np.testing.assert_allclose(self.report['bounds'],[[5.7,7.243,2.1],[10.3,8.67,13.9]],atol=1e-12)
        self.assertEqual(59,self.report['elements']);self.assertEqual(314,self.report['faces']);self.assertGreater(self.vertices[:,1].ptp() if hasattr(self.vertices[:,1],'ptp') else np.ptp(self.vertices[:,1]),1.4)
    def test_seven_display_contexts_are_explicit_and_sized(self):
        self.assertEqual(set(displays()),set(self.model['display']));self.assertEqual('front',self.model['gui_light'])
        for context,d in self.model['display'].items():self.assertTrue(all(0<s<=1.1 for s in d['scale']),context)
        self.assertEqual(self.model['display']['firstperson_righthand'],self.model['display']['firstperson_lefthand'])
    def test_all_textures_are_existing_byte_frozen_solid_crt_palette(self):
        self.assertEqual(7,len(self.report['texture_sha256']))
        for ref,pixels in self.textures.items():
            self.assertTrue(ref.startswith('piq_fc_arcade:block/home_retro_tv_'));self.assertEqual(1,len(np.unique(pixels.reshape(-1,4),axis=0)));self.assertTrue(np.all(pixels[:,:,3]==255))
    def test_scan_symbol_and_red_key_do_not_claim_unimplemented_functions(self):
        names=[e['name'] for e in self.model['elements']]
        self.assertEqual(3,names.count('三行扫描线图标'));self.assertEqual(3,len([n for n in names if n.startswith('红色小键')]))
        self.assertFalse(any(any(word in n for word in ('电源','频道','音量','POWER','VOLUME')) for n in names))
    def test_fourteen_actual_geometry_and_pose_checks_pass(self):
        self.assertTrue(self.report['ok'],self.report['checks']);self.assertEqual(14,len(self.report['checks']));self.assertFalse(self.report['exposed_coplanar_overlaps'])
    def test_gui_uses_real_unfitted_matrix_and_fits_safely(self):
        p=points(self.vertices/16,self.gui)[:,:2]*[16,-16]+8
        self.assertGreater(p.min(),1);self.assertLess(p.max(),15)
        np.testing.assert_allclose([p.min(0),p.max(0)],self.report['gui_pixel_bounds'])
    def test_oversized_gui_is_rejected(self):
        altered=copy.deepcopy(self.model);altered['display']['gui']['scale']=[4,4,4]
        report,_,_=audit(altered);self.assertFalse(next(c['ok'] for c in report['checks'] if c['name'].startswith('true vanilla GUI')))
    def test_idle_left_and_right_are_symmetric_and_buttons_visible(self):
        r,l=self.report['idle_first_person'];np.testing.assert_allclose(r['bounds'],[1-l['bounds'][2],l['bounds'][1],1-l['bounds'][0],l['bounds'][3]],atol=1e-12)
        self.assertGreater(r['button_facing_cosine'],.8);self.assertGreater(r['bounds'][1],.57);self.assertLess(r['bounds'][3],.9)
    def test_wrong_first_person_tilt_is_rejected(self):
        altered=copy.deepcopy(self.model);altered['display']['firstperson_righthand']['rotation'][0]=-55
        report,_,_=audit(altered);self.assertFalse(next(c['ok'] for c in report['checks'] if c['name'].startswith('right first-person buttons')))
    def test_swing_is_vanilla_and_only_idle_framing_is_promised(self):
        self.assertEqual(56,len(self.report['first_person_cases']));self.assertTrue(all(c['inside'] for c in self.report['first_person_cases'] if c['swing']==0))
        self.assertTrue(any(not c['inside'] for c in self.report['first_person_cases'] if c['swing']!=0))
        self.assertTrue(any('beyond the viewport' in s for s in self.report['limitations']))
    def test_third_person_supports_normal_and_slim_both_hands(self):
        self.assertEqual({(True,True),(True,False),(False,True),(False,False)},{(c['right'],c['slim']) for c in self.report['third_person_cases']})
        self.assertTrue(all(c['button_normal'][1]<-.45 for c in self.report['third_person_cases']))
    def test_duplicate_visible_face_is_caught(self):
        altered=copy.deepcopy(self.model);altered['elements'].append(copy.deepcopy(next(e for e in altered['elements'] if e['name']=='三行扫描线图标')))
        self.assertTrue(exposed_overlaps(collect_quads(altered)))
    def test_real_item_transform_mirrors_yz_rotations_and_translation_before_centering(self):
        with zipfile.ZipFile(SOURCES) as z:source=z.read('net/minecraft/client/renderer/block/model/ItemTransform.java').decode()
        for s in ('f1 = -f1','f2 = -f2','(float)i * this.translation.x()','rotationXYZ','scale(this.scale.x(), this.scale.y(), this.scale.z())'):self.assertIn(s,source)
        self.assertLess(source.index('translate((float)i'),source.index('rotationXYZ'));self.assertLess(source.index('rotationXYZ'),source.index('scale(this.scale.x()'))

if __name__=='__main__':unittest.main()
