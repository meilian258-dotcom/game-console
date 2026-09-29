"""SB926 v5: compact chassis footprint, v4 thickness and full-size cartridge/controllers.

Frozen alpha5/v1/v2 files and the original PNG are read-only. All new colored faces
sample existing uniform atlas tiles. No AI, repaint, bitmap rescale, or game launch.
"""
from __future__ import annotations
import argparse
import copy
import io
import json
import math
import zipfile
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from import_subor_hardware import (ASSETS, CATEGORY, MESH_PATH as OLD_MESH_PATH, SOURCE_SHA,
                                  TEXTURE, TEXTURE_SHA, encoded, load_source, sha, triangles,
                                  transform_element, write_new, mesh_quads)
from render_rocket_arcade_preview import Quad, collect_quads, render_view

OLD_MESH_SHA = '02671F529B09883842970D7ECEF4C67F679821CC8B5BCAF31078353586D85D42'
NEW_MESH = ASSETS / 'meshes/home_subor_sb926_wide.json'
OUT = CATEGORY / '小霸王SB926紧凑机身-v5'
BASELINE_JAR = CATEGORY / 'piq_fc_arcade-0.31.0-alpha.7.jar'
BASELINE_JAR_SHA = 'B2DAF27EA28F33A7DFA5138E6D324BE15BECDE140305C6FD280B3542F3FA52A4'
BASELINE_MESH_SHA = '816F031BEE02B8268F9E54088E8B69AF45384E27EAB61006C6B860F0ED5949D1'
BOTTOM_REDUCTION = 1.20
PLANAR_SCALE = .8
SLOT_SHIFT_Z = -1.541
SCALE, RAISE = .94, 1.25
OFFSET = np.array([16., 0., 18.3])
OPENING = (11.45, 21.359, 20.55, 23.009)
FLOOR_Y, WELL_Y = 1.82, 1.30
CARD_ANCHOR = np.array([16., 1.52, 22.164])
CARD_SCALE = .60
HINGE = np.array([16., 3.0025, 23.059])
LID_ANGLE = 110.
SOCKETS = ((24.32, 1.05, 23.632), (22.72, 1.05, 23.632), (21.12, 1.05, 23.632))
POWER = (19.68, 1.05, 23.632)
CONTACT_CUTS = ((12.70,21.724,13.12,22.604), (13.12,22.074,18.88,22.254),
                (18.88,21.724,19.30,22.604))
GROUPS = ('body','p1_docked','p2_docked','p1_held','p2_held','lid_closed','lid_open')
REFERENCE = np.array([(-19,0,7),(-19,0,23),(-3,0,23),(-3,0,7)])


def source_point(p):
    return compact_point(OFFSET + np.asarray(p) * [-SCALE,SCALE,-SCALE] + [0,RAISE,0])


def compact_point(p):
    return (np.asarray(p)-[16,0,16])*[PLANAR_SCALE,1,PLANAR_SCALE]+[16,0,16]


def compact_axis(value):return 16+(value-16)*PLANAR_SCALE


def compact_rect(rect):return tuple(compact_axis(value) for value in rect)


def slot_point(p):return np.asarray(p)+[0,0,SLOT_SHIFT_Z]


def badge_point(name,p):
    p=np.array(p,dtype=float).copy()
    if name=='型号':p[...,0]=8.48+(p[...,0]-8.48)*.98-.70
    elif name=='品牌':p[...,0]+=.10
    return p


def top_y(z):
    old_z=16+(z-16)/PLANAR_SCALE
    return 1.4*SCALE + RAISE + .055*(old_z-OFFSET[2])


def within(point, rectangle, epsilon=1e-9):
    a,b,c,d = rectangle
    return a-epsilon <= point[0] <= c+epsilon and b-epsilon <= point[1] <= d+epsilon


def surface_grid(rectangle, holes, make_point, uv, normal):
    """Partition a planar face, physically omitting every hole interior (not a dark overlay)."""
    a,b,c,d = rectangle
    xs = sorted({a,c,*[v for h in holes for v in (h[0],h[2]) if a<v<c]})
    ys = sorted({b,d,*[v for h in holes for v in (h[1],h[3]) if b<v<d]})
    output = []
    for x0,x1 in zip(xs,xs[1:]):
        for y0,y1 in zip(ys,ys[1:]):
            if any(within(((x0+x1)/2,(y0+y1)/2),h) for h in holes):
                continue
            output += face([make_point(x0,y0),make_point(x1,y0),make_point(x1,y1),make_point(x0,y1)],uv,normal)
    return output


def face(p, uv, outward=None):
    p = np.asarray(p,dtype=float)
    normal = np.cross(p[1]-p[0],p[2]-p[0])
    if outward is not None and normal @ outward < 0:
        p = p[::-1]
    result=[]
    for at in range(1,len(p)-1):
        points = p[[0,at,at+1]]
        n = np.cross(points[1]-points[0],points[2]-points[0]); length=np.linalg.norm(n)
        if length < 1e-12:
            raise ValueError('Degenerate new surface')
        result.append({'p':np.round(points,9).tolist(),'uv':[list(uv)]*3,'n':np.round(n/length,9).tolist()})
    return result


