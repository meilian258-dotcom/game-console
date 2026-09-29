import unittest
from prepare_interaction24 import classify
class ScopeTest(unittest.TestCase):
    def test_allowed_interaction_only(self):
        n='cn/piq/sfchome/server/SfcHomeServer.class';self.assertIn(n,classify('sfc',{n:b'a'},{n:b'b'})['changed'])
    def test_assets_cannot_change(self):
        n='assets/piq_sfc_home/meshes/sfc_hardware.json'
        with self.assertRaises(ValueError):classify('sfc',{n:b'a'},{n:b'b'})
    def test_no_removed_entry(self):
        with self.assertRaises(ValueError):classify('fc',{'old':b'a'},{})
    def test_core_cannot_change(self):
        n='cn/piq/sfchome/client/SfcPlayback.class'
        with self.assertRaises(ValueError):classify('sfc',{n:b'a'},{n:b'b'})
    def test_old_wire_cannot_change(self):
        n='cn/piq/fcarcade/cabinet/CabinetRoomNetwork$Input.class'
        with self.assertRaises(ValueError):classify('fc',{n:b'a'},{n:b'b'})
    def test_new_wire_allowed(self):
        n='cn/piq/fcarcade/cabinet/CabinetJoinNetwork$Offer.class';self.assertEqual([n],classify('fc',{}, {n:b'a'})['added'])
    def test_arbitrary_new_class_rejected(self):
        with self.assertRaises(ValueError):classify('native',{}, {'cn/piq/nativearcade/Surprise.class':b'a'})
    def test_unchanged_assets_counted(self):
        n='assets/a.json';self.assertEqual(1,classify('fc',{n:b'a'},{n:b'a'})['assets_and_data_unchanged'])
if __name__=='__main__':unittest.main()
