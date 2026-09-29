import io,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import verify_compact_ui_alpha20 as audit
import freeze_fc_compact_alpha20 as freeze
from check_fc_cartridge_workbench import instruction_positions

def zipped(entries):
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w')as jar:
        for name,raw in entries.items():jar.writestr(name,raw)
    return output.getvalue()

def fixture():
    old=audit.originals();new={key:dict(entries)for key,entries in old.items()}
    for key in ('fc','sfc'):
        before,after=audit.VERSIONS[key]
        new[key][audit.META]=new[key][audit.META].replace(before.encode(),after.encode())
        if key=='sfc':new[key][audit.META]=new[key][audit.META].replace(b'[0.31.0-alpha.19,0.32.0)',b'[0.31.0-alpha.20,0.32.0)')
        new[key][audit.MANIFEST]=new[key][audit.MANIFEST].replace(before.encode(),after.encode())
        for name in audit.ADDED[key]:new[key][name]=b'\xca\xfe\xba\xbe-test-only-not-executed'
    new['sfc'][audit.MESH]=(audit.ROOT/'piq-sfc-home/src/main/resources'/audit.MESH).read_bytes()
    return old,new

class Alpha20GuardTests(unittest.TestCase):
    def test_exact_seven_palette_consumers_accept_only_integer_payloads(self):
        old,new=fixture();self.assertEqual(7,len(audit.PALETTE_ONLY));total=0
        for name in audit.PALETTE_ONLY:
            original=old['fc'][name];changed,proof=audit.rewrite_palette_constants(original)
            self.assertEqual(len(original),len(changed));self.assertTrue(proof);total+=len(proof)
            new['fc'][name]=changed;self.assertTrue(audit.palette_only(name,original,changed)['all_other_class_bytes_identical'])
        self.assertEqual(20,total)
        self.assertEqual(7,len(audit.classify('fc',old['fc'],new['fc'])['palette_constant_only']))
    def test_palette_guard_rejects_code_attributes_unknown_colors_and_unknown_class(self):
        old,new=fixture();name=sorted(audit.PALETTE_ONLY)[0];raw=old['fc'][name];changed,_=audit.rewrite_palette_constants(raw)
        variants=[changed+b'evil',changed[:-1]+bytes([changed[-1]^1]),changed.replace(bytes.fromhex('88000000'),bytes.fromhex('88000001'),1),changed.replace(b'render',b'xender',1)]
        for variant in variants:
            self.assertNotEqual(changed,variant)
            with self.assertRaisesRegex(ValueError,'other byte changes'):audit.palette_only(name,raw,variant)
        with self.assertRaisesRegex(ValueError,'not approved'):audit.palette_only('cn/piq/fcarcade/server/ServerCabinets.class',raw,changed)
        for raw in (b'',b'\xca\xfe\xba\xbe',b'\xca\xfe\xba\xbe'+b'\0'*4+b'\0\2\xff'):
            with self.assertRaises(ValueError):audit.rewrite_palette_constants(raw)
    def test_exact_frozen_baselines_and_reviewed_asset(self):
        old,new=fixture()
        for key in old:audit.classify(key,old[key],new[key])
        self.assertEqual('65A70EB4C77F90B49DEAF76608BF3AF65D4694D50847BF553305484668D9C454',audit.digest(new['sfc'][audit.MESH]))
    def test_non_ui_network_server_core_changes_rejected(self):
        old,new=fixture()
        for key,prefix in [('fc','cn/piq/fcarcade/server/'),('fc','cn/piq/fcarcade/FcNetwork'),('fc','cn/piq/retro/client/GamepadInput'),('sfc','cn/piq/sfchome/net/'),('sfc','cn/piq/sfchome/server/'),('sfc','cn/piq/sfcarcade/core/'),('native','cn/piq/nativearcade/bridge/')]:
            name=next(n for n in old[key]if n.startswith(prefix)and n.endswith('.class'));bad=dict(new[key]);bad[name]+=b'bad'
            with self.subTest(name=name),self.assertRaisesRegex(ValueError,'Unapproved'):audit.classify(key,old[key],bad)
    def test_every_unknown_added_or_removed_entry_rejected(self):
        old,new=fixture()
        for key in old:
            for name in ['cn/piq/Injected.class','assets/new.png','roms/game.sfc','META-INF/jarjar/hidden.jar']:
                bad=dict(new[key]);bad[name]=b'bad'
                with self.subTest(key=key,name=name),self.assertRaisesRegex(ValueError,'inventory'):audit.classify(key,old[key],bad)
            bad=dict(new[key]);del bad[next(n for n in old[key]if n.endswith('.class'))]
            with self.assertRaisesRegex(ValueError,'inventory'):audit.classify(key,old[key],bad)
    def test_only_exact_reviewed_mesh_and_no_other_texture_changes(self):
        old,new=fixture();bad=dict(new['sfc']);bad[audit.MESH]+=b' '
        with self.assertRaisesRegex(ValueError,'reviewed'):audit.classify('sfc',old['sfc'],bad)
        for key in ('fc','sfc','native'):
            name=next(n for n in old[key]if n.startswith('assets/')and n!=audit.MESH and not n.endswith('.wasm'));bad=dict(new[key]);bad[name]+=b'changed'
            with self.assertRaisesRegex(ValueError,'Unapproved'):audit.classify(key,old[key],bad)
    def test_metadata_changes_limited_to_exact_versions_and_home_fc_minimum(self):
        old,new=fixture()
        for key,before,after in [('fc','0.31.0-alpha.20','0.31.0-alpha.19'),('sfc','0.1.0-alpha.7','0.1.0-alpha.6'),('sfc','0.31.0-alpha.20','0.31.0-alpha.19'),('sfc','required','optional'),('native','0.1.0-alpha.6','0.1.0-alpha.7')]:
            bad=dict(new[key]);bad[audit.META]=bad[audit.META].replace(before.encode(),after.encode())
            with self.subTest(key=key,after=after),self.assertRaises(ValueError):audit.classify(key,old[key],bad)
    def test_manifest_cannot_sneak_in_runtime_entrypoint(self):
        old,new=fixture();new['fc'][audit.MANIFEST]+=b'Main-Class: injected.Main\n'
        with self.assertRaisesRegex(ValueError,'Manifest'):audit.classify('fc',old['fc'],new['fc'])
    def test_old_sfc_mod_core_metadata_stays_frozen(self):
        old,new=fixture();new['sfc'][audit.META]=new['sfc'][audit.META].replace(b'0.2.0-alpha.6',b'0.2.0-alpha.7')
        with self.assertRaises(ValueError):audit.classify('sfc',old['sfc'],new['sfc'])
    def test_new_class_requires_class_header(self):
        old,new=fixture();new['fc'][next(iter(audit.ADDED['fc']))]=b'notaclass'
        with self.assertRaisesRegex(ValueError,'invalid'):audit.classify('fc',old['fc'],new['fc'])
    def test_ldc_width_normalization_keeps_branch_target_identity(self):
        one='0: ldc #CP // String x\n2: ifeq 6\n5: return\n6: return'
        two='0: ldc_w #CP // String x\n3: ifeq 7\n6: return\n7: return'
        self.assertEqual(instruction_positions(one),instruction_positions(two))
        self.assertNotEqual(instruction_positions(one),instruction_positions(two.replace('ifeq 7','ifeq 6')))
        self.assertNotEqual(instruction_positions(one),instruction_positions(two.replace('String x','String changed')))
    def test_slot_render_palette_does_not_normalize_any_other_operand(self):
        old='0: ldc #CP // int -1291055086\n2: ifeq 6\n5: return\n6: return'
        new='0: ldc_w #CP // int -2013265920\n3: ifeq 7\n6: return\n7: return'
        expected=instruction_positions(audit.mapped_palette_body(old));self.assertEqual(expected,instruction_positions(new))
        self.assertNotEqual(expected,instruction_positions(new.replace('ifeq 7','ifeq 6')))
        self.assertNotEqual(expected,instruction_positions(new.replace('-2013265920','-2013265919')))
        self.assertEqual('0: ldc #CP // String -1291055086',audit.mapped_palette_body('0: ldc #CP // String -1291055086'))
    def test_freezer_restores_only_two_named_pngs_and_no_other_bytes(self):
        old,new=fixture()
        with tempfile.TemporaryDirectory(prefix='alpha20-freeze-test-')as folder:
            tmp=Path(folder);source=tmp/'build.jar';entries=dict(new['fc']);restore={}
            for name,(_,expected)in freeze.RESTORE.items():
                raw=b'test-only-inherited-source-'+name.encode();entries[name]=raw;restore[name]=(audit.digest(raw),expected)
            source.write_bytes(zipped(entries));output=tmp/'final.jar';report=tmp/'freeze.json'
            with patch.dict(freeze.RESTORE,restore):
                p=freeze.plan(source,audit.digest(source.read_bytes()));check=freeze.freeze(p,output,report,True)
                self.assertTrue(check['check_only']);self.assertFalse(output.exists());self.assertFalse(report.exists())
                result=freeze.freeze(p,output,report);_,actual=audit.read(output)
                self.assertEqual(2,len(result['restored_artwork']))
                for name,raw in entries.items():self.assertEqual(actual[name],old['fc'][name]if name in restore else raw)
                with self.assertRaisesRegex(ValueError,'overwrite'):freeze.freeze(p,output,report)
    def test_freezer_wrong_hash_unknown_png_and_runtime_change_rejected(self):
        old,new=fixture()
        for mode in ('hash','png','runtime'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory(prefix='alpha20-freeze-reject-')as folder:
                entries=dict(new['fc'])
                if mode=='png':entries[next(iter(freeze.RESTORE))]=b'unknown-new-art'
                if mode=='runtime':entries[next(n for n in entries if n.endswith('.wasm'))]+=b'mutated'
                source=Path(folder)/'build.jar';source.write_bytes(zipped(entries));expected='0'*64 if mode=='hash'else audit.digest(source.read_bytes())
                with self.assertRaises(ValueError):freeze.plan(source,expected)
    def test_freezer_changed_source_refuses_commit(self):
        old,new=fixture()
        with tempfile.TemporaryDirectory(prefix='alpha20-freeze-race-')as folder:
            tmp=Path(folder);source=tmp/'build.jar';source.write_bytes(zipped(new['fc']));p=freeze.plan(source,audit.digest(source.read_bytes()));source.write_bytes(source.read_bytes()+b'changed')
            with self.assertRaisesRegex(ValueError,'Input changed'):freeze.freeze(p,tmp/'final.jar',tmp/'report.json')
            self.assertFalse((tmp/'final.jar').exists())
if __name__=='__main__':unittest.main()
