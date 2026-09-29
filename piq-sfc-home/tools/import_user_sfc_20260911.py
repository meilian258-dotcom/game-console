"""Repeatable native-mesh import of the user's read-only Blockbench models.

No supplied code is executed; UV PNGs are copied verbatim. Preview images are
offline renders of exactly the exported triangles, not Minecraft screenshots.
"""
from __future__ import annotations
import argparse, hashlib, json, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'piq-fc-arcade/tools'))
from render_rocket_arcade_preview import vertices_for, uv_for, Quad, render_view
SOURCE=ROOT/'piq-fc-arcade/design/user-models-20260911/source'
ASSETS=ROOT/'piq-sfc-home/src/main/resources/assets/piq_sfc_home'
OUT=ROOT/'piq-sfc-home/design/user-sfc-20260911'
CONSOLE=SOURCE/'02_SFC双手柄/SFC_日版双手柄_线材肩键修正版.bbmodel'
CARD=SOURCE/'03_SFC独立卡带/SFC卡带_独立版_单张UV.bbmodel'
TEXTURES={'hardware':('piq_sfc_home:block/user_sfc_20260911',SOURCE/'02_SFC双手柄/SFC_单张UV.png'),
          'card':('piq_sfc_home:block/user_sfc_cartridge_20260911',SOURCE/'03_SFC独立卡带/SFC卡带_完整UV.png')}
INSERT=np.array([0.,2.18,3.711]); ITEM=np.array([0.,6.55,0.])
PAD_CENTER=np.array([11.5,.375,1.7225]); PAD_SCALE=2.

def console_item_bytes():
    """Pure GUI wrapper; every non-GUI key equals the frozen SFC9 item wrapper."""
    display={'gui':{'rotation':[30,225,0],'translation':[-.625,5.125,0],'scale':[.81,.81,.81]}}
    for mode,angle,y,size in (('firstperson',0,3,.55),('thirdperson',75,2.5,.375)):
        for hand in ('righthand','lefthand'):
            display[mode+'_'+hand]={'rotation':[angle,45,0],'translation':[0,y,0],'scale':[size]*3}
    return (json.dumps({'parent':'piq_sfc_home:block/sfc_console_inventory','display':display},separators=(',',':'))+'\n').encode('utf-8')

def sha(data):return hashlib.sha256(data).hexdigest().upper()
def rounded(a):return np.round(a,9).tolist()

class Model:
    def __init__(self,path):
        self.path=path; self.raw=path.read_bytes(); self.doc=json.loads(self.raw.decode('utf-8-sig'))
        assert self.doc['meta']['model_format']=='java_block'
        self.elements={e['uuid']:e for e in self.doc['elements']}
        self.groups={g['uuid']:g for g in self.doc['groups']}; self.paths={}; self.members={}
        def walk(node,ancestors=()):
            if isinstance(node,str):
                assert node not in self.paths
                self.paths[node]=ancestors
                for group in ancestors:self.members.setdefault(group,[]).append(node)
                return
            group=self.groups[node['uuid']]; assert not any(group.get('rotation',[0,0,0]))
            for child in node['children']:walk(child,ancestors+(group['name'],))
        for node in self.doc['outliner']:walk(node)
        assert set(self.paths)==set(self.elements)
        self.group_names={g['name']:g for g in self.groups.values()}

    def quads(self,uuid):
        e=self.elements[uuid]; assert e['type']=='cube' and e.get('export',True)
        elem={'from':e['from'],'to':e['to']}
        rotation=e.get('rotation',[0,0,0]); axes=[i for i,v in enumerate(rotation) if v]
        assert len(axes)<=1
        if axes:
            i=axes[0]; assert rotation[i] in (-45,-22.5,22.5,45)
            elem['rotation']={'origin':e['origin'],'axis':'xyz'[i],'angle':rotation[i],'rescale':e.get('rescale',False)}
        dims=np.array([self.doc['resolution']['width'],self.doc['resolution']['height']])
        for face,f in e['faces'].items():
            if f.get('texture') is None:continue
            assert f['texture']==0 and f.get('rotation',0) in (0,90,180,270)
            yield face,vertices_for(elem,face),uv_for(f)/dims

