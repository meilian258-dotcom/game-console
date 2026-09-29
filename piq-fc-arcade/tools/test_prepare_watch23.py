import unittest
from prepare_watch23 import classify

class WatchScopeTests(unittest.TestCase):
    def test_refuses_model_edit(self):
        with self.assertRaises(ValueError): classify('fc',{'assets/a.png':b'a'},{'assets/a.png':b'b'})
    def test_refuses_any_removed_entry(self):
        with self.assertRaises(ValueError): classify('sfc',{'assets/a':b'a'},{})
    def test_refuses_emulator_change(self):
        n='cn/piq/sfcarcade/WasmSfcCore.class'
        with self.assertRaises(ValueError): classify('sfc',{n:b'a'},{n:b'b'})
    def test_refuses_controller_authority_change(self):
        n='cn/piq/fcarcade/cabinet/CabinetRoomLedger.class'
        with self.assertRaises(ValueError): classify('fc',{n:b'a'},{n:b'b'})
    def test_refuses_foreign_owner(self):
        with self.assertRaises(ValueError): classify('sfc',{}, {'cn/piq/fcarcade/cabinet/WatchEvil.class':b'b'})
    def test_accepts_new_watch_type_and_protected_assets(self):
        old={'assets/a':b'a'};new=old|{'cn/piq/fcarcade/cabinet/WatchLedger.class':b'b'}
        report=classify('fc',old,new);self.assertEqual(report['model_and_data_entries_unchanged'],1)
    def test_rejects_non_watch_new_class(self):
        with self.assertRaises(ValueError): classify('fc',{}, {'cn/piq/fcarcade/client/Unrelated.class':b'b'})

if __name__=='__main__':unittest.main()
