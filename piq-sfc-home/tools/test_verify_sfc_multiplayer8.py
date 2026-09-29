import unittest
import verify_sfc_multiplayer8 as v

class DeltaTests(unittest.TestCase):
    def test_only_reviewed_classes_may_change(self):
        before={'assets/piq_sfc_home/model.json':b'model','cn/piq/sfchome/client/SfcHomeClient.class':b'old'}
        after=before|{'cn/piq/sfchome/client/SfcHomeClient.class':b'new','cn/piq/sfchome/client/SfcPlayback$Host.class':b'host'}
        self.assertEqual(1,v.verify_delta(before,after)['all_asset_entries_unchanged'])
        for name in ('assets/piq_sfc_home/model.json','cn/piq/sfcarcade/Core.class','cn/piq/sfchome/world/SfcHomeConsoleBlock.class'):
            with self.subTest(name=name),self.assertRaises(ValueError):v.verify_delta(before,after|{name:b'bad'})
    def test_no_deletions(self):
        with self.assertRaises(ValueError):v.verify_delta({'old.class':b'old'}, {})

if __name__=='__main__':unittest.main()