def convert(model,ids,material,transform=lambda a:a,pad=False):
    batches={}
    for uuid in ids:
        ancestry=model.paths[uuid]; leaf=ancestry[-1]
        key=leaf
        motion=leaf[3:] if pad and leaf.startswith(('p1_','p2_')) and leaf not in ('p1_shell','p2_shell') else ''
        if motion not in ('button_a','button_b','button_x','button_y','dpad','select','start','shoulder_l','shoulder_r'):motion=''
        part=batches.setdefault(key,{'name':leaf,'material':material,'triangles':[]})
        if motion:
            part['motion']=motion; part['pivot']=rounded(transform(np.array(model.group_names[leaf]['origin'])))
            part['press']=.035*(PAD_SCALE if pad=='held' else 1)
        for face,p,uv in model.quads(uuid):
            p=transform(p)
            n=np.cross(p[1]-p[0],p[2]-p[0]); n/=np.linalg.norm(n)
            assert np.isfinite(p).all() and p.min()>=-1e-7 and p.max()<=16+1e-7
            assert uv.min()>=0 and uv.max()<=1
            for tri in ((0,1,2),(0,2,3)):
                part['triangles'].append({'p':rounded(p[list(tri)]),'n':rounded(n),'uv':rounded(uv[list(tri)])})
    return {'parts':list(batches.values())}

def build():
    m,c=Model(CONSOLE),Model(CARD)
    # Plug and strain relief belong to console_ports in the supplied hierarchy;
    # include them in their matching docked layer so claiming a pad leaves no stub.
    plugs={p:[u for u in m.members['console_ports'] if m.elements[u]['name'].startswith(f'{p}P') and ('已插入插头' in m.elements[u]['name'] or '插头出线护套' in m.elements[u]['name'])] for p in (1,2)}
    moved=set(plugs[1]+plugs[2]); body=[u for u in m.members['console'] if u not in moved]
    groups={'body':convert(m,body,'hardware'),
            'p1_docked':convert(m,m.members['controller_1']+m.members['p1_cable']+plugs[1],'hardware',pad=True),
            'p2_docked':convert(m,m.members['controller_2']+m.members['p2_cable']+plugs[2],'hardware',pad=True),
            'slot_cover':{'parts':[]}, # supplied slot is open; never invent a second dust lid
            'inserted':convert(c,list(c.elements),'card',lambda p:p+INSERT),
            'cartridge':convert(c,list(c.elements),'card',lambda p:p+ITEM),
            'controller':convert(m,m.members['controller_1'],'hardware',lambda p:(p-PAD_CENTER)*PAD_SCALE+8,pad='held')}
    doc={'version':1,'materials':{k:v[0] for k,v in TEXTURES.items()},'groups':groups,
         'contract':{'source':'user Blockbench 20260911','insert_translation':INSERT.tolist(),'item_translation':ITEM.tolist(),
                     'held_source_center':PAD_CENTER.tolist(),'held_scale':PAD_SCALE,'front_label':[6.075,.845,9.925,2.58,7.5985],
                     'multi_out_center':[5.55,.695,15.5375],'controller_socket_centers':[[10.025,.745,5.795],[5.975,.745,5.795]]}}
    counts={k:sum(len(p['triangles']) for p in v['parts']) for k,v in groups.items()}
    assert all(v<=18000 for v in counts.values())
    # All user console/controller/cable elements accounted for exactly once;
    # only the obsolete 38-element preinserted cartridge is excluded.
    represented=len(body)+sum(len(m.members[f'controller_{p}'])+len(m.members[f'p{p}_cable'])+len(plugs[p]) for p in (1,2))
    assert represented+len(m.members['cartridge'])==len(m.elements)
    report={'ok':True,'source_bbmodel_sha256':{str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in (CONSOLE,CARD)},
            'source_elements':{'console_set':len(m.elements),'independent_card':len(c.elements)},'excluded_old_card':len(m.members['cartridge']),
            'represented_world_elements':represented+len(c.elements),'docked_plugs_moved':{str(k):len(v) for k,v in plugs.items()},
            'triangles':counts,'contract':doc['contract'],'textures':{k:{'source':str(p.relative_to(ROOT)),'sha256':sha(p.read_bytes())} for k,(_,p) in TEXTURES.items()},
            'notes':['Actual converted model/UV; not a Minecraft screenshot.','Source atlases copied byte-for-byte; no bitmap editing.',
                     'No network/core/server changes. Borrowed controller and its original fixed cord/plug hide together.',
                     'No remote-owner world wire is invented; existing block metadata exposes only docked masks.']}
    return doc,report

