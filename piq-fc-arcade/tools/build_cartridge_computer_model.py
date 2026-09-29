"""Native one-block beige CRT workstation. Existing textures only; never paints PNGs."""
from __future__ import annotations
import argparse,io,json,zipfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from import_subor_hardware import ASSETS,CATEGORY,encoded,sha,write_new
from build_lcd_tv_model import cube,TEXTURE_HASHES
from render_rocket_arcade_preview import collect_quads,render_view,raster_triangle
from check_controller_pose_pipeline import display_matrix,translation,points

MODEL=ASSETS/'models/block/cartridge_computer.json'
ITEM=ASSETS/'models/item/cartridge_computer.json'
STATE=ASSETS/'blockstates/cartridge_computer.json'
OUT=CATEGORY/'老式卡带电脑-alpha12'/'接缝校正-v2'
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar')
SCREEN=(3.05,5.55,11.95,12.225,6.15)
TEXTURES=('screen','dark','rim','back','metal','red','white','yellow')

def build():
    tex={n:'piq_fc_arcade:block/home_retro_tv_'+n for n in TEXTURES}
    tex.update(particle=tex['white'],terminal='minecraft:block/lime_concrete')
    e=[]
    def add(name,a,b,t='white',faces=None,uv=None):
        c=cube(name,a,b,t,faces)
        if uv:
            for f in c['faces'].values():f['uv']=uv
        e.append(c)
    def ring(name,x0,y0,x1,y1,z0,z1,w,t):
        add(name+'-左',(x1,y0-w,z0),(x1+w,y1+w,z1),t)
        add(name+'-右',(x0-w,y0-w,z0),(x0,y1+w,z1),t)
        add(name+'-下',(x0,y0-w,z0),(x1,y0,z1),t)
        add(name+'-上',(x0,y1,z0),(x1,y1+w,z1),t)
    # The horizontal chassis and four real table feet are not a floating slab.
    for x in (1.4,13.9):
        for z in (5.4,13.5):add('机箱脚垫',(x,0,z),(x+.7,.35,z+.7),'rim')
    add('横卧机箱底边',(1,.3,5),(15,.5,14.6),'metal')
    add('横卧机箱后实体',(1,.5,5.6),(14.985,3.15,14.585))
    add('机箱上盖厚边',(1,3.15,5),(15,3.35,14.6))
    # Two actual recessed openings. Front fascia is partitioned, never an overlay.
    holes=((2.2,1.28,5.8,2.62),(8.2,1.3,12.6,2.55))
    xs=sorted({1.,15.,*(r[i] for r in holes for i in (0,2))})
    ys=sorted({.5,3.15,*(r[i] for r in holes for i in (1,3))})
    for xa,xb in zip(xs,xs[1:]):
        for ya,yb in zip(ys,ys[1:]):
            x,y=(xa+xb)/2,(ya+yb)/2
            if not any(l<x<r and b<y<t for l,b,r,t in holes):add('机箱前面板分区',(xa,ya,5.02),(xb,yb,5.6))
    add('写卡槽深色内壁',(2.2,1.28,5.2),(5.8,2.62,5.59),'dark')
    # A thin-slot face inside the larger socket leaves a clearly recessed empty mouth.
    add('写卡槽下导轨',(2.35,1.38,5.09),(5.65,1.59,5.2),'metal')
    add('写卡槽上导轨',(2.35,2.18,5.09),(5.65,2.48,5.2),'metal')
    add('写卡槽空槽黑底',(2.35,1.59,5.08),(5.65,2.18,5.1),'screen',('north',))
    for n in range(8):add('写卡槽接点',(2.55+n*.37,1.64,5.066),(2.72+n*.37,1.75,5.075),'yellow',('north',))
    add('磁盘驱动器内框',(8.2,1.3,5.08),(12.6,2.55,5.59),'metal')
    add('磁盘狭缝',(8.5,1.94,5.064),(12.3,2.12,5.07),'screen',('north',))
    add('磁盘弹出键',(8.6,1.46,5.01),(9.4,1.77,5.064),'dark')
    add('机箱方电源键',(13.55,1.35,4.995),(14.45,2.25,5.0),'rim',('north',))
    # Keep tiny face details inside the exact collision envelope (front remains z >= 5).
    e[-1]['from'][2]=5.;e[-1]['to'][2]=5.012
    for n in range(8):add('机箱侧面散热孔',(14.99,1.02,7+n*.69),(15,2.54,7.16+n*.69),'dark',('east',))
    for n in range(13):add('机箱背部散热孔',(2+n*.72,1.1,14.595),(2.36+n*.72,2.6,14.6),'dark',('south',))
    # CRT stand, thick box and smaller rear tube enclosure.
    add('CRT底座压边',(4.45,3.35,7.15),(10.55,3.6,12.5),'metal')
    add('CRT支座',(5.15,3.6,7.75),(9.85,4.1,11.8))
    add('CRT左侧厚壳',(12.7,4.1,5.25),(13.2,13.5,8.6))
    add('CRT右侧厚壳',(1.8,4.1,5.25),(2.3,13.5,8.6))
    add('CRT上壳',(2.3,13.05,5.25),(12.7,13.5,8.6))
    add('CRT下壳',(2.3,4.1,5.25),(12.7,4.55,8.6))
    # Face surrounds the open recess; no face is stretched over the glass.
    add('CRT左前框',(12.1,4.55,5.25),(12.7,13.05,8.6))
    add('CRT右前框',(2.3,4.55,5.25),(2.9,13.05,8.6))
    add('CRT上前框',(2.9,12.375,5.25),(12.1,13.05,8.6))
    add('CRT下前框',(2.9,4.55,5.265),(12.1,5.4,8.6))
    ring('内凹屏深色内沿',3.05,5.55,11.95,12.225,5.25,6.15,.15,'metal')
    add('4比3内凹黑玻璃',(3.05,5.55,6.15),(11.95,12.225,6.25),'screen',('north',))
    add('CRT后内板',(2.9,5.4,6.25),(12.1,12.375,8.6))
    add('CRT显像管深后壳',(2.35,4.55,8.6),(12.65,13.05,13.7))
    add('CRT后盖包边',(2.7,4.85,13.7),(12.3,12.75,14.36),'metal')
    add('CRT后盖中心',(3.1,5.2,14.36),(11.9,12.4,14.395))
    for z in (9.2,9.65,10.1,10.55,11,11.45,11.9,12.35):
        add('CRT左侧散热缝',(12.645,6.1,z),(12.655,11.9,z+.17),'dark',('east',))
        add('CRT右侧散热缝',(2.345,6.1,z),(2.355,11.9,z+.17),'dark',('west',))
    for n in range(10):add('CRT背散热缝',(3.5+n*.72,6.1,14.395),(3.7+n*.72,11.5,14.4),'dark',('south',))
    add('CRT小电源按键',(3.05,4.71,5.25),(3.56,5.12,5.265),'metal')
    add('CRT电源指示灯',(3.77,4.84,5.25),(3.94,4.98,5.26),'terminal',('north',),[7.5,7.5,8.5,8.5])
    for n in range(3):add('CRT品牌短块',(10.62+n*.29,4.83,5.25),(10.82+n*.29,5.03,5.26),'dark',('north',))
    # Static 3x5 letter shapes: no ROM name, progress, success, percentage or animation.
    # Reversed X coordinate renders the text left-to-right when viewed from north.
    glyphs={'P':['110','101','110','100','100'],'I':['111','010','010','010','111'],'Q':['111','101','101','111','001'],
            'C':['111','100','100','100','111'],'A':['010','101','111','101','101'],'R':['110','101','110','101','101'],
            'D':['110','101','101','101','110'],'>':['100','010','001','010','100'],'_':['000','000','000','000','111']}
    def text_row(label,top,size):
        x=10.95
        for ch in label:
            if ch==' ':x-=size*4;continue
            for row,pattern in enumerate(glyphs[ch]):
                for col,p in enumerate(pattern):
                    if p=='1':
                        right=x-col*size;high=top-row*size
                        add('静态终端字符-'+ch,(right-size*.82,high-size*.82,6.126),(right,high,6.132),'terminal',('north',),[7.5,7.5,8.5,8.5])
            x-=size*4
    text_row('PIQ',11.35,.17);text_row('CARD',10.15,.15);text_row('> _',8.7,.17)
    # Raised keyboard keys, physically separated; upper rows slightly higher.
    add('键盘底边',(3.2,.25,.6),(14.7,.55,4.6),'metal')
    add('键盘奶油壳',(3.2,.55,.6),(14.7,.91,4.6))
    add('键盘键帽凹板',(3.45,.912,.88),(14.45,.95,4.35),'dark',('up',))
    def key(name,x,z,w=.57,d=.49,top=1.35,color='white',mark=True):
        add(name,(x,.95,z),(x+w,top,z+d),color)
        if mark:add(name+'刻字短笔',(x+w*.36,top+.003,z+d*.51),(x+w*.61,top+.008,z+d*.64),'dark',('up',))
    for row in range(3):
        for col in range(12):key('字母键',6.6+col*.65,1.77+row*.65,top=1.3+row*.07)
    for col in range(12):key('功能键',6.6+col*.65,3.8,.57,.36,1.54,'metal',False)
    key('空格键',9.08,1.08,3.8,.46,1.22,mark=False)
    for x in (6.6,7.43,8.26,13.03,13.86):key('控制键',x,1.08,.58,.46,1.22,'metal')
    for row in range(4):
        for col in range(3):key('数字区键',3.55+col*.84,1.12+row*.7,.64,.54,1.3+row*.07,'white' if row<3 else 'metal')
    # Ball mouse on the viewer's right (low X), with two separated top buttons.
    add('鼠标下壳',(.75,.25,1.2),(2.55,.55,4.1),'metal')
    add('鼠标圆角中壳',(.65,.55,1.45),(2.65,.85,3.85))
    add('鼠标背部掌托',(.84,.85,1.65),(2.46,1.05,2.74))
    add('鼠标右键',(.84,.85,2.78),(1.60,1.25,3.68))
    add('鼠标左键',(1.7,.85,2.78),(2.46,1.25,3.68))
    add('键盘短连接线',(9.1,.3,4.6),(9.3,.55,5),'dark')
    add('鼠标短连接线',(1.5,.3,4.1),(1.7,.55,5),'dark')
    return {'credit':'PIQ original native cartridge-writing desktop; existing CRT solids and vanilla lime concrete only.',
            'ambientocclusion':True,'textures':tex,'elements':e}

