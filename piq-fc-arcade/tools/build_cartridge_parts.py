"""Deterministic 3D PCB variants and two detached hollow shell halves; no new bitmap."""
from __future__ import annotations
import copy
import io
import json
import zipfile
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from import_subor_hardware import ASSETS, CATEGORY, encoded, sha, write_new
from render_rocket_arcade_preview import collect_quads, render_view

OUT = CATEGORY / '卡带拆壳模型-alpha9' / '第二版'
SOURCE = ASSETS / 'models/block/home_fc_cartridge.json'
ATLAS = ASSETS / 'textures/block/home_fc_cartridge_skin.png'
MC_JAR = Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar')
MATERIALS = {'pcb':'green_concrete','edge':'lime_terracotta','trace':'lime_concrete',
             'chip':'black_concrete','pin':'light_gray_concrete','gold':'yellow_terracotta',
             'ceramic':'orange_terracotta','mark':'white_concrete'}

def cube(name, lo, hi, material, rotation=None):
    e = {'name':name, 'from':list(lo), 'to':list(hi), 'faces':{
        d:{'uv':[0,0,16,16], 'texture':'#'+material} for d in ('north','south','east','west','up','down')}}
    if rotation: e['rotation'] = rotation
    return e

def board(variant):
    if variant not in range(3): raise ValueError('Unknown PCB variant')
    height = (6.65,7.0,4.8)[variant]
    es = [cube('绿色玻纤板', [2.8,1.05,7.90],[13.2,height,8.10],'pcb'),
          cube('插接板舌', [3.0,.0,7.90],[13.0,1.05,8.10],'pcb')]
    def box(n,lo,hi,m): es.append(cube(n,lo,hi,m))
    for side in ('front','back'):
        z = 7.878 if side=='front' else 8.105
        for n in range(30):
            x = 3.12+n*.326
            box(f'{side}金手指{n+1}',[x,.04,z],[x+.22,.86,z+.017],'gold')
            # Distinct short right-angle traces; kept clear of neighboring contacts.
            end = 1.4+(n%5)*.22
            box(f'{side}连线{n+1}',[x+.087,.87,z+.002],[x+.127,end,z+.011],'trace')
            box(f'{side}折线{n+1}',[x+.087,end,z+.002],[x+.23,end+.035,z+.011],'trace')
    def dip(x,y,w,h,pins):
        box('DIP封装',[x,y,7.28],[x+w,y+h,7.87],'chip')
        for n in range(pins):
            px=x+.17+n*(w-.34)/(pins-1)
            for py in (y-.20,y+h): box('金属引脚',[px,py,7.63],[px+.105,py+.20,7.89],'pin')
        box('定位缺口',[x+.16,y+h*.35,7.268],[x+.27,y+h*.65,7.28],'pin')
    if variant==0:
        dip(4,3.0,7.5,1.4,14)
        dip(8.5,5.2,2.8,.8,6)
    elif variant==1:
        dip(3.9,2.75,3.25,2.65,8)
        dip(8.55,2.75,3.25,2.65,8)
    else:
        # Layered octagonal epoxy glob tops; geometry, not a flat black decal.
        for x in (5.3,10.5):
            for radius,z0,z1 in ((.79,7.50,7.895),(.57,7.32,7.50),(.30,7.25,7.32)):
                a=radius*(2**.5-1)
                box('黑胶横芯',[x-radius,3.2-a,z0],[x+radius,3.2+a,z1],'chip')
                box('黑胶竖芯',[x-a,3.2-radius,z0],[x+a,3.2+radius,z1],'chip')
                for sx in (-1,1):
                    for sy in (-1,1):
                        cx,cy=x+sx*a,3.2+sy*a
                        es.append(cube('黑胶圆角',[cx-a,cy-a,z0],[cx+a,cy+a,z1],'chip',
                            {'angle':45,'axis':'z','origin':[cx,cy,8],'rescale':False}))
    for n,x in enumerate((3.4,12.4)):
        box('电容引脚',[x,height-.95,7.61],[x+.08,height-.18,7.89],'pin')
        box('电容',[x-.12,height-.73,7.36],[x+.21,height-.34,7.63],'ceramic')
    # Plated screw pads, visibly empty center, on front and rear surfaces.
    for x in (3.2,12.8):
        for z in (7.87,8.11):
            box('固定孔铜环',[x-.19,height-.37,z],[x+.19,height+.01,z+.018],'gold')
            box('固定孔暗心',[x-.095,height-.275,z-.005],[x+.095,height-.085,z+.024],'chip')
    return {'credit':'PIQ original code-built PCB; generic components, no ROM identity artwork.',
            'ambientocclusion':True,'textures':{**{k:'minecraft:block/'+v for k,v in MATERIALS.items()},'particle':'#pcb'},'elements':es}

