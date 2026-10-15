"""Fixed-input notice and packaging contracts; not a legal approval."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
NAME = 'MD-ARCADE-LINKING-EXCEPTION.txt'
MODULES = ('piq-fc-arcade', 'piq-retro-platform', 'piq-md-home', 'piq-native-arcade')


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
            header = '\n'.join(path.read_text('utf8').splitlines()[:5])
            self.assertIn(NAME, header, str(path.relative_to(ROOT)))

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
