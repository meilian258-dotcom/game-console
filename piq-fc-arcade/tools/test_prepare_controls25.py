"""Validator fixtures, NOT final-JAR evidence. Uses only SHA-pinned new resource bytes.

Never invokes stage.plan(), a compiler, or a game. Arbitrary class bytes below are
deliberate map-diff fixtures, not replacement production classes.
"""
import copy, io, json, tempfile, unittest, warnings, zipfile
from pathlib import Path
from unittest.mock import patch
import prepare_controls25 as stage
from freeze_fc_core_alpha19 import checked_zip

class ControlsScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.resources={name:(stage.ROOT/'piq-fc-arcade/src/main/resources'/name).read_bytes()
                       for name in stage.RESOURCES}
        for name,value in cls.resources.items():
            if stage.digest(value)!=stage.RESOURCES[name]:
                raise AssertionError('Approved actual resource changed: '+name)

    def maps(self,kind='fc'):
        old={'assets/example/old.png':b'fixture artwork','data/example/table.json':b'fixture data',
             'core/old.wasm':b'fixture old binary','META-INF/jarjar/metadata.json':b'fixture runtime'}
        new=dict(old)
        if kind=='fc':
            old.update({n:b'fixture previous artwork' for n in self.resources if n.startswith('assets/')})
            old['cn/piq/fcarcade/client/ArcadeBlockScreenRenderer$1.class']=b'obsolete switch fixture'
            new.update(self.resources)
            new.update({n:b'fixture approved class inventory' for n in stage.NEW_CLASSES['fc']})
            new['piq_fc_keyboard.mixins.json']=json.dumps(stage.MIXIN).encode()
            new['cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class']=b'fixture mixin class'
        return old,new

    def rejects_delta(self,name,value=b'unapproved',kind='fc'):
        old,new=self.maps(kind);old[name]=b'fixture existing';new[name]=value
        with self.assertRaises(ValueError):stage.classify(kind,old,new)

    def test_exact_two_assets_independent_wasm_and_obsolete_switch_removal(self):
        old,new=self.maps();result=stage.classify('fc',old,new)
        self.assertEqual(result['removed'],['cn/piq/fcarcade/client/ArcadeBlockScreenRenderer$1.class'])
        self.assertEqual(set(result['changed']),{n for n in self.resources if n.startswith('assets/')})
        self.assertIn('core/nes_zapper_v1.wasm',result['added'])
        self.assertEqual(result['protected_assets_data_cores_unchanged'],3)

    def test_existing_authorized_outer_changes_allowed(self):
        for kind,n in (('fc','cn/piq/fcarcade/client/ArcadeBlockScreenRenderer.class'),
                       ('sfc','cn/piq/sfchome/client/SfcHomeClient.class'),
                       ('native','cn/piq/nativearcade/client/NativeArcadeClient.class')):
            with self.subTest(kind=kind):
                old,new=self.maps(kind);old[n]=b'old fixture';new[n]=b'new fixture'
                self.assertIn(n,stage.classify(kind,old,new)['changed'])

    def test_exact_new_geometry_nested_class_allowed(self):
        old,new=self.maps();n='cn/piq/fcarcade/layout/ScreenSurfaceGeometry$Surface.class'
        new[n]=b'fixture record';self.assertIn(n,stage.classify('fc',old,new)['added'])

    def test_arbitrary_old_texture_rejected(self):self.rejects_delta('assets/example/old.png')
    def test_old_data_rejected(self):self.rejects_delta('data/example/table.json')
    def test_old_core_binary_rejected(self):self.rejects_delta('core/old.wasm')
    def test_nested_runtime_metadata_rejected(self):self.rejects_delta('META-INF/jarjar/metadata.json')
    def test_original_nes_core_implementation_rejected(self):self.rejects_delta('cn/piq/fcarcade/core/wasm/WasmNesCore.class')
    def test_old_sfc_core_rejected(self):self.rejects_delta('cn/piq/sfcarcade/core/WasmSfcCore.class',kind='sfc')

    def test_all_old_wire_and_server_entries_rejected(self):
        for kind,n in (('fc','cn/piq/fcarcade/cabinet/CabinetRoomNetwork$Input.class'),
                       ('fc','cn/piq/fcarcade/cabinet/CabinetRooms.class'),
                       ('sfc','cn/piq/sfchome/net/SfcHomeNetwork$Frames.class'),
                       ('sfc','cn/piq/sfchome/server/SfcHomeServer.class'),
                       ('native','cn/piq/nativearcade/net/NativeArcadeNetwork.class')):
            with self.subTest(name=n):self.rejects_delta(n,kind=kind)

    def test_new_roms_assets_runtime_and_unapproved_classes_rejected(self):
        for n in ('roms/test.nes','roms/test.sfc','bios.zip','helper.jar','assets/example/new.png',
                  'cn/piq/fcarcade/client/ArcadeBlockScreenRendererExtra.class',
                  'cn/piq/retro/client/KeyboardInputTest.class','core/nes_other.wasm'):
            with self.subTest(name=n):
                old,new=self.maps();new[n]=b'fixture forbidden payload'
                with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_unreviewed_nested_probe_cannot_hide_under_authorized_outer(self):
        old,new=self.maps();new['cn/piq/retro/client/KeyboardInput$UnexpectedProbe.class']=b'fixture probe'
        with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_each_pinned_resource_rejects_one_byte_corruption(self):
        for n in self.resources:
            with self.subTest(name=n):
                old,new=self.maps();new[n]=new[n]+b'!'
                with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_independent_wasm_is_required(self):
        old,new=self.maps();del new['core/nes_zapper_v1.wasm']
        with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_arbitrary_deletion_rejected(self):
        for kind in ('fc','sfc','native'):
            with self.subTest(kind=kind):
                old,new=self.maps(kind);del new['assets/example/old.png']
                with self.assertRaises(ValueError):stage.classify(kind,old,new)

    def test_required_old_switch_deletion_is_exact(self):
        old,new=self.maps();new.update({n:old[n] for n in stage.REMOVED['fc']})
        with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_mixin_config_cannot_weaken_or_add_mixins(self):
        for edit in ({'required':False},{'client':['KeyboardHandlerMixin','UnknownMixin']},
                     {'injectors':{'defaultRequire':0}},{'server':['KeyboardHandlerMixin']}):
            with self.subTest(edit=edit):
                old,new=self.maps();config=copy.deepcopy(stage.MIXIN);config.update(edit)
                new['piq_fc_keyboard.mixins.json']=json.dumps(config).encode()
                with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_declared_mixin_config_cannot_be_missing(self):
        old,new=self.maps();del new['piq_fc_keyboard.mixins.json']
        with self.assertRaises(ValueError):stage.classify('fc',old,new)

    def test_declared_mixin_class_cannot_be_missing(self):
        old,new=self.maps();del new['cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class']
        with self.assertRaises(ValueError):stage.classify('fc',old,new)

