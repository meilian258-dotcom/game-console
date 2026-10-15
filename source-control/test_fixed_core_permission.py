"""Fixed-input notice and packaging contracts; not a legal approval."""
import hashlib
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
NAME = 'MD-ARCADE-LINKING-EXCEPTION.txt'
MODULES = ('piq-fc-arcade', 'piq-retro-platform', 'piq-md-home', 'piq-native-arcade')
HELPER_SOURCE_BODIES = {
    'BridgeProtocol.java': '68e6dd99a6bd0669ed390e00ab586561ab3ebe3846b9e1eec41b0bcc3cf8eb20',
    'NativeInputPorts.java': 'c6190d42e3e72c2af6c2338094990387f6ee6aa8f838381e8d636443a55f2c2b',
    'NativeArcadeButtons.java': 'a250d0d0728e7871217f01c5bdc018e0d63d9542b184f6ea4dac52df04fc7fb2',
}
HELPER_SOURCE_ROOT = ROOT / 'piq-native-arcade/src/main/java/cn/piq/nativearcade/bridge'


class FixedCorePermissionTests(unittest.TestCase):
    def test_permission_identifies_exact_modified_cores_and_corresponding_sources(self):
        text = (ROOT / 'piq-fc-arcade' / NAME).read_text('utf8')
        for identity in (
            '9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7',
            'dd4f5ef7ad3bae410854da8d0d7b99c99f13caa1df77f4ef1f2b8f59be3b6977',
            '8200a6d5e7c39f60afefd8e87e369d253f737de6a73bf8d920d2ab81888a5fb5',
            'adeb3901c851c941fbeb07080aac3c49f64f4c3be5faf4d315cb1f015ea0b3fb',
            '3e0ab5db5898e9e75e1704caa703d6f4e4cb269c4f971ab498b434f22d18f885',
            '3c8a61cf1519e1f27df3776822a1be54573d640b671e6885088bbd60f41100dc',
        ):
            self.assertIn(identity, text)
        for module in MODULES:
            self.assertIn(module + '/src/main/java/', text)
        for boundary in ("Grantor's copyright interests", 'covered code remains GPL-3.0-or-later',
                         'permission grants no commercial rights', 'complete corresponding source',
                         'does not grant an exception for RetroArch', 'Neither permission expands the other'):
            self.assertIn(boundary, text)

    def test_consumers_package_one_canonical_text(self):
        for module in MODULES:
            source = NAME if module == 'piq-fc-arcade' else '../piq-fc-arcade/' + NAME
            script = (ROOT / module / 'build.gradle').read_text('utf8')
            self.assertIn("from('" + source + "') { into 'META-INF/licenses' }", script)
        main = (ROOT / 'piq-fc-arcade/build.gradle').read_text('utf8')
        self.assertIn("from('" + NAME + "') { into 'licenses' }", main)
        md = (ROOT / 'piq-md-home/build.gradle').read_text('utf8')
        self.assertIn("from('../piq-fc-arcade/src/main/resources/META-INF/licenses/GPL-3.0.txt') { into 'META-INF/licenses' }", md)

    def test_covered_program_sources_point_to_permission(self):
        sources = [path for module in MODULES
                   for path in (ROOT / module / 'src/main/java').rglob('*.java')]
        sources += [ROOT / 'piq-fc-arcade/native/libretro-jni' / name
                    for name in ('piq_libretro_jni.cpp', 'runtime_dependencies.h')]
        sources += list((ROOT / 'piq-fc-arcade/libretro-generic-helper/src/main/java').rglob('*.java'))
        self.assertTrue(sources)
        for path in sources:
            lines = path.read_text('utf8').splitlines()
            location = lines[-1:] if path.parent == HELPER_SOURCE_ROOT and path.name in HELPER_SOURCE_BODIES else lines[:5]
            self.assertIn(NAME, '\n'.join(location), str(path.relative_to(ROOT)))

    def test_helper_permission_footer_preserves_reviewed_source_line_numbers(self):
        # javac includes LineNumberTable: prepending even a notice changes the
        # fixed helper identity. Keep the notice and the reviewed body intact;
        # the production helper JAR/catalog/manifest checks remain authoritative.
        footer = '// PIQ-authored code: additional permission in piq-fc-arcade/' + NAME + '.\n'
        for name, digest in HELPER_SOURCE_BODIES.items():
            text = (HELPER_SOURCE_ROOT / name).read_text('utf8')
            with self.subTest(source=name):
                self.assertEqual(1, text.count(footer))
                self.assertTrue(text.endswith(footer))
                body = text[:-len(footer)]
                self.assertEqual(digest, hashlib.sha256(body.encode('utf8')).hexdigest())

    def test_runtime_notices_are_part_of_future_package_inputs(self):
        main = (ROOT / 'piq-fc-arcade/build.gradle').read_text('utf8')
        for name in ('compiler-rt-LICENSE.txt', 'MinGW-w64-v14.0.0-COPYING',
                     'MinGW-w64-v14.0.0-project-notices.txt',
                     'MinGW-w64-v14.0.0-runtime-notices.txt'):
            self.assertIn("'" + name + "'", main)
            self.assertGreater((ROOT / 'piq-fc-arcade/native/libretro-jni/licenses' / name).stat().st_size, 100)
        sfc = (ROOT / 'piq-sfc-arcade/build.gradle').read_text('utf8')
        self.assertIn("from('third-party/wasm-runtime') { into 'META-INF/licenses/sfc-wasm-runtime' }", sfc)


if __name__ == '__main__':
    unittest.main()
