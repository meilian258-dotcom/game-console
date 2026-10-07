"""No external tools are launched by these path-discovery tests."""
from pathlib import Path
import importlib.util
import inspect
import os
import tempfile
import unittest
from unittest.mock import patch

from dev_tool_paths import executable, gradle_home, java_home


class DevToolPathsTest(unittest.TestCase):
    def test_gradle_explicit_cache_and_default(self):
        with patch.dict(os.environ, {'GRADLE_USER_HOME': 'custom-cache'}, clear=True):
            self.assertEqual(gradle_home(), Path('custom-cache'))
        with patch.dict(os.environ, {}, clear=True), patch('pathlib.Path.home', return_value=Path('test-home')):
            self.assertEqual(gradle_home(), Path('test-home/.gradle'))

    def test_java_explicit_and_environment(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            (home / 'bin').mkdir()
            (home / 'bin/javac.exe').touch()
            with patch.dict(os.environ, {'JAVA_HOME': folder}, clear=True):
                self.assertEqual(java_home(), home.resolve())
            with patch.dict(os.environ, {}, clear=True):
                self.assertEqual(java_home(folder), home.resolve())

    def test_java_path_and_missing_tools(self):
        with patch.dict(os.environ, {}, clear=True), patch('shutil.which', return_value='/test-jdk/bin/javac'):
            self.assertEqual(java_home(), Path('/test-jdk').resolve())
        with patch.dict(os.environ, {}, clear=True), patch('shutil.which', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'JAVA_HOME'):
                java_home()
            with self.assertRaisesRegex(RuntimeError, 'CUSTOM_TOOL'):
                executable('example-tool', 'CUSTOM_TOOL')

    def test_executable_environment(self):
        with patch.dict(os.environ, {'CUSTOM_TOOL': 'configured-tool'}, clear=True), patch('shutil.which', return_value='resolved-tool') as lookup:
            self.assertEqual(executable('default-tool', 'CUSTOM_TOOL'), 'resolved-tool')
            lookup.assert_called_once_with('configured-tool')


class NativeHelperArgumentsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / 'piq-native-arcade/tools/build_native_helper48.py'
        spec = importlib.util.spec_from_file_location('native_helper_path_test', path)
        cls.helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.helper)

    def test_legacy_output_argument_and_safety_gate(self):
        parameters = inspect.signature(self.helper.build).parameters
        self.assertEqual(parameters['output'].kind, inspect.Parameter.POSITIONAL_OR_KEYWORD)
        with self.assertRaisesRegex(ValueError, 'beneath this project build directory'):
            self.helper.build(Path('not-an-allowed-helper.jar'))

    def test_explicit_jna_keeps_hash_gate_before_tool_execution(self):
        with tempfile.TemporaryDirectory() as folder:
            wrong = Path(folder) / 'wrong-jna.jar'
            wrong.write_bytes(b'not the pinned JNA dependency')
            with patch.object(self.helper.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'Fixed JNA compile dependency changed'):
                    self.helper.build(jna=wrong)
                run.assert_not_called()

    def test_cli_forwarding_keeps_old_default(self):
        with patch.object(self.helper, 'build', return_value={'checked': True}) as build, patch('builtins.print'):
            self.helper.main(['--output', 'candidate.jar', '--jna', 'dependency.jar', '--java-home', 'jdk-home'])
            build.assert_called_once_with(Path('candidate.jar'), jna=Path('dependency.jar'), java_home_path=Path('jdk-home'))


if __name__ == '__main__':
    unittest.main()
