"""Editable SB-926-inspired reference mesh; no production resources/JAR modified.

Deterministic geometry, per-face UV and typeset labels (not a photo collage).
Preview is rasterized directly from the delivered bbmodel vertices and UVs.
"""
from __future__ import annotations
import base64
import hashlib
import io
import json
import math
import uuid
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from render_rocket_arcade_preview import Quad, render_view

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '制作Mod/03-街机模拟/PIQ-FC街机/小霸王SB926模型草案-v1'
SIZE = 2048
COLORS = {'壳体': '#d7d3ad', '底座': '#b1b197', '浅键': '#e4e2c8',
          '深键': '#77786d', '凹槽': '#303632', '紫面板': '#39334f',
          '灰按钮': '#a6a796', '线材': '#29282d', '卡黄': '#d6ad37',
          '金属': '#898e86', '红灯': '#d95240', '文字': '#30372f'}

def uid(name): return str(uuid.uuid5(uuid.NAMESPACE_URL, 'piq/subor-v1/' + name))
def font(n, bold=False):
    return ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', n)

class Atlas:
    def __init__(self, key_hints=True):
        self.key_hints = key_hints
        self.image = Image.new('RGBA', (SIZE, SIZE), (216, 212, 181, 255))
        self.draw = ImageDraw.Draw(self.image)
        self.x = self.y = 8
        self.row = 0
        self.tiles = {}
        for name, color in COLORS.items(): self.tile(name, '', color, 48, 48)
        for name, color in COLORS.items():
            rgb=tuple(int(color[i:i+2],16) for i in (1,3,5))
            self.tile(name+'_侧面','',tuple(round(c*.82) for c in rgb),48,48)

    def tile(self, name, text, bg, w=80, h=80, fg='#30372f', sub=''):
        if name in self.tiles: return self.tiles[name]
        if self.x + w + 8 > SIZE: self.x, self.y, self.row = 8, self.y + self.row + 8, 0
        assert self.y + h + 8 < SIZE, 'atlas overflow'
        x,y = self.x,self.y
        self.draw.rectangle((x-3,y-3,x+w+3,y+h+3),fill=bg)
        if text:
            n = min(96, int(h * (.39 if sub else .55)))
            while n > 8 and self.draw.textlength(text, font=font(n, True)) > w-10: n-=1
            self.draw.text((x+w/2,y+h*(.32 if sub else .48)),text,font=font(n,True),fill=fg,anchor='mm')
        if sub and (self.key_hints or not name.startswith('键_')):
            sn=max(9,int(h*.17))
            while sn>8 and self.draw.textlength(sub,font=font(sn))>w-14: sn-=1
            self.draw.text((x+w/2,y+h*.78),sub,font=font(sn),fill='#607a76',anchor='mm')
        self.tiles[name] = [x,y,x+w,y+h]
        self.x += w+8
        self.row = max(self.row,h)
        return self.tiles[name]

