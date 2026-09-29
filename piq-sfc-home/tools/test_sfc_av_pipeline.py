import copy,json,tempfile,unittest
from pathlib import Path
import check_sfc_av_pipeline as q

class SfcAvPipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='sfc-av-tests-');cls.folder=Path(cls.temp.name);q.compile_probe(cls.folder)
        cls.data=json.loads(q.run(cls.folder,[0,0,0,-4,0,0]))
        cls.renderer=q.JAVA.with_name('SfcAvCableRenderer.java').read_text(encoding='utf-8')
        cls.hardware=q.JAVA.with_name('SfcHardwareRenderer.java').read_text(encoding='utf-8')
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def test_actual_crt_mesh(self):self.assertTrue(q.metrics(self.data)['ok'])
    def test_six_tv_and_four_facings_matrix(self):self.assertIn('good=384 blocked=0 bad=0',q.run(self.folder,[]))
    def test_near_and_mixed_height_matrix(self):self.assertIn('bad=0',q.run(self.folder,['--edge']))
    def test_socket_shift_rejected(self):
        d=copy.deepcopy(self.data);d['console_sockets'][1][0]+=.01;self.assertFalse(q.metrics(d)['ok'])
    def test_floating_trunk_rejected(self):
        d=copy.deepcopy(self.data);d['trunk'][1][1]+=.03;self.assertFalse(q.metrics(d)['ok'])
    def test_wrong_plug_colors_rejected(self):
        d=copy.deepcopy(self.data)
        for f in d['quads']:
            if f[1]==0xC63831:f[1]=0xFFFFF6
        self.assertFalse(q.metrics(d)['ok'])
    def test_nonfinite_vertices_rejected(self):
        d=copy.deepcopy(self.data);d['quads'][0][2][0]=float('nan');self.assertFalse(q.metrics(d)['ok'])
    def test_trunk_through_housing_rejected(self):
        d=copy.deepcopy(self.data);box=d['tv_box'];center=[(box[i]+box[i+3])/2 for i in range(3)]
        for i in range(2,6):d['quads'][0][i]=[center[0]+(.01 if i%2 else 0),center[1]+(.01 if i<4 else 0),center[2]]
        self.assertFalse(q.metrics(d)['ok'])
    def test_actual_adapter_wiring(self):self.assertTrue(all(c['ok'] for c in q.wiring_checks(self.renderer,self.hardware)))
    def test_missing_reciprocal_link_rejected(self):
        r=self.renderer.replace('console.linkId().equals(tv.linkId())','true');self.assertFalse(q.wiring_checks(r,self.hardware)[0]['ok'])
    def test_draw_inside_rotated_pose_rejected(self):
        h=self.hardware.replace('poses.popPose();','');self.assertFalse(q.wiring_checks(self.renderer,h)[3]['ok'])
    def test_two_housing_sat_has_positive_and_negative_cases(self):
        self.assertTrue(q.triangle_box([[.2,.2,.5],[.8,.2,.5],[.5,.8,.5]],[0,0,0,1,1,1]))
        self.assertFalse(q.triangle_box([[.2,.2,1.1],[.8,.2,1.1],[.5,.8,1.1]],[0,0,0,1,1,1]))

if __name__=='__main__':unittest.main()