class MetadataTest(unittest.TestCase):
    def fixture(self,kind):
        owner={'fc':'piq_fc_arcade','sfc':'piq_sfc_home','native':'piq_native_arcade'}[kind]
        before={'fc':'0.31.0-alpha.24','sfc':'0.1.0-alpha.13','native':'0.1.0-alpha.8'}[kind]
        old='modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n'
        old+='[[mods]]\nmodId="'+owner+'"\nversion="'+before+'"\n'
        if kind=='sfc':old+='[[mods]]\nmodId="piq_sfc_arcade"\nversion="0.2.0-alpha.6"\n'
        if kind!='fc':old+='[[dependencies.'+owner+']]\nmodId="piq_fc_arcade"\nversionRange="[0.31.0-alpha.24,0.32.0)"\ntype="required"\nside="BOTH"\nordering="AFTER"\n'
        new=old.replace(before,stage.VERSIONS[kind])
        if kind=='fc':new+='[[mixins]]\nconfig="piq_fc_keyboard.mixins.json"\n'
        else:new=new.replace('[0.31.0-alpha.24,0.32.0)','[0.31.0-alpha.25,0.32.0)')
        manifest=('Manifest-Version: 1.0\r\nImplementation-Version: '+before+'\r\n\r\n').encode()
        return {stage.META:old.encode(),stage.MANIFEST:manifest},{stage.META:new.encode(),stage.MANIFEST:manifest.replace(before.encode(),stage.VERSIONS[kind].encode())}

    def test_precise_owner_version_and_dependency_change_allowed(self):
        for kind in ('fc','sfc','native'):
            with self.subTest(kind=kind):stage.metadata(kind,*self.fixture(kind))

    def test_unrelated_metadata_change_rejected(self):
        for kind in ('fc','sfc','native'):
            old,new=self.fixture(kind);new[stage.META]=new[stage.META].replace(b'GPL-3.0-or-later',b'changed')
            with self.subTest(kind=kind),self.assertRaises(ValueError):stage.metadata(kind,old,new)

    def test_wrong_version_and_core_version_rejected(self):
        for kind,token in (('fc',b'0.31.0-alpha.25'),('sfc',b'0.2.0-alpha.6'),('native',b'0.1.0-alpha.9')):
            old,new=self.fixture(kind);new[stage.META]=new[stage.META].replace(token,b'9.9.9')
            with self.subTest(kind=kind),self.assertRaises(ValueError):stage.metadata(kind,old,new)

    def test_optional_or_client_only_addon_dependency_rejected(self):
        for kind in ('sfc','native'):
            for before,after in ((b'required',b'optional'),(b'BOTH',b'CLIENT'),(b'alpha.25,',b'alpha.24,')):
                old,new=self.fixture(kind);new[stage.META]=new[stage.META].replace(before,after)
                with self.subTest(kind=kind,edit=after),self.assertRaises(ValueError):stage.metadata(kind,old,new)

    def test_manifest_extra_fields_rejected(self):
        for kind in ('fc','sfc','native'):
            old,new=self.fixture(kind);new[stage.MANIFEST]+=b'Unreviewed: true\r\n'
            with self.subTest(kind=kind),self.assertRaises(ValueError):stage.metadata(kind,old,new)

class ContainerSafetyTest(unittest.TestCase):
    def test_zip_unsafe_or_duplicate_paths_rejected(self):
        for names in (('../outside',),('a\\b',),('/absolute',),('C:/absolute',),('duplicate','duplicate')):
            out=io.BytesIO()
            with warnings.catch_warnings():
                warnings.simplefilter('ignore',UserWarning)
                with zipfile.ZipFile(out,'w')as jar:
                    for name in names:jar.writestr(name.replace('\\','/'),b'fixture')
            raw=out.getvalue()
            # Windows ZipInfo normalizes names while writing; change both actual
            # stored filename fields (not file data) so this is a genuine negative.
            if names==('a\\b',):raw=raw.replace(b'a/b',b'a\\b')
            with self.subTest(names=names),self.assertRaises(ValueError):checked_zip(raw)

    def test_existing_output_refused_before_plan(self):
        with tempfile.TemporaryDirectory(prefix='controls25-validator-',dir=stage.ROOT/'piq-fc-arcade/build')as folder:
            with patch('sys.argv',['prepare_controls25','--output',folder]),patch.object(stage,'plan')as plan:
                with self.assertRaises(ValueError):stage.main()
                plan.assert_not_called()

if __name__=='__main__':unittest.main()
