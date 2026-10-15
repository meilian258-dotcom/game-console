"""Notice/scope contracts, not a legal approval or an executed Gradle build."""
import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
NAME = 'SNES9X-LINKING-EXCEPTION.txt'


class Snes9xPermissionTests(unittest.TestCase):
    def test_permission_is_for_the_fixed_source_and_owned_code_only(self):
        text = (ROOT / 'piq-fc-arcade' / NAME).read_text('utf8')
        lock = json.loads((ROOT / 'source-control/sfc-stock-inputs.json').read_text('utf8'))
        self.assertIn(lock['inputs']['source']['revision'], text)
        for module in ('piq-fc-arcade', 'piq-retro-platform', 'piq-sfc-home', 'piq-sfc-arcade'):
            self.assertIn(module + '/src/main/java/', text)
        for boundary in ("Grantor's copyright interests", 'third-party rights holder',
                         'does not grant commercial rights', 'covered code remains GPL-3.0-or-later',
                         'complete corresponding source', 'later Snes9x revisions'):
            self.assertIn(boundary, text)

    def test_all_consumers_package_one_canonical_text(self):
        for module in ('piq-fc-arcade', 'piq-retro-platform', 'piq-sfc-home', 'piq-sfc-arcade'):
            source = NAME if module == 'piq-fc-arcade' else '../piq-fc-arcade/' + NAME
            script = (ROOT / module / 'build.gradle').read_text('utf8')
            self.assertIn("from('" + source + "') { into 'META-INF/licenses' }", script)
        main = (ROOT / 'piq-fc-arcade/build.gradle').read_text('utf8')
        self.assertIn("from('" + NAME + "') { into 'licenses' }", main)

    def test_call_boundary_sources_locate_the_permission(self):
        for path in (
            'piq-fc-arcade/src/main/java/cn/piq/fcarcade/netplay/JniNetplaySession.java',
            'piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroJniRuntime.java',
            'piq-retro-platform/src/main/java/cn/piq/retro/libretro/jni/NativeLibretroBridge.java',
            'piq-sfc-home/src/main/java/cn/piq/sfchome/core/SfcNetplayProfile.java',
            'piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/core/SfcCore.java',
            'piq-fc-arcade/native/libretro-jni/piq_libretro_jni.cpp',
            'piq-fc-arcade/native/libretro-jni/runtime_dependencies.h',
        ):
            lines = (ROOT / path).read_text('utf8').splitlines()
            self.assertEqual('// SPDX-License-Identifier: GPL-3.0-or-later', lines[0])
            self.assertIn(NAME, lines[1])

    def test_standard_gpl_text_is_not_rewritten(self):
        expected = {
            'piq-fc-arcade/src/main/resources/META-INF/licenses/GPL-3.0.txt':
                '8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903',
            'piq-sfc-home/LICENSE': '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986',
            'piq-sfc-arcade/LICENSE': '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986',
        }
        # Git may check out LF or CRLF. Compare canonical LF text, not checkout policy.
        for path, digest in expected.items():
            raw = (ROOT / path).read_bytes().replace(b'\r\n', b'\n')
            self.assertEqual(digest, hashlib.sha256(raw).hexdigest(), path)


if __name__ == '__main__':
    unittest.main()
