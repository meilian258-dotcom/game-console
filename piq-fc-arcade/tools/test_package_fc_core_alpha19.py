"""Synthetic packaging fixtures only: never create or certify a real delivery."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import package_fc_core_alpha19 as package

def zipped(entries):
    raw=io.BytesIO()
    with zipfile.ZipFile(raw,'w',compression=zipfile.ZIP_STORED) as archive:
        for name,data in entries.items():archive.writestr(name,data)
    return raw.getvalue()

def metadata(mods):
    lines=['modLoader="javafml"','loaderVersion="[4,)"']
    for mod,version in mods.items():
        lines+=['[[mods]]','modId="'+mod+'"','version="'+version+'"']
        dependencies={'minecraft':('[1.21.1,1.22)','required','NONE'),'neoforge':('[21.1.236,22)','required','NONE')}
        if mod=='piq_fc_arcade':dependencies['waterframes']=('[2.1.23,3)','optional','AFTER')
        else:dependencies['piq_fc_arcade']=('[0.31.0-alpha.19,0.32.0)','required','AFTER')
        if mod=='piq_sfc_home':dependencies['piq_sfc_arcade']=('[0.2.0-alpha.6,0.3.0)','required','AFTER')
        for dep,(version_range,kind,ordering) in dependencies.items():
            lines+=['[[dependencies.'+mod+']]','modId="'+dep+'"','versionRange="'+version_range+'"','type="'+kind+'"','side="BOTH"','ordering="'+ordering+'"']
    return ('\n'.join(lines)+'\n').encode()

class PackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='piq-alpha19-package-test-');self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name);self.delivery=self.root/'delivery';self.delivery.mkdir()
        self.jars={
            'fc':{package.META:metadata(package.EXPECTED_MODS['fc']),package.MANIFEST:b'Implementation-Version: 0.31.0-alpha.19\n',
                  **{'assets/piq_fc_arcade/textures/'+str(i)+'.png':('frozen-'+str(i)).encode() for i in range(113)},
                  **{'cn/piq/retro/api/'+name+'.class':name.encode() for name in ('RetroEmulator','RetroFrame','RetroBackendRegistry','RetroFactoryRegistry')},
                  'cn/piq/retro/client/GamepadInput.class':b'synthetic-api-class','runtime/nes.wasm':b'frozen-nes-runtime'},
            'sfc':{package.META:metadata(package.EXPECTED_MODS['sfc']),package.MANIFEST:b'Implementation-Version: 0.1.0-alpha.6\n',
                   'assets/piq_sfc_home/textures/home.png':b'frozen-home-art',package.SFC_WASM:b'frozen-sfc-runtime',
                   'cn/piq/sfcarcade/Core.class':b'frozen-sfc-class','cn/piq/sfchome/Home.class':b'synthetic-home-class'},
            'native':{package.META:metadata(package.EXPECTED_MODS['native']),package.MANIFEST:b'Implementation-Version: 0.1.0-alpha.6\n',
                      'assets/piq_native_arcade/textures/cabinet.png':b'frozen-native-art','cn/piq/nativearcade/Native.class':b'synthetic-native-class'},
        }
        old={key:dict(entries) for key,entries in self.jars.items()}
        old['core']={package.META:metadata({'piq_sfc_arcade':'0.2.0-alpha.6'}),package.MANIFEST:b'Implementation-Version: 0.2.0-alpha.6\n',
                     package.SFC_WASM:old['sfc'].pop(package.SFC_WASM),'cn/piq/sfcarcade/Core.class':old['sfc'].pop('cn/piq/sfcarcade/Core.class')}
        baselines={}
        for key,entries in old.items():
            path=self.root/(key+'-old.jar');raw=zipped(entries);path.write_bytes(raw);baselines[key]=(path,package.digest(raw))
        self.addCleanup(patch.stopall);patch.object(package,'DELIVERY',self.delivery).start();patch.object(package,'BASELINES',baselines).start()
        self.audit={'ok':True,'schema':'piq-retro-alpha19-compat-1','jars':{},'installed':False,'minecraft_or_native_core_started':False,
                    'probes':{'production_compiled':False,'old_separate_core_on_classpath':False,
                              'fml_discovery':{'ok':True,'production_origin':'final-jar-only'},
                              'sfc_real_neoforge_outer_packet_codec':{'passed':True,'assertions':37,'max_upload_outer_packet_bytes':30856}}}
        self.build_report={'ok':True,'projects':{key:{'tests':5,'failures':0,'errors':0,'skipped':1} for key in package.PROJECTS},'minecraft_started':False,'installed':False}
        for key in self.jars:self.write_jar(key)
        self.write_document('安装说明.md',b'Synthetic test instructions. Not a real release.')
        self.write_reports()

    def write_jar(self,key):
        path=self.delivery/package.JARS[key];path.parent.mkdir(parents=True,exist_ok=True);raw=zipped(self.jars[key]);path.write_bytes(raw)
        self.audit['jars'][key]={'path':str(path),'sha256':package.digest(raw)}

    def write_document(self,name,raw):
        path=self.delivery/package.DOCUMENTS[name];path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)

    def write_reports(self):
        self.write_document('成品独立检查.json',json.dumps(self.audit).encode());self.write_document('构建测试汇总.json',json.dumps(self.build_report).encode())

    def test_package_exactly_three_jars_four_mod_ids_and_only_explicit_documents(self):
        p=package.plan();result=package.build(p)
        self.assertTrue(result['ok']);self.assertFalse(result['installed']);self.assertEqual(4,len(result['mod_owners']))
        self.assertEqual(113,result['inherited_asset_counts']['fc']);self.assertEqual(0,result['ownership']['duplicate_classes'])
        with zipfile.ZipFile(result['path']) as archive:
            self.assertEqual(7,len(archive.namelist()));self.assertEqual(3,sum(n.endswith('.jar') for n in archive.namelist()))
            self.assertEqual(set(p.payloads),set(archive.namelist()))
            for name,raw in p.payloads.items():self.assertEqual(raw,archive.read(name))
        self.assertEqual(package.digest(Path(result['path']).read_bytes()),result['sha256'])
        self.assertFalse(any('piq_sfc_home-' in name or 'piq_sfc_arcade-' in name or 'platform' in name for name in p.payloads))
        with self.assertRaisesRegex(ValueError,'overwrite'):package.build(p)

    def test_check_only_creates_no_final_package_or_report(self):
        result=package.build(package.plan(),True)
        self.assertTrue(result['check_only']);self.assertFalse((self.delivery/package.OUTPUT_NAME).exists())
        self.assertFalse((self.delivery/package.OUTPUT_NAME).with_suffix('.verification.json').exists())

    def test_audit_hash_mismatch_is_rejected(self):
        self.audit['jars']['fc']['sha256']='0'*64;self.write_reports()
        with self.assertRaisesRegex(ValueError,'SHA mismatch'):package.plan()

    def test_audit_cannot_redirect_to_thin_sfc_or_another_path(self):
        alternate=self.root/'piq_sfc_home-0.1.0-alpha.6.jar';alternate.write_bytes(zipped(self.jars['sfc']))
        self.audit['jars']['sfc']['path']=str(alternate);self.write_reports()
        with self.assertRaisesRegex(ValueError,'path differs'):package.plan()

    def test_unpassed_fixture_and_recompiled_audits_are_rejected(self):
        original=copy.deepcopy(self.audit)
        for change in ('not-ok','fixture','schema','recompiled','separate-core','not-final-jar','packet-limit'):
            self.audit=copy.deepcopy(original)
            if change=='not-ok':self.audit['ok']=False
            elif change=='fixture':self.audit['fixture_only']=True
            elif change=='schema':self.audit['schema']='other'
            elif change=='recompiled':self.audit['probes']['production_compiled']=True
            elif change=='separate-core':self.audit['probes']['old_separate_core_on_classpath']=True
            elif change=='not-final-jar':self.audit['probes']['fml_discovery']['production_origin']='temporary-fixture'
            else:self.audit['probes']['sfc_real_neoforge_outer_packet_codec']['max_upload_outer_packet_bytes']=32767
            self.write_reports()
            with self.subTest(change=change),self.assertRaises(ValueError):package.plan()

    def test_missing_or_failed_build_project_is_rejected(self):
        original=copy.deepcopy(self.build_report)
        for change in ('missing','failure','error','empty','all-skipped','bool-count'):
            self.build_report=copy.deepcopy(original);counts=self.build_report['projects']['piq-fc-arcade']
            if change=='missing':del self.build_report['projects']['piq-native-arcade']
            elif change=='failure':counts['failures']=1
            elif change=='error':counts['errors']=1
            elif change=='empty':counts['tests']=0
            elif change=='all-skipped':counts['skipped']=counts['tests']
            else:counts['tests']=True
            self.write_reports()
            with self.subTest(change=change),self.assertRaises(ValueError):package.plan()

    def test_missing_instruction_and_duplicate_json_keys_are_rejected(self):
        path=self.delivery/package.DOCUMENTS['安装说明.md'];path.unlink()
        with self.assertRaises(ValueError):package.plan()
        with self.assertRaisesRegex(ValueError,'Duplicate JSON'):package.json_document(b'{"ok":false,"ok":true}')

    def test_thin_sfc_wrong_version_or_extra_platform_mod_are_rejected(self):
        for mods in ({'piq_sfc_home':'0.1.0-alpha.6'},{'piq_sfc_home':'0.1.0-alpha.5','piq_sfc_arcade':'0.2.0-alpha.6'},
                     dict(package.EXPECTED_MODS['sfc'],piq_retro_platform='0.1.0-alpha.1')):
            self.jars['sfc'][package.META]=metadata(mods);self.write_jar('sfc');self.write_reports()
            with self.subTest(mods=mods),self.assertRaisesRegex(ValueError,'Wrong mod IDs'):package.plan()

    def test_duplicate_classes_runtime_and_nested_extra_jars_are_rejected(self):
        original=dict(self.jars['sfc'])
        for name,raw in (('cn/piq/retro/api/RetroFrame.class',b'copy'),('runtime/nes.wasm',b'frozen-nes-runtime'),
                         ('META-INF/jarjar/piq-retro-platform.jar',b'extra'),('private-qa/check.txt',b'private'),
                         ('cn/piq/sfchome/LeakedTest$1.class',b'private-test')):
            self.jars['sfc']=dict(original);self.jars['sfc'][name]=raw;self.write_jar('sfc');self.write_reports()
            with self.subTest(name=name),self.assertRaises(ValueError):package.plan()

    def test_changed_artwork_or_frozen_core_rejected_even_with_matching_new_audit_hash(self):
        self.jars['fc']['assets/piq_fc_arcade/textures/0.png']=b'tampered';self.write_jar('fc');self.write_reports()
        with self.assertRaisesRegex(ValueError,'Frozen model/texture/core'):package.plan()
        self.jars['fc']['assets/piq_fc_arcade/textures/0.png']=b'frozen-0';self.write_jar('fc')
        self.jars['sfc']['cn/piq/sfcarcade/Core.class']=b'changed-core';self.write_jar('sfc');self.write_reports()
        with self.assertRaisesRegex(ValueError,'Frozen SFC6 core'):package.plan()

    def test_changed_baseline_sha_is_rejected(self):
        path,expected=package.BASELINES['fc'];package.BASELINES['fc']=(path,'0'*64)
        with self.assertRaisesRegex(ValueError,'Frozen baseline SHA'):package.plan()

    def test_changed_input_after_plan_is_rejected_before_creating_zip(self):
        p=package.plan();self.write_document('安装说明.md',b'changed after validation')
        with self.assertRaisesRegex(ValueError,'Input changed'):package.build(p)
        self.assertFalse((self.delivery/package.OUTPUT_NAME).exists())

    def test_existing_verification_report_is_not_overwritten(self):
        p=package.plan();report=(self.delivery/package.OUTPUT_NAME).with_suffix('.verification.json');report.write_bytes(b'keep')
        with self.assertRaisesRegex(ValueError,'overwrite'):package.build(p)
        self.assertEqual(b'keep',report.read_bytes());self.assertFalse((self.delivery/package.OUTPUT_NAME).exists())

    def test_added_payload_or_illegal_archive_path_is_rejected(self):
        p=package.plan()
        for name in ('piq_sfc_home-0.1.0-alpha.6.jar','../escape'):
            payloads=dict(p.payloads);payloads[name]=b'not-allowed'
            with self.subTest(name=name),self.assertRaises(ValueError):package.verify_archive(zipped(payloads),p)

if __name__=='__main__':unittest.main()