class Model:
    def __init__(self, key_hints=True): self.atlas=Atlas(key_hints); self.elements=[]; self.groups={}; self.key_count=0
    def mesh(self,name,vertices,faces,group):
        points={f'v{i}':[round(float(v[0]),5),round(float(v[1]),5),round(float(-v[2]),5)] for i,v in enumerate(vertices)}
        mapped={}
        for i,(indices,material,uv) in enumerate(faces):
            r=self.atlas.tiles[material]
            if uv is None: uv=[(r[0],r[1]),(r[0],r[3]),(r[2],r[3]),(r[2],r[1])][:len(indices)]
            keys=[f'v{j}' for j in indices]
            # Reflect Z to put the keyboard's readable front toward Blockbench +Z.
            mapped[f'f{i}']={'vertices':keys[::-1],'uv':dict(zip(keys,uv)),'texture':0}
        element={'name':name,'type':'mesh','uuid':uid(group+'/'+name),'origin':[0,0,0],
                 'rotation':[0,0,0],'vertices':points,'faces':mapped,'visibility':True,
                 'export':True,'locked':False,'shading':'flat','color':len(self.groups)%8}
        self.elements.append(element); self.groups.setdefault(group,[]).append(element['uuid'])

    def box(self,name,x0,z0,x1,z1,y0,y1,mat,group,top=None,inset=0,slope=0):
        vertices=[]
        for z in (z0,z1):
            for y in (0,1):
                for x in (x0,x1):
                    xx=x+(inset if x==x0 else -inset) if y else x
                    zz=z+(inset if z==z0 else -inset) if y else z
                    vertices.append((xx,(y1 if y else y0)+slope*zz,zz))
        faces=[]
        # vertices: x bit0, y bit1, z bit2; outward winding before reflection.
        for direction,ix in [('下',[4,0,1,5]),('上',[2,6,7,3]),('前',[3,1,0,2]),
                             ('后',[6,4,5,7]),('左',[2,0,4,6]),('右',[7,5,1,3])]:
            tile=(top or mat) if direction=='上' else mat+'_侧面'
            uv=None
            if direction=='上':
                u0,v0,u1,v1=self.atlas.tiles[tile]
                uv=[(u0,v1),(u0,v0),(u1,v0),(u1,v1)]
            faces.append((ix,tile,uv))
        self.mesh(name,vertices,faces,group)

    def key(self,label,x,z,w=1.19,h=1.12,dark=False,group='02 主键盘',sub=''):
        mat='深键' if dark else '浅键'
        tile='键_'+mat+'_'+label+'_'+sub
        self.atlas.tile(tile,label,COLORS[mat],int(max(80,w*65)),int(max(80,h*65)),fg='#242d2a',sub=sub)
        self.box('键帽 '+str(self.key_count+1).zfill(3)+' '+label,x,z,x+w,z+h,1.43,1.95,
                 mat,group,tile,inset=.085,slope=.055)
        self.key_count+=1

    def disk(self,name,x,z,r,y0,y1,mat,group,n=12):
        vs=[(x+r*math.cos(i*2*math.pi/n),y,z+r*math.sin(i*2*math.pi/n)) for y in (y0,y1) for i in range(n)]
        vs += [(x,y0,z),(x,y1,z)]
        faces=[]
        ruv=self.atlas.tiles[mat]; sample=(ruv[0]+10,ruv[1]+10)
        for i in range(n):
            j=(i+1)%n
            faces += [([i,j,j+n,i+n],mat,None),([2*n,i,j],mat,[sample]*3),
                      ([2*n+1,j+n,i+n],mat,[sample]*3)]
        self.mesh(name,vs,faces,group)

    def cable(self,name,points,group):
        vertices=[]; n=6
        for i,p in enumerate(points):
            a=np.array(points[min(i+1,len(points)-1)])-np.array(points[max(i-1,0)])
            a=a/np.linalg.norm(a); b=np.cross(a,(0,1,0)); b=b/np.linalg.norm(b); c=np.cross(a,b)
            for j in range(n): vertices.append(np.array(p)+.065*(b*math.cos(j*2*math.pi/n)+c*math.sin(j*2*math.pi/n)))
        faces=[]
        for i in range(len(points)-1):
            for j in range(n): faces.append(([i*n+j,i*n+(j+1)%n,(i+1)*n+(j+1)%n,(i+1)*n+j],'线材',None))
        self.mesh(name,vertices,faces,group)

    def build(self):
        g='01 主机外壳与接口'
        self.box('下壳',-16,-7.6,16,7.6,.22,.74,'底座',g,inset=.12)
        self.box('一体斜面上壳',-15.9,-7.5,15.9,7.5,.75,1.4,'壳体',g,inset=.22,slope=.055)
        # Recessed key beds; real caps stand above them, rather than painted keyboard.
        for name,a,b,c,d in [('主键盘',-14.99,-5.32,4.68,1.68),('编辑区',5.52,-.08,9.06,2.5),('数字区',10.02,-5.32,14.83,1.68),('方向区',5.52,-5.32,9.06,-2.55)]:
            self.box(name+'底盘',a,b,c,d,1.405,1.435,'凹槽',g,slope=.055)
        rows=[([('~',1)]+[(s,1) for s in '1234567890-=']+[('Backspace',2)],.42),
              ([('Tab',1.5)]+[(s,1) for s in 'QWERTYUIOP[]']+[('\\',1.5)],-.93),
              ([('Caps Lock',1.75)]+[(s,1) for s in "ASDFGHJKL;'"]+[('Enter',2.25)],-2.28),
              ([('Shift',2.25)]+[(s,1) for s in 'ZXCVBNM,./']+[('Shift',2.75)],-3.63),
              ([('Ctrl',1.25),('',1.25),('Alt',1.25),(' ',7.5),('Alt',1.25),('',1.25),('Ctrl',1.25)],-4.98)]
        for row,z in rows:
            x=-14.8
            for label,u in row:
                if label: self.key(label,x,z,u*1.29-.10,dark=len(label)>1 and label!=' ',sub='拼音' if label in 'QWERTYASDF' else '')
                x+=u*1.29
            assert abs(x-4.55)<.01
        self.key('Esc',-14.8,2.35,dark=True,group='03 功能键')
        for i in range(12):
            self.key('F'+str(i+1),-12.45+i*1.29+(i//4)*.34,2.35,dark=4<=i<8,group='03 功能键')
        for i,s in enumerate(['Print','Scroll','Pause']): self.key(s,5.6+i*1.16,2.35,w=1.06,dark=True,group='03 功能键')
        for j,row in enumerate([['Delete','End','PgDn'],['Insert','Home','PgUp']]):
            for i,s in enumerate(row): self.key(s,5.6+i*1.16,.02+j*1.24,w=1.06,h=1.06,dark=True,group='04 编辑与方向键')
        for i,s in enumerate(['←','↓','→']): self.key(s,5.6+i*1.16,-4.98,w=1.06,dark=True,group='04 编辑与方向键')
        self.key('↑',6.76,-3.64,w=1.06,dark=True,group='04 编辑与方向键')
        for row,z in [(['Num','/','*','-'],.42),(['7','8','9','+'],-.93),(['4','5','6'], -2.28),(['1','2','3','Enter'],-3.63),(['0','.',],-4.98)]:
            for i,s in enumerate(row):
                x=10.1+i*1.16
                if s=='.': x=12.42
                self.key(s,x,z-1.35 if s in ['+','Enter'] else z,w=2.22 if s=='0' else 1.06,h=2.47 if s in ['+','Enter'] else 1.12,
                         dark=s in ['Num','/','*','-','+','Enter'],group='05 数字小键盘')
        # Header decals are UV faces on matching sloped solid panels.
        for name,text,rect,w,h in [('品牌','小霸王',(-14.5,5.10,-6,6.65),512,128),
                                 ('型号','中英文电脑学习机  SB-926',(5.2,5.6,14.8,6.5),640,80),
                                 ('英文标','SUBOR',(-2.7,3.25,2.7,4.13),320,64)]:
            self.atlas.tile(name,text,COLORS['壳体'],w,h,fg='#a13935' if name=='品牌' else '#414339')
            self.box(name,*rect,1.402,1.42,'壳体',g,top=name,slope=.055)
        self.box('卡槽黑色内腔',-4.85,4.8,4.85,6.93,1.408,1.46,'凹槽','06 插卡槽',slope=.055)
        for name,rect in [('左框',(-5.10,4.56,-4.79,7.1)),('右框',(4.79,4.56,5.10,7.1)),('前框',(-4.79,4.56,4.79,4.85)),('后框',(-4.79,6.93,4.79,7.1))]:
            self.box(name,*rect,1.43,1.76,'壳体','06 插卡槽',slope=.055)
        self.box('插槽连接器',-4.25,5.59,4.25,6.03,1.46,1.6,'线材','06 插卡槽',slope=.055)
        for i in range(30): self.box('触点'+str(i+1),-4.1+i*.28,5.79,-3.98+i*.28,5.87,1.605,1.66,'金属','06 插卡槽',slope=.055)
        for i in range(3):
            self.box('指示灯底框'+str(i),10.25+i*.95,2.45,11.1+i*.95,3.35,1.415,1.5,'深键',g,slope=.055)
            self.box('指示灯'+str(i),10.50+i*.95,2.63,10.85+i*.95,2.84,1.51,1.56,'红灯',g,slope=.055)
        self.disk('圆形电源键',14.15,2.92,.42,1.57,1.74,'深键',g)
        for side in (-1,1):
            x0,x1=(-16.32,-15.88) if side<0 else (15.88,16.32)
            self.box(('左' if side<0 else '右')+'手柄接口',x0,3.8,x1,5.05,.75,1.34,'线材',g)
        for i in range(12): self.box('背部散热孔'+str(i),7+i*.5,7.46,7.19+i*.5,7.54,1.12,1.5,'凹槽',g)
        for i,(x,mat) in enumerate([(-11,'卡黄'),(-9.6,'浅键'),(-8.2,'线材')]):
            self.box('背部AV与电源接口'+str(i),x,7.46,x+.8,7.75,.85,1.38,mat,g)
        for x in [-13.5,13.5]:
            for z in [-5.7,5.7]: self.box('橡胶脚'+str(x)+str(z),x-.5,z-.4,x+.5,z+.4,0,.22,'线材',g)
        self.cartridge()
        self.controller(-8.7,0)
        self.controller(7.9,1)
        return self

    def cartridge(self):
        g='07 可拆黄色学习卡'
        self.box('黄色卡带外壳',-3.85,5.38,3.85,6.12,1.8,6.25,'卡黄',g,inset=.08)
        self.atlas.tile('卡带标签','中英文学习卡','#eee7c7',512,256,sub='BASIC  /  8-BIT  /  LEARNING')
        # North face UV is oriented by world X, so text reads correctly from +Z after reflection.
        el=self.elements[-1]; f=el['faces']['f2']; keys=f['vertices']; r=self.atlas.tiles['卡带标签']
        # Use a separate inset label slab instead of covering the yellow perimeter.
        self.box('卡带标签贴纸',-3.3,5.355,3.3,5.376,2.7,5.60,'卡黄',g)
        face=self.elements[-1]['faces']['f2']; ids=face['vertices']; u0,v0,u1,v1=r
        face['uv']=dict(zip(ids,[(u0,v0),(u0,v1),(u1,v1),(u1,v0)]))
        for i in range(3): self.box('卡带顶纹'+str(i),-3.56,5.347,3.56,5.371,5.83+i*.10,5.865+i*.10,'底座',g)

    def controller(self,cx,port):
        g=f'0{8+port} P{port+1} 紫面板手柄'
        z=-11.3
        self.box('手柄底壳',cx-3.5,z-1.55,cx+3.5,z+1.55,.17,.60,'底座',g,inset=.12)
        self.box('手柄上壳',cx-3.44,z-1.49,cx+3.44,z+1.49,.61,.83,'浅键',g,inset=.08)
        self.atlas.tile('手柄面板'+str(port),'','#39334f',768,320)
        r=self.atlas.tiles['手柄面板'+str(port)]; d=self.atlas.draw; x,y,x1,y1=r
        d.rectangle((x+8,y+8,x1-8,y1-8),outline='#b2b29f',width=3)
        d.text((x+30,y+17),str(port+1),font=font(34,True),fill='#d4d4bb')
        d.text((x+295,y+74),'JOYSTICK',font=font(30,True),fill='#b2b6ad')
        d.text((x+550,y+24),'TURBO',font=font(26,True),fill='#c3c8b8')
        d.text((x+280,y+191),'SELECT   START',font=font(17),fill='#cccbb8')
        d.text((x+555,y+165),'B      A',font=font(21,True),fill='#d7d7be')
        self.box('紫色面板',cx-3.23,z-1.29,cx+3.23,z+1.29,.837,.853,'紫面板',g,top='手柄面板'+str(port))
        self.box('十字键横臂',cx-2.80,z-.25,cx-1.20,z+.25,.86,1.05,'灰按钮',g)
        self.box('十字键上臂',cx-2.25,z+.25,cx-1.75,z+.80,.86,1.05,'灰按钮',g)
        self.box('十字键下臂',cx-2.25,z-.80,cx-1.75,z-.25,.86,1.05,'灰按钮',g)
        for i in range(2): self.box('选择开始'+str(i),cx-.55+i*.82,z-.88,cx+.03+i*.82,z-.60,.86,1.015,'线材',g,inset=.045)
        for row in range(2):
            for col in range(2): self.disk(('连发' if row else 'AB')+str(col),cx+1.50+col*.84,z-.65+row*1.06,.32,.865,1.09,'灰按钮',g)
        self.box('出线护套',cx-.22,z+1.48,cx+.22,z+2.02,.36,.62,'线材',g)
        side=-1 if port==0 else 1
        control=np.array([[cx,.5,z+1.98],[cx+side*13,.27,z+5],[side*21,.28,3],[side*16.25,1.0,4.4]])
        points=[]
        for t in np.linspace(0,1,29): points.append((1-t)**3*control[0]+3*(1-t)**2*t*control[1]+3*(1-t)*t*t*control[2]+t**3*control[3])
        self.cable('独立手柄线',points,g)

    def document(self,groups=None):
        chosen=self.groups if groups is None else {k:v for k,v in self.groups.items() if k in groups}
        ids={i for row in chosen.values() for i in row}
        stream=io.BytesIO(); self.atlas.image.save(stream,format='PNG')
        return {'meta':{'format_version':'5.0','model_format':'free','box_uv':False},
                'name':'小霸王 SB-926 参考模型 v1','model_identifier':'subor_sb926_reference_v1',
                'visible_box':[4,2,0],'resolution':{'width':SIZE,'height':SIZE},
                'elements':[e for e in self.elements if e['uuid'] in ids],
                'outliner':[{'name':k,'uuid':uid(k),'origin':[0,0,0],'rotation':[0,0,0],
                             'export':True,'visibility':True,'children':v} for k,v in chosen.items()],
                'textures':[{'path':'','name':'小霸王_统一UV.png','id':'0','uuid':uid('texture'),
                             'width':SIZE,'height':SIZE,'uv_width':SIZE,'uv_height':SIZE,
                             'mode':'bitmap','saved':True,'internal':True,
                             'source':'data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode()}]}

def quads_from_document(doc):
    result=[]
    for i,e in enumerate(doc['elements']):
        for face in e['faces'].values():
            keys=face['vertices']
            for j in range(1,len(keys)-1):
                tri=[keys[0],keys[j],keys[j+1],keys[j+1]]
                result.append(Quad(np.array([e['vertices'][k] for k in tri]),
                                   np.array([face['uv'][k] for k in tri])*16/SIZE,'skin',i,'mesh'))
    return result

def validate(doc):
    ids=[e['uuid'] for e in doc['elements']]; assert len(ids)==len(set(ids))
    children=[i for g in doc['outliner'] for i in g['children']]; assert sorted(children)==sorted(ids)
    faces=triangles=0
    for e in doc['elements']:
        for face in e['faces'].values():
            keys=face['vertices']; assert len(set(keys))==len(keys) and len(keys)>=3
            vertices=np.array([e['vertices'][k] for k in keys]); assert np.isfinite(vertices).all()
            assert np.linalg.norm(np.cross(vertices[1]-vertices[0],vertices[2]-vertices[0]))>1e-9
            uv=np.array([face['uv'][k] for k in keys]); assert np.isfinite(uv).all() and uv.min()>=0 and uv.max()<=SIZE
            assert face['texture']==0; faces+=1; triangles+=len(keys)-2
    return {'elements':len(ids),'faces':faces,'triangles':triangles,'groups':[g['name'] for g in doc['outliner']]}

def validate_key_spacing(doc):
    keys=[e for e in doc['elements'] if e['name'].startswith('键帽')]
    for i,a in enumerate(keys):
        va=np.array(list(a['vertices'].values())); amin=va.min(axis=0); amax=va.max(axis=0)
        for b in keys[i+1:]:
            vb=np.array(list(b['vertices'].values())); bmin=vb.min(axis=0); bmax=vb.max(axis=0)
            overlap=np.minimum(amax,bmax)-np.maximum(amin,bmin)
            assert not (overlap[0]>.001 and overlap[2]>.001), f'Overlapping keycaps: {a["name"]}, {b["name"]}'
    return len(keys)

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    previous=OUT/'模型校验.json'
    if previous.exists():
        old=json.loads(previous.read_text(encoding='utf-8'))
        for name,expected in old['sha256'].items():
            candidate=OUT/name
            if candidate.exists() and hashlib.sha256(candidate.read_bytes()).hexdigest()!=expected:
                raise RuntimeError(f'User-edited output preserved; choose a new output directory: {candidate}')
    model=Model().build(); doc=model.document()
    raw=json.dumps(doc,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    target=OUT/'小霸王SB926_完整套装.bbmodel'; target.write_bytes(raw)
    single=model.document([k for k in model.groups if not k.startswith(('07','08','09'))])
    (OUT/'小霸王SB926_主机.bbmodel').write_text(json.dumps(single,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    model.atlas.image.save(OUT/'小霸王_统一UV.png')
    # Read delivered file back: all previews consume exported geometry and per-face UV.
    doc=json.loads(target.read_text(encoding='utf-8')); result=validate(doc); validate(single)
    assert validate_key_spacing(doc)==model.key_count
    texture=np.array(Image.open(io.BytesIO(base64.b64decode(doc['textures'][0]['source'].split(',',1)[1]))))
    views=[('完整套装',doc,(.85,1.25,1.75),(1440,820)),
           ('主机俯视',single,(0,3.5,.75),(1440,740)),
           ('主机后侧',single,(-1,1.1,-1.6),(1440,650))]
    report=[]
    for title,data,direction,size in views:
        image,info=render_view(quads_from_document(data),{'skin':texture},direction,size=size,supersample=2)
        canvas=Image.new('RGB',(size[0],size[1]+94),'#e9e9e2'); canvas.paste(image,(0,72),image)
        d=ImageDraw.Draw(canvas); d.text((30,16),'小霸王 SB-926 · '+title,font=font(30,True),fill='#424941')
        d.text((30,size[1]+72),'实际 BBMODEL 网格与 UV 离线渲染 · 不是游戏截图 · 独立模型草案',font=font(16),fill='#72796e')
        canvas.save(OUT/(title+'预览.png')); report.append(info)
    result.update({'keyboard_keys':model.key_count,'atlas':[SIZE,SIZE],'reference':'用户提供第4/5张SB-926，非精密扫描复刻',
                   'body_dimensions_model_units':[32,2.3,15.2],'not_game_integrated':True,'preview_views':report,
                   'sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.suffix in ['.png','.bbmodel']}})
    (OUT/'模型校验.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=True))

if __name__=='__main__': main()
