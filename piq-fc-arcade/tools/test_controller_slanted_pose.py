import unittest
import numpy as np
import check_controller_slanted_pose as qa


class ControllerSlantedPoseTests(unittest.TestCase):
    def test_ray_checks_geometry_between_eye_and_key_not_behind_it(self):
        face=np.array([[-1,-1,-1],[1,-1,-1],[0,1,-1]],dtype=float)
        self.assertTrue(qa.hit(np.array([0,0,-2]),face))
        self.assertFalse(qa.hit(np.array([0,0,-.5]),face))
        self.assertFalse(qa.hit(np.array([5,0,-2]),face))

    def test_active_pose_is_read_and_historical_angle_is_explicit(self):
        current=qa.pose.first_transform(True,True,0,-.62,-1.46,-1.58)
        old=qa.pose.first_transform(True,True,0,-.62,-1.46,-1.58,pitch=-16)
        self.assertAlmostEqual(current[1,2],np.sqrt(3)/2)
        self.assertNotAlmostEqual(current[1,2],old[1,2])

    def test_cap_bounds_and_travel_come_from_production_geometry(self):
        fc,fc_travel=qa.source_caps(False);subor,subor_travel=qa.source_caps(True)
        self.assertEqual(5,len(fc));self.assertEqual(7,len(subor))
        self.assertEqual(.10,fc_travel);self.assertEqual(.13,subor_travel)
        self.assertLess(fc[0][1,0],fc[1][0,0])
        self.assertLess(subor[0][1,0],subor[1][0,0])


if __name__=='__main__':unittest.main()
