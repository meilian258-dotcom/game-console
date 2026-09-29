"""Synthetic validator fixtures ONLY. Not a final audit and never packages real MODs."""
import copy, io, json, tempfile, unittest, zipfile
from pathlib import Path
import package_controls25 as target
from package_fc_core_alpha19 import PackagePlan, snapshot, verify_archive

class ControlsPackageTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory(prefix='controls25-package-fixture-',dir=target.ROOT/'piq-fc-arcade/build')
        self.addCleanup(temp.cleanup);self.directory=Path(temp.name)
        self.entries={'cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class':b'fixture bytecode, never loaded',
                      'piq_fc_keyboard.mixins.json':json.dumps(target.stage.MIXIN).encode(),
                      'core/nes_rust_wasm_bg.wasm':b'fixture old module',
                      'core/nes_zapper_v1.wasm':b'fixture new module'}
        self.jars={}
        for kind in ('fc','sfc','native'):
            out=io.BytesIO()
            with zipfile.ZipFile(out,'w')as jar:
                for n,v in (self.entries if kind=='fc'else {'fixture.txt':kind.encode()}).items():jar.writestr(n,v)
            path=self.directory/(kind+'.jar');path.write_bytes(out.getvalue());self.jars[kind]=snapshot(path)
        self.out=self.directory/'output'

    def binding(self,kinds=('fc',)):
        return {'ok':True,'jars':{k:{'path':str(self.jars[k].path),'sha256':self.jars[k].sha256}for k in kinds}}

    def controls(self):
        r=dict(self.binding(self.jars),schema='piq-controls25-final-1',production_compiled=False,tests={})
        for name,total in (('keyboard',47),('gamepad_and_layout',19),('geometry',38)):
            r['tests'][name]={'ok':True,'production_origin':'final-jar-only','production_compiled':False,
                             'origin_classes':79,'tests_aborted':0,'tests_found':total,'tests_succeeded':total,
                             'tests_failed':0,'tests_skipped':0,'skipped_names':[],'aborted_names':[]}
        r['mixin']={'client_only':True,'registered_exactly_once':True,'head_cancellable':True}
        r['compatibility']={'production_compiled':False,'old_separate_core_on_classpath':False,
            'fml_discovery':{'ok':True,'assertions':28},
            'sfc_real_neoforge_outer_packet_codec':{'ok':True,'assertions':37},
            'hash_checked_temporary_copies':{k:{'sha256':v.sha256,'original':str(v.path),
              'byte_identical_before':True,'byte_identical_after':True}for k,v in self.jars.items()}}
        return r

    def mixin(self):
        return {'ok':True,'schema':'piq-real-keyboard-mixin-1','mode':'final-jar-only','production_compiled':False,
                'minecraft_started':False,'input':{'path':str(self.jars['fc'].path),'sha256':self.jars['fc'].sha256,
                  'mixin_class_sha256':target.digest(self.entries['cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class']),
                  'mixin_config_sha256':target.digest(self.entries['piq_fc_keyboard.mixins.json'])},
                'probe':{'ok':True,'assertions':40,'prefix_instructions':18,'original_instructions_preserved':382,
                  'gui_key_gates':3,'actual_mixin_transformer':True,'router_before_keymapping_click':True,
                  'vanilla_gui_uses_consumed_clicks':True,'cancel_return_before_vanilla':True,'minecraft_classes_defined':0,
                  'mixin_sha256':target.digest(self.entries['cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class'])}}

    def zapper(self):
        return dict(self.binding(),mode='final-jar-only',production_compiled=False,
                    origin={'ok':True,'production_origin':'final-jar-only','production_compiled':False,'origin_assertions':4},
                    diagnostic={'ok':True,'assertions':61,'actual_wasm_core':True},commercial_rom_used=False,minecraft_started=False,
                    legacy_module_sha256=target.digest(self.entries['core/nes_rust_wasm_bg.wasm']),
                    zapper_module_sha256=target.digest(self.entries['core/nes_zapper_v1.wasm']))

    def test_precise_report_shapes_accepted(self):
        target.check_controls(self.controls(),self.jars)
        target.check_mixin(self.mixin(),self.jars)
        target.check_zapper(self.zapper(),self.jars)

    def test_empty_passing_reports_are_not_evidence(self):
        for function in (target.check_controls,target.check_mixin,target.check_zapper):
            with self.subTest(function=function.__name__),self.assertRaises((ValueError,KeyError)):
                function(dict(self.binding(self.jars),production_compiled=False),self.jars)

    def test_real_stage_directory_passes_path_gate_but_missing_reports_rejected(self):
        with self.assertRaisesRegex(ValueError,'Six independent'):
            target.plan(self.directory,{},self.directory/'guide.md',{})

    def test_controls_require_all_final_owners(self):
        r=self.controls();del r['jars']['native']
        with self.assertRaises(ValueError):target.check_controls(r,self.jars)

    def test_controls_reject_failed_missing_or_substitute_groups(self):
        for name in ('keyboard','gamepad_and_layout','geometry'):
            for field,value in (('tests_failed',1),('tests_found',0),('origin_classes',0),
                                ('production_origin','source'),('production_compiled',True),('tests_skipped',1)):
                r=self.controls();r['tests'][name][field]=value
                with self.subTest(name=name,field=field),self.assertRaises(ValueError):target.check_controls(r,self.jars)
        r=self.controls();del r['tests']['geometry']
        with self.assertRaises(ValueError):target.check_controls(r,self.jars)

    def test_only_named_environment_abort_is_allowed(self):
        r=self.controls();t=r['tests']['gamepad_and_layout'];t.update(tests_aborted=1,tests_succeeded=18,
                                            aborted_names=['symlinkConfigCannotReadOrOverwriteAnotherFile()'])
        target.check_controls(r,self.jars)
        t['aborted_names']=['somethingElse()']
        with self.assertRaises(ValueError):target.check_controls(r,self.jars)

    def test_compatibility_requires_real_fml_outer_codec_and_unchanged_final_copies(self):
        for name in ('fml_discovery','sfc_real_neoforge_outer_packet_codec'):
            r=self.controls();r['compatibility'][name]['assertions']=0
            with self.subTest(name=name),self.assertRaises(ValueError):target.check_controls(r,self.jars)
        for name,value in (('sha256','0'*64),('byte_identical_before',False),('byte_identical_after',False)):
            r=self.controls();r['compatibility']['hash_checked_temporary_copies']['fc'][name]=value
            with self.subTest(name=name),self.assertRaises(ValueError):target.check_controls(r,self.jars)
        r=self.controls();r['compatibility']['old_separate_core_on_classpath']=True
        with self.assertRaises(ValueError):target.check_controls(r,self.jars)

    def test_mixin_requires_real_transform_order_and_vanilla_instructions(self):
        for key,value in (('actual_mixin_transformer',False),('router_before_keymapping_click',False),
                          ('cancel_return_before_vanilla',False),('vanilla_gui_uses_consumed_clicks',False),
                          ('gui_key_gates',2),('original_instructions_preserved',381),('minecraft_classes_defined',1)):
            r=self.mixin();r['probe'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):target.check_mixin(r,self.jars)

    def test_mixin_rejects_old_class_config_hash_and_source_mode(self):
        for key in ('sha256','mixin_class_sha256','mixin_config_sha256'):
            r=self.mixin();r['input'][key]='0'*64
            with self.subTest(key=key),self.assertRaises(ValueError):target.check_mixin(r,self.jars)
        r=self.mixin();r['mode']='existing-compiled-production-class'
        with self.assertRaises(ValueError):target.check_mixin(r,self.jars)

    def test_zapper_needs_actual_core_origin_and_immutable_module_bindings(self):
        for group,key,value in (('origin','production_origin','source'),('origin','origin_assertions',0),
                                ('diagnostic','actual_wasm_core',False),('diagnostic','assertions',0)):
            r=self.zapper();r[group][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):target.check_zapper(r,self.jars)
        for key in ('legacy_module_sha256','zapper_module_sha256'):
            r=self.zapper();r[key]='0'*64
            with self.subTest(key=key),self.assertRaises(ValueError):target.check_zapper(r,self.jars)

    def test_wrong_paths_hashes_and_fixture_claims_rejected(self):
        for check,make in ((target.check_controls,self.controls),(target.check_mixin,self.mixin),(target.check_zapper,self.zapper)):
            for key in ('fixture_only','not_a_final_release_validation'):
                r=make();r[key]=True
                with self.subTest(function=check.__name__,key=key),self.assertRaises(ValueError):check(r,self.jars)
        r=self.zapper();r['jars']['fc']['path']=str(self.jars['sfc'].path)
        with self.assertRaises(ValueError):target.check_zapper(r,self.jars)

    def package(self):
        return PackagePlan({'checks/fixture.txt':b'only a validator fixture'},tuple(self.jars.values()),{'fixture_only':True})

    def test_check_only_never_creates_output(self):
        self.assertTrue(target.build(self.package(),self.out,True)['check_only'])
        self.assertFalse(self.out.exists());self.assertFalse(self.out.with_suffix('.zip').exists())

    def test_each_existing_output_is_rejected(self):
        for path in (self.out,self.out.with_suffix('.zip'),self.out.with_suffix('.verification.json')):
            path.write_bytes(b'keep')
            with self.subTest(path=path),self.assertRaises(ValueError):target.build(self.package(),self.out,True)
            self.assertEqual(path.read_bytes(),b'keep');path.unlink()

    def test_input_change_after_snapshot_is_rejected(self):
        self.jars['fc'].path.write_bytes(b'changed fixture')
        with self.assertRaises(ValueError):target.build(self.package(),self.out,True)

    def test_synthetic_roundtrip_only_and_crc_inventory_rejected_on_extra_file(self):
        package=self.package();result=target.build(package,self.out)
        self.assertTrue(result['fixture_only']);verify_archive(self.out.with_suffix('.zip').read_bytes(),package)
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w')as jar:
            for n,v in package.payloads.items():jar.writestr(n,v)
            jar.writestr('mods/unexpected.jar',b'bad')
        with self.assertRaises(ValueError):verify_archive(data.getvalue(),package)

if __name__=='__main__':unittest.main()
