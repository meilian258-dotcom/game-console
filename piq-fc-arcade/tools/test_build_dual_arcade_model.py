import copy
import unittest
import numpy as np
from build_dual_arcade_model import build,build_alpha6,build_alpha7,build_alpha8,audit,inputs,world_quads,SCALE,donor_index,PIVOT,encoded,sha,OLD_BODY_SHA,ALPHA7_BODY_SHA,ALPHA8_BODY_SHA,MODEL_Y_OFFSET
from render_rocket_arcade_preview import collect_quads


class DualArcadeModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source,cls.legacy=inputs();cls.model,cls.mapping=build();cls.report=audit(cls.model,cls.mapping)

    def test_two_wide_taller_header_keeps_single_cabinet_depth(self):
        np.testing.assert_allclose(self.report['world_bounds_north'],[[.6,0,.3820101013],[31.4,37.6,14.65]],atol=1e-8)
        self.assertEqual(1,SCALE)

    def test_227_legal_vanilla_elements(self):
        self.assertEqual(227,len(self.model['elements']))
        for e in self.model['elements']:
            self.assertGreaterEqual(min(e['from']),-16);self.assertLessEqual(max(e['to']),32)
            self.assertIn(e.get('rotation',{}).get('angle',0),(-45,0,22.5,45))

    def test_physical_screen_really_is_16_by_9_not_stretched_game(self):
        p=np.array(self.report['screen_quad_world_units'])
        self.assertAlmostEqual(16/9,np.linalg.norm(p[1]-p[2])/np.linalg.norm(p[0]-p[1]))
        self.assertAlmostEqual(24,np.linalg.norm(p[1]-p[2]))
        self.assertAlmostEqual(13.5,np.linalg.norm(p[0]-p[1]))
        np.testing.assert_allclose(self.report['screen_normal'],[0,.382683432365,-.923879532511],atol=1e-10)

    def test_every_uv_is_exact_existing_atlas_mapping(self):
        for i,e in enumerate(self.model['elements']):self.assertEqual(self.legacy['elements'][self.mapping[i]]['faces'],e['faces'])
        self.assertEqual(self.legacy['textures'],self.model['textures'])

    def test_both_67_piece_controls_reuse_single_cabinet_rigidly(self):
        old=collect_quads(self.legacy);new=world_quads(self.model)
        for start,x in ((68,16),(135,0)):
            for i in range(start,start+67):
                a=[q.vertices for q in old if q.element_index==donor_index(i)]
                b=[q.vertices for q in new if q.element_index==i]
                np.testing.assert_allclose(b,np.array(a)+[x,.035,0],atol=2e-9)

    def test_control_surface_and_front_edge_align_with_single(self):
        old=collect_quads(self.legacy);new=world_quads(self.model)
        for i in (36,37,38,39):
            a=np.concatenate([q.vertices for q in old if q.element_index==i])
            b=np.concatenate([q.vertices for q in new if q.element_index==i])
            np.testing.assert_allclose(a[:,1:].min(0),b[:,1:].min(0),atol=1e-9)
            np.testing.assert_allclose(a[:,1:].max(0),b[:,1:].max(0),atol=1e-9)

    def test_buttons_are_seated_on_top_not_embedded(self):
        q=world_quads(self.model)
        controls=np.concatenate([a.vertices for a in q if 68<=a.element_index<202])
        self.assertAlmostEqual(16.245,controls[:,1].min())
        self.assertGreaterEqual(controls[:,2].min(),.9)
        self.assertLess(controls[:,2].max(),5.05)

    def test_thin_screen_borders_leave_no_massive_side_letterboxing(self):
        e=self.model['elements']
        self.assertAlmostEqual(.65,e[42]['to'][0]-e[42]['from'][0])
        self.assertAlmostEqual(.65,e[43]['to'][0]-e[43]['from'][0])
        self.assertEqual([4,28],[e[47]['from'][0],e[47]['to'][0]])

    def test_marquee_top_aligned_and_lettering_not_distorted(self):
        old=self.legacy['elements'][54];new=self.model['elements'][54]
        self.assertAlmostEqual((old['to'][0]-old['from'][0])/(old['to'][1]-old['from'][1]),
                               (new['to'][0]-new['from'][0])/(new['to'][1]-new['from'][1]),places=8)
        self.assertEqual(32,self.model['elements'][49]['to'][1])
        self.assertAlmostEqual(16,new['to'][0]-new['from'][0])
        self.assertGreater(new['to'][1]-new['from'][1],3.5)
        self.assertGreater(self.model['elements'][48]['to'][1]-self.model['elements'][48]['from'][1],4.5)

    def test_only_header_changed_in_world_space(self):
        old=world_quads(build_alpha8()[0]);new=world_quads(self.model)
        allowed={4,9,17,21,23,31,35}|set(range(48,68))
        for a,b in zip(old,new):
            if a.element_index not in allowed:np.testing.assert_allclose(a.vertices,b.vertices,atol=1e-10)
        self.assertAlmostEqual(-5.6,min(e['from'][1] for e in self.model['elements']))
        self.assertEqual(5.6,MODEL_Y_OFFSET)

    def test_tall_header_is_six_sided_and_connected_to_back_and_speakers(self):
        e=self.model['elements'];header=e[48];top=e[49];back=e[4];speaker=e[55]
        self.assertEqual({'north','south','east','west','up','down'},set(header['faces']))
        self.assertGreaterEqual(header['to'][1],top['from'][1])
        self.assertGreaterEqual(back['to'][1],top['from'][1])
        self.assertGreaterEqual(speaker['to'][1],header['from'][1])
        self.assertGreaterEqual(speaker['to'][2],back['from'][2])
        for side in (17,31):
            self.assertLess(e[side]['from'][1],speaker['from'][1])
            self.assertGreaterEqual(e[side]['to'][1],top['from'][1])

    def test_rotation_matches_anchor_not_cabinet_center(self):
        p=world_quads(self.model)[0].vertices;q=world_quads(self.model,1)[0].vertices
        np.testing.assert_allclose(q[:,0],16-p[:,2]);np.testing.assert_allclose(q[:,2],p[:,0])
        np.testing.assert_array_equal(PIVOT,[8,0,8])

    def test_old_alpha6_alpha7_alpha8_builds_stay_exact(self):
        self.assertEqual(OLD_BODY_SHA,sha(encoded(build_alpha6()[0])))
        self.assertEqual(ALPHA7_BODY_SHA,sha(encoded(build_alpha7()[0])))
        self.assertEqual(ALPHA8_BODY_SHA,sha(encoded(build_alpha8()[0])))

    def test_changed_uv_rejected(self):
        altered=copy.deepcopy(self.model);altered['elements'][47]['faces']['north']['uv'][0]+=1
        with self.assertRaisesRegex(ValueError,'UV'):audit(altered,self.mapping)

    def test_deterministic_inventory(self):
        self.assertEqual((self.model,self.mapping),build())
        self.assertEqual(list(range(78,145)),[donor_index(i) for i in range(68,135)])


if __name__=='__main__':unittest.main()
