"""Negative/roundtrip packaging unit fixtures only; not final game/addon evidence."""
from pathlib import Path
from unittest.mock import patch
import copy,io,tempfile,unittest,zipfile
import package_gba_preview as p

class PackageGuards(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='piq-gba-package-tests-');self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        def item(name):
            file=self.root/name;file.write_bytes(name.encode());return p.snapshot(file)
        self.fc=item('fc.jar');self.mod=item('gba.jar');self.runtime={name:item(name) for name in ('piq-gba-helper.jar','jna-5.14.0.jar','mgba_libretro.dll')}
        self.report={'schema':'piq-gba-final-bundle-1','ok':True,'production_compiled':False,'compiled_only_probes':True,'minecraft_started':False,'user_rom_or_save_used':False,
          'fc':{'path':str(self.fc.path),'sha256':self.fc.sha256},'gba':{'path':str(self.mod.path),'sha256':self.mod.sha256},
          'runtime':{n:{'sha256':s.sha256,'bytes':len(s.raw)} for n,s in self.runtime.items()},
          'core':{'ok':True,'production_origin':'final-jar-only','assertions':98434,'core_version':'0.11-219-e31759b','actual_rgb565':True,'native_sample_rate':65536,'save_ram_bytes':32768,'nonzero_samples':2000,'gameplay_compatibility_claimed':False,'minecraft_started':False},
          'process':{'ok':True,'production_origin':'final-jar-only','assertions':27,'actual_child_process_protocol':True,'actual_save_restart':True,'minecraft_started':False,'user_rom_or_save_used':False},
          'fml':{'ok':True,'production_origin':'final-jar-only','assertions':28,'actual_fml_reader':True,'actual_annotation_scan':True,'actual_common_registration':True,'mod_entry_points_executed':False,'minecraft_or_native_core_started':False}}
    def validate(self,r=None):p.validate_audit(self.report if r is None else r,self.fc,self.mod,self.runtime)
    def test_positive_validator_fixture(self):self.validate()
    def test_fixture_rejected(self):
        self.report['fixture_only']=True
        with self.assertRaises(ValueError):self.validate()
    def test_recompiled_production_rejected(self):
        self.report['production_compiled']=True
        with self.assertRaises(ValueError):self.validate()
    def test_wrong_fc_hash_rejected(self):
        self.report['fc']['sha256']='0'*64
        with self.assertRaises(ValueError):self.validate()
    def test_wrong_gba_path_rejected(self):
        self.report['gba']['path']=str(self.fc.path)
        with self.assertRaises(ValueError):self.validate()
    def test_wrong_helper_rejected(self):
        self.report['runtime']['piq-gba-helper.jar']['sha256']='0'*64
        with self.assertRaises(ValueError):self.validate()
    def test_missing_runtime_rejected(self):
        del self.report['runtime']['jna-5.14.0.jar']
        with self.assertRaises(ValueError):self.validate()
    def test_missing_actual_fml_registration_rejected(self):
        self.report['fml']['actual_common_registration']=False
        with self.assertRaises(ValueError):self.validate()
    def test_fake_core_probe_rejected(self):
        self.report['core']['production_origin']='fresh-standalone-compile'
        with self.assertRaises(ValueError):self.validate()
    def test_unverified_save_recovery_rejected(self):
        self.report['process']['assertions']=17
        with self.assertRaises(ValueError):self.validate()
    def test_unpassed_child_rejected(self):
        self.report['process']['ok']=False
        with self.assertRaises(ValueError):self.validate()
    def test_broad_compatibility_claim_rejected(self):
        self.report['core']['gameplay_compatibility_claimed']=True
        with self.assertRaises(ValueError):self.validate()
    def source(self,changes=None):
        entries={'tools/build_gba_preview.py':b'build','src/main/resources/META-INF/neoforge.mods.toml':b'meta'}
        expected={n:p.digest(v) for n,v in entries.items()}
        if changes:changes(entries)
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w') as z:
            for n,v in entries.items():z.writestr(n,v)
        return output.getvalue(),{'source_sha256':expected}
    def test_source_exact(self):p.check_source_zip(*self.source())
    def test_missing_source_rejected(self):
        with self.assertRaises(ValueError):p.check_source_zip(*self.source(lambda e:e.pop('tools/build_gba_preview.py')))
    def test_mismatched_source_rejected(self):
        with self.assertRaises(ValueError):p.check_source_zip(*self.source(lambda e:e.update({'tools/build_gba_preview.py':b'changed'})))
    def test_extra_source_rejected(self):
        with self.assertRaises(ValueError):p.check_source_zip(*self.source(lambda e:e.update({'unexpected.gba':b'rom'})))
    def test_snapshot_revalidation(self):
        plan=p.PackagePlan({'fixture.txt':self.mod.raw},(self.mod,),{'fixture_only':True});self.mod.path.write_bytes(b'changed')
        with patch.object(p,'DELIVERY',self.root),self.assertRaises(ValueError):p.build(plan,self.root/'x.zip',True)
    def test_crc_inventory_readback_and_no_overwrite(self):
        plan=p.PackagePlan({'fixture.txt':self.mod.raw},(self.mod,),{'fixture_only':True})
        output=self.root/'fixture.zip'
        with patch.object(p,'DELIVERY',self.root):
            dry=p.build(plan,output,True);self.assertTrue(dry['check_only']);self.assertFalse(output.exists())
            done=p.build(plan,output);self.assertTrue(done['fixture_only']);self.assertEqual(done['sha256'],p.digest(output.read_bytes()))
            p.verify_archive(output.read_bytes(),plan)
            with self.assertRaises(ValueError):p.build(plan,output)
    def test_outside_delivery_rejected(self):
        plan=p.PackagePlan({},(),{'fixture_only':True})
        with self.assertRaises(ValueError):p.build(plan,self.root/'outside.zip',True)

if __name__=='__main__':unittest.main()