def item_model():
    return {'parent':'piq_fc_arcade:block/cartridge_computer','display':{
        'gui':{'rotation':[25,135,0],'translation':[0,.9,0],'scale':[.65,.65,.65]},
        'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},
        'fixed':{'rotation':[0,180,0],'scale':[.7,.7,.7]},
        'thirdperson_righthand':{'rotation':[75,45,0],'scale':[.4,.4,.4]},
        'thirdperson_lefthand':{'rotation':[75,45,0],'scale':[.4,.4,.4]},
        'firstperson_righthand':{'rotation':[0,135,0],'scale':[.5,.5,.5]},
        'firstperson_lefthand':{'rotation':[0,135,0],'scale':[.5,.5,.5]}}}

def blockstate():
    return {'variants':{'facing='+f:dict(model='piq_fc_arcade:block/cartridge_computer',**({'y':i*90} if i else {})) for i,f in enumerate(('north','east','south','west'))}}

def textures():
    result={};hashes={}
    for n in TEXTURES:
        raw=(ASSETS/('textures/block/home_retro_tv_'+n+'.png')).read_bytes()
        if sha(raw)!=TEXTURE_HASHES[n]:raise ValueError('Original CRT texture changed: '+n)
        result['piq_fc_arcade:block/home_retro_tv_'+n]=np.array(Image.open(io.BytesIO(raw)).convert('RGBA'));hashes[n]=sha(raw)
    with zipfile.ZipFile(MC) as z:raw=z.read('assets/minecraft/textures/block/lime_concrete.png')
    result['minecraft:block/lime_concrete']=np.array(Image.open(io.BytesIO(raw)).convert('RGBA'));hashes['vanilla_lime_concrete']=sha(raw)
    return result,hashes

