"""Code-native SFC free meshes and honest offline previews; never edits a source PNG.

References: https://www.nintendo.co.jp/clvs/index.html (controller original layout,
NOT mini console dimensions) plus user-supplied original Japanese hardware photographs.
"""
from __future__ import annotations
import argparse, copy, hashlib, io, json, math, sys, zipfile
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

PROJECT=Path(__file__).resolve().parents[1]
WORKSPACE=PROJECT.parent
sys.path.insert(0,str(WORKSPACE/'piq-fc-arcade/tools'))
from render_rocket_arcade_preview import Quad, render_view
from check_controller_pose_pipeline import display_matrix, translation, rotation, points
from build_tv_remote_model import render_gui
ASSETS=PROJECT/'src/main/resources/assets/piq_sfc_home'
OUT=PROJECT/'design/sfc-hardware-lowpoly-20260910-v1'
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar')
MATERIALS={'shell':'minecraft:block/iron_block','deck':'minecraft:block/smooth_stone',
 'metal':'minecraft:block/smooth_stone','pad_face':'minecraft:block/stone','dark':'piq_fc_arcade:block/home_retro_tv_dark',
 'black':'piq_fc_arcade:block/home_retro_tv_screen','rim':'piq_fc_arcade:block/home_retro_tv_rim',
 'label':'piq_fc_arcade:block/home_retro_tv_white',
 'a':'minecraft:block/red_concrete','b':'minecraft:block/yellow_concrete',
 'x':'minecraft:block/lapis_block','y':'minecraft:block/emerald_block'}
# Existing texels only: neutral shell 214/214/214, deck 176/176/176, button field 104/104/104.
MATERIAL_UV={'shell':(14.5/16,12.5/16),'deck':(14.5/16,11.5/16),
 'metal':(15.5/16,12.5/16),'pad_face':(.5/16,10.5/16),'x':(.5/16,1.5/16),'y':(1.5/16,15.5/16)}
GROUPS=('body','p1_docked','p2_docked','slot_cover','inserted','controller','cartridge')
# Fine single-stroke vector labels. No bitmap lettering, generated PNG, or per-frame font renderer.
STROKES={
 'A':[[(0,5),(1.5,0),(3,5)],[(.6,3),(2.4,3)]],
 'B':[[(0,5),(0,0),(2.2,0),(3,.65),(3,1.8),(2.2,2.5),(0,2.5)],[(2.2,2.5),(3,3.15),(3,4.35),(2.2,5),(0,5)]],
 'C':[[(3,.55),(2.3,0),(.7,0),(0,.7),(0,4.3),(.7,5),(2.3,5),(3,4.45)]],
 'E':[[(3,0),(0,0),(0,5),(3,5)],[(0,2.5),(2.5,2.5)]],
 'F':[[(0,5),(0,0),(3,0)],[(0,2.5),(2.5,2.5)]],
 'I':[[(.4,0),(2.6,0)],[(1.5,0),(1.5,5)],[(.4,5),(2.6,5)]],
 'J':[[(3,0),(3,4.2),(2.2,5),(.8,5),(0,4.2)]],
 'L':[[(0,0),(0,5),(3,5)]],
 'M':[[(0,5),(0,0),(1.5,2.7),(3,0),(3,5)]],
 'N':[[(0,5),(0,0),(3,5),(3,0)]],
 'O':[[(.7,0),(2.3,0),(3,.7),(3,4.3),(2.3,5),(.7,5),(0,4.3),(0,.7),(.7,0)]],
 'P':[[(0,5),(0,0),(2.2,0),(3,.7),(3,1.8),(2.2,2.5),(0,2.5)]],
 'R':[[(0,5),(0,0),(2.2,0),(3,.7),(3,1.8),(2.2,2.5),(0,2.5)],[(1.5,2.5),(3,5)]],
 'S':[[(3,.55),(2.3,0),(.7,0),(0,.7),(0,1.8),(.7,2.5),(2.3,2.5),(3,3.2),(3,4.3),(2.3,5),(.7,5),(0,4.45)]],
 'T':[[(0,0),(3,0)],[(1.5,0),(1.5,5)]],
 'U':[[(0,0),(0,4.2),(.8,5),(2.2,5),(3,4.2),(3,0)]],
 'W':[[(0,0),(.6,5),(1.5,2.7),(2.4,5),(3,0)]],
 'X':[[(0,0),(3,5)],[(3,0),(0,5)]],
 'Y':[[(0,0),(1.5,2.5),(3,0)],[(1.5,2.5),(1.5,5)]], ' ':[]}

