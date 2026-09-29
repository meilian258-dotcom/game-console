import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from build_inputs import cache_path, import_inputs, load_lock, relative, verify


class BuildInputsTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='piq-build-input-test-'))
        self.data = b'pinned test fixture\n'
        self.source = self.root/'fixture.dll'
        self.source.write_bytes(self.data)
        self.entry = dict(filename='fixture.dll', bytes=len(self.data),
                          sha256=hashlib.sha256(self.data).hexdigest(), legacyPath='native/fixture.dll')
        self.cache = self.root/'cache'

    def test_import_is_byte_exact_and_idempotent(self):
        first = import_inputs({'fixture':self.entry}, {'fixture':self.source}, self.cache)
        self.assertEqual(first['added'], ['fixture'])
        target = cache_path(self.cache, self.entry)
        self.assertEqual(target.read_bytes(), self.data)
        before = target.stat().st_mtime_ns
        again = import_inputs({'fixture':self.entry}, {'fixture':self.source}, self.cache)
        self.assertEqual(again['retained'], ['fixture'])
        self.assertEqual(target.stat().st_mtime_ns, before)
        self.assertEqual(self.source.read_bytes(), self.data)

    def test_existing_conflict_never_overwritten(self):
        target = cache_path(self.cache, self.entry); target.parent.mkdir(parents=True)
        target.write_bytes(b'X'*len(self.data))
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            import_inputs({'fixture':self.entry}, {'fixture':self.source}, self.cache)
        self.assertEqual(target.read_bytes(), b'X'*len(self.data))

    def test_missing_source_preflight_prevents_partial_import(self):
        with self.assertRaisesRegex(ValueError, 'Missing'):
            import_inputs({'one':self.entry,'two':self.entry},
                          {'one':self.source,'two':self.root/'missing'}, self.cache)
        self.assertFalse(self.cache.exists())

    def test_bad_size_and_equal_size_bad_hash(self):
        self.source.write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError, 'size'): verify(self.source, self.entry)
        self.source.write_bytes(b'X'*len(self.data))
        with self.assertRaisesRegex(ValueError, 'SHA256'): verify(self.source, self.entry)

    def test_path_traversal_and_absolute_paths(self):
        for name in ('../x','/x','C:/x','a\\x','a/../b','a//b','./b',''):
            with self.subTest(name=name), self.assertRaises(ValueError): relative(name)

    def test_manifest_validation(self):
        manifest=self.root/'inputs.json'
        for change in (dict(filename='../x.dll'),dict(sha256='not-a-hash'),dict(bytes=-1),dict(bytes=True),dict(legacyPath='../secret')):
            manifest.write_text(json.dumps(dict(schemaVersion=1,inputs={'fixture':{**self.entry,**change}})),encoding='utf8')
            with self.assertRaises(ValueError): load_lock(manifest)
        manifest.write_text(json.dumps(dict(schemaVersion=1,inputs={'fixture':self.entry})),encoding='utf8')
        self.assertEqual(load_lock(manifest), {'fixture':self.entry})

    def test_cli_missing_cache_and_unknown_id_fail(self):
        script=Path(__file__).with_name('build_inputs.py')
        result=subprocess.run([sys.executable,str(script),'check','--cache',str(self.cache)],capture_output=True)
        self.assertEqual(result.returncode,1)
        report=json.loads(result.stdout)
        self.assertFalse(report['ok']); self.assertEqual(len(report['errors']),9)
        self.assertFalse(self.cache.exists())
        unknown=subprocess.run([sys.executable,str(script),'check','--id','unknown'],capture_output=True)
        self.assertEqual(unknown.returncode,1)

    def test_link_rejected(self):
        link=self.root/'link.dll'
        try: link.symlink_to(self.source)
        except OSError: self.skipTest('This Windows account cannot create symlinks')
        with self.assertRaisesRegex(ValueError, 'reparse'): verify(link,self.entry)


if __name__=='__main__': unittest.main()
