import copy,json,unittest
from unittest.mock import patch
import verify_home_fc_alpha12_final_jar as audit


class Alpha12FinalJarTests(unittest.TestCase):
    def document(self):
        assets=dict(audit.alpha11.frozen_manifest())
        assets.update({n:'F'*64 for n in audit.ADDED})
        return {'version':audit.VERSION,'protocol':29,'assets':assets}
    def model(self):
        return json.loads((audit.old.ASSETS/'assets/piq_fc_arcade/models/block/cartridge_computer.json').read_bytes())
    def test_frozen_manifest_has_exactly_67_assets(self):
        self.assertEqual(67,len(audit.frozen_manifest()))
    def test_all_64_previous_assets_unchanged(self):
        self.assertEqual(67,len(audit.validate_manifest(self.document())))
    def test_unknown_manifest_fails_closed(self):
        with patch.object(audit,'MANIFEST_SHA',None),self.assertRaises(ValueError):audit.frozen_manifest()
    def test_wrong_manifest_path_rejected(self):
        with self.assertRaises(ValueError):audit.frozen_manifest(audit.alpha11.MANIFEST)
    def test_manifest_tampering_rejected(self):
        with patch.object(audit.old,'file_sha',return_value='0'*64),self.assertRaises(ValueError):audit.frozen_manifest()
    def test_version_and_protocol_mismatch_rejected(self):
        for k,v in [('version','0.31.0-alpha.11'),('protocol',28)]:
            d=self.document();d[k]=v
            with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_any_inherited_asset_change_rejected(self):
        d=self.document();n=next(iter(audit.alpha11.frozen_manifest()));d['assets'][n]='0'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_missing_new_resource_rejected(self):
        d=self.document();d['assets'].pop(next(iter(audit.ADDED)))
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_extra_appearance_rejected(self):
        d=self.document();d['assets']['assets/piq_fc_arcade/textures/new.png']='F'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_malformed_new_hash_rejected(self):
        d=self.document();d['assets'][next(iter(audit.ADDED))]='pending'
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_actual_computer_geometry(self):
        r=audit.computer_geometry(self.model())
        self.assertEqual(319,r['elements']);self.assertEqual(954,r['actual_quads'])
        self.assertGreater(r['static_terminal_glyph_quads'],0)
        self.assertEqual(4/3,r['screen_aspect'])
    def test_unfrozen_geometry_rejected(self):
        with patch.object(audit,'COMPUTER_BOUNDS',None),self.assertRaises(ValueError):audit.computer_geometry(self.model())
    def test_geometry_does_not_overflow_single_block(self):
        m=self.model();m['elements'][0]['to'][0]=17
        with self.assertRaises(ValueError):audit.computer_geometry(m)
    def test_unknown_texture_dependency_rejected(self):
        m=self.model();m['textures']['white']='external:block/unapproved'
        with self.assertRaises(ValueError):audit.computer_geometry(m)
    def test_uv_outside_atlas_rejected(self):
        m=self.model();next(iter(m['elements'][0]['faces'].values()))['uv']=[0,0,17,16]
        with self.assertRaises(ValueError):audit.computer_geometry(m)
    def test_glass_wrong_size_or_double_face_rejected(self):
        for double in (False,True):
            m=self.model();g=next(e for e in m['elements'] if e.get('name')=='4比3内凹黑玻璃')
            if double:g['faces']['south']=copy.deepcopy(g['faces']['north'])
            else:g['from'][0]+=.1
            with self.assertRaises(ValueError):audit.computer_geometry(m)
    def test_terminal_cannot_sink_behind_glass(self):
        m=self.model();g=next(e for e in m['elements'] if e.get('name','').startswith('静态终端字符-'))
        g['from'][2]=6.16;g['to'][2]=6.17
        with self.assertRaises(ValueError):audit.computer_geometry(m)
    def test_players_success_requires_status_at_actual_send(self):
        s='// Method ServerRomLibrary.setMaxPlayers:(Ljava/lang/String;I)V\n162: aload_0\n163: aload_1\n164: aload_2\n165: iconst_1\n166: ldc #236 // String\n168: ldc_w #326 // String done\n175: invokevirtual #266 // Method ServerRomLibrary.catalog:()Ljava/util/List;\n178: invokevirtual #246 // Method send:()V'
        self.assertTrue(audit.players_success_is_status(s))
        self.assertFalse(audit.players_success_is_status(s.replace('iconst_1','iconst_0')))
        self.assertFalse(audit.players_success_is_status(s.replace('ServerRomLibrary.catalog:','Other.catalog:')))
        self.assertFalse(audit.players_success_is_status(s.replace('ServerRomLibrary.setMaxPlayers:','Other.setMaxPlayers:')))
        self.assertFalse(audit.players_success_is_status('1: iconst_1\n'+s.replace('iconst_1','iconst_0')))


if __name__=='__main__':unittest.main()
