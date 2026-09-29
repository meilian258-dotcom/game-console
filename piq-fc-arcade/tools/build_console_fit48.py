"""FC48 deterministic console-only geometry edit from the preserved pre-edit snapshot.

No bitmap edits. Removes real shell material for controller wells, adds a hollow
coax socket, and gives all Subor sizes a separate reset key. Old Subor meshes keep
their dimensions; the new compact mesh is an independently exported derivative.
"""
from __future__ import annotations
import argparse, copy, hashlib, json, math, zipfile
from pathlib import Path
import numpy as np
from PIL import Image
from render_rocket_arcade_preview import collect_quads, render_view
from import_subor_hardware import mesh_quads
from build_subor_wide_hardware import box, triangle_bounds

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'src/main/resources/assets/piq_fc_arcade'
SNAPSHOT = ROOT.parent/'outputs/tvcoin48/source-before.zip'
OUT = ROOT.parent/'outputs/tvcoin48/console-fit'
PREFIX = 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/'
WELLS = (([11.94,1.20,5.30],[13.60,5,13.30]),([2.40,1.20,5.30],[4.06,5,13.30]))

def load(name):
    with zipfile.ZipFile(SNAPSHOT) as z:return json.loads(z.read(PREFIX+name))

def cube(name,lo,hi,uv):
    return {'name':name,'from':lo,'to':hi,'faces':{face:{'uv':uv,'texture':'#0'}
            for face in ('north','east','south','west','up','down')}}

def subtract(element,lo,hi):
    """Six disjoint slabs, so the opening is a volume subtraction, not dark paint."""
    a,b=element['from'],element['to']
    u=[max(a[i],lo[i]) for i in range(3)]; v=[min(b[i],hi[i]) for i in range(3)]
    if any(u[i]>=v[i] for i in range(3)):return [element]
    if 'rotation' in element:raise ValueError('Unexpected rotated shell intersects new aperture: '+element['name'])
    parts=[]; remain_a=a.copy();remain_b=b.copy()
    for axis in range(3):
        for side,bound in ((0,u[axis]),(1,v[axis])):
            p,q=remain_a.copy(),remain_b.copy()
            if side==0:q[axis]=bound;remain_a[axis]=bound
            else:p[axis]=bound;remain_b[axis]=bound
            if all(q[i]-p[i]>1e-8 for i in range(3)):
                e=copy.deepcopy(element);e.update(name=element['name']+f'·cut{axis}{side}', **{'from':p,'to':q});parts.append(e)
    return parts

def fc_body(original):
    body=copy.deepcopy(original)
    for lo,hi in (*WELLS,([7.56,1.84,14.24],[8.44,2.72,15.1])):
        body['elements']=[part for e in body['elements'] for part in subtract(e,lo,hi)]
    colors={e['name']:next(iter(e['faces'].values()))['uv'] for e in original['elements']}
    red=colors['红底壳主芯'];dark=colors['卡槽底部暗腔'];cream=colors['白壳下段主芯']
    metal=[5.125,.125,5.5,.5]
    for e in body['elements']:
        if e['to'][0]==11.94:
            e['faces']['east']['uv']=dark.copy()
        if e['from'][0]==4.06:
            e['faces']['west']['uv']=dark.copy()
    for port,(lo,hi) in enumerate(WELLS):
        left=port==1; x0,x1=(2.45,4.06) if left else (11.94,13.55)
        wall=(4.061,4.11) if left else (11.89,11.939)
        body['elements'] += [
            cube(f'FC48 P{port+1} visible well back',[wall[0],1.20,5.30],[wall[1],3.339,13.30],dark),
            cube(f'FC48 P{port+1} support shelf',[x0,1.04,5.30],[x1,1.195,13.30],red),
            cube(f'FC48 P{port+1} front stop',[x0,1.20,5.16],[x1,3.339,5.299],cream),
            cube(f'FC48 P{port+1} rear stop',[x0,1.20,13.301],[x1,3.339,13.44],cream)]
    body['elements'].append(cube('FC48 coax recessed dark interior',[7.57,1.85,14.245],[8.43,2.71,14.265],dark))
    # Eight tangent metal segments, a real central hole and isolated contact.
    for i in range(8):
        phi=i*math.pi/4;cx=8+.48*math.cos(phi);cy=2.28+.48*math.sin(phi)
        horizontal=i%4 in (1,2,3); angle=0
        if i%2:angle=-45 if i in (1,5) else 45
        w,h=(.38,.15) if horizontal else (.15,.38)
        e=cube('FC48 coax octagonal metal rim '+str(i),[cx-w/2,cy-h/2,14.57],[cx+w/2,cy+h/2,14.97],metal)
        if angle:e['rotation']={'origin':[cx,cy,14.77],'axis':'z','angle':angle,'rescale':False}
        body['elements'].append(e)
    body['elements'].append(cube('FC48 coax center contact',[7.93,2.21,14.27],[8.07,2.35,14.54],metal))
    return body

