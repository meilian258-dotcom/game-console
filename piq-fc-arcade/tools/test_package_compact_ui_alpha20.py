import copy,tempfile,unittest
from pathlib import Path
from dataclasses import replace
from package_fc_core_alpha19 import Snapshot
import package_compact_ui_alpha20 as pack

class Alpha20PackageTests(unittest.TestCase):
    def fixtures(self,tmp):
        jars={}
        for key in pack.JARS:
            path=tmp/(key+'.jar');path.write_bytes(key.encode())
            sha=pack.audit.BASELINES['native'][1]if key=='native'else key[0].upper()*64
            jars[key]=Snapshot(path,key.encode(),sha)
        audit={'ok':True,'schema':'piq-compact-alpha20-final-1','jars':{k:{'path':str(v.path),'sha256':v.sha256}for k,v in jars.items()},'native_unchanged':True,
               'probes':{'production_compiled':False,'old_separate_core_on_classpath':False,'fml_discovery':{'ok':True,'production_origin':'final-jar-only','assertions':28},
                         'sfc_real_neoforge_outer_packet_codec':{'passed':True,'assertions':37,'max_upload_outer_packet_bytes':30856},
                         'compact_workbench':{'ok':True,'production_origin':'final-jar-only','layouts':108,'assertions':13000}},
               'sfc_mesh_sha256':pack.audit.MESH_SHA,'installed':False,'minecraft_or_native_core_started':False}
        build={'ok':True,'schema':'piq-compact-alpha20-build-1','projects':{p:{'tests':100,'failures':0,'errors':0,'skipped':0}for p in ('piq-fc-arcade','piq-sfc-home')},
               'unchanged':{'piq-native-arcade':{'path':str(jars['native'].path),'sha256':jars['native'].sha256,'rebuilt':False}},
               'jars':{k:{'final_sha256':v.sha256}for k,v in jars.items()},'minecraft_started':False,'installed':False}
        return jars,audit,build
    def test_two_new_builds_and_original_native_accepted(self):
        with tempfile.TemporaryDirectory(prefix='alpha20-package-test-')as folder:
            jars,audit,build=self.fixtures(Path(folder));pack.check_audit(audit,jars);pack.check_build(build,jars)
    def test_failed_or_fake_native_build_rejected(self):
        with tempfile.TemporaryDirectory(prefix='alpha20-package-test-')as folder:
            jars,audit,build=self.fixtures(Path(folder))
            for mode in ('failed','native-new','native-rebuilt','wrong-sha'):
                bad=copy.deepcopy(build)
                if mode=='failed':bad['projects']['piq-sfc-home']['failures']=1
                if mode=='native-new':bad['projects']['piq-native-arcade']={'tests':36,'failures':0,'errors':0,'skipped':0}
                if mode=='native-rebuilt':bad['unchanged']['piq-native-arcade']['rebuilt']=True
                if mode=='wrong-sha':bad['jars']['sfc']['final_sha256']='0'*64
                with self.subTest(mode=mode),self.assertRaises(ValueError):pack.check_build(bad,jars)
    def test_fixtures_nonfinal_probes_and_wrong_version_audit_rejected(self):
        with tempfile.TemporaryDirectory(prefix='alpha20-package-test-')as folder:
            jars,audit,build=self.fixtures(Path(folder))
            for mode in ('fixture','source','no-ui','wrong-schema','old-core','packet-too-big'):
                bad=copy.deepcopy(audit)
                if mode=='fixture':bad['fixture_only']=True
                if mode=='source':bad['probes']['production_compiled']=True
                if mode=='no-ui':bad['probes']['compact_workbench']['ok']=False
                if mode=='wrong-schema':bad['schema']='piq-retro-alpha19-compat-1'
                if mode=='old-core':bad['probes']['old_separate_core_on_classpath']=True
                if mode=='packet-too-big':bad['probes']['sfc_real_neoforge_outer_packet_codec']['max_upload_outer_packet_bytes']=32767
                with self.subTest(mode=mode),self.assertRaises(ValueError):pack.check_audit(bad,jars)
    def test_exact_path_sha_and_native_identity_required(self):
        with tempfile.TemporaryDirectory(prefix='alpha20-package-test-')as folder:
            jars,audit,build=self.fixtures(Path(folder));audit['jars']['fc']['sha256']='0'*64
            with self.assertRaises(ValueError):pack.check_audit(audit,jars)
            jars,audit,build=self.fixtures(Path(folder));jars['native']=replace(jars['native'],sha256='0'*64)
            with self.assertRaises(ValueError):pack.check_audit(audit,jars)
    def test_final_package_fixed_allowlist_contains_no_internal_split_or_runtime(self):
        self.assertEqual(3,len(pack.JARS));self.assertEqual(3,len(pack.DOCS));self.assertIn('piq_sfc-0.1.0-alpha.7.jar',pack.JARS['sfc'])
        self.assertNotIn('piq_sfc_home-',pack.JARS['sfc']);self.assertIn('0.1.0-alpha.6',pack.JARS['native']);self.assertNotIn('platform',str(pack.JARS))
if __name__=='__main__':unittest.main()
