"""Synthetic delivery fixtures only; never package the real stage or install anything."""
import copy, io, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
import package_interaction24 as target
from package_fc_core_alpha19 import PackagePlan, snapshot, verify_archive

class InteractionPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='interaction24-package-fixture-',dir=target.ROOT/'piq-fc-arcade/build')
        self.addCleanup(self.temp.cleanup);self.directory=Path(self.temp.name)
        self.input=self.directory/'input.txt';self.input.write_bytes(b'fixture only')
        self.item=snapshot(self.input);self.out=self.directory/'out'
        self.package=PackagePlan({'checks/fixture.txt':self.item.raw},(self.item,),{'fixture_only':True})
    def test_dry_run_does_not_create_delivery(self):
        self.assertTrue(target.build(self.package,self.out,True)['check_only'])
        self.assertFalse(self.out.exists());self.assertFalse(self.out.with_suffix('.zip').exists())
    def test_synthetic_roundtrip_hash_crc_and_no_overwrite(self):
        result=target.build(self.package,self.out)
        raw=self.out.with_suffix('.zip').read_bytes();verify_archive(raw,self.package)
        self.assertEqual(result['sha256'],target.digest(raw))
        with self.assertRaises(ValueError):target.build(self.package,self.out)
    def test_each_existing_output_is_protected(self):
        for path in (self.out,self.out.with_suffix('.zip'),self.out.with_suffix('.verification.json')):
            path.write_bytes(b'protected')
            with self.subTest(path=path),self.assertRaises(ValueError):target.build(self.package,self.out,True)
            self.assertEqual(path.read_bytes(),b'protected');path.unlink()
    def test_inputs_cannot_change_after_snapshot(self):
        self.input.write_bytes(b'different')
        with self.assertRaises(ValueError):target.build(self.package,self.out,True)
    def test_unsafe_zip_path_rejected_before_any_output(self):
        bad=PackagePlan({'../escape.txt':b'bad'},self.package.inputs,{})
        with self.assertRaises(ValueError):target.build(bad,self.out)
        self.assertFalse(self.out.exists());self.assertFalse((self.directory/'escape.txt').exists())
    def test_exact_zip_inventory_rejects_extra_runtime(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w')as archive:
            archive.writestr('checks/fixture.txt',self.item.raw);archive.writestr('mods/piq-retro-platform.jar',b'bad')
        with self.assertRaises(ValueError):verify_archive(data.getvalue(),self.package)
    def test_test_counts_require_three_exact_distinct_projects(self):
        expected=target.parse_counts(['fc=1000/7','sfc=250/0','native=46/0'])
        self.assertEqual(expected,{'fc':(1000,7),'sfc':(250,0),'native':(46,0)})
        for values in (['fc=1000/7'],['fc=1/0','fc=2/0','sfc=2/0','native=2/0'],
                       ['fc=0/0','sfc=2/0','native=2/0'],['fc=2/2','sfc=2/0','native=2/0']):
            with self.subTest(values=values),self.assertRaises(ValueError):target.parse_counts(values)
    def test_xml_counts_and_failure_status_are_real_inputs(self):
        root=self.directory/'project-root';results=root/'piq-fc-arcade/build/test-results/test';results.mkdir(parents=True)
        xml=results/'TEST-fixture.xml';xml.write_text('<testsuite tests="3" failures="0" errors="0" skipped="1"/>')
        with patch.object(target,'ROOT',root):
            inputs=[];actual=target.test_xml('fc',(3,1),inputs)
            self.assertEqual(actual['tests'],3);self.assertEqual(len(inputs),1)
            with self.assertRaises(ValueError):target.test_xml('fc',(4,1),[])
            xml.write_text('<testsuite tests="3" failures="1" errors="0" skipped="1"/>')
            with self.assertRaises(ValueError):target.test_xml('fc',(3,1),[])
    def test_evidence_must_pass_without_conflicting_status(self):
        target.passing({'ok':True});target.passing({'passed':True})
        for report in ({},{'ok':False},{'passed':1},{'ok':True,'passed':False},{'ok':True,'fixture_only':True}):
            with self.subTest(report=report),self.assertRaises(ValueError):target.passing(report)
    def test_binding_rejects_wrong_hash_path_and_unknown_owner(self):
        good={'ok':True,'jar':str(self.item.path),'sha256':self.item.sha256}
        self.assertEqual(target.report_bindings(good,{'fc':self.item}),{'fc'})
        for bad in (dict(good,sha256='0'*64),{'ok':True},
                    {'ok':True,'jars':{'foreign':{'path':str(self.item.path),'sha256':self.item.sha256}}}):
            with self.subTest(report=bad),self.assertRaises(ValueError):target.report_bindings(bad,{'fc':self.item})
        other=self.directory/'other.txt';other.write_bytes(self.item.raw)
        with self.assertRaises(ValueError):target.report_bindings(dict(good,jar=str(other)),{'fc':self.item})
    def test_final_audit_cannot_omit_real_registration_codec_or_jar_origin(self):
        good={'ok':True,'jar':str(self.item.path),'sha256':self.item.sha256,'compiled_only_probes_and_tests':True,
              'probe':{'ok':True,'pure_tests':37,'assertions':42,'actual_production_registration':True,
                       'actual_neoforge_outer_codec':True,'production_origin':'final-jar-only','production_compiled':False}}
        target.check_audit(good,{'fc':self.item})
        for field,value in (('pure_tests',0),('actual_production_registration',False),('actual_neoforge_outer_codec',False),
                            ('production_origin','source'),('production_compiled',True)):
            bad=copy.deepcopy(good);bad['probe'][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):target.check_audit(bad,{'fc':self.item})

if __name__=='__main__':unittest.main()