def encode(x):return (json.dumps(x,ensure_ascii=False,separators=(',',':'))+'\n').encode()
def sha(x):return hashlib.sha256(x).hexdigest().upper()
def rounded(x0,x1,z0,z1,r,steps=5):
    pts=[]
    for cx,cz,start in ((x1-r,z1-r,0),(x0+r,z1-r,90),(x0+r,z0+r,180),(x1-r,z0+r,270)):
        for deg in np.linspace(start,start+90,steps+1):
            a=math.radians(deg);pts.append([cx+r*math.cos(a),cz+r*math.sin(a)])
    return np.array(pts)
def ellipse(x,z,rx,rz,n=28):return np.array([[x+rx*math.cos(a),z+rz*math.sin(a)] for a in np.arange(n)*2*math.pi/n])

class Mesh:
    def __init__(self):self.parts=[]
    def add(self,name,material,polygons):
        triangles=[]
        for polygon in polygons:
            p=np.asarray(polygon,dtype=float)
            for i in range(1,len(p)-1):
                tri=p[[0,i,i+1]];n=np.cross(tri[1]-tri[0],tri[2]-tri[0]);length=np.linalg.norm(n)
                if length<1e-9:continue
                triangles.append({'p':np.round(tri,7).tolist(),'n':np.round(n/length,7).tolist(),'uv':[list(MATERIAL_UV.get(material,(.46875,.46875)))]*3})
        if triangles:self.parts.append({'name':name,'material':material,'triangles':triangles})
    def loft(self,name,outline,layers,material,bottom=True,top=True):
        outline=np.asarray(outline);center=outline.mean(axis=0);rings=[]
        for y,inset in layers:
            factor=np.maximum(0,1-inset/np.maximum(np.ptp(outline,axis=0)/2,.001))
            o=center+(outline-center)*factor;rings.append(np.column_stack((o[:,0],np.full(len(o),y),o[:,1])))
        polys=[]
        for lower,upper in zip(rings,rings[1:]):
            for i in range(len(outline)):
                j=(i+1)%len(outline);polys.append([lower[i],upper[i],upper[j],lower[j]])
        if bottom:
            c=[center[0],rings[0][0,1],center[1]]
            polys += [[c,rings[0][i],rings[0][(i+1)%len(outline)]] for i in range(len(outline))]
        if top:
            c=[center[0],rings[-1][0,1],center[1]]
            polys += [[c,rings[-1][(i+1)%len(outline)],rings[-1][i]] for i in range(len(outline))]
        self.add(name,material,polys)
    def solid(self,name,outline,y0,y1,material,bevel=.03):
        b=min(bevel,(y1-y0)*.45)
        self.loft(name,outline,[(y0,b),(y0+b,0),(y1-b,0),(y1,b)],material)
    def box(self,name,x0,x1,z0,z1,y0,y1,material,r=.08,b=.03):self.solid(name,rounded(x0,x1,z0,z1,r,1),y0,y1,material,b)
    def disk(self,name,x,z,rx,rz,y0,y1,material,bevel=.03,n=24):self.solid(name,ellipse(x,z,rx,rz,n),y0,y1,material,bevel)
    def text(self,text,x,z,y,size,material='dark'):
        polys=[]
        for ch in text:
            for path in STROKES[ch]:
                for start,end in zip(path,path[1:]):
                    a=np.array([x-start[0]*size,z-start[1]*size]);b=np.array([x-end[0]*size,z-end[1]*size])
                    direction=b-a;normal=np.array([-direction[1],direction[0]])/np.linalg.norm(direction)*size*.18
                    polys.append([[v[0],y,v[1]] for v in (a-normal,a+normal,b+normal,b-normal)])
            x-=4.1*size
        self.add('print '+text,material,polys)
    def extend(self,other):self.parts.extend(copy.deepcopy(other.parts))

