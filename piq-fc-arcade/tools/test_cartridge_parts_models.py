import copy
import unittest
import build_cartridge_parts as model

class CartridgePartsModelTest(unittest.TestCase):
    def test_three_distinct_finite_models_fit_original_card(self):
        result=model.outputs(); report=model.audit(result)
        self.assertTrue(report['ok']); self.assertEqual(4,len(report['models']))
        self.assertEqual(3,len({model.sha(model.encoded(model.board(i))) for i in range(3)}))
        for m in report['models'][:3]:
            self.assertGreaterEqual(m['bounds'][0][0],2.3)
            self.assertLessEqual(m['bounds'][1][0],13.7)
            self.assertGreaterEqual(m['bounds'][0][2],7.25)
            self.assertLessEqual(m['bounds'][1][2],8.7)
            self.assertEqual(0,m['bounds'][0][1])
    def test_shell_has_no_contacts_and_retains_complete_cover(self):
        result=model.shell()
        self.assertFalse(any('金手指' in e['name'] or '线路板' in e['name'] for e in result['elements']))
        original=next(e for e in model.json.loads(model.SOURCE.read_bytes())['elements'] if e['name']=='中央游戏标签')
        front=next(e for e in result['elements'] if e['name']=='前壳 / 中央游戏标签')
        self.assertEqual(original['faces'],front['faces'])
        for axis in range(3):self.assertAlmostEqual(original['to'][axis]-original['from'][axis],front['to'][axis]-front['from'][axis])
    def test_all_runtime_models_equal_reviewed_build(self):
        for path,data in model.outputs().items():self.assertEqual(data,path.read_bytes(),str(path))
    def test_bad_variant_rejected(self):
        for v in (-1,3,200):
            with self.assertRaises(ValueError):model.board(v)
