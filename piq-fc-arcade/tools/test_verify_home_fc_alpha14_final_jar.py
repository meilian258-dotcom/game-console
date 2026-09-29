import unittest
import verify_home_fc_alpha14_final_jar as audit
class Archive:
    def __init__(self,entries):self.entries=entries
    def namelist(self):return list(self.entries)
    def read(self,name):return self.entries[name]
class Alpha14FinalJarTests(unittest.TestCase):
    def archives(self):
        old={n:b'old' for n in audit.CHANGED};old['unchanged']=b'keep'
        new={n:b'new' for n in audit.CHANGED};new['unchanged']=b'keep'
        new.update({n:b'\xca\xfe\xba\xbe\x00\x00\x00\x41safe' for n in audit.ADDED})
        return Archive(new),Archive(old)
    def test_inherited_68_assets_are_exact_frozen_contract(self):self.assertEqual(68,len(audit.appearance_contract()))
    def test_exact_api_delta(self):a,b=self.archives();r=audit.exact_delta(a,b);self.assertEqual(7,r['server_api_class_count']);self.assertEqual(1,r['public_display_class_count'])
    def test_unknown_added_resource_rejected(self):a,b=self.archives();a.entries['extra']=b'bad';self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_missing_added_api_rejected(self):a,b=self.archives();a.entries.pop(next(iter(audit.ADDED)));self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_removed_inherited_entry_rejected(self):a,b=self.archives();a.entries.pop('unchanged');self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_unexpected_core_or_other_byte_change_rejected(self):a,b=self.archives();a.entries['unchanged']=b'bad';self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_missing_expected_lifecycle_change_rejected(self):a,b=self.archives();n=next(iter(audit.CHANGED));a.entries[n]=b.entries[n];self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_wrong_classfile_version_rejected(self):a,b=self.archives();a.entries[next(iter(audit.ADDED))]=b'\xca\xfe\xba\xbe\x00\x00\x00\x3d';self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_client_dependency_in_server_api_rejected(self):
        for forbidden in (b'net/minecraft/client/',b'cn/piq/fcarcade/client/',b'WasmNesCore'):
            a,b=self.archives();a.entries['cn/piq/fcarcade/'+audit.SERVER_API[0]+'.class']+=forbidden;self.assertRaises(ValueError,audit.exact_delta,a,b)
    def test_alpha14_does_not_rewrite_historical_versions(self):self.assertEqual('0.31.0-alpha.14',audit.VERSION);self.assertEqual('0.31.0-alpha.13',audit.alpha13.VERSION);self.assertEqual(30,audit.PROTOCOL)
if __name__=='__main__':unittest.main()
