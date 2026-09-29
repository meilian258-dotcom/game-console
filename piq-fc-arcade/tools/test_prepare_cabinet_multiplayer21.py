import json
import unittest
import prepare_cabinet_multiplayer21 as stage

class GuardTests(unittest.TestCase):
    def test_unchanged_asset(self):
        self.assertEqual(stage.classify('fc',{'assets/x/model.json':b'a'},{'assets/x/model.json':b'a'})['unchanged_entries'],1)
    def test_model_edit_rejected(self):
        with self.assertRaises(ValueError):stage.classify('fc',{'assets/x/model.json':b'a'},{'assets/x/model.json':b'b'})
    def test_remove_rejected(self):
        with self.assertRaises(ValueError):stage.classify('fc',{'old':b'a'},{})
    def test_rom_add_rejected(self):
        with self.assertRaises(ValueError):stage.classify('fc',{}, {'rom/game.zip':b'unknown'})
    def test_sfc_home_network_change_rejected(self):
        with self.assertRaises(ValueError):stage.classify('sfc',{}, {'cn/piq/sfchome/net/SfcHomeNetwork.class':b'unknown'})
    def test_native_models_change_rejected(self):
        with self.assertRaises(ValueError):stage.classify('native',{}, {'assets/piq_native_arcade/models/a.json':b'{}'})
    def test_cable_asset_allowed(self):
        a={'assets/piq_fc_arcade/models/item/cabinet_link_cable.json':json.dumps({'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/string'}}).encode()}
        self.assertEqual(len(stage.classify('fc',{},a)['added']),1)
    def test_language_preserves_old_values(self):
        n='assets/piq_fc_arcade/lang/zh_cn.json';old={n:b'{"old":"a"}'}
        fresh={'old':'b',**{k:'c' for k in stage.LANG_KEYS}}
        with self.assertRaises(ValueError):stage.classify('fc',old,{n:json.dumps(fresh).encode()})
    def test_duplicate_mod_id_rejected(self):
        raw=b'[[mods]]\nmodId="piq_fc_arcade"\nversion="0.31.0-alpha.21"\n[[mods]]\nmodId="piq_fc_arcade"\nversion="0.31.0-alpha.21"\n'
        with self.assertRaises(ValueError):stage.metadata('fc',{stage.META:raw})
    def test_wrong_version_rejected(self):
        raw=b'[[mods]]\nmodId="piq_fc_arcade"\nversion="0.31.0-alpha.20"\n'
        with self.assertRaises(ValueError):stage.metadata('fc',{stage.META:raw})
    def test_deterministic_archive(self):
        self.assertEqual(stage.jar_bytes({'b':b'2','a':b'1'}),stage.jar_bytes({'a':b'1','b':b'2'}))

if __name__=='__main__':unittest.main()
