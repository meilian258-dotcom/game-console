import copy,json,unittest
from unittest.mock import patch
import verify_home_fc_alpha13_final_jar as audit


class Archive:
    def __init__(self,entries):self.entries=entries
    def namelist(self):return list(self.entries)
    def read(self,key):return self.entries[key]


class Alpha13FinalJarTests(unittest.TestCase):
    def document(self):
        assets=dict(audit.alpha12.frozen_manifest());assets.update({n:'F'*64 for n in audit.ADDED})
        return {'version':audit.VERSION,'protocol':30,'assets':assets}
    def model(self):
        return json.loads((audit.old.ASSETS/next(iter(audit.ADDED))).read_bytes())
    def test_frozen_manifest_has_68_assets(self):self.assertEqual(68,len(audit.frozen_manifest()))
    def test_all_67_previous_assets_unchanged(self):self.assertEqual(68,len(audit.validate_manifest(self.document())))
    def test_unknown_manifest_fails_closed(self):
        with patch.object(audit,'MANIFEST_SHA',None),self.assertRaises(ValueError):audit.frozen_manifest()
    def test_wrong_manifest_path_rejected(self):
        with self.assertRaises(ValueError):audit.frozen_manifest(audit.alpha12.MANIFEST)
    def test_manifest_tampering_rejected(self):
        with patch.object(audit.old,'file_sha',return_value='0'*64),self.assertRaises(ValueError):audit.frozen_manifest()
    def test_version_and_protocol_mismatch_rejected(self):
        for k,v in [('version','0.31.0-alpha.12'),('protocol',29)]:
            d=self.document();d[k]=v
            with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_inherited_asset_change_rejected(self):
        d=self.document();d['assets'][next(iter(audit.alpha12.frozen_manifest()))]='0'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_missing_new_resource_rejected(self):
        d=self.document();d['assets'].pop(next(iter(audit.ADDED)))
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_extra_appearance_rejected(self):
        d=self.document();d['assets']['assets/piq_fc_arcade/textures/new.png']='F'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_malformed_hash_rejected(self):
        d=self.document();d['assets'][next(iter(audit.ADDED))]='pending'
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_actual_remote_geometry(self):
        r=audit.remote_geometry(self.model());self.assertEqual(314,r['actual_quads']);self.assertEqual(7,len(r['display_contexts']))
    def test_remote_bounds_rejected(self):
        m=self.model();m['elements'][0]['to'][0]=17
        with self.assertRaises(ValueError):audit.remote_geometry(m)
    def test_unknown_texture_rejected(self):
        m=self.model();m['textures']['white']='external:block/new'
        with self.assertRaises(ValueError):audit.remote_geometry(m)
    def test_remote_uv_rejected(self):
        m=self.model();next(iter(m['elements'][0]['faces'].values()))['uv']=[0,0,17,16]
        with self.assertRaises(ValueError):audit.remote_geometry(m)
    def test_missing_hand_pose_rejected(self):
        m=self.model();m['display'].pop('firstperson_lefthand')
        with self.assertRaises(ValueError):audit.remote_geometry(m)
    def test_invalid_or_changed_display_rejected(self):
        for v in (float('nan'),99,.5):
            m=self.model();m['display']['gui']['scale'][0]=v
            with self.assertRaises(ValueError):audit.remote_geometry(m)
    def test_extra_parent_rejected(self):
        m=self.model();m['parent']='minecraft:item/generated'
        with self.assertRaises(ValueError):audit.remote_geometry(m)
    def test_native_core_input_av_bytes_are_preserved(self):
        entries={'cn/piq/fcarcade/core/Class'+str(i)+'.class':b'old' for i in range(20)}
        self.assertEqual(20,audit.unchanged_core_input_av_classes(Archive(entries),Archive(entries))['unchanged_classes'])
        changed=dict(entries);changed[next(iter(changed))]=b'new'
        with self.assertRaises(ValueError):audit.unchanged_core_input_av_classes(Archive(changed),Archive(entries))
    def test_empty_protected_class_selection_rejected(self):
        with self.assertRaises(ValueError):audit.unchanged_core_input_av_classes(Archive({}),Archive({}))
    def test_unreviewed_remote_contract_rejected(self):
        with patch.object(audit,'REMOTE_CONTRACT_FROZEN',False),self.assertRaises(ValueError):audit.bytecode(None,None)


if __name__=='__main__':unittest.main()