def audit(model):
    tex,hashes=textures();q=collect_quads(model);v=np.concatenate([a.vertices for a in q]);lo=v.min(0);hi=v.max(0)
    if not np.allclose([lo,hi],[[.65,0,.6],[15,13.5,14.6]]):raise ValueError('Workstation bounds changed: '+str([lo,hi]))
    if np.any(lo<0) or np.any(hi>16):raise ValueError('Workstation outside one cell')
    glass=next(a for a in q if model['elements'][a.element_index]['name']=='4比3内凹黑玻璃')
    if not np.allclose(glass.vertices[:,2],6.15):raise ValueError('Glass must be inset')
    if abs((SCREEN[2]-SCREEN[0])/(SCREEN[3]-SCREEN[1])-4/3)>1e-12:raise ValueError('CRT not 4:3')
    item=item_model();matrix=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)
    pixel=points(v/16,matrix)[:,:2]*[16,-16]+8
    if pixel.min()<0 or pixel.max()>16:raise ValueError('GUI item cropped')
    return {'ok':True,'elements':len(model['elements']),'faces':len(q),'bounds':[lo.tolist(),hi.tolist()],
            'glass_quad':glass.vertices.tolist(),'glass_aspect':4/3,'front_recess':.9,'gui_pixel_bounds':[pixel.min(0).tolist(),pixel.max(0).tolist()],
            'texture_sha256':hashes,'notes':['Plain baked Java block model; no new PNG/BER or game ROM.',
            'Static terminal says PIQ / CARD / prompt only; not a write status or progress indicator.',
            'Offline actual vertices, UV and vanilla GUI transform, not Minecraft execution.']},tex,matrix

