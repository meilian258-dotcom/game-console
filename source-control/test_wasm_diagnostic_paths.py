import unittest
from wasm_diagnostic_paths import redact


def section(kind, payload):
    size=len(payload);encoded=[]
    while size>=128:
        encoded.append((size&127)|128);size>>=7
    return bytes([kind,*encoded,size])+payload


class DiagnosticPathsTest(unittest.TestCase):
    def test_only_equal_width_data_changes(self):
        code=b'\x01\x02\x00\x0b'
        path=b'C:/Users/example/.cargo/registry/src/crate/src/lib.rs'
        original=b'\0asm\1\0\0\0'+section(10,code)+section(11,b'prefix\0'+path+b'\0suffix')
        result,receipt=redact(original)
        self.assertEqual(len(result),len(original))
        self.assertIn(section(10,code),result)
        self.assertIn(b'.cargo/registry/src/crate/src/lib.rs\0suffix',result)
        self.assertNotIn(b'Users/example',result)
        self.assertEqual(1,len(receipt['replacements']))
        self.assertNotEqual(receipt['originalSha256'],receipt['sha256'])

    def test_reject_code_custom_and_unrelated_paths(self):
        path=b'C:\\Users\\example\\.cargo\\registry\\src\\crate\\lib.rs'
        for kind in (0,10):
            with self.assertRaises(ValueError):
                redact(b'\0asm\1\0\0\0'+section(kind,path))
        with self.assertRaises(ValueError):
            redact(b'\0asm\1\0\0\0'+section(11,b'C:/project/src/lib.rs'))

    def test_malformed(self):
        for raw in (b'',b'\0asm\1\0\0\0\x0b\xff',b'\0asm\1\0\0\0\x0b\x01'):
            with self.assertRaises(ValueError):redact(raw)

    def test_explicit_workspace_prefix(self):
        prefix=b'Z:/example/build-workspace'
        original=b'\0asm\1\0\0\0'+section(11,prefix+b'/component/src/lib.rs\0')
        result,receipt=redact(original,(prefix,))
        self.assertEqual(len(result),len(original))
        self.assertNotIn(prefix,result)
        self.assertIn(b'/component/src/lib.rs',result)
        self.assertEqual(1,len(receipt['replacements']))


if __name__=='__main__':unittest.main()
