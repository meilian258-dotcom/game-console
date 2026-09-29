"""Native CRT scanline remote. Existing solid textures, actual vanilla item transforms."""
from __future__ import annotations
import argparse,io,json,math,zipfile
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from import_subor_hardware import ASSETS,CATEGORY,PROJECT,encoded,sha,write_new
from build_lcd_tv_model import cube,TEXTURE_HASHES
from render_rocket_arcade_preview import Quad,collect_quads,render_view,raster_triangle
from check_controller_pose_pipeline import display_matrix,translation,rotation,points,projected_bounds
from check_cartridge_computer_model import exposed_overlaps

MODEL=ASSETS/'models/item/tv_remote.json'
OUT=CATEGORY/'电视扫描线遥控器-alpha13'/'原生模型-v1'
COLORS=('screen','dark','rim','back','metal','white','red')
SOURCES=PROJECT/'build/moddev/artifacts/neoforge-21.1.236-sources.jar'

def displays():
    return {'gui':{'rotation':[85,18,0],'scale':[1.06]*3},
            'ground':{'translation':[0,1,0],'scale':[.5]*3},
            'fixed':{'rotation':[90,0,0],'scale':[.85]*3},
            'firstperson_righthand':{'rotation':[55,15,0],'translation':[-2,4.6,-1],'scale':[.42]*3},
            'firstperson_lefthand':{'rotation':[55,15,0],'translation':[-2,4.6,-1],'scale':[.42]*3},
            'thirdperson_righthand':{'rotation':[75,0,0],'translation':[0,2,0],'scale':[.6]*3},
            'thirdperson_lefthand':{'rotation':[75,0,0],'translation':[0,2,0],'scale':[.6]*3}}

def build():
    textures={c:'piq_fc_arcade:block/home_retro_tv_'+c for c in COLORS};textures['particle']=textures['dark'];elements=[]
    def add(name,low,high,color,faces=None):elements.append(cube(name,low,high,color,faces))
    # Three non-overlapping strips give stepped rounded corners, not coincident cubes.
    def rounded(name,x0,x1,z0,z1,y0,y1,corner,color):
        add(name+'中段',(x0,y0,z0+corner),(x1,y1,z1-corner),color)
        add(name+'头圆角',(x0+corner,y0,z0),(x1-corner,y1,z0+corner),color)
        add(name+'尾圆角',(x0+corner,y0,z1-corner),(x1-corner,y1,z1),color)
    rounded('下壳',5.8,10.2,2.2,13.8,7.30,7.66,.35,'dark')
    rounded('壳体接缝',5.7,10.3,2.1,13.9,7.66,7.76,.35,'rim')
    rounded('上壳',5.77,10.23,2.17,13.83,7.76,8.30,.35,'back')
    # Narrow infrared window at the nose. The red key is intentionally unlabeled:
    # this mod toggles scanlines, not television power, channels or volume.
    add('前端红外窗',(7.05,7.96,2.145),(8.95,8.18,2.18),'screen',('north',))
    rounded('红色小键',6.4,7.24,2.96,3.76,8.31,8.67,.12,'red')
    add('顶端素色小标',(8.7,8.308,3.21),(9.45,8.316,3.37),'metal',('up',))
    rounded('扫描线主键',6.48,9.52,4.48,5.70,8.31,8.65,.16,'white')
    for z in (4.72,5.05,5.38):add('三行扫描线图标',(7.05,8.654,z),(8.95,8.664,z+.10),'dark',('up',))
    for row,z in enumerate((6.70,8.10,9.50)):
        for col,x in enumerate((6.40,7.63,8.86)):
            rounded('装饰浅灰键'+str(row)+str(col),x,x+.75,z,z+.69,8.31,8.61,.10,'metal')
    rounded('尾部浅灰横键',6.62,9.38,11.35,12.05,8.31,8.61,.12,'metal')
    # Back battery cover sits on top of the bottom shell, never coplanar with it.
    rounded('电池盖边沿',6.47,9.53,7.15,12.7,7.27,7.30,.18,'rim')
    rounded('电池盖',6.62,9.38,7.32,12.52,7.25,7.27,.14,'dark')
    for z in (11.4,11.68,11.96):add('电池盖开启指纹槽',(7.35,7.243,z),(8.65,7.249,z+.10),'rim',('down',))
    return {'credit':'PIQ native television scanline remote; existing CRT solid PNG textures unchanged.',
            'gui_light':'front','ambientocclusion':False,'textures':textures,'display':displays(),'elements':elements}

