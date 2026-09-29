import copy
import unittest
from check_av_table_mesh import analyze,metrics


class AvTableActualMeshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.report,cls.samples=analyze()

    def test_all_36_frozen_alpha7_jar_placements_pass(self):
        self.assertTrue(self.report['ok'],self.report['findings'])
        self.assertEqual('frozen_alpha7_jar',self.report['mode'])
        self.assertEqual(36,self.report['current_placements_checked'])
        for case in self.report['actual_java_mesh_metrics']:
            self.assertFalse(case['empty']);self.assertGreaterEqual(case['actual_surface_min_y'],0)

    def test_current_main_wire_rests_above_table_not_suspended(self):
        before=self.report['comparison_fc_crt_four_blocks']['before']
        after=self.report['comparison_fc_crt_four_blocks']['after']
        self.assertEqual(0,before['table_contact_length_fraction'])
        self.assertGreater(after['table_contact_length_fraction'],.8)
        self.assertAlmostEqual(.004,after['trunk_min_y'])
        self.assertGreater(before['trunk_min_y'],.1)

    def test_color_audit_detects_missing_plug_surface(self):
        broken=copy.deepcopy(self.samples['after'])
        index=next(i for i,q in enumerate(broken['mesh']) if q[0]==0xAC2828)
        del broken['mesh'][index]
        self.assertFalse(metrics(broken)['six_plugs_color_contract'])

    def test_pipe_clearance_uses_actual_surface_not_route_centerline(self):
        broken=copy.deepcopy(self.samples['after'])
        broken['mesh'][0][1][1]=-.015
        self.assertLess(metrics(broken)['actual_surface_min_y'],0)
        self.assertTrue(all(p[1]>=0 for p in broken['route']))


if __name__=='__main__':unittest.main()