def reset_mesh(mesh,wide):
    mesh=copy.deepcopy(mesh);group=mesh['groups']['body'];tri=group['triangles']
    # Sample the existing red power/indicator material rather than modifying PNGs.
    reference=load('meshes/home_subor_sb926_wide.json')['groups']['body']
    candidates=[p for p in reference['parts'] if p['name']=='指示灯0']
    if not candidates:raise ValueError('Missing existing indicator material')
    uv=reference['triangles'][candidates[0]['start']]['uv'][0]
    coords=(6.12,2.67,19.66,6.88,3.01,20.42) if wide else (2.02,.67,7.57,2.56,.88,8.11)
    new=box(*coords,uv)
    group.setdefault('parts',[]).append({'name':'FC48 独立RESET复位键','start':len(tri),'count':len(new)})
    tri.extend(new);group['bounds']=triangle_bounds(tri)
    return mesh

def compact_mesh(mesh):
    mesh=copy.deepcopy(mesh)
    for name,group in mesh['groups'].items():
        if name.endswith('_held'):continue
        for triangle in group['triangles']:
            for p in triangle['p']:p[2]=round(p[2]*.8-4,9)
            n=np.cross(np.subtract(triangle['p'][1],triangle['p'][0]),np.subtract(triangle['p'][2],triangle['p'][0]))
            triangle['n']=(n/np.linalg.norm(n)).round(9).tolist()
        group['bounds']=triangle_bounds(group['triangles'])
    metadata=mesh['metadata'];metadata['revision']='FC48-two-cell-compact';metadata['layout_size']=[32,16,16]
    metadata['depth_transform']={'scale':.8,'offset':-4,'legacy_mesh_retained':True}
    anchors=metadata['anchors']
    for name in ('cartridge_bottom_center','av_cable_start','power_socket'):
        anchors[name][2]=round(anchors[name][2]*.8-4,9)
    for p in [*anchors['rca_sockets'].values(),*anchors['controller_sockets']]:p[2]=round(p[2]*.8-4,9)
    center=anchors['cartridge_bottom_center'][2]
    anchors['cartridge_inserted_bounds'][0][2]=round(center-.45,9)
    anchors['cartridge_inserted_bounds'][1][2]=round(center+.45,9)
    for name in ('opening_xz',):
        for at in (1,3):metadata[name][at]=round(metadata[name][at]*.8-4,9)
    for rect in metadata['contact_cutouts_xz']:
        for at in (1,3):rect[at]=round(rect[at]*.8-4,9)
    metadata['lid']['hinge'][2]=round(metadata['lid']['hinge'][2]*.8-4,9)
    for name in ('world_body_bounds','world_closed_bounds','world_open_bounds'):
        for p in metadata[name]:p[2]=round(p[2]*.8-4,9)
    return mesh

def collect():
    old=load('models/block/home_console_body.json');body=fc_body(old)
    full=load('models/block/home_famicom_console.json')
    controllers=[e for e in full['elements'] if e['name'].startswith(('一号','二号','Ⅰ手柄','Ⅱ手柄'))]
    full['elements']=copy.deepcopy(body['elements'])+controllers
    small=reset_mesh(load('meshes/home_subor_sb926.json'),False)
    wide=reset_mesh(load('meshes/home_subor_sb926_wide.json'),True)
    return {'models/block/home_console_body.json':body,'models/block/home_famicom_console.json':full,
            'meshes/home_subor_sb926.json':small,'meshes/home_subor_sb926_wide.json':wide,
            'meshes/home_subor_sb926_compact.json':compact_mesh(wide)}

