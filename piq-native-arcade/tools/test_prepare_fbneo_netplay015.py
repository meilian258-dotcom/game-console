import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_fbneo_netplay015 as prepare


class FixedInputsTest(unittest.TestCase):
    def test_reviewed_assets_and_clean_build_receipt(self):
        result = prepare.validate()
        self.assertEqual(result['coreSha256'].upper(), prepare.CORE_SHA)
        self.assertFalse(result['binaryReproducibilityVerified'])

    def test_corrupt_fixed_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'test.bin').write_bytes(b'bad')
            with patch.object(prepare, 'PINS', {'test.bin': (3, '0' * 64)}):
                with self.assertRaisesRegex(ValueError, 'fixed input mismatch'):
                    prepare.validate(root)

    def test_wrong_provenance_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'build.json').write_text(json.dumps({'coreSha256': '1'*64}), encoding='utf8')
            pins = {name: (None, '0'*64) for name in prepare.PINS}
            with patch.object(prepare, 'PINS', pins), patch.object(prepare, 'digest', return_value='0'*64):
                with self.assertRaisesRegex(ValueError, 'build provenance mismatch'):
                    prepare.validate(root)


if __name__ == '__main__':
    unittest.main()