def preview(model,tex,matrix):
    q=collect_quads(model);sheet=Image.new('RGB',(1640,1450),'#e5e0d4');d=ImageDraw.Draw(sheet)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',28);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    d.text((25,18),'老式卡带电脑 · 米黄色 CRT / 横卧机箱 / 键盘鼠标',font=title,fill='#302e29')
    for i,(label,view) in enumerate((('正面：空写卡槽，鼠标在右侧',(0,.34,-1)),('三分之四：内凹屏与厚 CRT 壳',(1.2,.70,-1.8)),('后侧：显像管后壳与散热格栅',(-1.2,.60,1.8)))):
        x=20+(i%2)*820;y=82+(i//2)*640
        pic,_=render_view(q,tex,view,size=(780,580),supersample=2)
        sheet.paste(pic,(x,y),pic);d.text((x+8,y+570),label,font=font,fill='#423f36')
    # Exact 16x16 GUI coordinate transform, without refitting its projected bounds.
    target=np.zeros((512,512,4),dtype=np.uint8);target[:]=(73,69,63,255);depth=np.full((512,512),-np.inf)
    for face in q:
        p=points(face.vertices/16,matrix);p[:,:2]=p[:,:2]*[512,-512]+256;p[:,2]*=512
        for ix in ((0,1,2),(0,2,3)):raster_triangle(target,depth,p[list(ix)],face.uv[list(ix)],tex[face.texture])
    sheet.paste(Image.fromarray(target).convert('RGB'),(965,760));d.rectangle((965,760,1477,1272),outline='#776c59',width=2)
    d.text((900,1312),'物品栏：真实16像素变换，未重新适配',font=font,fill='#423f36')
    d.text((25,1390),'直接读取模型几何与原纹理离线渲染；终端为静态装饰，非真实写入状态；非游戏截图。',font=font,fill='#514b3e')
    output={}
    for ext in ('png','jpg'):
        b=io.BytesIO();sheet.save(b,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':92}));output[OUT/('老式电脑_实际模型四视图.'+ext)]=b.getvalue()
    return output

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');p.add_argument('--check-only',action='store_true');p.add_argument('--replace-model-sha256');a=p.parse_args()
    model=build();report,tex,matrix=audit(model);outputs={MODEL:encoded(model),ITEM:encoded(item_model()),STATE:encoded(blockstate())}
    report['resource_sha256']={str(path.relative_to(ASSETS)):sha(raw) for path,raw in outputs.items()}
    if a.check_only:
        for path,raw in outputs.items():
            if path.read_bytes()!=raw:raise ValueError('Installed resource differs: '+str(path))
    if a.write:
        outputs.update(preview(json.loads(outputs[MODEL]),tex,matrix));report['output_sha256']={str(p):sha(b) for p,b in outputs.items()}
        outputs[OUT/'cartridge-computer-model-audit.json']=encoded(report)
        if a.replace_model_sha256:
            old=MODEL.read_bytes()
            if sha(old)!=a.replace_model_sha256.upper():raise ValueError('Refusing to replace an unexpectedly edited model')
            write_new({OUT.parent/'初版-v1'/'cartridge_computer.json':old})
            replacement=outputs.pop(MODEL)
            write_new(outputs) # Validate every historical/preview destination before replacing our model.
            MODEL.write_bytes(replacement)
        else:write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))

if __name__=='__main__':main()