def shell():
    source=json.loads(SOURCE.read_bytes()); es=[]
    # Source front and rear are already separate. Remove the PCB, contacts and middle seam.
    for original in source['elements']:
        name=original.get('name','')
        if any(s in name for s in ('线路板','金手指','中线合模')): continue
        e=copy.deepcopy(original)
        if e['from'][2] < 8 < e['to'][2]:
            halves=[]
            for front in (True,False):
                half=copy.deepcopy(e)
                half['to' if front else 'from'][2]=7.98 if front else 8.02
                halves.append((front,half))
        else: halves=[((e['from'][2]+e['to'][2])/2<8,e)]
        for front,half in halves:
            if '黄壳' in name: # Thin skin and inward raised rim leave a true recess.
                half['to' if front else 'from'][2]=7.50 if front else 8.50
            half['name']=('前壳 / ' if front else '后壳 / ')+name
            es.append((front,half))
    donor=copy.deepcopy(source['elements'][0]['faces'])
    for front in (True,False):
        lo,hi=(7.50,7.98) if front else (8.02,8.50)
        for n,a,b in (('左围边',[2.50,1.25,lo],[2.78,7.6,hi]),
                      ('右围边',[13.22,1.25,lo],[13.5,7.6,hi]),
                      ('顶围边',[2.78,7.32,lo],[13.22,7.6,hi])):
            e={'name':n,'from':a,'to':b,'faces':copy.deepcopy(donor)};es.append((front,e))
        for x in (3.1,12.9):
            es.append((front,{'name':'内侧定位柱','from':[x-.16,1.27,lo],'to':[x+.16,1.6,hi], 'faces':copy.deepcopy(donor)}))
    result=[]
    for front,e in es:
        # Rear half's north face is already the inside. Separate and stagger it,
        # do not flip the exterior back label toward the viewer.
        delta=[-1.45,-.35,-1.05] if front else [1.45,1.15,1.05]
        for key in ('from','to'):e[key]=[round(v+d,8) for v,d in zip(e[key],delta)]
        if 'rotation' in e:e['rotation']['origin']=[round(v+d,8) for v,d in zip(e['rotation']['origin'],delta)]
        result.append(e)
    return {'credit':'PIQ detached hollow shell halves; original cover faces/UV retained, no PCB.',
            'ambientocclusion':True,'textures':copy.deepcopy(source['textures']),'elements':result}

def outputs():
    base=ASSETS/'models'
    result={base/f'block/home_fc_board_{i}.json':encoded(board(i)) for i in range(3)}
    result[base/'block/home_fc_cartridge_shell.json']=encoded(shell())
    item=json.loads((base/'item/fc_cartridge.json').read_bytes())
    for name in ('fc_cartridge_board','fc_cartridge_shell'):
        model=copy.deepcopy(item)
        if name.endswith('shell'): model['display']['gui']['scale']=[.85,.85,.85]
        result[base/f'item/{name}.json']=encoded(model)
    return result