def load_textures(model):
    textures={};hashes={}
    for c in COLORS:
        raw=(ASSETS/('textures/block/home_retro_tv_'+c+'.png')).read_bytes()
        if sha(raw)!=TEXTURE_HASHES[c]:raise ValueError('Frozen old texture changed: '+c)
        a=np.array(Image.open(io.BytesIO(raw)).convert('RGBA'))
        if len(np.unique(a.reshape(-1,4),axis=0))!=1:raise ValueError('Existing palette must be solid')
        textures[model['textures'][c]]=a;hashes[c]=sha(raw)
    return textures,hashes

def verify_vanilla_sources():
    with zipfile.ZipFile(SOURCES) as z:
        hand=z.read('net/minecraft/client/renderer/ItemInHandRenderer.java').decode()
        layer=z.read('net/minecraft/client/renderer/entity/layers/ItemInHandLayer.java').decode()
        item=z.read('net/minecraft/client/renderer/entity/ItemRenderer.java').decode()
        humanoid=z.read('net/minecraft/client/model/HumanoidModel.java').decode()
    for snippet in ('(float)i * 0.56F, -0.52F + p_109385_ * -0.6F, -0.72F','float f5 = -0.4F * Mth.sin(Mth.sqrt(p_109376_) * (float) Math.PI)',
                    'float f6 = 0.2F * Mth.sin(Mth.sqrt(p_109376_) * (float) (Math.PI * 2))','float f10 = -0.2F * Mth.sin(p_109376_ * (float) Math.PI)',
                    '45.0F + f * -20.0F','(float)i * f1 * -20.0F','f1 * -80.0F','(float)i * -45.0F'):
        if snippet not in hand:raise ValueError('Vanilla first-person chain changed: '+snippet)
    for snippet in ('rotationDegrees(-90.0F)','rotationDegrees(180.0F)',' / 16.0F, 0.125F, -0.625F'):
        if snippet not in layer:raise ValueError('Vanilla third-person chain changed')
    if 'this.rightArm.xRot * 0.5F - (float) (Math.PI / 10)' not in humanoid:raise ValueError('Vanilla held arm changed')
    if item.index('handleCameraTransforms')>item.index('translate(-0.5F, -0.5F, -0.5F)'):raise ValueError('Item transform order changed')
    return {'ItemInHandRenderer.java':sha(hand.encode()),'ItemInHandLayer.java':sha(layer.encode()),'ItemRenderer.java':sha(item.encode()),'HumanoidModel.java':sha(humanoid.encode())}

def first_matrix(model,right=True,swing=0):
    side=1 if right else -1;w=math.sin(math.sqrt(swing)*math.pi);q=math.sin(swing*swing*math.pi)
    attack=rotation('y',side*(45-20*q))@rotation('z',side*w*-20)@rotation('x',w*-80)@rotation('y',side*-45)
    travel=translation(side*-.4*w,.2*math.sin(math.sqrt(swing)*math.pi*2),-.2*math.sin(swing*math.pi))
    d=model['display']['firstperson_righthand' if right else 'firstperson_lefthand']
    return travel@translation(side*.56,-.52,-.72)@attack@display_matrix(d,not right)@translation(-.5,-.5,-.5)

def third_matrix(model,right=True,slim=False):
    side=1 if right else -1
    # Normal ITEM arm pose at rest. Model-space +Y is down; conversion to world +Y
    # for the preview happens afterwards. No custom controller pose is used here.
    arm=translation(-side*(4.5 if slim else 5)/16,2/16,0)@rotation('z',side*math.degrees(.1))@rotation('x',-18)
    d=model['display']['thirdperson_righthand' if right else 'thirdperson_lefthand']
    return arm@rotation('x',-90)@rotation('y',180)@translation(side/16,.125,-.625)@display_matrix(d,not right)@translation(-.5,-.5,-.5)