def transformed(mesh,scale=1,offset=(0,0,0),matrix=None,prefix=''):
    out=Mesh();mat=np.eye(3) if matrix is None else np.asarray(matrix);offset=np.asarray(offset)
    for part in mesh.parts:
        polys=[]
        for t in part['triangles']:polys.append(np.asarray(t['p'])@mat.T*scale+offset)
        out.add(prefix+part['name'],part['material'],polys)
    return out
def front(mesh,z):return transformed(mesh,offset=(0,0,z),matrix=[[1,0,0],[0,0,1],[0,-1,0]])
def cylinder_front(name,x,y,r,z,depth,material,n=20):
    m=Mesh();m.disk(name,x,y,r,r,0,depth,material,n=n);return front(m,z)
def tube(mesh,name,centers,r,material='dark',sides=8):
    centers=np.asarray(centers);rings=[]
    for i,c in enumerate(centers):
        tangent=centers[min(len(centers)-1,i+1)]-centers[max(0,i-1)];tangent/=np.linalg.norm(tangent)
        ref=np.array([0.,1.,0.]) if abs(tangent[1])<.9 else np.array([1.,0.,0.])
        side=np.cross(tangent,ref);side/=np.linalg.norm(side);up=np.cross(side,tangent)
        rings.append([c+r*(side*math.cos(a)+up*math.sin(a)) for a in np.arange(sides)*2*math.pi/sides])
    polys=[]
    for first,last in zip(rings,rings[1:]):
        for i in range(sides):j=(i+1)%sides;polys.append([first[i],last[i],last[j],first[j]])
    mesh.add(name,material,polys)
def bezier(control,count=12):
    p=np.array(control);return np.array([(1-t)**3*p[0]+3*(1-t)**2*t*p[1]+3*(1-t)*t*t*p[2]+t**3*p[3] for t in np.linspace(0,1,count)])

