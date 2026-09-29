"""GUI-only framing from existing authored meshes. No bitmap/UV or placed geometry edits."""
from pathlib import Path
import json,math,itertools
root=Path(__file__).resolve().parents[1]/'src/main/resources/assets/piq_computer'
def matrix(angles):
    # Minecraft ItemTransform: JOML rotationXYZ, i.e. R_x R_y R_z.
    x,y,z=map(math.radians,angles)
    def rot(p):
        a,b,c=p;a,b=a*math.cos(z)-b*math.sin(z),a*math.sin(z)+b*math.cos(z)
        a,c=a*math.cos(y)+c*math.sin(y),-a*math.sin(y)+c*math.cos(y)
        b,c=b*math.cos(x)-c*math.sin(x),b*math.sin(x)+c*math.cos(x)
        return [a,b,c]
    return rot
def gui(model,angles):
    r=matrix(angles);pts=[]
    for e in model['elements']:
        for p in itertools.product(*zip(e['from'],e['to'])):
            p=list(p)
            if 'rotation' in e:
                q=e['rotation'];origin=q['origin'];a=[0,0,0];a['xyz'.index(q['axis'])]=q['angle']
                v=matrix(a)([p[i]-origin[i] for i in range(3)]);p=[v[i]+origin[i] for i in range(3)]
            pts.append(r([v-8 for v in p]))
    lo=[min(p[i] for p in pts) for i in range(3)];hi=[max(p[i] for p in pts) for i in range(3)]
    scale=min(4,14/max(hi[i]-lo[i] for i in (0,1)))
    return {'rotation':angles,'translation':[-(lo[i]+hi[i])*.5*scale if i<2 else 0 for i in range(3)],'scale':[scale]*3}
angles={'motherboard':[12,105,-8],'cpu':[12,105,-12],'cooler':[22,115,0],
        'ram':[12,170,-62],'gpu':[-62,25,-10],'hdd':[64,20,-10],'psu':[25,25,0]}
for name,a in angles.items():
    path=root/f'models/item/{name}.json';m=json.loads(path.read_text('utf8'))
    m.setdefault('display',{})['gui']=gui(m,a)
    path.write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n','utf8')
for color in ('black','white'):
    name='keyboard_mouse_'+color;m=json.loads((root/f'models/block/{name}.json').read_text('utf8'))
    item={'parent':'piq_computer:block/'+name,'display':{'gui':gui(m,[68,180,0])}}
    (root/f'models/item/{name}.json').write_text(json.dumps(item,ensure_ascii=False,indent=2)+'\n','utf8')
# The pairing tool is a receiver, not a coil of vanilla string.
m=json.loads((root/'models/block/wireless_receiver.json').read_text('utf8'))
# A separate item mesh magnifies the tiny hardware while the placed receiver remains unchanged.
points=[e[k] for e in m['elements'] for k in ('from','to')]
lo=[min(p[i] for p in points) for i in range(3)];hi=[max(p[i] for p in points) for i in range(3)]
scale=11/max(hi[i]-lo[i] for i in range(3));center=[(a+b)/2 for a,b in zip(lo,hi)]
for e in m['elements']:
    for k in ('from','to'):e[k]=[(v-center[i])*scale+8 for i,v in enumerate(e[k])]
m['display']={'gui':gui(m,[25,110,0])}
(root/'models/item/connector.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n','utf8')
print('Updated 10 inventory models; all block meshes/textures unchanged.')