def validate(files):
    body=files['models/block/home_console_body.json'];checked=0
    # The true rotated controller bounds must fit the actual removed volume with a gap.
    for port,(lo,hi) in enumerate(WELLS):
        dock=load(f'models/block/home_controller_p{port+1}_docked.json')
        dock['elements']=[e for e in dock['elements'] if e['name'].startswith('一号' if port==0 else '二号')]
        for q in collect_quads(dock):
            for p in q.vertices:
                if not all(lo[i]+.02<p[i]<hi[i]-.02 for i in range(3)):raise AssertionError(('Controller outside cavity',port,p.tolist(),lo,hi))
                checked+=1
        for e in body['elements']:
            if 'rotation' in e:continue
            if all(min(e['to'][i],hi[i])-max(e['from'][i],lo[i])>1e-8 for i in range(3)):
                raise AssertionError(('Shell remains in controller cavity',port,e['name']))
    compact=files['meshes/home_subor_sb926_compact.json']
    for name,g in compact['groups'].items():
        if name.endswith('_held'):continue
        bounds=triangle_bounds(g['triangles'])
        if bounds[0][2]<0 or bounds[1][2]>16:raise AssertionError((name,bounds))
    return {'controller_vertex_clearance_checks':checked,'actual_empty_wells':2,'compact_depth_within_one_block':True}

def preview(files):
    OUT.mkdir(parents=True,exist_ok=True)
    texture=np.array(Image.open(ASSETS/'textures/block/home_famicom_console.png').convert('RGBA'))
    for key,name in [('models/block/home_console_body.json','fc-empty'),('models/block/home_famicom_console.json','fc-docked')]:
        quads=collect_quads(files[key])
        for view,direction in [('front',(1.5,1,-1.4)),('rear',(-1.3,.7,1.6)),('side',(1,.3,0))]:
            image,_=render_view(quads,{'piq_fc_arcade:block/home_famicom_console':texture},direction,size=(850,600),supersample=1)
            image.save(OUT/f'{name}-{view}.png')
    skin=np.array(Image.open(ASSETS/'textures/block/home_subor_sb926.png').convert('RGBA'))
    for kind in ('wide','compact'):
        mesh=files[f'meshes/home_subor_sb926_{kind}.json']
        quads=mesh_quads(mesh,['body','lid_closed','p1_docked','p2_docked'])
        image,_=render_view(quads,{'skin':skin},(1.3,1,-1.3),size=(1050,700),supersample=1)
        image.save(OUT/f'subor-{kind}.png')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    files=collect();report=validate(files);report['outputs']={}
    previous=json.loads((OUT/'geometry-verification.json').read_text()) if (OUT/'geometry-verification.json').exists() else {}
    encoded={name:(json.dumps(value,ensure_ascii=False,separators=(',',':'))+'\n').encode() for name,value in files.items()}
    if args.write:
        with zipfile.ZipFile(SNAPSHOT) as snapshot:
            for name,raw in encoded.items():
                path=ASSETS/name
                if not path.exists():continue
                current=hashlib.sha256(path.read_bytes()).hexdigest().upper()
                accepted={hashlib.sha256(raw).hexdigest().upper(),previous.get('outputs',{}).get(name)}
                if PREFIX+name in snapshot.namelist():accepted.add(hashlib.sha256(snapshot.read(PREFIX+name)).hexdigest().upper())
                if path.is_symlink() or current not in accepted:raise ValueError('Refusing changed asset: '+name)
    for name,value in files.items():
        raw=encoded[name]
        path=ASSETS/name
        if args.write:path.write_bytes(raw)
        report['outputs'][name]=hashlib.sha256(raw).hexdigest().upper()
    if args.preview:preview(files)
    if args.write:
        OUT.mkdir(parents=True,exist_ok=True);(OUT/'geometry-verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
