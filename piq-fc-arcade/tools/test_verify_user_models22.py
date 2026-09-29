"""Negative controls for additive-method protection; no games or production writes."""
import unittest
from verify_user_models22 import method_comparison,comparable

OLD={'public void tick();':'Code:\n 0: aload_0\n 1: invokevirtual #CP // Method keep:()V\n 4: return'}
ADDED={'public static int[] visualInputs();'}
GETTER='Code:\n 0: iconst_2\n 1: newarray int\n 3: areturn'

class MethodGuard(unittest.TestCase):
    def test_additive_getter_preserves_old(self):
        result=method_comparison(OLD,{**OLD,next(iter(ADDED)):GETTER},ADDED)
        self.assertEqual(['public void tick();'],result['protected_methods'])
    def test_getter_cannot_hide_changed_old_tick(self):
        with self.assertRaises(AssertionError):method_comparison(OLD,{'public void tick();':OLD['public void tick();'].replace('keep:','send:'),next(iter(ADDED)):GETTER},ADDED)
    def test_getter_cannot_write_shared_state(self):
        with self.assertRaises(AssertionError):method_comparison(OLD,{**OLD,next(iter(ADDED)):GETTER+'\n 4: putstatic #CP // Field masks:[I'},ADDED)
    def test_getter_cannot_send(self):
        with self.assertRaises(AssertionError):method_comparison(OLD,{**OLD,next(iter(ADDED)):GETTER+'\n 4: invokestatic #CP // Method XNetwork.send:()V'},ADDED)
    def test_unlisted_new_method_rejected(self):
        with self.assertRaises(AssertionError):method_comparison(OLD,{**OLD,'public void reset();':'Code:\n 0: return'})
    def test_deleted_old_method_rejected(self):
        with self.assertRaises(AssertionError):method_comparison(OLD,{})
    def test_ldc_width_only_normalized(self):
        a='Code:\n 0: ldc #CP // String abc\n 2: ifnull 6\n 5: return\n 6: return'
        b='Code:\n 0: ldc_w #CP // String abc\n 3: ifnull 7\n 6: return\n 7: return'
        self.assertEqual(comparable(a),comparable(b));self.assertNotEqual(comparable(a),comparable(b.replace('ifnull 7','ifnull 6')))
    def test_ui_exception_is_method_limited(self):
        before={**OLD,'protected void init();':'Code:\n 0: return'};after={**before,'protected void init();':'Code:\n 0: nop\n 1: return'}
        self.assertEqual(['public void tick();'],method_comparison(before,after,allowed_ui=(' init(',))['protected_methods'])
    def test_switch_exact_instruction_target_preserved(self):
        a='Code:\n 0: ldc #CP // String abc\n 2: tableswitch {\n 1: 24\n default: 25\n }\n 24: return\n 25: return'
        b='Code:\n 0: ldc_w #CP // String abc\n 3: tableswitch {\n 1: 24\n default: 25\n }\n 24: return\n 25: return'
        self.assertEqual(comparable(a),comparable(b));self.assertNotEqual(comparable(a),comparable(b.replace('default: 25','default: 24')))

if __name__=='__main__':unittest.main()