def console():
    m=Mesh();outline=rounded(2.61,13.39,4.05,15.15,.46,1)
    for x in (3.5,12.5):
        for z in (5.0,14.3):m.box('rubber foot',x-.42,x+.42,z-.52,z+.52,.10,.34,'dark',.20,.07)
    m.loft('lower rounded shell',outline,[(.30,.23),(.48,0),(1.23,0),(1.34,.025)],'shell')
    m.loft('continuous case seam',outline,[(1.34,.025),(1.43,.025)],'rim')
    m.loft('upper rounded shell',outline,[(1.43,.025),(2.55,.025),(2.83,.22)],'shell',top=False)
    outer=rounded(2.83,13.17,4.27,14.93,.32,1);inner=rounded(3.93,12.07,5.50,14.35,.20,1)
    m.add('sloping shoulder surround','shell',[[[outer[i,0],2.83,outer[i,1]],[inner[i,0],3.04,inner[i,1]],
              [inner[(i+1)%len(inner),0],3.04,inner[(i+1)%len(inner),1]],[outer[(i+1)%len(outer),0],2.83,outer[(i+1)%len(outer),1]]] for i in range(len(outer))])
    # Actual opening remains x4.64..11.36 / z9.83..10.97; inserted card cannot intersect a hidden cap.
    m.box('front grey deck',3.94,12.06,5.50,9.83,2.75,3.025,'deck',.25,.04)
    m.box('rear grey deck',3.94,12.06,10.97,14.35,2.75,3.025,'deck',.25,.04)
    m.box('slot end left',3.94,4.64,9.58,11.22,2.75,3.025,'deck',.14,.03)
    m.box('slot end right',11.36,12.06,9.58,11.22,2.75,3.025,'deck',.14,.03)
    m.box('slot dark bottom',4.64,11.36,9.83,10.97,2.754,2.778,'black',.12,.005)
    m.box('slot front lining',4.64,11.36,9.83,9.90,2.778,3.048,'dark',.025,.01)
    m.box('slot rear lining',4.64,11.36,10.90,10.97,2.778,3.048,'dark',.025,.01)
    for title,x in (('POWER',10.6),('RESET',5.4)):
        m.box(title+' recessed rim',x-.77,x+.77,6.50,8.21,3.026,3.085,'shell',.20,.024)
        m.box(title+' black well',x-.61,x+.61,6.64,8.08,3.085,3.11,'dark',.14,.008)
        if title=='POWER':
            m.box('POWER sliding switch',x-.43,x+.43,7.17,7.89,3.112,3.34,'metal',.12,.055)
            for z in (7.35,7.49,7.63):m.box('POWER grip ridge',x-.32,x+.32,z,z+.045,3.34,3.375,'deck',.014,.01)
        else:m.box('RESET push switch',x-.45,x+.45,6.83,7.76,3.112,3.28,'metal',.13,.05)
        m.text(title,x+.68,6.40,3.038,.048)
    m.box('EJECT inset surround',6.45,9.55,6.69,8.82,3.026,3.08,'rim',.30,.025)
    m.loft('EJECT sculpted paddle',rounded(6.53,9.47,6.75,8.76,.18,1),[(3.079,.04),(3.18,0),(3.36,.05)],'deck')
    m.text('EJECT',8.55,7.89,3.365,.055,'dark')
    m.text('SUPER FAMICOM',9.25,6.07,3.032,.046,'dark')
    # Normal front (+screen-left is high X), true rounded 7-hole connectors.
    for port,x in ((1,10.75),(2,5.25)):
        face=Mesh();face.box(f'P{port} connector surround',x-1.14,x+1.14,1.56,2.59,0,.12,'deck',.45,.035)
        face.box(f'P{port} recessed opening',x-.95,x+.95,1.74,2.40,.121,.145,'dark',.29,.008)
        for i in range(7):face.disk(f'P{port} pin {i}',x-.66+i*.22,2.07,.065,.065,.146,.151,'black',.001,n=10)
        m.extend(front(face,4.115))
    # Preserve every AV center and terminal z from the shipped connector contract.
    for c,material in enumerate(('b','label','a')):
        x=9.335-.66*c
        m.extend(cylinder_front('AV '+str(c)+' rim',x,1.955,.245,-15.10,.10,material))
        # Cylinder helper faces north; reflect x/z to place its outward face at the rear.
        part=m.parts.pop();part['name']='AV '+str(c)+' ring'
        for t in part['triangles']:
            for p in t['p']:p[2]=-p[2]
            for p in t['p']:p[0]=2*x-p[0]
            t['n']=[-t['n'][0],t['n'][1],-t['n'][2]]
        m.parts.append(part)
        disc=Mesh();disc.disk('AV '+str(c)+' black socket',x,1.955,.104,.104,0,.001,'black',.0001,n=16)
        rear=front(disc,-15.201)
        m.extend(transformed(rear,offset=(2*x,0,0),matrix=[[-1,0,0],[0,1,0],[0,0,-1]]))
    for x in (3.06,12.7):
        for z in np.arange(11.8,14.0,.28):m.box('vent groove',x,x+.26,z,z+.095,2.881,2.897,'rim',.035,.003)
    for key,x,z in (('x',4.83,13.41),('y',5.13,13.12),('a',4.53,13.12),('b',4.83,12.83)):
        m.disk('four-color emblem '+key,x,z,.11,.11,3.028,3.045,key,.006,n=16)
    return m

