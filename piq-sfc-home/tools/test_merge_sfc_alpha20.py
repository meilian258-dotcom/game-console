import io,tempfile,tomllib,unittest,zipfile
from pathlib import Path
import merge_sfc_addon as old
import merge_sfc_alpha20 as new

FINAL_HOME6=old.ROOT/'制作Mod/03-街机模拟/PIQ-SFC家用/0.1.0-alpha.6/piq_sfc-0.1.0-alpha.6.jar'
def zipped(entries):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w')as jar:
        for name,raw in entries.items():jar.writestr(name,raw)
    return out.getvalue()
def fixtures():
    _,core=old.read_archive(old.FROZEN_CORE);_,merged=old.read_archive(FINAL_HOME6)
    home={k:v for k,v in merged.items()if k not in core or k in old.SYNTHESIZED}
    text=merged[old.META].decode('utf-8');text=text[text.rindex('[[mods]]'):]
    home[old.META]=('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n'+text.replace('0.1.0-alpha.6','0.1.0-alpha.7').replace('[0.31.0-alpha.19,0.32.0)','[0.31.0-alpha.20,0.32.0)')).encode('utf-8')
    home[old.MANIFEST]=b'Manifest-Version: 1.0\nImplementation-Version: 0.1.0-alpha.7\n'
    return core,home

class Alpha20MergeTests(unittest.TestCase):
    def test_home8_requires_explicit_selection_and_preserves_core(self):
        c,h=fixtures()
        h[old.META]=h[old.META].replace(b'0.1.0-alpha.7',b'0.1.0-alpha.8')
        with self.assertRaises(ValueError):new.combined_metadata(c,h)
        value=tomllib.loads(new.combined_metadata(c,h,'0.1.0-alpha.8').decode())
        self.assertEqual(value['mods'][1]['version'],'0.1.0-alpha.8')
        self.assertEqual(value['mods'][0],tomllib.loads(c[old.META].decode())['mods'][0])
        with self.assertRaises(ValueError):new.combined_metadata(c,h,'0.1.0-alpha.9')
    def test_old_default_guard_still_rejects_fc20(self):
        c,h=fixtures()
        with self.assertRaisesRegex(ValueError,'FC19'):old.combined_metadata(c,h,new.VERSION)
    def test_new_metadata_preserves_both_exact_input_mod_records(self):
        c,h=fixtures();value=tomllib.loads(new.combined_metadata(c,h).decode('utf-8'))
        self.assertEqual(value['mods'],tomllib.loads(c[old.META].decode())['mods']+tomllib.loads(h[old.META].decode())['mods'])
        self.assertEqual(value['dependencies']['piq_sfc_arcade'],tomllib.loads(c[old.META].decode())['dependencies']['piq_sfc_arcade'])
    def test_wrong_fc_minimum_side_type_ordering_and_version_rejected(self):
        for before,after in [('alpha.20','alpha.19'),('type="required"','type="optional"'),('side="BOTH"','side="CLIENT"'),('ordering="AFTER"','ordering="NONE"'),('0.1.0-alpha.7','0.1.0-alpha.6')]:
            c,h=fixtures();text=h[old.META].decode();self.assertIn(before,text);h[old.META]=text.replace(before,after).encode()
            with self.subTest(after=after),self.assertRaises(ValueError):new.combined_metadata(c,h)
    def test_real_immutable_core_merge_deterministic_and_all_other_bytes_preserved(self):
        c,h=fixtures()
        with tempfile.TemporaryDirectory(prefix='piq-merge20-test-')as folder:
            tmp=Path(folder);source=tmp/'home7.jar';source.write_bytes(zipped(h));p=new.plan(old.FROZEN_CORE,source,old.sha(source.read_bytes()))
            one=tmp/'one.jar';two=tmp/'two.jar';old.build(one,p);old.build(two,p);self.assertEqual(one.read_bytes(),two.read_bytes())
            _,actual=old.read_archive(one)
            for name,raw in (c|h).items():
                if name not in old.SYNTHESIZED:self.assertEqual(actual[name],raw,name)
            with self.assertRaisesRegex(ValueError,'overwrite'):old.build(one,p)
    def test_injected_core_or_platform_runtime_rejected(self):
        for name in ['cn/piq/retro/Bad.class','assets/piq_sfc_home/native.dll','assets/piq_sfc_home/game.sfc']:
            c,h=fixtures();h[name]=b'forbidden'
            with self.subTest(name=name),tempfile.TemporaryDirectory(prefix='piq-merge20-negative-')as folder:
                source=Path(folder)/'home.jar';source.write_bytes(zipped(h))
                with self.assertRaises(ValueError):new.plan(old.FROZEN_CORE,source,old.sha(source.read_bytes()))
    def test_wrong_home_hash_rejected(self):
        c,h=fixtures()
        with tempfile.TemporaryDirectory(prefix='piq-merge20-hash-')as folder:
            source=Path(folder)/'home.jar';source.write_bytes(zipped(h))
            with self.assertRaisesRegex(ValueError,'SHA'):new.plan(old.FROZEN_CORE,source,'0'*64)
if __name__=='__main__':unittest.main()