def audit(model):
    tex,hashes=load_textures(model);source_hashes=verify_vanilla_sources();quads=collect_quads(model);v=np.concatenate([q.vertices for q in quads]);checks=[]
    def check(name,condition):checks.append({'name':name,'ok':bool(condition)})
    check('native complete remote dimensions are exact',np.allclose([v.min(0),v.max(0)],[[5.7,7.243,2.1],[10.3,8.67,13.9]],atol=1e-10))
    check('remote is compact and remains within normal Java model limits',np.all(v>=0) and np.all(v<=16))
    overlap=exposed_overlaps(quads);check('no exposed six-axis coplanar face overlap',not overlap)
    gui=display_matrix(model['display']['gui'],False)@translation(-.5,-.5,-.5)
    gp=points(v/16,gui)[:,:2]*[16,-16]+8
    check('true vanilla GUI retains whole remote without auto refitting',gp.min()>=0 and gp.max()<=16)
    cases=[]
    for right in (True,False):
        for swing in (0,.05,.125,.25,.5,.75,1):
            m=first_matrix(model,right,swing);fv=points(v/16,m)
            for fov in (60,70):
                for aspect in (4/3,16/9):
                    ndc,b=projected_bounds(fv,fov,aspect)
                    cases.append({'right':right,'swing':swing,'fov':fov,'aspect':aspect,'bounds':b,'inside':bool(abs(ndc).max()<=1)})
    check('idle first-person remote remains inside both FOVs and screen ratios',all(c['inside'] for c in cases if c['swing']==0))
    check('56 ordinary click swing cases stay clear of camera near plane',all(np.isfinite(c['bounds']).all() for c in cases))
    idle=[]
    for right in (True,False):
        m=first_matrix(model,right);center=points([[.5,.5,.5]],m)[0];n=m[:3,:3]@[0,1,0]
        cosine=float(n@(-center)/np.linalg.norm(n)/np.linalg.norm(center));_,b=projected_bounds(points(v/16,m),70,16/9)
        head=points([[8/16,8.3/16,2.1/16]],m)[0];tail=points([[8/16,8.3/16,13.9/16]],m)[0]
        check(('right' if right else 'left')+' first-person buttons face the eyes and tip points away',cosine>.7 and head[2]<tail[2] and head[1]>tail[1])
        check(('right' if right else 'left')+' idle remote stays below central gameplay area',b[1]>.57 and b[3]<.98)
        idle.append({'right':right,'button_facing_cosine':cosine,'bounds':b})
    third=[]
    for right in (True,False):
        for slim in (False,True):
            m=third_matrix(model,right,slim);n=m[:3,:3]@[0,1,0];tv=points(v/16,m)
            check(('right' if right else 'left')+(' slim' if slim else ' standard')+' third-person button face tilts upward',n[1]<-.45)
            third.append({'right':right,'slim':slim,'button_normal':n.tolist(),'bounds':[tv.min(0).tolist(),tv.max(0).tolist()]})
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'model_sha256':sha(encoded(model)),'elements':len(model['elements']),'faces':len(quads),
        'bounds':[v.min(0).tolist(),v.max(0).tolist()],'gui_pixel_bounds':[gp.min(0).tolist(),gp.max(0).tolist()],
        'texture_sha256':hashes,'vanilla_transform_source_sha256':source_hashes,'exposed_coplanar_overlaps':overlap,
        'first_person_cases':cases,'idle_first_person':idle,'third_person_cases':third,
        'limitations':['Actual JSON geometry and reviewed local MC1.21.1 source transforms; offline preview, not Minecraft execution.',
        'Decorative keys are unlabeled; only scanline switching is implemented by the separate item logic.',
        'Ordinary vanilla swing can briefly take part of the remote beyond the viewport; only idle framing is guaranteed.',
        'Standard idle/swing and slim/normal items are checked; third-party arm pose mods are not simulated.']},tex,gui

def render_gui(quads,tex,matrix,size=480):
    target=np.zeros((size,size,4),dtype=np.uint8);target[:]=(218,222,226,255);depth=np.full((size,size),-np.inf)
    for q in quads:
        p=points(q.vertices/16,matrix);p[:,:2]=p[:,:2]*[size,-size]+size/2;p[:,2]*=size
        for ix in ((0,1,2),(0,2,3)):raster_triangle(target,depth,p[list(ix)],q.uv[list(ix)],tex[q.texture])
    return Image.fromarray(target)

