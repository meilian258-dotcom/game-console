"""Build policy tests. Set TEMP/TMP to the chosen work disk for local fixtures."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import types

spec=importlib.util.spec_from_file_location('replay_build',Path(__file__).resolve().parents[1]/'build.py')
build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)


class BuildPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture=Path(tempfile.mkdtemp(prefix='piq-replay-build-policy-'))

    def test_fresh_output_allowed_without_preemptive_creation(self):
        path=self.fixture/'new-build'
        self.assertEqual(build.validate_build_directory(path),path)
        self.assertFalse(path.exists())

    def test_existing_output_preserved_and_refused(self):
        path=self.fixture/'previous';path.mkdir()
        artifact=path/'previous.jar';artifact.write_bytes(b'previous evidence')
        with self.assertRaises(FileExistsError):build.validate_build_directory(path)
        self.assertEqual(artifact.read_bytes(),b'previous evidence')

    def test_relative_parent_nonascii_and_broad_roots_refused(self):
        for path in (Path('relative'),self.fixture/'..'/'escape',self.fixture/'中文',Path(self.fixture.anchor),Path.home(),build.ROOT):
            with self.subTest(path=path),self.assertRaises(ValueError):build.validate_build_directory(path)

    @unittest.skipUnless(os.name=='nt','Windows system-drive policy')
    def test_windows_system_drive_is_not_a_build_workspace(self):
        with self.assertRaises(ValueError):build.validate_build_directory(Path(os.environ.get('SystemDrive','C:')+'/piq-uncreated-build-test'))

    def test_environment_is_per_child_and_does_not_mutate_global_temp(self):
        before=dict(os.environ);target=self.fixture/'process-temp';result=build.build_environment(target)
        self.assertEqual(os.environ,before);self.assertEqual(result['TEMP'],str(target));self.assertEqual(result['TMP'],str(target))
        for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'):self.assertNotIn(key,result)

    def test_public_contract_gate_is_required_and_failures_propagate(self):
        target=self.fixture/'future-main.jar'
        verifier=Mock(return_value={'guard':1})
        with patch.dict('sys.modules',{'replay_api':types.SimpleNamespace(inspect_archive=verifier)}):
            self.assertEqual(build.verify_main_provider(target),{'guard':1})
            verifier.assert_called_once_with(target,require=True)
            verifier.side_effect=ValueError('incompatible public contract')
            with self.assertRaisesRegex(ValueError,'incompatible public contract'):
                build.verify_main_provider(target)


if __name__=='__main__':unittest.main(verbosity=2)
