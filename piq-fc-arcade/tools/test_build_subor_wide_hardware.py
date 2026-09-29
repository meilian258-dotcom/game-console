import copy
import json
from pathlib import Path
import tempfile
import unittest
import numpy as np
from PIL import Image
from build_subor_wide_hardware import (audit,build,card_quads,CARD_ANCHOR,CARD_SCALE,CONTACT_CUTS,
    face,FLOOR_Y,GROUPS,HINGE,LID_ANGLE,NEW_MESH,OLD_MESH_PATH,OLD_MESH_SHA,OPENING,POWER,
    RAISE,SCALE,SOCKETS,source_point,top_y,triangle_bounds,vertical_hits,PLANAR_SCALE,SLOT_SHIFT_Z,
    compact_axis,badge_point,load_baseline,part_triangles)
from import_subor_hardware import (encoded,load_source,sha,TEXTURE,TEXTURE_SHA,triangles,
                                   transform_element,write_new)


class SuborWideHardwareTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source,cls.png=load_source();cls.old=json.loads(OLD_MESH_PATH.read_bytes())
        cls.mesh,cls.parts,_=build();cls.report=audit(cls.mesh,cls.parts)

    def test_original_mesh_and_texture_are_frozen(self):
        self.assertEqual(OLD_MESH_SHA,sha(OLD_MESH_PATH.read_bytes()))
        self.assertEqual(TEXTURE_SHA,sha(TEXTURE.read_bytes()))
        self.assertEqual(self.old['texture'],self.mesh['texture'])
        self.assertNotEqual(OLD_MESH_PATH,NEW_MESH)

    def test_seven_groups_keep_alpha5_held_triangles_exactly(self):
        self.assertEqual(list(GROUPS),list(self.mesh['groups']))
        for name in ('p1_held','p2_held'):
            self.assertEqual(self.old['groups'][name]['triangles'],self.mesh['groups'][name]['triangles'])
            self.assertEqual(self.old['groups'][name]['bounds'],self.mesh['groups'][name]['bounds'])
        self.assertTrue(self.mesh['metadata']['lid']['mutually_exclusive'])

    def test_all_101_keys_keep_v4_height_and_original_uv_while_chassis_planar_compacts(self):
        named=dict(self.parts['body']);keys=[e for e in self.source['elements'] if e['name'].startswith('键帽')]
        self.assertEqual(101,len(keys))
        for e in keys+[e for e in self.source['elements'] if e['name'] in ('品牌','型号','英文标')]:
            wanted=triangles([transform_element(e,lambda p:badge_point(e['name'],source_point(p)))])
            self.assertEqual(wanted,named[e['name']])
        self.assertEqual(.94,SCALE);self.assertEqual(1.25,RAISE)
        self.assertEqual(.8,PLANAR_SCALE)

    def test_lower_shell_slimmed_without_moving_feet(self):
        named=dict(self.parts['body'])
        lower=np.array(triangle_bounds(named['下壳']))
        self.assertAlmostEqual(.2068,lower[0,1]);self.assertAlmostEqual(1.9456,lower[1,1])
        foot=[t for name,values in self.parts['body'] if name.startswith('橡胶脚') for t in values]
        self.assertAlmostEqual(0,triangle_bounds(foot)[0][1])
        self.assertAlmostEqual(3.2580092,self.mesh['metadata']['world_body_bounds'][1][1])

    def test_shipped_alpha7_planar_parts_compact_without_changing_height(self):
        result=self.report['shipped_alpha7_comparison']
        self.assertEqual(106,result['planar_upper_parts_checked'])
        self.assertEqual(5,len(result['shell_vertical_sections']))
        self.assertTrue(result['held_unchanged_and_docked_translation_only']);self.assertTrue(result['card_size_unchanged'])
        self.assertAlmostEqual(0,result['body_height_before']-result['body_height_after'])
        self.assertAlmostEqual(.8,result['body_width_after']/result['body_width_before'])

    def test_actual_bounds_fit_two_by_two_with_card_under_one_block_high(self):
        for state in ('closed','open'):
            b=np.array(self.mesh['metadata']['world_'+state+'_bounds'])
            self.assertTrue((b[0]>=0).all());self.assertLess(b[1,0],32);self.assertLess(b[1,2],32)
            self.assertLess(b[1,1],6.3)
        b=np.array(self.mesh['metadata']['world_body_bounds'])
        self.assertAlmostEqual(24.54528,b[1,0]-b[0,0])
        self.assertLess(self.mesh['metadata']['anchors']['cartridge_inserted_bounds'][1][1],6.3)

    def test_top_and_inner_shell_caps_really_omit_slot(self):
        self.assertEqual(35,len(self.report['slot_vertical_ray_tests']))
        self.assertTrue(all(row['depth_below_deck']>1 for row in self.report['slot_vertical_ray_tests']))
        self.assertTrue(all(row['top_hit_y']<=FLOOR_Y+1e-8 for row in self.report['slot_vertical_ray_tests']))
        names=dict(self.parts['body'])
        for name in ('上盖顶面·物理切除卡槽','上盖内底面·物理切除卡槽','下壳顶面·物理切除卡槽'):
            self.assertEqual([],vertical_hits(names[name],16,22.164))

    def test_recess_audit_rejects_a_black_plane_over_the_hole(self):
        broken=copy.deepcopy(self.mesh)
        broken['groups']['body']['triangles']+=face([(11.45,4.1,21.359),(20.55,4.1,21.359),
            (20.55,4.1,23.009),(11.45,4.1,23.009)],self.mesh['metadata']['material_uv_samples']['dark'],[0,1,0])
        with self.assertRaises(ValueError):audit(broken,self.parts)

    def test_recess_has_four_walls_and_closed_contact_end_boundaries(self):
        names=dict(self.parts['body'])
        for name in ('卡槽左内侧壁','卡槽右内侧壁','卡槽前内侧壁','卡槽后内侧壁'):
            self.assertEqual(2,len(names[name]))
        self.assertGreaterEqual(sum(name.startswith('接触口侧端壁') for name in names),6)
        self.assertLess(max(vertical_hits(self.mesh['groups']['body']['triangles'],16,22.164)),FLOOR_Y)

    def test_closed_lid_covers_hole_and_open_is_exact_rear_hinge_rotation(self):
        closed=self.mesh['groups']['lid_closed']['triangles'];opened=self.mesh['groups']['lid_open']['triangles']
        self.assertEqual(len(closed),len(opened))
        a=np.deg2rad(LID_ANGLE);c,s=np.cos(a),np.sin(a);matrix=np.array([[1,0,0],[0,c,-s],[0,s,c]])
        for old,new in zip(closed,opened):
            np.testing.assert_allclose((np.array(old['p'])-HINGE)@matrix.T+HINGE,new['p'],atol=1e-8)
            self.assertEqual(old['uv'],new['uv'])
        self.assertGreater(max(vertical_hits(closed,16,21.859)),2.8)
        self.assertEqual([],vertical_hits(opened,16,21.859))

    def test_original_card_is_point_six_and_passes_real_connector_apertures(self):
        self.assertEqual(.60,CARD_SCALE);np.testing.assert_array_equal(CARD_ANCHOR,[16,1.52,22.164])
        actual=np.concatenate([q.vertices for q in card_quads()])
        np.testing.assert_allclose([actual.min(0),actual.max(0)],self.mesh['metadata']['anchors']['cartridge_inserted_bounds'],atol=1e-8)
        self.assertGreater(self.report['floor_card_crossing_points_checked'],1000)
        self.assertGreater(self.report['open_lid_card_z_clearance'],.35)

    def test_rca_yellow_white_red_and_independent_power_are_real_recesses(self):
        body=self.mesh['groups']['body']['triangles']
        # Swap Y/Z so the same exact-triangle ray routine looks into the rear panel from +Z.
        rear=[dict(t,p=np.array(t['p'])[:,[0,2,1]].tolist()) for t in body]
        for x,y,z in (*SOCKETS,POWER):
            self.assertAlmostEqual(z-.37,max(vertical_hits(rear,x,y)),places=7)
        uv=self.mesh['metadata']['material_uv_samples'];image=np.array(Image.open(TEXTURE).convert('RGBA'))
        samples={name:image[int(pair[1]*2048),int(pair[0]*2048),:3].tolist() for name,pair in uv.items()}
        self.assertEqual([214,173,55],samples['yellow']);self.assertEqual([228,226,200],samples['white'])
        self.assertEqual([217,82,64],samples['red']);self.assertNotIn(POWER,SOCKETS)
        self.assertEqual([1.6,1.6],list(np.round(-np.diff([v[0] for v in SOCKETS]),9)))

    def test_docked_controllers_match_fc_size_and_lie_in_front_of_keyboard(self):
        for port in (0,1):
            controller=dict(self.parts[f'p{port+1}_docked'])['原尺寸手柄桌面摆位'];bounds=np.array(triangle_bounds(controller))
            self.assertAlmostEqual(7.614,bounds[1,0]-bounds[0,0])
            self.assertLess(bounds[1,2],self.mesh['metadata']['world_body_bounds'][0][2])
            self.assertGreater(bounds[0,1],0)
        self.assertEqual([],self.report['findings'])
        self.assertGreater(self.report['cord_triangle_vs_part_aabb_checks'],130000)

    def test_full_size_slot_lids_and_contact_apertures_translate_without_shrinking(self):
        baseline,_=load_baseline()
        before=baseline['metadata']['opening_xz'];after=self.mesh['metadata']['opening_xz']
        np.testing.assert_allclose(np.array(before)+[0,SLOT_SHIFT_Z,0,SLOT_SHIFT_Z],after,atol=1e-9)
        for name in ('lid_closed','lid_open'):
            for a,b in zip(baseline['groups'][name]['triangles'],self.mesh['groups'][name]['triangles']):
                np.testing.assert_allclose(np.array(a['p'])+[0,0,SLOT_SHIFT_Z],b['p'],atol=1e-8)
                self.assertEqual(a['uv'],b['uv'])

    def test_repositioned_badges_do_not_cover_the_unshrunk_slot(self):
        for name in ('型号','品牌'):
            bounds=np.array(triangle_bounds(part_triangles(self.mesh,'body',name)))
            self.assertTrue(bounds[1,0]<OPENING[0] or bounds[0,0]>OPENING[2])
        for x in (11.46,11.7,20.52,20.54):
            self.assertLessEqual(max(vertical_hits(self.mesh['groups']['body']['triangles'],x,22.164)),FLOOR_Y)

    def test_all_triangles_are_finite_non_degenerate_with_unit_matching_normals(self):
        for group in self.mesh['groups'].values():
            self.assertEqual(len(group['triangles']),group['triangle_count'])
            for t in group['triangles']:
                p,uv,n=np.array(t['p']),np.array(t['uv']),np.array(t['n'])
                self.assertTrue(np.isfinite(p).all() and np.isfinite(uv).all())
                self.assertGreaterEqual(uv.min(),0);self.assertLessEqual(uv.max(),1)
                actual=np.cross(p[1]-p[0],p[2]-p[0]);self.assertGreater(np.linalg.norm(actual),1e-12)
                np.testing.assert_allclose(actual/np.linalg.norm(actual),n,atol=1e-8)

    def test_all_uvs_are_original_or_new_existing_uniform_material_samples(self):
        allowed={tuple(v) for e in self.source['elements'] for f in e['faces'].values() for v in f['uv'].values()}
        samples={tuple(np.array(v)*2048) for v in self.mesh['metadata']['material_uv_samples'].values()}
        for group in self.mesh['groups'].values():
            for t in group['triangles']:
                for uv in t['uv']:
                    self.assertIn(tuple(np.array(uv)*2048),allowed|samples)

    def test_build_is_deterministic_and_never_overwrites_different_existing_files(self):
        again,_,_=build();self.assertEqual(encoded(self.mesh),encoded(again))
        with tempfile.TemporaryDirectory() as directory:
            old,new=Path(directory)/'keep',Path(directory)/'new';old.write_bytes(b'keep')
            with self.assertRaises(FileExistsError):write_new({new:b'new',old:b'change'})
            self.assertEqual(b'keep',old.read_bytes());self.assertFalse(new.exists())

    def test_one_block_reference_is_exact_size_and_faces_up(self):
        from build_subor_wide_hardware import REFERENCE
        np.testing.assert_array_equal(REFERENCE.max(0)-REFERENCE.min(0),[16,0,16])
        self.assertGreater(np.cross(REFERENCE[1]-REFERENCE[0],REFERENCE[2]-REFERENCE[0])[1],0)


if __name__=='__main__':unittest.main()
