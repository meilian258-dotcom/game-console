import copy
import json
from pathlib import Path
import tempfile
import unittest
import numpy as np
from import_subor_hardware import (build, bounds, cable_centers, encoded, GROUPS, HELD_SCALE,
                                  inserted_card_quads, load_source, routed_cable, sha, SOURCE_SHA,
                                  TEXTURE_SHA, triangles, world, write_new)


class SuborHardwareImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source, cls.png = load_source()
        cls.mesh, cls.docs, _ = build()
        cls.original = {e['uuid']: e for e in cls.source['elements']}
        cls.v2 = next(iter(cls.docs.values()))

    def test_frozen_source_and_embedded_atlas_unchanged(self):
        self.assertEqual(TEXTURE_SHA, sha(self.png))
        self.assertEqual(SOURCE_SHA, self.mesh['metadata']['source_sha256'])
        for doc in self.docs.values():
            self.assertEqual(self.source['textures'], doc['textures'])
        with tempfile.TemporaryDirectory() as directory:
            source, texture = Path(directory)/'source.json', Path(directory)/'texture.png'
            source.write_bytes(encoded(self.source)); texture.write_bytes(self.png)
            with self.assertRaises(ValueError):
                load_source(source, texture)

    def test_five_runtime_groups_are_complete_and_no_decorative_card_is_baked(self):
        self.assertEqual(list(GROUPS), list(self.mesh['groups']))
        self.assertEqual([2124,636,636,300,300], [v['triangle_count'] for v in self.mesh['groups'].values()])
        self.assertEqual([174,14,14,13,13], [v['elements'] for v in self.mesh['groups'].values()])
        self.assertTrue(self.mesh['metadata']['excluded_runtime_source_group'].startswith('07 '))
        self.assertFalse(next(g for g in self.v2['outliner'] if g['name'].startswith('07 '))['visibility'])

    def test_body_vertices_are_only_rigid_uniform_transform_and_all_original_uvs_survive(self):
        body_ids = {u for g in self.source['outliner'][:6] for u in g['children']}
        for e in self.v2['elements']:
            original = self.original[e['uuid']]
            self.assertEqual(original['faces'], e['faces'])
            if e['uuid'] in body_ids:
                for key, point in e['vertices'].items():
                    np.testing.assert_allclose(point, world(original['vertices'][key]), atol=1e-9)
        self.assertEqual(101, sum(e['name'].startswith('键帽') for e in self.v2['elements']))

    def test_triangles_have_finite_unit_outward_winding_and_exact_normalized_uvs(self):
        allowed_uv = {tuple(uv) for e in self.source['elements'] for f in e['faces'].values() for uv in f['uv'].values()}
        for group in self.mesh['groups'].values():
            for triangle in group['triangles']:
                p, uv, normal = np.array(triangle['p']), np.array(triangle['uv']), np.array(triangle['n'])
                self.assertTrue(np.isfinite(p).all() and np.isfinite(uv).all())
                cross = np.cross(p[1]-p[0], p[2]-p[0])
                self.assertGreater(np.linalg.norm(cross), 1e-12)
                np.testing.assert_allclose(cross/np.linalg.norm(cross), normal, atol=1e-8)
                for pair in uv:
                    self.assertIn(tuple(pair*2048), allowed_uv)

    def test_world_bounds_inside_single_block_for_all_four_facings(self):
        self.assertEqual([[.32075,0,3.3675],[15.67925,.967725,12.6375]], self.mesh['metadata']['world_all_bounds'])
        p = np.concatenate([np.array([v for t in self.mesh['groups'][name]['triangles'] for v in t['p']]) for name in GROUPS[:3]])
        for _ in range(4):
            self.assertGreaterEqual(p.min(), 0)
            self.assertLessEqual(p.max(), 16)
            p = np.c_[16-p[:,2],p[:,1],p[:,0]]

    def test_controller_placement_is_in_front_parallel_and_symmetric(self):
        by_id = {e['uuid']:e for e in self.v2['elements']}
        low_high = []
        for g in self.v2['outliner'][7:]:
            pieces = [by_id[u] for u in g['children'] if by_id[u]['name'] != '独立手柄线']
            box = np.array(bounds(pieces)); low_high.append(box)
            self.assertLess(box[1,2], self.mesh['metadata']['world_body_bounds'][0][2])
        self.assertAlmostEqual(low_high[0][:,0].mean()+low_high[1][:,0].mean(),16)
        np.testing.assert_allclose(low_high[0][:,2], low_high[1][:,2])

    def test_cords_are_external_with_original_topology_and_outward_normals(self):
        self.assertEqual(0, self.mesh['metadata']['cable_clearance']['possible_intersections'])
        self.assertEqual(672, self.mesh['metadata']['cable_clearance']['tested_cord_triangles'])
        source_cables = [e for e in self.source['elements'] if e['name']=='独立手柄线']
        for port, old in enumerate(source_cables):
            new = routed_cable(old, port)
            self.assertEqual(old['faces'], new['faces'])
            centers = cable_centers(port)
            self.assertEqual((29,3), centers.shape)
            p = np.array(list(new['vertices'].values())).reshape(29,6,3)
            np.testing.assert_allclose(np.linalg.norm(p-centers[:,None,:],axis=2), .065, atol=1e-8)
            for face in new['faces'].values():
                ids=face['vertices'][:3]; v=np.array([new['vertices'][k] for k in ids])
                c=np.mean([centers[int(k[1:])//6] for k in ids],axis=0)
                self.assertGreater(float(np.cross(v[1]-v[0],v[2]-v[0])@(v.mean(0)-c)),0)

    def test_held_is_canonical_centered_proportional_and_no_wire(self):
        for port in (0,1):
            doc = self.docs[f'小霸王SB926_P{port+1}手持v2.bbmodel']
            self.assertFalse(any(e['name']=='独立手柄线' for e in doc['elements']))
            box=np.array(bounds(doc['elements']))
            np.testing.assert_allclose(box.mean(0),[8,8,8],atol=1e-8)
            self.assertAlmostEqual(12.69,box[1,0]-box[0,0])
            self.assertAlmostEqual(3.57*HELD_SCALE,box[1,1]-box[0,1])
            dpad=next(e for e in doc['elements'] if e['name']=='十字键横臂')
            a=next(e for e in doc['elements'] if e['name']=='AB1')
            self.assertLess(np.mean(np.array(bounds([dpad]))[:,0]),8)
            self.assertGreater(np.mean(np.array(bounds([a]))[:,0]),8)
            panel=next(e for e in doc['elements'] if e['name']=='紫色面板')
            self.assertGreater(max(t['n'][2] for t in triangles([panel])),.999999)

    def test_actual_inserted_card_is_inside_slot_rim_and_stops_in_connector(self):
        p=np.concatenate([q.vertices for q in inserted_card_quads()]); box=np.array([p.min(0),p.max(0)])
        # Original decimal 45-degree bevel coordinates protrude by <.000003 after .3 uniform scale.
        np.testing.assert_allclose(box,[[6.29,.81,11.5125],[9.71,3.15,11.9625]],atol=3e-6)
        np.testing.assert_allclose(box,self.mesh['metadata']['anchors']['cartridge_inserted_bounds'],atol=1e-9)
        # Opening bounded by the inner edges of the left/right/front/back slot rim.
        self.assertGreater(box[0,0],5.8445); self.assertLess(box[1,0],10.1555)
        self.assertGreater(box[0,2],11.3325); self.assertLess(box[1,2],12.2685)
        self.assertLess(box[0,1],.86922) # Intended lower edge seated inside connector, not a floating card.
        self.assertGreater(box[0,1],.78) # Never enters the solid upper case under the slot.

    def test_idempotent_new_outputs_refuse_any_different_existing_file_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            first, second=Path(directory)/'existing',Path(directory)/'new'
            first.write_bytes(b'keep')
            with self.assertRaises(FileExistsError):
                write_new({second:b'new',first:b'different'})
            self.assertEqual(b'keep',first.read_bytes());self.assertFalse(second.exists())
            write_new({first:b'keep',second:b'new'});write_new({first:b'keep',second:b'new'})
            self.assertEqual(b'new',second.read_bytes())


if __name__ == '__main__':
    unittest.main()
