"""Negative fixtures for offset normalization; no production classes are changed."""
import unittest
from check_fc_cartridge_workbench import instruction_positions

class InstructionComparisonTest(unittest.TestCase):
    SHORT='Code:\n 0: aload_0\n 1: ifnull 7\n 4: ldc #CP // String admin-only\n 6: pop\n 7: return'
    WIDE='Code:\n 0: aload_0\n 1: ifnull 8\n 4: ldc_w #CP // String admin-only\n 7: pop\n 8: return'

    def test_ldc_width_and_corresponding_byte_offsets_only_are_equivalent(self):
        self.assertEqual(instruction_positions(self.SHORT),instruction_positions(self.WIDE))

    def test_branch_to_different_instruction_is_not_hidden(self):
        self.assertNotEqual(instruction_positions(self.SHORT),instruction_positions(self.WIDE.replace('ifnull 8','ifnull 7')))

    def test_changed_condition_or_string_operand_is_not_hidden(self):
        for changed in (self.WIDE.replace('ifnull','ifnonnull'),self.WIDE.replace('admin-only','everyone')):
            with self.subTest(changed=changed):self.assertNotEqual(instruction_positions(self.SHORT),instruction_positions(changed))

    def test_invalid_branch_target_is_rejected(self):
        with self.assertRaises(AssertionError):instruction_positions(self.WIDE.replace('ifnull 8','ifnull 6'))

if __name__=='__main__':unittest.main()
