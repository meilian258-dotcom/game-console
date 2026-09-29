import copy,unittest
from check_vintage_alpha11_seams import inspect


def model():
    result={'textures':{'0':'minecraft:block/black_concrete'},'elements':[]}
    def cube(name,a,b,faces=('north','south','east','west','up','down')):
        result['elements'].append({'name':name,'from':list(a),'to':list(b),
            'faces':{f:{'uv':[0,0,1,1],'texture':'#0'} for f in faces}})
    def ring(name,inner,z,w):
        x1,y1,x2,y2=inner
        cube(name+'L',(x1-w,y1-w,z[0]),(x1,y2+w,z[1]))
        cube(name+'R',(x2,y1-w,z[0]),(x2+w,y2+w,z[1]))
        cube(name+'B',(x1,y1-w,z[0]),(x2,y1,z[1]))
        cube(name+'T',(x1,y2,z[0]),(x2,y2+w,z[1]))
    ring('mouth',(4.12,1.77,14.78,9.88),(2.16,2.43),.18)
    ring('recess-wall',(4.22,1.87,14.68,9.78),(2.43,2.96),.10)
    ring('inner-shadow',(4.35,2,14.55,9.65),(2.96,3.35),.13)
    cube('完整4比3黑屏',(4.35,2,3.35),(14.55,9.65,3.38),('north',))
    return result


class VintageSeamTests(unittest.TestCase):
    def test_exactly_adjacent_ring_walls_do_not_false_positive(self):
        self.assertTrue(inspect(model())['ok'])
    def test_positive_same_facing_face_overlap_detected(self):
        m=model();duplicate=copy.deepcopy(m['elements'][-1]);duplicate['name']='duplicate black plane';m['elements'].append(duplicate)
        self.assertTrue(inspect(m)['coplanar_same_facing_overlaps'])
    def test_point_zero_one_vertical_seam_detected(self):
        m=model();m['elements'][6]['from'][1]+=.01
        self.assertTrue(inspect(m)['frame_continuity_gaps'])
    def test_point_zero_one_glass_depth_gap_detected(self):
        m=model()
        for e in m['elements'][8:12]:e['to'][2]-=.01
        self.assertTrue(inspect(m)['frame_continuity_gaps'])


if __name__=='__main__':unittest.main()
