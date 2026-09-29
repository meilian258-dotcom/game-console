"""Negative controls for the SFC12 visual-only independent audit. No production edits."""
import copy,json,unittest
import verify_sfc_scale12 as q

class ScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        digest,cls.old=q.compat.archive(q.BASELINE);assert digest==q.BASE_SHA
        cls.new=dict(cls.old)
        cls.new[q.META]=cls.new[q.META].replace(b'0.1.0-alpha.11',b'0.1.0-alpha.12')
        cls.new[q.MANIFEST]=cls.new[q.MANIFEST].replace(b'0.1.0-alpha.11',b'0.1.0-alpha.12')
        for name in q.ADDED:cls.new[name]=b'\xca\xfe\xba\xbeFIXTURE'
        for name in q.ASSETS:
            data=json.loads(cls.new[name]);data['display']['gui']['scale']=[2.4]*3 if name.endswith('/cartridge.json')else [.669155]*3
            if name.endswith('/console.json'):data['display']['gui']['translation']=[.17252,3.57112,0]
            cls.new[name]=json.dumps(data).encode()
    def test_only_explicit_visual_scope_is_allowed(self):
        result=q.classify(self.old,self.new);self.assertTrue(result['old_network_server_watch_playback_preserved']);self.assertEqual(len(result['changed_assets']),2)
    def test_eight_distinct_protected_mutations_rejected(self):self.assertEqual(len(q.negative_controls(self.old,self.new)),8)
    def test_additional_class_is_not_implicitly_allowed(self):
        bad=dict(self.new);bad[q.PREFIX+'client/Unexpected.class']=b'\xca\xfe\xba\xbe';self.assertRaises(AssertionError,q.classify,self.old,bad)
    def test_fc_dependency_cannot_be_lowered(self):
        bad=dict(self.new);bad[q.META]=bad[q.META].replace(b'0.31.0-alpha.23',b'0.31.0-alpha.22');self.assertRaises(AssertionError,q.classify,self.old,bad)
    def test_card_non_gui_and_rotation_are_preserved(self):
        name='assets/piq_sfc_home/models/item/cartridge.json'
        for key in('firstperson_righthand','gui'):
            bad=dict(self.new);data=json.loads(bad[name]);data['display'][key]['rotation']=[1,2,3];bad[name]=json.dumps(data).encode();self.assertRaises(AssertionError,q.classify,self.old,bad)
    def test_console_non_gui_and_nonuniform_scale_rejected(self):
        name='assets/piq_sfc_home/models/item/console.json'
        for key in('firstperson_righthand','gui'):
            bad=dict(self.new);data=json.loads(bad[name]);data['display'][key]['scale']=[.6,.5,.4];bad[name]=json.dumps(data).encode();self.assertRaises(AssertionError,q.classify,self.old,bad)
    def test_other_methods_in_visual_class_stay_protected(self):
        old={'public int safe();':'0: iconst_0\n1: ireturn','public int getShape();':'0: iconst_0\n1: ireturn'}
        new=copy.deepcopy(old);new['public int getShape();']='0: iconst_1\n1: ireturn'
        self.assertEqual(q.compare_methods(old,new,(' getShape(',)),1)
        new['public int safe();']='0: iconst_1\n1: ireturn';self.assertRaises(AssertionError,q.compare_methods,old,new,(' getShape(',))
    def test_new_nonvisual_method_rejected(self):
        self.assertRaises(AssertionError,q.compare_methods,{}, {'public void send();':'0: return'},(' getShape(',))
if __name__=='__main__':unittest.main()
