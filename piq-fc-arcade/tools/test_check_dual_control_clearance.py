import copy
import json
import unittest
import numpy as np
from check_dual_control_clearance import analyze,intersect_triangles,MODEL


class DualControlClearanceTest(unittest.TestCase):
    def test_exported_controls_clear_actual_shell(self):
        result=analyze();self.assertTrue(result['ok'],result)
        self.assertEqual(1608,result['control_triangles'])
        self.assertGreater(result['minimum_front_of_screen_plane_model_units'],1)

    def test_regression_wedge_pushed_into_stick_is_detected(self):
        model=json.loads(MODEL.read_bytes());e=model['elements'][40]
        for key in ('from','to'):e[key][2]-=2.3
        e['rotation']['origin'][2]-=2.3
        report=analyze(model)
        self.assertFalse(report['ok']);self.assertTrue(report['intersection_element_pairs'])

    def test_lowered_controls_embed_in_support_and_fail(self):
        model=json.loads(MODEL.read_bytes())
        for e in model['elements'][68:202]:
            for key in ('from','to'):e[key][1]-=.1
            if 'rotation'in e:e['rotation']['origin'][1]-=.1
        self.assertFalse(analyze(model)['ok'])

    def test_sat_detects_crossing_and_rejects_disjoint_coplanar(self):
        a=np.array([[0.,0,0],[1,0,0],[0,1,0]])
        self.assertTrue(intersect_triangles(a,np.array([[.2,.2,-1],[.2,.2,1],[.4,.4,0]])))
        self.assertFalse(intersect_triangles(a,a+[2,0,0]))


if __name__=='__main__':unittest.main()