def preview(model,tex,gui):
    quads=collect_quads(model);canvas=Image.new('RGB',(1420,1370),'#e9e6df');d=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',27);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    d.text((24,16),'电视扫描线遥控器 · 原生 3D 物品 / 旧纯色纹理',font=title,fill='#282c33')
    views=(('按键面：三行扫描线图标',(0,1,.001)),('侧前：圆角阶梯壳与真实按键高度',(.75,.75,-1.05)),('背面：电池盖与开启纹路',(0,-1,.001)))
    for i,(label,view) in enumerate(views):
        x=20+i%2*710;y=78+i//2*620;pic,_=render_view(quads,tex,view,size=(660,550),supersample=2);canvas.paste(pic,(x,y),pic);d.text((x+5,y+553),label,font=font,fill='#363b43')
    canvas.paste(render_gui(quads,tex,gui),(830,720));d.rectangle((830,720,1310,1200),outline='#9c9b96',width=2)
    d.text((760,1250),'物品栏：实际变换矩阵，未自动放大重排',font=font,fill='#363b43')
    d.text((24,1320),'直接读取模型顶点/原纹理；遥控器只用于扫描线开关，其他无文字按键为装饰；非游戏截图。',font=font,fill='#555b63')
    result={}
    for ext in ('png','jpg'):
        out=io.BytesIO();canvas.save(out,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':92}));result[OUT/('扫描线遥控器_实际模型四视图.'+ext)]=out.getvalue()
    return result

def held_preview(model,tex):
    base=collect_quads(model);sheet=Image.new('RGB',(1500,1160),'#e6e4de');d=ImageDraw.Draw(sheet)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',27);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    d.text((22,15),'遥控器握持 QA · 原版变换链 / 未自动缩放物品',font=title,fill='#303640')
    for i,right in enumerate((True,False)):
        width,height=704,396;canvas=np.zeros((height,width,4),dtype=np.uint8);canvas[:]=(105,124,136,255);depth=np.full((height,width),-np.inf);m=first_matrix(model,right)
        for q in base:
            v=points(q.vertices/16,m);n=np.cross(v[1]-v[0],v[2]-v[0])
            if n@(-v.mean(0))<=0:continue
            denominator=-v[:,2]*math.tan(math.radians(35));p=np.column_stack(((.5+v[:,0]/(denominator*16/9)/2)*width,(.5-v[:,1]/denominator/2)*height,v[:,2]))
            for ix in ((0,1,2),(0,2,3)):raster_triangle(canvas,depth,p[list(ix)],q.uv[list(ix)],tex[q.texture])
        pic=Image.fromarray(canvas);pd=ImageDraw.Draw(pic);pd.line((width/2-8,height/2,width/2+8,height/2),fill='#bdc9cf',width=1);pd.line((width/2,height/2-8,width/2,height/2+8),fill='#bdc9cf',width=1)
        x=24+i*746;sheet.paste(pic,(x,90));d.text((x,60),('第一人称 · 右手' if right else '第一人称 · 左手')+' / 70°，16:9',font=font,fill='#303640')
    for i,right in enumerate((True,False)):
        side=1 if right else -1;slim=not right
        arm=translation(-side*(4.5 if slim else 5)/16,2/16,0)@rotation('z',side*math.degrees(.1))@rotation('x',-18)
        m=third_matrix(model,right,slim);scene=[Quad(points(q.vertices/16,m)*[16,-16,-16],q.uv,q.texture,q.element_index,q.direction) for q in base]
        low=(-2 if slim else -3) if right else -1;arm_model={'textures':{'skin':'qa:skin'},'elements':[cube('原版手臂体积参照',(low,-2,-2),(low+(3 if slim else 4),10,2),'skin')]}
        for q in collect_quads(arm_model):scene.append(Quad(points(q.vertices/16,arm)*[16,-16,-16],q.uv,q.texture,q.element_index,q.direction))
        pic,_=render_view(scene,{**tex,'qa:skin':np.array([[[188,160,132,255]]],dtype=np.uint8)},(.9 if right else -.9,.5,1.5),size=(700,500),supersample=2)
        x=24+i*746;sheet.paste(pic,(x,585),pic);d.text((x,550),'第三人称 · '+('标准右臂' if right else '纤细左臂')+'，原版手臂体积参照',font=font,fill='#303640')
    d.text((22,1108),'离线真实 JSON 与本地 Minecraft 原版变换；手臂为尺寸参照，不是玩家皮肤或游戏截图。',font=font,fill='#555d67')
    result={}
    for ext in ('png','jpg'):
        out=io.BytesIO();sheet.save(out,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':92}));result[OUT/('扫描线遥控器_左右手握持QA.'+ext)]=out.getvalue()
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');p.add_argument('--check-only',action='store_true');a=p.parse_args()
    model=build();report,tex,gui=audit(model);raw=encoded(model)
    if a.check_only and MODEL.read_bytes()!=raw:raise ValueError('Installed remote differs from generator')
    if a.write:
        if not report['ok']:raise ValueError('Remote geometry/pose validation failed: '+json.dumps([c for c in report['checks'] if not c['ok']]))
        outputs={MODEL:raw,**preview(model,tex,gui),**held_preview(model,tex)};report['output_sha256']={str(k):sha(v) for k,v in outputs.items()};outputs[OUT/'tv-remote-model-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
if __name__=='__main__':main()