def controller():
    m=Mesh()
    # Deliberate flat runs and sparse chamfers, not a densely smoothed capsule.
    outline=np.array([[6.08,.75],[5.76,1.52],[5.13,2.08],[4.20,2.28],[3.18,2.22],[1.65,1.66],
        [-1.65,1.66],[-3.18,2.22],[-4.20,2.28],[-5.13,2.08],[-5.76,1.52],[-6.08,.75],
        [-6.08,-.65],[-5.65,-1.62],[-4.65,-2.25],[-3.32,-2.34],[-2.22,-1.85],[-1.38,-1.44],
        [1.38,-1.44],[2.22,-1.85],[3.32,-2.34],[4.65,-2.25],[5.65,-1.62],[6.08,-.65]])+[8,8]
    m.loft('continuous lower grip shell',outline,[(7.15,.17),(7.33,0),(7.66,0)],'deck')
    m.loft('thin grip shell seam',outline,[(7.66,.015),(7.72,.015)],'rim')
    m.loft('continuous upper grip shell',outline,[(7.72,0),(8.06,0),(8.19,.13)],'shell')
    for name,x in (('L',11.70),('R',4.30)):
        m.box(name+' shoulder',x-1.3,x+1.3,9.55,10.43,7.70,8.22,'deck',.38,.07)
        m.text(name,x+.10,10.12,8.225,.055)
    m.disk('D-pad recessed circular seat',11.35,7.82,1.3,1.3,8.191,8.212,'metal',.008,n=12)
    m.disk('D-pad recess floor',11.35,7.82,1.24,1.24,8.2121,8.214,'deck',.0001,n=12)
    # A single concave cross, visibly raised over its recess.
    cross=np.array([[-.32,-1.02],[.32,-1.02],[.32,-.32],[1.02,-.32],[1.02,.32],[.32,.32],[.32,1.02],[-.32,1.02],[-.32,.32],[-1.02,.32],[-1.02,-.32],[-.32,-.32]])+[11.35,7.82]
    m.loft('D-pad rocker',cross,[(8.214,.025),(8.41,0),(8.52,.035)],'dark')
    m.disk('D-pad center well',11.35,7.82,.21,.21,8.521,8.527,'rim',.001,n=8)
    m.disk('face button dark oval inset',4.65,7.85,1.88,1.88,8.191,8.222,'pad_face',.01,n=12)
    for name,x,z in (('X',4.65,8.82),('Y',5.62,7.85),('A',3.68,7.85),('B',4.65,6.88)):
        m.disk(name+' recessed collar',x,z,.485,.485,8.223,8.255,'rim',.012,n=12)
        m.loft(name+' color button',ellipse(x,z,.39,.39,12),[(8.254,.006),(8.46,0),(8.54,.03)],name.lower())
        tx,tz={'X':(4.78,9.57),'Y':(6.53,8.04),'A':(3.00,8.04),'B':(4.78,6.33)}[name]
        m.text(name,tx,tz,8.232,.055,'shell')
    # SELECT and START are diagonal rubber pills, not upright squares.
    angle=math.radians(34);rot=np.array([[math.cos(angle),-math.sin(angle)],[math.sin(angle),math.cos(angle)]])
    for name,x in (('SELECT',8.82),('START',7.35)):
        pill=(rounded(-.20,.20,-.53,.53,.14,1)@rot.T)+[x,7.77]
        m.solid(name+' diagonal rubber key',pill,8.202,8.38,'dark',.038)
        m.text(name,x+.48,6.79,8.202,.035)
    m.text('SUPER FAMICOM',9.25,9.34,8.202,.037)
    m.box('rear cable strain relief',7.77,8.23,9.56,10.54,7.70,8.01,'dark',.10,.035)
    for z in (9.95,10.12,10.29,10.46):m.box('cable flexible rib',7.72,8.28,z,z+.07,7.72,7.98,'rim',.08,.01)
    return m

def cartridge():
    f=Mesh();outline=rounded(3.40,12.60,4.60,10.83,.27,1)
    f.loft('grey cartridge casing',outline,[(0,.10),(.13,0),(1.10,0),(1.20,.10)],'deck')
    frontshell=Mesh();frontshell.solid('front light cartridge shell',rounded(3.48,12.52,4.73,10.28,.22,1),0,.11,'shell',.04)
    # Preserve the existing external cover plane and full 2:1 fitted UV rectangle.
    frontshell.solid('label recessed border',rounded(4.31,11.69,6.26,9.72,.20),.111,.135,'rim',.005)
    frontshell.solid('plain SFC label',rounded(4.51,11.49,6.43,9.55,.12),.136,.151,'label',.004)
    frontshell.text('SUPER FAMICOM',10.05,8.20,.153,.060,'dark')
    for x in (3.68,12.18):
        for y in np.arange(5.2,9.6,.37):frontshell.box('cartridge side grip',x,x+.14,y,y+.065,.111,.128,'deck',.028,.003)
    # Canonical body starts at rear z8.60; front face z7.26, label reaches z7.247.
    out=front(f,8.60);out.extend(front(frontshell,7.40))
    slot=Mesh();slot.box('bottom contact opening',4.4,11.6,7.62,8.38,4.574,4.596,'black',.08,.005);out.extend(slot)
    return out

