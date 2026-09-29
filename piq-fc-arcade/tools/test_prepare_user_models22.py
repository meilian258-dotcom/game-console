import unittest
from prepare_user_models22 import classify

class ModelStageScopeTest(unittest.TestCase):
    def test_no_delete(self):
        with self.assertRaises(ValueError):classify('fc',{'world.dat':b'old'},{},{})
    def test_network_replacement_rejected(self):
        name='cn/piq/fcarcade/cabinet/CabinetNetwork.class'
        with self.assertRaises(ValueError):classify('fc',{name:b'old'},{name:b'new'},{})
    def test_core_replacement_rejected(self):
        name='assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm'
        with self.assertRaises(ValueError):classify('sfc',{name:b'old'},{name:b'new'},{})
    def test_old_unrelated_artwork_rejected(self):
        name='assets/piq_fc_arcade/textures/block/famicom.png'
        with self.assertRaises(ValueError):classify('fc',{name:b'old'},{name:b'new'},{})
    def test_only_exact_derived_asset_bytes(self):
        name='assets/piq_sfc_home/meshes/sfc_hardware.json'
        with self.assertRaises(ValueError):classify('sfc',{name:b'old'},{name:b'wrong'},{name:b'derived'})
        self.assertTrue(classify('sfc',{name:b'old'},{name:b'derived'},{name:b'derived'})['explicit_allowlist_enforced'])
    def test_missing_derived_asset_rejected(self):
        with self.assertRaises(ValueError):classify('fc',{}, {},{'assets/piq_fc_arcade/models/block/user_dual/body.json':b'new'})
    def test_added_unknown_class_rejected(self):
        with self.assertRaises(ValueError):classify('sfc',{}, {'cn/piq/sfchome/client/Unexpected.class':b'new'},{})

if __name__=='__main__':unittest.main()