def quads(doc,names):
    out=[]
    for name in names:
        for part in doc['groups'][name]['parts']:
            for t in part['triangles']:
                p=np.array(t['p']); uv=np.array(t['uv'])*16
                out.append(Quad(p[[0,1,2,2]],uv[[0,1,2,2]],doc['materials'][part['material']],len(out),'mesh'))
    return out

def preview(doc):
    tex={resource:np.asarray(Image.open(path).convert('RGBA')) for resource,path in TEXTURES.values()}
    views=[('front',['body','p1_docked','p2_docked','inserted'],(1,1.1,-1.6),'完整主机 · 双柄及独立卡带'),
           ('rear',['body','p1_docked','p2_docked','inserted'],(-1,1,1.6),'背面 · 原 MULTI OUT 接口'),
           ('claimed',['body','p2_docked'],(1,1.1,-1.6),'P1 已领取 · P1 线 / 插头隐藏 · 无卡'),
           ('controller',['controller'],(0,1,-.01),'手柄 · 原始 UV / 独立按钮'),
           ('cartridge',['cartridge'],(.45,.45,-1),'独立卡带 · 原比例与标签'),
           ('card-back',['cartridge'],(-.45,.45,1),'独立卡带背面 / 金手指')]
    fontpath=Path('C:/Windows/Fonts/msyh.ttc');font=ImageFont.truetype(str(fontpath),21)
    sheet=Image.new('RGB',(1440,1440),(225,227,230)); draw=ImageDraw.Draw(sheet)
    for i,(name,groups,direction,label) in enumerate(views):
        im,_=render_view(quads(doc,groups),tex,direction,size=(710,415),supersample=2)
        tile=Image.new('RGB',(720,480),(225,227,230));tile.paste(im,(5,34),im);d=ImageDraw.Draw(tile);d.text((15,8),label,fill=(25,25,25),font=font)
        d.text((15,449),'真实导出网格离线渲染 · 非 Minecraft 截图',fill=(75,75,75),font=font)
        sheet.paste(tile,((i%2)*720,(i//2)*480));im.save(OUT/(name+'.png'))
    sheet.save(OUT/'overview.png')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    doc,report=build();raw=json.dumps(doc,ensure_ascii=False,separators=(',',':')).encode('utf-8');report['mesh_sha256']=sha(raw);report['bytes']=len(raw);report['console_item_sha256']=sha(console_item_bytes())
    if args.write:
        (ASSETS/'meshes').mkdir(parents=True,exist_ok=True);(ASSETS/'meshes/sfc_hardware.json').write_bytes(raw)
        (ASSETS/'models/item/console.json').write_bytes(console_item_bytes())
        for _,(resource,path) in TEXTURES.items():
            target=ASSETS/'textures'/(resource.split(':',1)[1]+'.png');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(path.read_bytes());assert sha(target.read_bytes())==sha(path.read_bytes())
    OUT.mkdir(parents=True,exist_ok=True)
    if args.preview:preview(doc)
    (OUT/'conversion-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
