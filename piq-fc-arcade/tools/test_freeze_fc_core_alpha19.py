"""Freezer guards exercised only with isolated temporary fixtures; never delivery paths."""
import io,json,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import freeze_fc_core_alpha19 as freeze

def archive(entries):
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_STORED) as zipped:
        for name,data in entries.items():zipped.writestr(name,data)
    return output.getvalue()

def metadata(version='0.31.0-alpha.19',mod='piq_fc_arcade'):
    return ('modLoader="javafml"\nloaderVersion="[4,)"\n[[mods]]\nmodId="'+mod+'"\nversion="'+version+'"\n').encode()

class FreezerTest(unittest.TestCase):
    def fixture(self,directory):
        root=Path(directory);baseline=root/'old.jar';source=root/'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.19.jar';source.parent.mkdir(parents=True)
        original={'assets/piq_fc_arcade/textures/test-'+str(i)+'.png':b'old-png-'+str(i).encode() for i in range(113)}
        original|={freeze.META:metadata('0.31.0-alpha.18'),freeze.MANIFEST:b'Implementation-Version: 0.31.0-alpha.18\n','cn/piq/fcarcade/Demo.class':b'old-class','core/nes_rust_wasm_bg.wasm':b'original-core'}
        old=archive(original);baseline.write_bytes(old)
        built=dict(original);built[freeze.META]=metadata();built[freeze.MANIFEST]=b'Implementation-Version: 0.31.0-alpha.19\n'
        built['assets/piq_fc_arcade/textures/test-0.png']=b'nonfrozen-source-art';built['cn/piq/fcarcade/Demo.class']=b'new-built-class';source.write_bytes(archive(built))
        return root,baseline,source,built,original

    def test_fixed_release_baseline_is_not_floating(self):
        self.assertEqual('E4A9FC00A492A1E8C7FC4E89D9534A1FBE6A389B64438C393E52D6163AACEC47',freeze.BASELINES['fc'][1])
        self.assertEqual('0.31.0-alpha.19',freeze.TARGETS['fc'][2]);self.assertEqual('0.1.0-alpha.6',freeze.TARGETS['sfc'][2])

    def test_actual_freeze_restores_only_assets_and_preserves_every_other_byte(self):
        with tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
            root,baseline,source,built,original=self.fixture(folder)
            with patch.object(freeze,'ROOT',root),patch.object(freeze,'DELIVERY',root/'delivery'),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                p=freeze.plan('fc');self.assertEqual(1,len(p.restored));result=freeze.freeze(p)
                _,_,actual=freeze.read_jar(Path(result['path']))
                self.assertEqual(set(built),set(actual));self.assertEqual(113,result['inherited_assets'])
                for name in built:self.assertEqual(original[name] if name.startswith('assets/') else built[name],actual[name])
                self.assertEqual(freeze.digest(source.read_bytes()),result['source_sha256'])
                self.assertEqual(0,result['class_core_runtime_entries_injected_or_modified_by_freezer'])
                with self.assertRaisesRegex(ValueError,'overwrite'):freeze.freeze(p)

    def test_check_only_never_creates_output_directory(self):
        with tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
            root,baseline,source,_,_=self.fixture(folder)
            with patch.object(freeze,'ROOT',root),patch.object(freeze,'DELIVERY',root/'delivery'),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                result=freeze.freeze(freeze.plan('fc'),True);self.assertTrue(result['check_only']);self.assertFalse((root/'delivery').exists())

    def test_bad_source_hash_and_asset_inventory_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
            root,baseline,source,built,_=self.fixture(folder)
            with patch.object(freeze,'ROOT',root),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                with self.assertRaisesRegex(ValueError,'Source SHA'):freeze.plan('fc','0'*64)
                del built['assets/piq_fc_arcade/textures/test-0.png'];source.write_bytes(archive(built))
                with self.assertRaisesRegex(ValueError,'inventory'):freeze.plan('fc')

    def test_new_rom_test_class_and_nested_library_are_rejected(self):
        for bad in ('private-qa/data.txt','cn/piq/fcarcade/LeakedTest.class','roms/game.nes','META-INF/jarjar/piq-retro-platform.jar'):
            with self.subTest(bad=bad),tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
                root,baseline,source,built,_=self.fixture(folder);built[bad]=b'negative fixture';source.write_bytes(archive(built))
                with patch.object(freeze,'ROOT',root),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                    with self.assertRaises(ValueError):freeze.plan('fc')

    def test_mod_and_manifest_versions_are_checked(self):
        for changed in ({freeze.META:metadata('0.31.0-alpha.18')},{freeze.META:metadata(mod='piq_retro_platform')},{freeze.MANIFEST:b'Implementation-Version: 0.31.0-alpha.18\n'}):
            with self.subTest(changed=changed),tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
                root,baseline,source,built,_=self.fixture(folder);built.update(changed);source.write_bytes(archive(built))
                with patch.object(freeze,'ROOT',root),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                    with self.assertRaises(ValueError):freeze.plan('fc')

    def test_changed_baseline_sha_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
            root,baseline,_,_,_=self.fixture(folder)
            with patch.object(freeze,'ROOT',root),patch.dict(freeze.BASELINES,{'fc':(baseline,'0'*64)}):
                with self.assertRaisesRegex(ValueError,'baseline changed'):freeze.plan('fc')

    def test_unsafe_archive_names_and_duplicate_entries_are_rejected(self):
        for name in ('../escape','/absolute','a//b','a/./b','C:/drive','bad\x01name'):
            with self.subTest(name=name),self.assertRaises(ValueError):freeze.checked_zip(archive({name:b'x'}))
        # Windows ZipInfo normalizes separators at construction; mutate both on-disk
        # names so the reader, not our fixture writer, sees an actual backslash.
        unsafe=archive({'a/b':b'x'}).replace(b'a/b',b'a\\b')
        with self.assertRaisesRegex(ValueError,'Unsafe ZIP path'):freeze.checked_zip(unsafe)
        raw=io.BytesIO()
        with zipfile.ZipFile(raw,'w') as zipped:
            zipped.writestr('duplicate',b'a')
            import warnings
            with warnings.catch_warnings():warnings.simplefilter('ignore');zipped.writestr('duplicate',b'b')
        with self.assertRaisesRegex(ValueError,'Duplicate'):freeze.checked_zip(raw.getvalue())

    def test_crc_damage_is_rejected(self):
        raw=bytearray(archive({'payload.bin':b'unique-payload'}));index=raw.index(b'unique-payload');raw[index]^=1
        with self.assertRaises(zipfile.BadZipFile):freeze.checked_zip(bytes(raw))

    def test_modified_source_cannot_be_frozen(self):
        with tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
            root,baseline,source,built,_=self.fixture(folder)
            with patch.object(freeze,'ROOT',root),patch.object(freeze,'DELIVERY',root/'delivery'),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                p=freeze.plan('fc');built['new.txt']=b'changed-during-review';source.write_bytes(archive(built))
                with self.assertRaisesRegex(ValueError,'Input changed'):freeze.freeze(p)
                self.assertFalse((root/'delivery/PIQ-FC街机/alpha19-fc-core-addons/piq_fc_arcade-0.31.0-alpha.19.jar').exists())

    def test_preexisting_report_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='piq-alpha19-freeze-test-') as folder:
            root,baseline,_,_,_=self.fixture(folder)
            with patch.object(freeze,'ROOT',root),patch.object(freeze,'DELIVERY',root/'delivery'),patch.dict(freeze.BASELINES,{'fc':(baseline,freeze.digest(baseline.read_bytes()))}):
                report=root/'delivery/PIQ-FC街机/alpha19-fc-core-addons/piq_fc_arcade-0.31.0-alpha.19.freeze.json'
                report.parent.mkdir(parents=True);report.write_bytes(b'keep-existing-report')
                with self.assertRaisesRegex(ValueError,'overwrite'):freeze.freeze(freeze.plan('fc'))
                self.assertEqual(b'keep-existing-report',report.read_bytes())
                self.assertFalse(report.parent/'piq_fc_arcade-0.31.0-alpha.19.jar' in list(report.parent.iterdir()))

if __name__=='__main__':unittest.main()
