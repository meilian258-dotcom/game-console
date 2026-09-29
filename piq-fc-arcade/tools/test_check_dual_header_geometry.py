import copy
import json
import unittest
from check_dual_header_geometry import analyze,MODEL


class DualHeaderGeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.model=json.loads(MODEL.read_bytes())

    def test_exported_header_is_closed_and_unchanged_screen_controls(self):
        report,_=analyze(self.model);self.assertTrue(report['ok'],report['checks'])

    def test_missing_header_bottom_fails_topology(self):
        model=copy.deepcopy(self.model);model['elements'][48]['faces'].pop('down')
        report,_=analyze(model);self.assertFalse(report['checks']['header_main_and_speaker_backer_are_closed_six_face_solids'])

    def test_retracted_top_of_back_panel_fails_shell_connectivity(self):
        model=copy.deepcopy(self.model);model['elements'][4]['to'][1]-=7
        report,_=analyze(model);self.assertFalse(report['checks']['top_side_back_canopy_have_connected_closed_shell'])

    def test_hidden_gameplay_control_move_is_not_accepted_as_header_change(self):
        model=copy.deepcopy(self.model);model['elements'][68]['to'][1]-=.1
        report,_=analyze(model);self.assertFalse(report['checks']['every_non_header_world_quad_equals_frozen_alpha8'])


if __name__=='__main__':unittest.main()
