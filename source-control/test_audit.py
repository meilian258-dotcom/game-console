import unittest
from audit import path_issues, content_issues

class GuardTests(unittest.TestCase):
    def test_source_scope(self):
        self.assertEqual([],path_issues('README.md'))
        self.assertEqual([],path_issues('piq-fc-arcade/src/main/java/cn/piq/Test.java'))
        self.assertEqual([],path_issues('piq-md-home/src/main/resources/assets/piq_md_home/models/block/md2_empty.json'))
        self.assertIn('outside-source-scope',path_issues('piq-server-assistant/server.py'))
    def test_credentials(self):
        for name in ['.env','登录信息.txt','keys/server.pem','private/config.json']:
            self.assertTrue(path_issues('piq-gba/'+name),name)
        self.assertIn('private-key',content_issues('sample.txt',b'-----BEGIN '+b'PRIVATE KEY-----'))
        self.assertIn('possible-secret-literal',content_issues('sample.txt',b'password = '+b'"a123456789abcdef"'))
    def test_no_false_positive_variable(self):
        self.assertEqual([],content_issues('sample.java',b'var password = prompt();'))
        self.assertEqual([],content_issues('sample.py',b'password = "test-placeholder"'))
    def test_no_build_rom_saves(self):
        for name in ['build/libs/mod.jar','run/world/level.dat','game-console/save.json','tools/game.nes','src/main/resources/pvz/main.pak']:
            self.assertTrue(path_issues('piq-fc-arcade/'+name),name)
    def test_wrapper_exception(self):
        name='piq-fc-arcade/gradle/wrapper/gradle-wrapper.jar'
        self.assertEqual([],path_issues(name));self.assertEqual([],content_issues(name,b'PK\x03\x04'))
        self.assertTrue(path_issues('piq-gba/arbitrary.jar'))
    def test_mislabeled_executable(self):
        self.assertIn('executable-magic',content_issues('piq-gba/innocent.txt',b'MZ'+b'\0'*60))
        self.assertIn('rom-magic',content_issues('piq-gba/innocent.txt',b'NES\x1a'+b'\0'*60))
    def test_size_and_paths(self):
        self.assertTrue(path_issues('../secret.txt'))
        self.assertTrue(path_issues('piq-gba/../../secret.txt'))
        self.assertIn('over-10MiB-review-required',content_issues('big.txt',b' '* (10*1024*1024+1)))
    def test_no_game_captures(self):
        self.assertTrue(path_issues('piq-fc-arcade/design/zapper-private/frame-0001.png'))
        self.assertTrue(path_issues('piq-gba/design/frame-0123.png'))
    def test_world_java_package_is_source_not_save_directory(self):
        self.assertEqual([],path_issues('piq-fc-arcade/src/main/java/cn/piq/fcarcade/world/FcArcadeBlock.java'))
        self.assertEqual([],path_issues('piq-fc-arcade/src/test/java/cn/piq/fcarcade/world/FcArcadeBlockTest.java'))
        self.assertTrue(path_issues('piq-fc-arcade/world/playerdata.json'))
        self.assertTrue(path_issues('piq-fc-arcade/run/world/level.dat'))

if __name__=='__main__':unittest.main()