def audit(result):
    reports=[]
    for path,raw in result.items():
        m=json.loads(raw)
        if 'elements' not in m:continue
        qs=collect_quads(m); pts=np.concatenate([q.vertices for q in qs])
        if not np.isfinite(pts).all() or pts.min() < -16 or pts.max()>32:raise ValueError('Invalid bounds')
        for q in qs:
            if np.linalg.norm(np.cross(q.vertices[1]-q.vertices[0],q.vertices[2]-q.vertices[0]))<1e-8:raise ValueError('Degenerate face')
        reports.append({'file':path.name,'quads':len(qs),'bounds':[pts.min(0).tolist(),pts.max(0).tolist()]})
    front=next(e for e in shell()['elements'] if e['name']=='前壳 / 中央游戏标签')
    original=next(e for e in json.loads(SOURCE.read_bytes())['elements'] if e['name']=='中央游戏标签')
    if front['faces']!=original['faces']:raise ValueError('Cover UV changed')
    if any('金手指' in e['name'] or '线路板' in e['name'] for e in shell()['elements']):raise ValueError('Empty shell retained board')
    return {'ok':True,'models':reports,'assets':{str(p.relative_to(ASSETS.parent.parent)).replace('\\','/'):sha(b) for p,b in result.items()},
            'limits':['Actual code-native model preview, not Minecraft screenshot','Original texture and game-cover UV unchanged','Variants are cosmetic, not ROM identification']}

def preview():
    textures={'piq_fc_arcade:block/home_fc_cartridge_skin':np.array(Image.open(ATLAS).convert('RGBA'))}
    with zipfile.ZipFile(MC_JAR) as z:
        for v in MATERIALS.values():textures['minecraft:block/'+v]=np.array(Image.open(io.BytesIO(z.read('assets/minecraft/textures/block/'+v+'.png'))).convert('RGBA'))
    canvas=Image.new('RGB',(1560,920),'#18232e');d=ImageDraw.Draw(canvas)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
    for i,(name,m) in enumerate((('长条 DIP 芯片',board(0)),('双 DIP 芯片',board(1)),('黑胶封装芯片',board(2)),('拆开空壳 · 原封面保留',shell()))):
        x=(i%2)*780;y=(i//2)*440
        pic,_=render_view(collect_quads(m),textures,(.35,.15,-1),size=(740,375),supersample=2)
        canvas.paste(pic.convert('RGB'),(x+20,y+42));d.text((x+24,y+8),name,font=font,fill='#e0eaf1')
    d.text((20,890),'实际模型离线预览；三种电路板外观不代表游戏名，反复拆装保持同一款。',font=font,fill='#b9c9d7')
    b=io.BytesIO();canvas.save(b,format='JPEG',quality=91);return b.getvalue()

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');p.add_argument('--check-only',action='store_true');p.add_argument('--update-generated',action='store_true');a=p.parse_args()
    result=outputs();report=audit(result)
    if a.check_only:
        for path,raw in result.items():
            if path.read_bytes()!=raw:raise ValueError('Runtime part model changed: '+str(path))
    if a.update_generated:
        prior={'home_fc_board_2.json':'E9366FB8A56DCEDC2BF3AE8168839D010336685131ED3A5E9D59DB4C77BE65C0',
               'home_fc_cartridge_shell.json':'780892FDECA9C270B91BB2110B37511ED1A9C9BE2159A7F1A2275BD240C9599C'}
        for path,raw in result.items():
            if path.is_symlink() or path.exists() and sha(path.read_bytes()) not in (prior.get(path.name),sha(raw)):raise ValueError('Unreviewed file: '+str(path))
        for path,raw in result.items():path.write_bytes(raw)
    if a.write:write_new({**result,OUT/'卡带拆壳与三种芯片.jpg':preview(),OUT/'模型检查.json':encoded(report)})
    print(json.dumps(report,ensure_ascii=True,indent=2))
