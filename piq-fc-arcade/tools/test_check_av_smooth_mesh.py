import copy
from pathlib import Path
import unittest
from unittest.mock import patch
import numpy as np
from check_av_smooth_mesh import analyze,smooth_metrics


class ActualAvSmoothMeshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.report,cls.samples=analyze()

    def test_all_actual_curves_have_six_plugs_table_clearance_and_continuous_joints(self):
        self.assertTrue(self.report['ok'],self.report['findings'])
        self.assertEqual(36,self.report['current_placements_checked'])
        for case in self.report['actual_java_mesh_metrics']:
            self.assertFalse(case['empty']);self.assertGreaterEqual(case['actual_surface_min_y'],0)
            self.assertLess(case['maximum_branch_to_trunk_angle_degrees'],.001)
            self.assertLess(case['maximum_trunk_ring_turn_degrees'],18)
            self.assertLessEqual(case['quads'],6000);self.assertLessEqual(case['route_points'],256)

    def test_actual_cap_normals_improve_135_degree_joint_to_shared_tangent(self):
        before=self.report['comparison']['before'];after=self.report['comparison']['after']
        self.assertGreater(before['maximum_branch_to_trunk_angle_degrees'],130)
        self.assertLess(after['maximum_branch_to_trunk_angle_degrees'],.001)
        self.assertGreater(before['maximum_trunk_ring_turn_degrees'],27)
        self.assertLess(after['maximum_trunk_ring_turn_degrees'],16)

    def test_cap_direction_mutation_is_detected_without_changing_route_centerline(self):
        broken=copy.deepcopy(self.samples['after']);center=np.array(broken['route'][1])
        changed=False
        for face in broken['mesh']:
            if np.linalg.norm(np.array(face[1])-center)>1e-8 or np.linalg.norm(np.array(face[4])-center)>1e-8:continue
            if abs(np.linalg.norm(np.array(face[2])-center)-.011)>1e-8:continue
            for at in (2,3):
                delta=np.array(face[at])-center;face[at]=(center+[-delta[1],delta[0],delta[2]]).tolist()
            changed=True;break
        self.assertTrue(changed)
        self.assertGreater(smooth_metrics(broken)['maximum_branch_to_trunk_angle_degrees'],20)

    def test_ring_centers_cannot_be_replaced_with_pretty_route_metadata(self):
        broken=copy.deepcopy(self.samples['after']);broken['route'][2][0]+=.04
        with self.assertRaises(ValueError):smooth_metrics(broken)

    def test_surface_below_table_is_reported_even_if_centerline_is_safe(self):
        broken=copy.deepcopy(self.samples['after'])
        barrel=next(face for face in broken['mesh'] if face[0]==0xE6B52C)
        barrel[1][1]=-.01
        self.assertTrue(all(p[1]>=0 for p in broken['route']))
        self.assertLess(smooth_metrics(broken)['actual_surface_min_y'],0)

    def test_final_inspection_rejects_unspecified_jar_hash_without_execution(self):
        with patch('check_av_smooth_mesh.subprocess.run') as run:
            with self.assertRaises(ValueError):analyze(Path('unused.jar'))
            run.assert_not_called()


if __name__=='__main__':unittest.main()
