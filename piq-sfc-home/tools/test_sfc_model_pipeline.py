import copy,json,unittest
import check_sfc_model_pipeline as q

class SfcModelPipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe,_=q.java_probe();cls.resources={p:(q.ASSETS/p).read_bytes() for p in q.MODELS};cls.report,*_=q.inspect(cls.resources,cls.probe)
    def test_actual_resources_and_java_all_pass(self):self.assertTrue(self.report['ok'],[c for c in self.report['checks'] if not c['ok']])
    def test_eight_states_and_four_rotations(self):
        self.assertEqual(len(self.report['states']),8);self.assertEqual(sum('correct single rotation' in c['name'] for c in self.report['checks']),32)
    def test_frozen_geometry_mutation_is_rejected(self):
        raw=dict(self.resources);m=json.loads(raw['models/item/controller.json']);m['elements'][0]['to'][1]+=.03;raw['models/item/controller.json']=q.encoded(m);r,*_=q.inspect(raw,self.probe)
        self.assertIn('byte-frozen v2 geometry models/item/controller.json',[c['name'] for c in r['checks'] if not c['ok']])
    def test_incorrect_p1_lease_visibility_is_rejected(self):
        p=copy.deepcopy(self.probe);p['visible'][0]='013';r,*_=q.inspect(self.resources,p)
        self.assertIn('Java synced visibility mask 0',[c['name'] for c in r['checks'] if not c['ok']])
    def test_double_facing_rotation_is_rejected(self):
        p=copy.deepcopy(self.probe);p['yaw'][1]=-180;r,*_=q.inspect(self.resources,p)
        self.assertEqual(sum('facing 1 correct single rotation' in c['name'] and not c['ok'] for c in r['checks']),8)
    def test_five_layers_not_preview_composite(self):
        self.assertEqual(len(self.probe['models']),5);self.assertTrue(all('preview' not in p and 'inventory' not in p for p in self.probe['models'].values()))

if __name__=='__main__':unittest.main()