def build():
    body=console();pad=controller();card=cartridge();groups={'body':body,'controller':pad,'cartridge':card}
    lid=Mesh();lid.box('front dust flap',4.92,11.08,10.0,10.36,2.94,2.98,'metal',.07,.008);lid.box('rear dust flap',4.92,11.08,10.44,10.80,2.94,2.98,'metal',.07,.008);groups['slot_cover']=lid
    for port,cx,px in ((1,11.0,10.75),(2,5.0,5.25)):
        g=transformed(pad,.415,(cx-8*.415,.17-7.15*.415,1.60-8*.415),prefix=f'P{port} ')
        centers=np.vstack((bezier([[cx,.45,2.65],[cx,.33,2.94],[px,.21,3.08],[px,.21,3.30]],12)[:-1],
                          bezier([[px,.21,3.30],[px,.21,3.62],[px,2.04,3.09],[px,2.04,3.70]],15)))
        tube(g,f'P{port} curved cord',centers,.062)
        plug=Mesh();plug.box(f'P{port} plug',px-.42,px+.42,1.80,2.32,0,.38,'dark',.18,.04);g.extend(front(plug,4.00));groups[f'p{port}_docked']=g
    groups['inserted']=transformed(card,.645,(8-8*.645,2.81-4.6*.645,10.40-8*.645),prefix='inserted ')
    return {'version':1,'credit':'PIQ original code-native SFC hardware; official Japanese layout, no commercial ROM or photo texture.',
            'materials':MATERIALS,'groups':{name:{'parts':groups[name].parts} for name in GROUPS},
            'contract':{'av_centers':[[9.335-.66*i,1.955,15.2] for i in range(3)],'cover':[4.51,6.43,11.49,9.55,7.241],
              'insert_scale':.645,'insert_offset':[8-8*.645,2.81-4.6*.645,10.40-8*.645],
              'button_centers':{'X':[4.65,8.54,8.82],'Y':[5.62,8.54,7.85],'A':[3.68,8.54,7.85],'B':[4.65,8.54,6.88]},
              'coordinate_note':'16 units per block; north=-Z; viewer-left=+X; held face normal=+Y'}},groups

def quads(mesh):
    return [Quad(np.array(t['p']+[t['p'][-1]]),np.array(t['uv']+[t['uv'][-1]])*16,MATERIALS[p['material']],i,'mesh') for i,p in enumerate(mesh.parts) for t in p['triangles']]
def textures():
    images={};hashes={}
    with zipfile.ZipFile(MC) as jar:
        for value in MATERIALS.values():
            namespace,path=value.split(':');rel=f'assets/{namespace}/textures/{path}.png'
            raw=jar.read(rel) if namespace=='minecraft' else (WORKSPACE/'piq-fc-arcade/src/main/resources'/rel).read_bytes()
            images[value]=np.array(Image.open(io.BytesIO(raw)).convert('RGBA'));hashes[value]=sha(raw)
    return images,hashes
