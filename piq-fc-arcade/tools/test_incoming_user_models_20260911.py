"""Independent positive/negative geometry checks; originals are read-only."""
import copy,json,math,unittest
from unittest.mock import patch
from PIL import Image
import audit_incoming_user_models_20260911 as a

class IncomingModels(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dual=a.ROOT/'source/01_双人街机';cls.sfc=a.ROOT/'source/02_SFC双手柄';cls.card=a.ROOT/'source/03_SFC独立卡带'
    def mutated(self,folder,filename,change):
        original=a.read
        def reader(path):
            value=original(path)
            if path.name==filename:change(value)
            return value
        with patch.object(a,'read',reader):return a.audit_group(folder)
    def test_immutable_all_48_and_pngs(self):
        r=a.audit();self.assertTrue(r['ok']);self.assertEqual(48,len(r['file_sha256']));self.assertEqual(21,len(r['png_files']))
        self.assertEqual([236,872,86],[m['elements']for m in r['models']])
    def test_outward_six_faces_rotated(self):
        for axis in range(3):
            for angle in(-45,-22.5,0,22.5,45):
                cube={'from':[1,2,3],'to':[4,6,8],'origin':[2,3,4],'rotation':[0,0,0]};cube['rotation'][axis]=angle
                for face in('north','south','east','west','up','down'):self.assertTrue(a.outward(cube,face))
    def test_inverted_element_rejected(self):
        def invert(b):b['elements'][0]['from'][0]=b['elements'][0]['to'][0]+1
        r=self.mutated(self.dual,next(self.dual.glob('*.bbmodel')).name,invert)
        self.assertFalse(r['ok']);self.assertTrue(any('inverted' in p for p in r['problems']))
    def test_wrong_java_uv_rejected(self):
        def wrong(j):j['elements'][0]['faces']['north']['uv'][0]+=1
        r=self.mutated(self.dual,'arcade_universal_deep.json',wrong)
        self.assertFalse(r['ok']);self.assertTrue(any('UV export' in p for p in r['problems']))
    def test_nonzero_group_rotation_rejected(self):
        def wrong(b):b['groups'][0]['rotation']=[0,22.5,0]
        r=self.mutated(self.dual,next(self.dual.glob('*.bbmodel')).name,wrong)
        self.assertFalse(r['ok']);self.assertTrue(any('Nonzero group transform' in p for p in r['problems']))
    def test_wrong_animation_membership_rejected(self):
        def wrong(m):m['groups'][0]['elementUuids']=m['groups'][0]['elementUuids'][1:]
        r=self.mutated(self.dual,'按键动画映射.json',wrong)
        self.assertFalse(r['ok']);self.assertTrue(any('Mapped exact group ownership' in p for p in r['problems']))
    def test_single_face_screen_has_real_four_by_three_plane(self):
        b=a.read(next(self.dual.glob('*.bbmodel')));e=next(e for e in b['elements']if e['name']=='4比3双人通用屏幕')
        self.assertEqual(['north'],[f for f,v in e['faces'].items()if v.get('texture')is not None])
        p=a.face_vertices(e,'north');self.assertAlmostEqual(math.dist(p[1],p[2])/math.dist(p[0],p[1]),4/3)
        self.assertAlmostEqual(math.dist(p[0],p[1]),12);self.assertEqual([22.5,0,0],e['rotation'])
    def test_standalone_card_shell_label_align_but_connector_is_separate(self):
        c=a.read(next(self.card.glob('*.bbmodel')));s=a.read(next(self.sfc.glob('*.bbmodel')));index={e['uuid']:e for e in s['elements']}
        aligned=[];reused=[];new=[]
        for e in c['elements']:
            target=index.get(e['uuid'])
            if target is None:new.append(e);continue
            if all(a.close([p[0],p[1]+2.18,p[2]+3.711],q)for p,q in zip(a.vertices(e),a.vertices(target))):aligned.append(e)
            else:reused.append(e)
        self.assertEqual([32,6,48],[len(aligned),len(reused),len(new)])
        self.assertTrue(any(e['name']=='卡带可替换游戏标签'for e in aligned))
        self.assertTrue(all('金手指'in e['name']for e in new))
        self.assertTrue(any(e['name']=='连接器PCB'for e in reused))
    def test_label_template_exact_pixels(self):
        with Image.open(self.card/'游戏标签_616x278.png')as template,Image.open(self.card/'SFC卡带_完整UV.png')as atlas:
            self.assertEqual((616,278),template.size);self.assertEqual(template.convert('RGBA').tobytes(),atlas.crop((32,32,648,310)).convert('RGBA').tobytes())
    def test_sfc_and_card_atlas_are_distinct(self):
        a1=a.read(next(self.sfc.glob('*.bbmodel')));a2=a.read(next(self.card.glob('*.bbmodel')))
        self.assertEqual(2048,a1['resolution']['width']);self.assertEqual(1024,a2['resolution']['width'])
        self.assertNotEqual(a1['textures'][0]['source'],a2['textures'][0]['source'])

if __name__=='__main__':unittest.main()