def box(x0,y0,z0,x1,y1,z1,uv):
    return (face([(x0,y0,z0),(x1,y0,z0),(x1,y0,z1),(x0,y0,z1)],uv,[0,-1,0]) +
            face([(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],uv,[0,1,0]) +
            face([(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0)],uv,[0,0,-1]) +
            face([(x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)],uv,[0,0,1]) +
            face([(x0,y0,z0),(x0,y1,z0),(x0,y1,z1),(x0,y0,z1)],uv,[-1,0,0]) +
            face([(x1,y0,z0),(x1,y1,z0),(x1,y1,z1),(x1,y0,z1)],uv,[1,0,0]))


def sloped_box(rectangle, center_y, thickness, uv):
    x0,z0,x1,z1=rectangle
    bottom=lambda z:center_y(z)-thickness/2
    top=lambda z:center_y(z)+thickness/2
    return (face([(x0,bottom(z0),z0),(x1,bottom(z0),z0),(x1,bottom(z1),z1),(x0,bottom(z1),z1)],uv,[0,-1,0])+
            face([(x0,top(z0),z0),(x1,top(z0),z0),(x1,top(z1),z1),(x0,top(z1),z1)],uv,[0,1,0])+
            face([(x0,bottom(z0),z0),(x1,bottom(z0),z0),(x1,top(z0),z0),(x0,top(z0),z0)],uv,[0,0,-1])+
            face([(x0,bottom(z1),z1),(x1,bottom(z1),z1),(x1,top(z1),z1),(x0,top(z1),z1)],uv,[0,0,1])+
            face([(x0,bottom(z0),z0),(x0,top(z0),z0),(x0,top(z1),z1),(x0,bottom(z1),z1)],uv,[-1,0,0])+
            face([(x1,bottom(z0),z0),(x1,top(z0),z0),(x1,top(z1),z1),(x1,bottom(z1),z1)],uv,[1,0,0]))


def move_triangles(values, matrix, offset):
    result=[]
    for triangle in values:
        p=np.array(triangle['p'])@matrix.T+offset
        n=np.cross(p[1]-p[0],p[2]-p[0]);n/=np.linalg.norm(n)
        result.append({'p':np.round(p,9).tolist(),'uv':copy.deepcopy(triangle['uv']),'n':np.round(n,9).tolist()})
    return result


def triangle_bounds(values):
    p=np.concatenate([np.array(t['p']) for t in values])
    return np.round([p.min(0),p.max(0)],9).tolist()


def load_baseline():
    raw=BASELINE_JAR.read_bytes()
    if sha(raw)!=BASELINE_JAR_SHA:raise ValueError('Frozen alpha7 JAR changed')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        mesh=archive.read('assets/piq_fc_arcade/meshes/home_subor_sb926_wide.json')
    if sha(mesh)!=BASELINE_MESH_SHA:raise ValueError('Frozen alpha7 wide mesh changed')
    return json.loads(mesh),mesh


def part_triangles(mesh,group,name):
    section=next(p for p in mesh['groups'][group]['parts'] if p['name']==name)
    return mesh['groups'][group]['triangles'][section['start']:section['start']+section['count']]


def card_quads():
    model=json.loads((ASSETS/'models/block/home_fc_cartridge.json').read_bytes())
    return [Quad((q.vertices-[8,0,8])*CARD_SCALE+CARD_ANCHOR,q.uv,'card',q.element_index,q.direction)
            for q in collect_quads(model)]


def material_samples(original,png):
    by_name={e['name']:e for e in original['elements']}
    names={'shell':'一体斜面上壳','base':'下壳','dark':'主键盘底盘','yellow':'背部AV与电源接口0',
           'white':'背部AV与电源接口1','red':'指示灯0','metal':'触点1'}
    image=np.array(Image.open(io.BytesIO(png)).convert('RGBA'))
    samples={}
    for label,name in names.items():
        uv=np.mean(list(by_name[name]['faces']['f1']['uv'].values()),axis=0)
        x,y=uv.astype(int); pixels=image[y-2:y+3,x-2:x+3].reshape(-1,4)
        if len(np.unique(pixels,axis=0))!=1 or pixels[0,3]!=255:
            raise ValueError('New material is not an existing opaque uniform atlas tile: '+label)
        samples[label]=(uv/2048).tolist()
    return samples


def cord_centers(port):
    # World model units; both symmetrical controllers are before the keyboard, not inside its shell.
    right=port==0
    def p(x,y,z):return np.array([x if right else 32-x,y,z])
    hand_y=.18+(.92*(12.69/7)*.6)/2
    start_y=hand_y+(.5-.63)*(12.69/7)*.6
    start_z=8.5+(11.065-9.32)*(12.69/7)*.6
    sections=[(6,[p(22.2,start_y,start_z),p(22.2,.45,11.1),p(23.5,.35,11.5),p(25,.35,11.5)]),
              (4,[p(25,.35,11.5),p(26,.35,11.5),p(27.5,.35,11.5),p(28.1,.35,11.5)]),
              (4,[p(28.1,.35,11.5),p(28.35,.35,11.5),p(28.56,.35,11.65),p(28.56,.35,11.9)]),
              (10,[p(28.56,.35,11.9),p(28.56,.35,14),p(28.56,1.4,18.8),p(28.56,2.19,20.8)]),
              (4,[p(28.56,2.19,20.8),p(28.56,2.19,21.0),p(28.45,2.19,21.1488),p(28.22,2.19,21.1488)])]
    centers=[]
    for count,c in sections:
        for t in np.linspace(0,1,count+1)[:-1]:
            centers.append((1-t)**3*c[0]+3*(1-t)**2*t*c[1]+3*(1-t)*t*t*c[2]+t**3*c[3])
    centers.append(sections[-1][1][-1])
    return np.array(centers)


def cord_mesh(original,port):
    centers=cord_centers(port); values=[]
    for i,center in enumerate(centers):
        tangent=centers[min(i+1,28)]-centers[max(i-1,0)];tangent/=np.linalg.norm(tangent)
        b=-np.cross(tangent,[0,1,0]);b/=np.linalg.norm(b);c=-np.cross(tangent,b)
        for angle in np.arange(6)*np.pi/3:
            values.append(center+.09*(b*np.cos(angle)+c*np.sin(angle)))
    element=copy.deepcopy(original)
    element['vertices']={f'v{i}':np.round(p,9).tolist() for i,p in enumerate(values)}
    return triangles([element])


def port_surfaces(center,material,uv,back_z,power=False):
    x,y,z=center; output=[]; outer=.40 if power else .51; inner=.22 if power else .32
    for i in range(16):
        a,b=i*np.pi/8,(i+1)*np.pi/8
        def outer_point(angle):
            dx,dy=np.cos(angle),np.sin(angle); distance=.64/max(abs(dx),abs(dy))
            yy=y+dy*distance
            return (x+dx*distance,yy,back_z(yy))
        ring=lambda angle,r,zz:(x+r*np.cos(angle),y+r*np.sin(angle),zz)
        # A square-to-round housing patch closes the cut rectangle around a genuine round aperture.
        output+=face([outer_point(a),outer_point(b),ring(b,outer,z),ring(a,outer,z)],uv['base'],[0,0,1])
        output+=face([ring(a,outer,z),ring(b,outer,z),ring(b,inner,z),ring(a,inner,z)],uv[material],[0,0,1])
        output+=face([ring(a,inner,z),ring(b,inner,z),ring(b,inner,z-.37),ring(a,inner,z-.37)],uv['dark'],[-np.cos((a+b)/2),-np.sin((a+b)/2),0])
        output+=face([(x,y,z-.37),ring(a,inner,z-.37),ring(b,inner,z-.37)],uv['dark'],[0,0,1])
    return output


def build():
    original,png=load_source(); old_raw=OLD_MESH_PATH.read_bytes()
    if sha(old_raw)!=OLD_MESH_SHA:raise ValueError('Frozen alpha5 mesh changed')
    old=json.loads(old_raw); uv=material_samples(original,png)
    parts={name:[] for name in GROUPS}
    def add(group,name,value):parts[group].append((name,value))
    by_id={e['uuid']:e for e in original['elements']}
    body_source=[by_id[u] for g in original['outliner'][:6] for u in g['children']]
    slot_ids=set(original['outliner'][5]['children'])
    new_port_cuts=[(c[0]-.64,c[1]-.64,c[0]+.64,c[1]+.64) for c in (*SOCKETS,POWER)]
    for element in body_source:
        name=element['name']
        if element['uuid'] in slot_ids or name.startswith('背部AV与电源接口'):
            continue
        if name.startswith('橡胶脚'):
            transformed=transform_element(element,lambda p:source_point(p)-[0,RAISE,0])
        elif name=='下壳':
            transformed=transform_element(element,lambda p:source_point(p)-[0,RAISE if p[1]<.5 else 0,0])
            # Remove the old unbroken cap and back panel before adding planar partitions with real holes.
            del transformed['faces']['f1'];del transformed['faces']['f3']
            add('body','下壳顶面·物理切除卡槽',surface_grid(compact_rect((1.0728,11.2688,30.9272,25.3312)),[OPENING],
                lambda x,z:(x,1.9456,z),uv['base'],[0,1,0]))
            lower_y,upper_y=.2068,1.9456
            back_z=lambda y:compact_axis(25.444-(y-lower_y)/(upper_y-lower_y)*.1128)
            add('body','下壳背面·四孔分块',surface_grid((compact_axis(2),lower_y,compact_axis(30),upper_y),new_port_cuts,
                lambda x,y:(x,y,back_z(y)),uv['base'],[0,0,1]))
            add('body','背面左端封边',face([compact_point(v) for v in [(.96,lower_y,25.444),(2,lower_y,25.444),(2,upper_y,25.3312),(1.0728,upper_y,25.3312)]],uv['base'],[0,0,1]))
            add('body','背面右端封边',face([compact_point(v) for v in [(30,lower_y,25.444),(31.04,lower_y,25.444),(30.9272,upper_y,25.3312),(30,upper_y,25.3312)]],uv['base'],[0,0,1]))
            for label,center in zip(('yellow','white','red','dark'),(*SOCKETS,POWER)):
                add('body','独立RCA凹孔 '+label if label!='dark' else '独立电源凹孔',port_surfaces(center,label,uv,back_z,label=='dark'))
        else:
            transformed=transform_element(element,source_point)
        if name in ('型号','品牌'):
            transformed=transform_element(transformed,lambda p:badge_point(name,p))
        if name=='一体斜面上壳':
            del transformed['faces']['f0'];del transformed['faces']['f1']
            add('body','上盖顶面·物理切除卡槽',surface_grid(compact_rect((1.2608,11.4568,30.7392,25.1432)),[OPENING],
                lambda x,z:(x,top_y(z),z),uv['shell'],[0,1,0]))
            add('body','上盖内底面·物理切除卡槽',surface_grid(compact_rect((1.054,11.25,30.946,25.35)),[OPENING],
                lambda x,z:(x,top_y(z)-.65*SCALE,z),uv['shell'],[0,-1,0]))
        add('body',name,triangles([transformed]))
    x0,z0,x1,z1=OPENING
    add('body','卡槽左内侧壁',face([(x0,FLOOR_Y,z0),(x0,top_y(z0),z0),(x0,top_y(z1),z1),(x0,FLOOR_Y,z1)],uv['dark'],[1,0,0]))
    add('body','卡槽右内侧壁',face([(x1,FLOOR_Y,z0),(x1,top_y(z0),z0),(x1,top_y(z1),z1),(x1,FLOOR_Y,z1)],uv['dark'],[-1,0,0]))
    add('body','卡槽前内侧壁',face([(x0,FLOOR_Y,z0),(x1,FLOOR_Y,z0),(x1,top_y(z0),z0),(x0,top_y(z0),z0)],uv['dark'],[0,0,1]))
    add('body','卡槽后内侧壁',face([(x0,FLOOR_Y,z1),(x1,FLOOR_Y,z1),(x1,top_y(z1),z1),(x0,top_y(z1),z1)],uv['dark'],[0,0,-1]))
    add('body','真正下沉凹底·三个窄口',surface_grid(OPENING,CONTACT_CUTS,lambda x,z:(x,FLOOR_Y,z),uv['dark'],[0,1,0]))
    for index,(a,b,c,d) in enumerate(CONTACT_CUTS):
        # The two wide end recesses take the actual card's guiding rails; the middle slit takes its PCB.
        add('body',f'接触口深底{index}',face([(a,WELL_Y,b),(c,WELL_Y,b),(c,WELL_Y,d),(a,WELL_Y,d)],uv['dark'],[0,1,0]))
        for side,p,normal in [('front',[(a,WELL_Y,b),(c,WELL_Y,b),(c,FLOOR_Y,b),(a,FLOOR_Y,b)],[0,0,1]),
                              ('rear',[(a,WELL_Y,d),(c,WELL_Y,d),(c,FLOOR_Y,d),(a,FLOOR_Y,d)],[0,0,-1])]:
            add('body',f'接触口内壁{index}{side}',face(p,uv['dark'],normal))
        for edge,outward,inward in ((a,-1,[1,0,0]),(c,1,[-1,0,0])):
            stops=sorted({b,d,*[v for other in CONTACT_CUTS for v in (other[1],other[3]) if b<v<d]})
            for lo,hi in zip(stops,stops[1:]):
                # Shared rectangular apertures are one connected well, so only exposed boundary gets a wall.
                if any(j!=index and within((edge+outward*1e-5,(lo+hi)/2),other)
                       for j,other in enumerate(CONTACT_CUTS)):
                    continue
                add('body',f'接触口侧端壁{index}:{edge}:{lo}',face([(edge,WELL_Y,lo),(edge,FLOOR_Y,lo),
                    (edge,FLOOR_Y,hi),(edge,WELL_Y,hi)],uv['dark'],inward))
    for i in range(30):
        x=13.2+i*.187
        add('body',f'口内接触片{i+1}',box(x,1.31,22.124,x+.08,1.40,22.204,uv['metal']))
    for i,rect in enumerate(((11.37,21.279,11.45,23.089),(20.55,21.279,20.63,23.089),
                             (11.45,21.279,20.55,21.359),(11.45,23.009,20.55,23.089))):
        add('body',f'薄槽缘{i}',sloped_box(rect,lambda z:top_y(z)+.025,.05,uv['shell']))
    # Small fixed rear hinge; the moving lid is separate from the body and mutually exclusive.
    for x in (11.5,20.2):
        add('body','后铰链底座'+str(x),box(x,2.86,22.919,x+.3,3.11,23.199,uv['base']))
    for i in range(12):
        a,b=i*np.pi/6,(i+1)*np.pi/6
        ring=lambda x,t:(x,HINGE[1]+.14*np.cos(t),HINGE[2]+.14*np.sin(t))
        add('body','后铰链圆轴'+str(i),face([ring(11.65,a),ring(20.35,a),ring(20.35,b),ring(11.65,b)],uv['base'],[0,np.cos((a+b)/2),np.sin((a+b)/2)]))
    closed=sloped_box((11.48,21.389,20.52,23.029),lambda z:HINGE[1]+.055*(z-HINGE[2]),.12,uv['shell'])
    closed+=sloped_box((15.5,21.339,16.5,21.459),lambda z:HINGE[1]+.055*(z-HINGE[2])+.075,.075,uv['base'])
    add('lid_closed','薄小翻盖闭合',closed)
    angle=math.radians(LID_ANGLE); c,s=math.cos(angle),math.sin(angle)
    rotate=np.array([[1,0,0],[0,c,-s],[0,s,c]])
    add('lid_open','薄小翻盖绕后轴110度',move_triangles(closed,rotate,HINGE-rotate@HINGE))
    for port in (0,1):
        held=old['groups'][f'p{port+1}_held']
        parts[f'p{port+1}_held']=[('alpha5原手持·逐三角形不变',copy.deepcopy(held['triangles']))]
        # Canonical +Z face -> +Y tabletop face; -X D-pad -> north viewer's left (+world X).
        matrix=np.array([[-1,0,0],[0,0,1],[0,1,0]])*.60
        hand_y=.18+(.92*(12.69/7)*.6)/2
        offset=np.array([22.2 if port==0 else 9.8,hand_y,8.5])-matrix@np.array([8,8,8])
        add(f'p{port+1}_docked','原尺寸手柄桌面摆位',move_triangles(held['triangles'],matrix,offset))
        cable=next(by_id[u] for u in original['outliner'][7+port]['children'] if by_id[u]['name']=='独立手柄线')
        add(f'p{port+1}_docked','加粗外绕手柄线',cord_mesh(cable,port))
    groups={}
    for name in GROUPS:
        values=[]; ranges=[]
        for label,part in parts[name]:
            ranges.append({'name':label,'start':len(values),'count':len(part)}); values+=part
        groups[name]={'triangles':values,'bounds':triangle_bounds(values),'triangle_count':len(values),'parts':ranges}
    actual_card=np.concatenate([q.vertices for q in card_quads()])
    metadata={'source_sha256':SOURCE_SHA,'alpha5_mesh_sha256':OLD_MESH_SHA,'texture_sha256':TEXTURE_SHA,
              'layout_size':[32,16,32],'keys_uniform_scale':SCALE,'upper_assembly_y_raise':RAISE,
              'revision':'v5-compact-chassis','lower_shell_reduction':BOTTOM_REDUCTION,
              'chassis_planar_scale':PLANAR_SCALE,'chassis_planar_center':[16,16],
              'slot_full_size_translation':[0,0,SLOT_SHIFT_Z],
              'badge_layout':{'型号':{'center_x':8.48,'scale_x':.98,'translate_x':-.70},'品牌':{'translate_x':.10}},
              'alpha7_mesh_sha256':BASELINE_MESH_SHA,
              'texture_policy':'Original PNG byte-identical; new faces sample existing opaque uniform atlas tiles',
              'material_uv_samples':uv,'opening_xz':list(OPENING),'cavity_floor_y':FLOOR_Y,
              'contact_cutouts_xz':[list(v) for v in CONTACT_CUTS],'contact_bottom_y':WELL_Y,
              'physical_removed_faces':['一体斜面上壳:f0','一体斜面上壳:f1','下壳:f1','下壳:f3'],
              'lid':{'closed_group':'lid_closed','open_group':'lid_open','hinge':HINGE.tolist(),'open_x_degrees':LID_ANGLE,'mutually_exclusive':True},
              'anchors':{'cartridge_bottom_center':CARD_ANCHOR.tolist(),'cartridge_source_bottom_center':[8,0,8],
                         'cartridge_render_scale':CARD_SCALE,'cartridge_front_normal':[0,0,-1],
                         'cartridge_inserted_bounds':np.round([actual_card.min(0),actual_card.max(0)],9).tolist(),
                         'rca_sockets':{'yellow':list(SOCKETS[0]),'white':list(SOCKETS[1]),'red':list(SOCKETS[2])},
                         'av_cable_start':list(SOCKETS[0]),'av_outward_normal':[0,0,1],'power_socket':list(POWER),
                         'controller_sockets':[[28.22,2.19,21.1488],[3.78,2.19,21.1488]]},
              'held_contract':copy.deepcopy(old['metadata']['held_contract']),
              'world_body_bounds':groups['body']['bounds']}
    for state in ('closed','open'):
        values=sum([groups[g]['triangles'] for g in ('body','p1_docked','p2_docked','lid_'+state)],[])
        metadata['world_'+state+'_bounds']=triangle_bounds(values)
    mesh={'version':1,'texture':old['texture'],'texture_size':old['texture_size'],'units':'model_16','front':'north_-z',
          'groups':groups,'metadata':metadata}
    return mesh,parts,png


def vertical_hits(values,x,z):
    hits=[]
    for triangle in values:
        p=np.array(triangle['p']); a,b,c=p[:,[0,2]]
        matrix=np.column_stack((b-a,c-a));det=np.linalg.det(matrix)
        if abs(det)<1e-10:continue
        uv=np.linalg.solve(matrix,np.array([x,z])-a)
        if uv.min()>=-1e-8 and uv.sum()<=1+1e-8:
            hits.append(float(p[0,1]+uv[0]*(p[1,1]-p[0,1])+uv[1]*(p[2,1]-p[0,1])))
    return sorted(hits)


def audit(mesh,parts):
    findings=[]; recess=[]
    body=mesh['groups']['body']['triangles']
    for x in (11.7,12.5,14,16,18,19.5,20.3):
        for z in np.array((23.1,23.4,23.705,24.1,24.3))+SLOT_SHIFT_Z:
            hits=vertical_hits(body,x,z); highest=max(hits)
            if highest>FLOOR_Y+1e-6:findings.append(['slot_not_recessed',x,z,highest])
            recess.append({'x':x,'z':z,'top_hit_y':highest,'depth_below_deck':top_y(z)-highest})
    # Actual card at the floor plane must pass through the connector/guide apertures, not through a painted floor.
    floor_intersections=0
    for quad in card_quads():
        for indices in ((0,1,2),(0,2,3)):
            p=quad.vertices[list(indices)];crossings=[]
            for a,b in zip(p,np.roll(p,-1,axis=0)):
                if (a[1]-FLOOR_Y)*(b[1]-FLOOR_Y)<0:
                    t=(FLOOR_Y-a[1])/(b[1]-a[1]);crossings.append(a+t*(b-a))
            if crossings:
                for point in crossings+[np.mean(crossings,axis=0)]:
                    floor_intersections+=1
                    if not any(within(point[[0,2]],rect,1e-7) for rect in CONTACT_CUTS):
                        findings.append(['card_hits_solid_cavity_floor',point.tolist()])
    obstacles=[(name,np.array(triangle_bounds(values))) for name,values in parts['body']
               if name not in ('左手柄接口','右手柄接口')]
    checked=0
    for port in (0,1):
        wire=next(t for name,t in parts[f'p{port+1}_docked'] if name=='加粗外绕手柄线')
        for index,triangle in enumerate(wire):
            p=np.array(triangle['p']);low,high=p.min(0),p.max(0)
            for label,box_bounds in obstacles:
                checked+=1
                if np.all(np.minimum(high,box_bounds[1])-np.maximum(low,box_bounds[0])>-1e-9):
                    findings.append(['cord_possible_chassis_hit',port,index,label])
    card_box=np.array(mesh['metadata']['anchors']['cartridge_inserted_bounds'])
    lid_box=np.array(mesh['groups']['lid_open']['bounds'])
    if not np.any(card_box[1]<lid_box[0]) and not np.any(lid_box[1]<card_box[0]):
        findings.append(['open_lid_card_bounds_overlap'])
    baseline,_=load_baseline(); rigid_parts=0
    # Compare against the shipped model, not just constants from this builder.
    for name,values in parts['body']:
        if not (name.startswith('键帽') or name in ('品牌','型号','英文标','左手柄接口','右手柄接口')):
            continue
        old=part_triangles(baseline,'body',name);rigid_parts+=1
        if len(old)!=len(values):findings.append(['changed_rigid_part_topology',name]);continue
        for before,after in zip(old,values):
            expected=badge_point(name,compact_point(before['p']))
            normal=np.cross(expected[1]-expected[0],expected[2]-expected[0]);normal/=np.linalg.norm(normal)
            if (not np.allclose(expected,after['p'],atol=2e-9,rtol=0)
                or before['uv']!=after['uv'] or not np.allclose(normal,after['n'],atol=2e-9,rtol=0)):
                findings.append(['compact_upper_part_transform_or_uv_wrong',name]);break
    for group in ('p1_held','p2_held'):
        if baseline['groups'][group]!=mesh['groups'][group]:findings.append(['held_group_changed',group])
    for group,dx in (('p1_docked',-1.55),('p2_docked',1.55)):
        before=part_triangles(baseline,group,'原尺寸手柄桌面摆位')
        after=part_triangles(mesh,group,'原尺寸手柄桌面摆位')
        for a,b in zip(before,after):
            if (not np.allclose(np.asarray(a['p'])+[dx,0,1.2],b['p'],atol=2e-9,rtol=0) or a['uv']!=b['uv']):
                findings.append(['docked_controller_scaled_or_wrong_translation',group]);break
    sections=[]
    for z in (12.2,15.,18.,21.,24.9):
        before=vertical_hits(baseline['groups']['body']['triangles'],29,z)
        after=vertical_hits(body,compact_axis(29),compact_axis(z))
        if abs(min(before)-min(after))>1e-8 or abs(max(before)-max(after))>1e-8:
            findings.append(['shell_height_changed_during_planar_compaction',z,before,after])
        sections.append({'before_xz':[29,z],'after_xz':[compact_axis(29),compact_axis(z)],
                         'before_vertical_hits':before,'after_vertical_hits':after})
    report={'ok':not findings,'slot_vertical_ray_tests':recess,'floor_card_crossing_points_checked':floor_intersections,
            'cord_triangle_vs_part_aabb_checks':checked,'intentional_cord_contact':'Only correct side sockets and controller strain reliefs',
            'open_lid_card_z_clearance':float(lid_box[0,2]-card_box[1,2]),'findings':findings,
            'shipped_alpha7_comparison':{'baseline_mesh_sha256':BASELINE_MESH_SHA,'planar_upper_parts_checked':rigid_parts,
                'shell_vertical_sections':sections,'body_height_before':baseline['groups']['body']['bounds'][1][1],
                'body_height_after':mesh['groups']['body']['bounds'][1][1],'held_unchanged_and_docked_translation_only':True,
                'body_width_before':float(np.diff(baseline['groups']['body']['bounds'],axis=0)[0,0]),
                'body_width_after':float(np.diff(mesh['groups']['body']['bounds'],axis=0)[0,0]),
                'card_size_unchanged':np.allclose(np.diff(baseline['metadata']['anchors']['cartridge_inserted_bounds'],axis=0),
                                                np.diff(mesh['metadata']['anchors']['cartridge_inserted_bounds'],axis=0),atol=1e-8)},
            'limits':['Offline actual mesh/UV geometry test, not Minecraft or Blockbench application verification',
                      'Keyboard is decorative FC-console hardware; no learning-ROM/BASIC or keyboard input emulation']}
    if findings:raise ValueError(json.dumps(report,ensure_ascii=False))
    return report


def preview(mesh,png):
    textures={'skin':np.array(Image.open(io.BytesIO(png)).convert('RGBA')),
              'card':np.array(Image.open(ASSETS/'textures/block/home_fc_cartridge_skin.png').convert('RGBA'))}
    canvas=Image.new('RGB',(1720,1260),'#19222c');draw=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',27);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    draw.text((28,15),'小霸王 SB926 · 紧凑机身 v5 / 壳宽约1.53格 / 原大小卡带与手柄',font=title,fill='#eef5fa')
    details=[]
    for i,(label,state,view) in enumerate([('无卡 · 小盖关闭 · 机身平面尺寸缩小20%','closed',(1,.85,-1.6)),
                                         ('插入原尺寸 FC 卡 · 盖沿后轴向后上打开','open',(1,.85,-1.6)),
                                         ('无卡开盖检查 · 槽壁、下沉底与窄接触口','open',(0,1.5,-1)),
                                         ('背面 · 黄白红 RCA 凹口与独立电源孔','closed',(-1,.65,1.6))]):
        x,y=(i%2)*860,65+(i//2)*555
        names=('body','p1_docked','p2_docked','lid_'+state)
        quads=mesh_quads(mesh,names)
        if i==1:quads+=card_quads()
        # Reference is a real 16-model-unit ground square drawn outside the hardware, never asset geometry.
        if i<2:
            reftexture=np.full((16,16,4),(49,69,79,255),dtype=np.uint8)
            reftexture[[0,-1],:]=(113,160,177,255);reftexture[:,[0,-1]]=(113,160,177,255)
            textures['reference']=reftexture
            quads.append(Quad(REFERENCE,np.array([(0,0),(16,0),(16,16),(0,16)]),'reference',0,'one_block_reference'))
        image,info=render_view(quads,textures,view,size=(840,490),supersample=2)
        canvas.paste(image.convert('RGB'),(x+10,y+34));draw.text((x+18,y),label,font=font,fill='#d8e8ef')
        if i<2:draw.text((x+28,y+495),'蓝边方形 = 1 格（16×16 模型单位）',font=font,fill='#8db7c6')
        details.append(info)
    draw.text((28,1202),'XZ收至80%，保留v4薄底与键帽高度；槽/翻盖/卡/柄不缩小，重新布局贴合。原贴图不变；非游戏截图。',font=font,fill='#b9cdd8')
    outputs={}
    for suffix in ('png','jpg'):
        buffer=io.BytesIO();canvas.save(buffer,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        outputs['SB926两格宽_开合槽实际预览.'+suffix]=buffer.getvalue()
    return outputs,details


def section_segments(values,x):
    result=[]
    for triangle in values:
        p=np.array(triangle['p']);hits=[]
        for a,b in zip(p,np.roll(p,-1,axis=0)):
            if (a[0]-x)*(b[0]-x)<0:
                hits.append(a+(x-a[0])/(b[0]-a[0])*(b-a))
            elif abs(a[0]-x)<1e-9:hits.append(a)
        unique=[]
        for p in hits:
            if not any(np.linalg.norm(p-old)<1e-7 for old in unique):unique.append(p)
        if len(unique)==2:result.append(np.array(unique)[:,[2,1]])
    return result


def slim_comparison_preview(mesh,png,baseline):
    textures={'skin':np.array(Image.open(io.BytesIO(png)).convert('RGBA'))}
    names=('body','p1_docked','p2_docked','lid_closed')
    # Both bodies are in ONE projected scene. A single camera and one pixel/unit
    # scale avoid the misleading auto-fit of separately normalized thumbnails.
    combined=mesh_quads(mesh,names)
    combined += [Quad(q.vertices+[0,0,24],q.uv,q.texture,q.element_index,q.direction)
                 for q in mesh_quads(baseline,names)]
    image,view=render_view(combined,textures,(0,1,-.06),size=(850,1030),supersample=2)
    canvas=Image.new('RGB',(1720,1180),'#19222c');draw=ImageDraw.Draw(canvas)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',28)
    canvas.paste(image.convert('RGB'),(0,85))
    draw.text((26,16),'真实三角形同尺度俯视 / 中线剖面（不是概念图）',font=title,fill='#eef5fa')
    draw.text((26,62),'左：上为 alpha7，下为 v5，只有一个相机/缩放',font=font,fill='#b9cdd8')
    draw.text((40,120),'alpha7 · 壳宽30.682单位 / 1.918格',font=font,fill='#f6b995')
    draw.text((40,615),'v5 · 壳宽24.545单位 / 1.534格',font=font,fill='#92d3b4')
    profiles=[]
    for row,(label,current,color) in enumerate((('alpha7',baseline,'#eaa079'),('v5',mesh,'#80d2ad'))):
        origin=np.array([905,500+row*490]); factor=32.
        # The longitudinal section is computed by intersecting every actual
        # body/lid triangle with x=16; no drawing from a hand-authored silhouette.
        count=0
        for group in ('body','lid_open'):
            for segment in section_segments(current['groups'][group]['triangles'],16):
                screen=origin+(segment-[5,0])*[factor,-factor]
                draw.line([tuple(p) for p in screen],fill=color,width=2);count+=1
        draw.line((905,origin[1],1695,origin[1]),fill='#718a9b',width=2)
        deck=current['metadata']['upper_assembly_y_raise']
        for z in (12,18,24):
            x=905+(z-5)*factor
            draw.line((x,origin[1]-8,x,origin[1]+8),fill='#718a9b',width=1)
            draw.text((x-12,origin[1]+12),str(z),font=font,fill='#9db4c4')
        draw.text((890,120+row*490),label+' · x=16 精确剖面（盖开启）',font=font,fill=color)
        draw.text((890,160+row*490),'壳体高度不变；卡槽深度、翻盖尺寸保持',font=font,fill='#bdcbd4')
        profiles.append({'revision':label,'plane_x':16,'actual_triangle_intersection_segments':count,'pixels_per_model_unit':factor})
    draw.text((26,1120),'左图同尺度：机身缩小，但键帽高度/手柄实际大小不变。右图由已发布与当前网格逐面求交；非游戏实测。',font=font,fill='#b9cdd8')
    outputs={}
    for suffix in ('png','jpg'):
        buffer=io.BytesIO();canvas.save(buffer,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        outputs['SB926紧凑_原版同尺度对比与剖面.'+suffix]=buffer.getvalue()
    return outputs,{'side_view':view,'profiles':profiles}


def refresh_preview(outputs):
    """Refresh only this stage's recorded previews; reject foreign or changed files."""
    report_path=OUT/'两格宽模型校验.json'
    previous=json.loads(report_path.read_text(encoding='utf-8'))['output_sha256']
    allowed={OUT/'SB926两格宽_开合槽实际预览.png',OUT/'SB926两格宽_开合槽实际预览.jpg',
             OUT/'SB926紧凑_原版同尺度对比与剖面.png',OUT/'SB926紧凑_原版同尺度对比与剖面.jpg',report_path}
    for path,data in outputs.items():
        if not path.exists():raise FileNotFoundError(path)
        if path!=report_path and sha(path.read_bytes())!=previous.get(str(path),sha(data)):
            raise FileExistsError(f'Existing stage output was changed externally: {path}')
        if path not in allowed and path.read_bytes()!=data:
            raise FileExistsError(f'Preview refresh cannot change resource or documentation: {path}')
    for path,data in outputs.items():
        if path in allowed:path.write_bytes(data)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write',action='store_true');parser.add_argument('--check-only',action='store_true')
    parser.add_argument('--refresh-preview',action='store_true',help='Refresh only hash-verified, existing v5 preview artifacts')
    args=parser.parse_args();mesh,parts,png=build();report=audit(mesh,parts);raw=encoded(mesh)
    report.update(mesh_sha256=sha(raw),mesh_bytes=len(raw),metadata=mesh['metadata'],
                  groups={name:{key:value for key,value in group.items() if key not in ('triangles','parts')} for name,group in mesh['groups'].items()})
    if args.check_only:
        if NEW_MESH.read_bytes()!=raw:raise ValueError('New runtime mesh differs from deterministic v5 build')
        print('PASS: wide mesh exact match; alpha5 mesh/PNG fixed hashes unchanged; physical recess/cord/card checks passed')
        return
    if args.write or args.refresh_preview:
        baseline,baseline_raw=load_baseline()
        pictures,views=preview(json.loads(raw),png);report['preview_views']=views
        comparison,comparison_info=slim_comparison_preview(json.loads(raw),png,baseline)
        pictures.update(comparison);report['same_scale_side_and_triangle_section_preview']=comparison_info
        outputs={NEW_MESH:raw,**{OUT/name:data for name,data in pictures.items()}}
        outputs[OUT/'alpha7原始宽机模型_只读留档.json']=baseline_raw
        outputs[OUT/'v5紧凑宽机模型.json']=raw
        report['output_sha256']={str(path):sha(data) for path,data in outputs.items()}
        outputs[OUT/'两格宽模型校验.json']=encoded(report)
        outputs[OUT/'使用说明.md']=('# SB926 紧凑机身 v5\n\n'
            '当前网格为 home_subor_sb926_wide.json；旧 alpha7 JAR/v4模型及报告、alpha6/v3、alpha5 网格、v1/v2、原 FC 与 PNG 均不改。'
            '主机XZ平面围绕(16,16)收至80%：机壳宽约1.534格，含外绕线约1.6格；机壳高度3.258和键帽高度不变。'
            '卡带、翻盖和手柄保留原大小，卡槽在新壳上真实重新开口，两个桌面手柄仅平移靠近，线材重新对齐接口。'
            '四格占位及旧WIDE状态不变，不改变已放方块归属。此物品仍是 FC 主机外观，不仿真学习键盘。\n\n'
            '空卡仅画 lid_closed，插卡仅画 lid_open；小盖绕后轴 110° 向后上打开。'
            '卡槽从上壳顶/底及下壳顶面物理切除，含四内壁、低凹底、两侧导轨孔与中央窄 PCB 接触口，不是黑色贴片。'
            '卡带保留原 0.60 display 大小。背面三 RCA 黄白红凹口及电源孔独立；手柄线沿侧面外绕。\n\n'
            '开合预览来自最终 JSON 三角形及原纹理，蓝边方形是实际16×16单格参照。'
            '对比图左侧将alpha7和v5置于同一相机同一缩放俯视，右侧从真实三角形在x=16求交生成剖面；不是游戏截图。'
            '工具 build_subor_wide_hardware.py --write 可复现，--check-only只读核验。'
            '仅允许替换哈希锁定的alpha7运行时宽机网格；其他不同已有文件拒绝覆盖，alpha7模型另存只读参考。\n').encode('utf-8')
        if args.refresh_preview:refresh_preview(outputs)
        else:
            current=NEW_MESH.read_bytes()
            if current!=raw and sha(current)!=BASELINE_MESH_SHA:
                raise FileExistsError('Current wide mesh is neither the reviewed alpha7 baseline nor this exact v5')
            write_new({path:data for path,data in outputs.items() if path!=NEW_MESH})
            NEW_MESH.write_bytes(raw)
        print(str(OUT))
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