def audit(doc,groups):
    checks=[];details={}
    def check(name,ok):checks.append({'name':name,'ok':bool(ok)})
    for name,g in groups.items():
        ts=[t for p in g.parts for t in p['triangles']];v=np.concatenate([np.array(t['p']) for t in ts]);norm=np.array([t['n'] for t in ts])
        check(name+' finite native one-cell bounds',np.isfinite(v).all() and (v>=-.000001).all() and (v<=16.000001).all())
        check(name+' finite unit normals',np.isfinite(norm).all() and np.allclose(np.linalg.norm(norm,axis=1),1,atol=1e-6))
        check(name+' bounded topology',0<len(ts)<18000 and 0<len(g.parts)<1000)
        check(name+' material references',all(p['material'] in MATERIALS for p in g.parts))
        details[name]={'parts':len(g.parts),'triangles':len(ts),'bounds':[v.min(0).tolist(),v.max(0).tolist()]}
    c=doc['contract']['button_centers'];check('X top B bottom Y visual-left A visual-right',c['X'][2]>c['Y'][2]>c['B'][2] and c['Y'][0]>c['X'][0]>c['A'][0])
    check('diagonal Select Start and L R shoulders',all(any(p['name']==n for p in groups['controller'].parts) for n in ('SELECT diagonal rubber key','START diagonal rubber key','L shoulder','R shoulder')))
    check('single continuous controller shells',sum(p['name']=='continuous upper grip shell' for p in groups['controller'].parts)==1)
    check('AV contract retained',np.allclose(doc['contract']['av_centers'],[[9.335,1.955,15.2],[8.675,1.955,15.2],[8.015,1.955,15.2]]))
    check('cover mapping retained',doc['contract']['cover']==[4.51,6.43,11.49,9.55,7.241] and doc['contract']['insert_scale']==.645)
    check('low-poly budget below old 26418 triangles',sum(d['triangles'] for d in details.values())<=18500)
    check('controller sparse outline and mechanical seam',details['controller']['triangles']<=2600 and any(p['name']=='thin grip shell seam' for p in groups['controller'].parts))
    check('neutral dark button field separate from shell',next(p['material'] for p in groups['controller'].parts if p['name']=='face button dark oval inset')=='pad_face')
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'groups':details,'mesh_sha256':sha(encode(doc)),
            'notes':['Actual triangle/UV resource geometry, not a Minecraft screenshot.','No existing PNG modified; no photo texture or AI bitmap.','No gameplay, controller-input or device-identity code changed.']}
def previews(groups,tex):
    complete=Mesh();complete.extend(groups['body']);complete.extend(groups['p1_docked']);complete.extend(groups['p2_docked']);complete.extend(groups['inserted'])
    out={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21);title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',30)
    views=[('console-front',complete,(.62,.90,-1.1),'主机前斜 · 低多边形斜角 / 前双插口'),('console-rear',complete,(-.67,.80,1.1),'主机后斜 · AV 端口保持原坐标'),
           ('controller-top',groups['controller'],(0,1,-.001),'手柄俯视 · X蓝上 / Y绿左 / A红右 / B黄下'),('cartridge',groups['cartridge'],(.35,.4,-1.0),'灰色卡带 · 原封面坐标保持')]
    board=Image.new('RGB',(1480,1250),'#e9e8e3');d=ImageDraw.Draw(board);d.text((25,12),'SFC 低多边形重制 · 实际资源离线预览（非游戏截图）',font=title,fill='#283139')
    for i,(name,mesh,direction,label) in enumerate(views):
        pic,_=render_view(quads(mesh),tex,direction,size=(710,530),supersample=2)
        x=22+(i%2)*735;y=76+(i//2)*585;board.paste(pic,(x,y),pic);d.text((x,y+540),label,font=font,fill='#38424a')
        raw=io.BytesIO();pic.save(raw,format='PNG');out[OUT/(name+'.png')]=raw.getvalue()
    raw=io.BytesIO();board.save(raw,format='PNG');out[OUT/'hardware-overview.png']=raw.getvalue()
    return out
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');parser.add_argument('--assets',action='store_true');args=parser.parse_args()
    doc,groups=build();report=audit(doc,groups);tex,hashes=textures();report['texture_sha256']=hashes
    report['sampled_material_rgb']={key:[int(v) for v in tex[value][int(MATERIAL_UV.get(key,(.46875,.46875))[1]*tex[value].shape[0]),int(MATERIAL_UV.get(key,(.46875,.46875))[0]*tex[value].shape[1]),:3]] for key,value in MATERIALS.items()}
    for key,expected in (('shell',[214,214,214]),('deck',[176,176,176]),('pad_face',[104,104,104])):
        assert report['sampled_material_rgb'][key]==expected,(key,'non-neutral sample')
    if not report['ok']:print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(1)
    out=previews(groups,tex);out[OUT/'sfc_hardware.json']=encode(doc);out[OUT/'geometry-audit.json']=encode(report)
    if args.write:
        OUT.mkdir(parents=True,exist_ok=True)
        for path,data in out.items():path.write_bytes(data)
    if args.assets:
        path=ASSETS/'meshes/sfc_hardware.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(encode(doc))
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
