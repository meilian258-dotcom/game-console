import json,unittest
from unittest.mock import patch
import prepare_controls26 as s

class ScopeTests(unittest.TestCase):
    def samples(self):
        old={'core/nes_rust_wasm_bg.wasm':b'legacy-core','core/nes_zapper_v1.wasm':b'isolated-core',
             'assets/piq_fc_arcade/models/block/famicom.json':b'old-model',
             'cn/piq/retro/client/KeyboardConfig.class':b'old',
             'piq_fc_keyboard.mixins.json':json.dumps(s.MIXIN).encode(),
             'cn/piq/retro/client/ControlSettingsScreen$1.class':b'old-switch',
             'cn/piq/retro/client/KeyboardInput$1.class':b'old-switch'}
        new=dict(old)
        for name in s.REMOVED['fc']:new.pop(name)
        new['cn/piq/retro/client/KeyboardConfig.class']=b'new'
        return old,new
    def check(self,old,new):
        with patch.object(s,'RESOURCE_SHA',{}):return s.classify('fc',old,new)
    def test_narrow_class_delta_allowed(self):
        old,new=self.samples();result=self.check(old,new)
        self.assertEqual(1,len(result['changed']));self.assertEqual(2,len(result['removed']))
    def test_unrelated_class_rejected(self):
        old,new=self.samples();new['cn/piq/other/Unreviewed.class']=b'x'
        with self.assertRaises(ValueError):self.check(old,new)
    def test_both_cores_immutable(self):
        for key in ('core/nes_rust_wasm_bg.wasm','core/nes_zapper_v1.wasm'):
            old,new=self.samples();new[key]=b'changed'
            with self.assertRaises(ValueError):self.check(old,new)
    def test_existing_fc_model_cannot_change(self):
        old,new=self.samples();new['assets/piq_fc_arcade/models/block/famicom.json']=b'changed'
        with self.assertRaises(ValueError):self.check(old,new)
    def test_rom_bios_or_private_frames_are_not_allowed(self):
        for key in ('roms/test.nes','assets/private.png','neogeo.zip','states/demo.bin'):
            old,new=self.samples();new[key]=b'private'
            with self.assertRaises(ValueError):self.check(old,new)
    def test_extra_class_removal_is_not_allowed(self):
        old,new=self.samples();new.pop('cn/piq/retro/client/KeyboardConfig.class')
        with self.assertRaises(ValueError):self.check(old,new)
    def test_mixin_must_stay_required_and_client_only(self):
        for key,value in (('required',False),('client',[]),('mixins',['KeyMappingStateAccess'])):
            old,new=self.samples();conf=dict(s.MIXIN);conf[key]=value;new['piq_fc_keyboard.mixins.json']=json.dumps(conf).encode()
            with self.assertRaises(ValueError):self.check(old,new)
    def test_approved_resource_must_match_exact_hash(self):
        old,new=self.samples();key='assets/piq_fc_arcade/textures/item/zapper.png';new[key]=b'asset'
        with patch.object(s,'RESOURCE_SHA',{key:s.digest(b'asset')}):
            s.classify('fc',old,new)
            new[key]=b'changed'
            with self.assertRaises(ValueError):s.classify('fc',old,new)
    def test_addons_cannot_acquire_platform_implementation(self):
        for kind in ('sfc','native'):
            with self.assertRaises(ValueError):s.classify(kind,{}, {'cn/piq/retro/client/KeyboardConfig.class':b'x'})

if __name__=='__main__':unittest.main()
