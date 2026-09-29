"""Convert supplied BBModel data to bounded Java parts, preserving UV and texture bytes.
Coordinates are halved for Minecraft's element limit, then restored by the renderer.
Also derive a small stepped selection hull from actual transformed model vertices.
"""
from pathlib import Path
import json,zipfile,math,copy,hashlib,sys
ROOT=Path(__file__).resolve().parents[1]; A=ROOT/'src/main/resources/assets/piq_fc_arcade'
SOURCE=Path(r'\\Kkp220\梅子共享\传输\单人竖屏街机_3比4_完整模型套件.zip')
def encode(v):return (json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n').encode()
def corners(e):
    rot=e.get('rotation');out=[]
    for x in [e['from'][0],e['to'][0]]:
      for y in [e['from'][1],e['to'][1]]:
       for z in [e['from'][2],e['to'][2]]:
        p=[x,y,z]
        if rot:
            ax='xyz'.index(rot['axis']);i=(ax+1)%3;j=(ax+2)%3;o=rot['origin'];a=math.radians(rot['angle']);c=math.cos(a);s=math.sin(a)
            u=p[i]-o[i];v=p[j]-o[j];p[i]=o[i]+u*c-v*s;p[j]=o[j]+u*s+v*c
        out.append(p)
    return out
def hull(elements,height):
    # Clip rotated cube edges to each band. Using whole-cube minima would retain
    # the empty wedge in front of a tall sloping screen in every band.
    groups=[corners(e) for e in elements];out=[]
    for bottom in range(0,height,2):
        top=min(bottom+2,height)
        points=[]
        for g in groups:
            points.extend(p for p in g if bottom<=p[1]<=top)
            for i,p in enumerate(g):
                for bit in [1,2,4]:
                    j=i^bit
                    if j<=i:continue
                    q=g[j]
                    if abs(q[1]-p[1])<1e-10:continue
                    for y in [bottom,top]:
                        t=(y-p[1])/(q[1]-p[1])
                        if 0<=t<=1:points.append([p[k]+t*(q[k]-p[k]) for k in range(3)])
        if points:out.append([min(p[0] for p in points),bottom,min(p[2] for p in points),max(p[0] for p in points),top,max(p[2] for p in points)])
    return out
def derive():
    with zipfile.ZipFile(SOURCE) as z:
        bb=json.loads(z.read('街机_单人竖屏_3比4.bbmodel'));mapping=json.loads(z.read('按键动画映射.json'));screen=json.loads(z.read('屏幕接入定义.json'))
        png=z.read('竖屏街机_完整UV.png')
    assert bb['resolution']=={'width':2048,'height':2048}
    elements={};full=[]
    for b in bb['elements']:
        assert b['type']=='cube'
        e={'name':b['name'],'from':b['from'],'to':b['to'],'faces':{}}
        for face,f in b['faces'].items():
            if f.get('texture') is None:continue
            e['faces'][face]={'uv':[v/128 for v in f['uv']],'texture':'#0'}
            if f.get('rotation'):e['faces'][face]['rotation']=f['rotation']
        axes=[i for i,v in enumerate(b.get('rotation',[0,0,0])) if v]
        assert len(axes)<=1
        if axes:
            i=axes[0];assert b['rotation'][i] in [-45,-22.5,22.5,45]
            e['rotation']={'axis':'xyz'[i],'angle':b['rotation'][i],'origin':b['origin'],'rescale':False}
        full.append(copy.deepcopy(e))
        for key in ['from','to']:e[key]=[v/2 for v in e[key]]
        if 'rotation' in e:e['rotation']['origin']=[v/2 for v in e['rotation']['origin']]
        assert all(-16<=v<=32 for key in ['from','to'] for v in e[key])
        elements[b['uuid']]=e
    moved=set();parts={};controls=[]
    for g in mapping['groups']:
        if not g['movable']:continue
        assert g['player']==1 and not moved.intersection(g['elementUuids'])
        moved.update(g['elementUuids']);parts[g['groupName']]=[elements[u] for u in g['elementUuids']]
        controls.append({'name':g['groupName'],'pivot':g['pivot']})
    assert len(parts)==8
    parts['body']=[e for u,e in elements.items() if u not in moved]
    files={A/f'models/block/portrait/{name}.json':encode({'textures':{'0':'piq_fc_arcade:block/portrait/skin','particle':'#0'},'elements':v}) for name,v in parts.items()}
    files[A/'textures/block/portrait/skin.png']=png
    files[A/'models/item/portrait_cabinet.json']=encode({'parent':'builtin/entity','textures':{'particle':'piq_fc_arcade:block/portrait/skin'}})
    files[A/'blockstates/portrait_cabinet.json']=encode({'variants':{'':{'model':'piq_fc_arcade:block/portrait/body'}}})
    files[A/'layout/portrait_outline.json']=encode(hull(full,35))
    dual=[e for p in sorted((A/'models/block/user_dual').glob('*.json')) for e in json.loads(p.read_text('utf8')).get('elements',[])]
    files[A/'layout/dual_outline.json']=encode(hull(dual,32))
    return files,controls,screen
if __name__=='__main__':
    files,controls,screen=derive()
    for p,b in files.items():
        if '--check' in sys.argv:assert p.read_bytes()==b,p
        else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
    print(json.dumps({'files':len(files),'controls':controls,'sourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest()},ensure_ascii=False))
