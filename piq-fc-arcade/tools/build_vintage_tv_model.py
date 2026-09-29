"""Original code-native one-cell red knob CRT; reuses existing solid-color PNGs without editing them."""
import argparse,io,json,math
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_lcd_tv_model import cube,TEXTURE_HASHES
from import_subor_hardware import ASSETS,CATEGORY,sha,encoded,write_new
from render_rocket_arcade_preview import collect_quads,render_view

OUT=CATEGORY/'红棕旋钮老电视-alpha10'/'最终正面校正-v2'
MODEL=ASSETS/'models/block/home_vintage_tv.json'
SOCKETS=[(6.5+2*c,3.1,14.04) for c in range(3)]
def build():
    textures={k:'piq_fc_arcade:block/home_retro_tv_'+k for k in TEXTURE_HASHES};textures['particle']=textures['red'];e=[]
    def add(n,a,b,t,faces=None):e.append(cube(n,a,b,t,faces))
    def ring(n,x1,y1,x2,y2,z1,z2,w,t):
        add(n+'左',(x1-w,y1-w,z1),(x1,y2+w,z2),t)
        add(n+'右',(x2,y1-w,z1),(x2+w,y2+w,z2),t)
        add(n+'下',(x1,y1-w,z1),(x2,y1,z2),t)
        add(n+'上',(x1,y2,z1),(x2,y2+w,z2),t)
    def octagon(n,x,y,z,r,depth,t):
        # Union of a square and its 45-degree turn makes a regular octagon;
        # faces are not coplanar with nested front trim.
        a=r/(1+math.sqrt(2))
        add(n+'中心',(x-r,y-a,z),(x+r,y+a,z+depth),t)
        add(n+'中心纵',(x-a,y-r,z+.002),(x+a,y+r,z+depth-.002),t)
        for sx in (-1,1):
            for sy in (-1,1):
                q=cube(n+'斜角',(x-a,y-a,z+.003),(x+a,y+a,z+depth-.003),t)
                q['from'][0]+=sx*a;q['to'][0]+=sx*a;q['from'][1]+=sy*a;q['to'][1]+=sy*a
                q['rotation']={'origin':[x+sx*a,y+sy*a,z+depth/2],'axis':'z','angle':45,'rescale':False}
                e.append(q)
    add('红棕主壳',(0.5,.45,3.1),(15.5,12.2,13.45),'red')
    add('上盖宽边',(0.75,12.2,3.35),(15.25,12.55,13.2),'red')
    add('下壳收边',(.8,.22,3.3),(15.2,.45,13.1),'red')
    add('红色前左包边',(.25,.75,2.3),(.75,11.9,3.35),'red')
    add('红色前右包边',(15.25,.75,2.3),(15.75,11.9,3.35),'red')
    add('红色前上包边',(.75,11.9,2.3),(15.25,12.45,3.35),'red')
    add('红色前下包边',(.75,.3,2.3),(15.25,.75,3.35),'red')
    add('黑色前面板',(.75,.75,2.50),(15.25,11.9,3.1),'dark')
    ring('显像管外框',1.20,1.90,11.60,9.80,2.08,2.49,.35,'rim')
    ring('显像管内黑压条',1.40,2.10,11.40,9.60,2.02,2.27,.20,'screen')
    add('完整4比3黑屏',(1.40,2.10,2.28),(11.40,9.60,2.30),'screen',('north',))
    add('屏下细金属边',(1.15,1.42,2.07),(11.6,1.52,2.11),'metal')
    add('旋钮区分隔',(12.06,1.02,2.39),(12.18,11.55,2.5),'rim')
    # Large tuning dial and smaller volume dial, with raised grips and tick marks.
    for n,x,y,r in [('频道',13.62,9.64,1.04),('音量',13.62,6.89,.74)]:
        octagon(n+'刻度圈',x,y,2.10,r,.34,'metal')
        octagon(n+'黑旋钮',x,y,1.92,r*.78,.17,'dark')
        add(n+'手指凸柄',(x-.15,y-r*.54,1.82),(x+.15,y+r*.54,1.91),'rim')
        for k in range(8):
            ang=k*math.pi/4;tx=x+math.cos(ang)*r*.87;ty=y+math.sin(ang)*r*.87
            add(n+'白刻线',(tx-.035,ty-.075,2.075),(tx+.035,ty+.075,2.10),'white')
    for row in range(7):
        y=1.62+row*.51
        for col in range(3):
            x=12.50+col*.66
            add('扬声器孔',(x,y,2.36),(x+.46,y+.23,2.38),'screen',('north',))
            add('喇叭孔下缘',(x,y-.055,2.35),(x+.46,y,2.39),'rim')
    add('电源小键',(12.74,.97,2.16),(13.11,1.33,2.38),'rim')
    add('耳机小孔',(14.04,1.00,2.34),(14.38,1.34,2.38),'screen',('north',))
    for x in (2.0,12.4):add('短脚',(x,0,4.1),(x+1.6,.22,11.5),'dark')
    add('后盖',(1,1,13.45),(15,11.8,13.82),'dark')
    # RCA recesses avoid a solid wall across cable center-lines.
    cuts=[(x-.36,y-.36,x+.36,y+.36) for x,y,z in SOCKETS]
    xs=sorted({1.,15.,*(v for r in cuts for v in (r[0],r[2]))});ys=sorted({1.,11.8,*(v for r in cuts for v in (r[1],r[3]))})
    for a,b in zip(xs,xs[1:]):
        for c,d in zip(ys,ys[1:]):
            if not any(l<(a+b)/2<r and lo<(c+d)/2<hi for l,lo,r,hi in cuts):add('后盖开孔',(a,c,13.82),(b,d,14),'dark')
    for (x,y,z),color in zip(SOCKETS,('yellow','white','red')):ring(color+'AV接口',x-.20,y-.20,x+.20,y+.20,13.83,z,.16,color)
    for x in np.arange(2.,14.3,.7):add('后部散热槽',(float(x),6,14.001),(float(x+.24),10.75,14.015),'screen',('south',))
    add('提手左支点',(4.0,12.55,7.0),(4.45,13.9,7.65),'dark')
    add('提手右支点',(11.55,12.55,7.0),(12,13.9,7.65),'dark')
    add('顶部提手',(4.45,13.65,7.0),(11.55,14.15,7.65),'dark')
    add('收起的拉杆天线',(3.0,12.62,9.65),(13.2,12.78,9.85),'metal')
    add('天线转轴',(2.5,12.55,9.5),(3.1,12.97,10.05),'dark')
    # Viewed from north, world +X is viewer-left. Mirror the draft so both knobs
    # sit to the viewer's right, as on the reference. Existing texture files stay unchanged.
    for part in e:
        low,high=part['from'][0],part['to'][0]
        part['from'][0],part['to'][0]=16-high,16-low
        faces=part['faces']
        east,west=faces.pop('east',None),faces.pop('west',None)
        if east is not None:faces['west']=east
        if west is not None:faces['east']=west
        if 'rotation' in part:
            rot=part['rotation'];rot['origin'][0]=16-rot['origin'][0]
            if rot['axis'] in ('y','z'):rot['angle']=-rot['angle']
    return {'credit':'PIQ original red-brown one-block portable knob CRT; code-native geometry; no reference photo pixels or new PNGs.','ambientocclusion':True,'textures':textures,'elements':e}

def audit(model):
    textures={}
    for k,digest in TEXTURE_HASHES.items():
        p=ASSETS/('textures/block/home_retro_tv_'+k+'.png')
        if sha(p.read_bytes())!=digest:raise ValueError('Protected existing PNG changed')
        textures[model['textures'][k]]=np.asarray(Image.open(p).convert('RGBA'))
    quads=collect_quads(model);vertices=np.concatenate([q.vertices for q in quads]);lo=vertices.min(0);hi=vertices.max(0)
    if not ((lo>=0).all() and (hi<=16).all()):raise ValueError('Vintage TV must fit one block')
    screens=[e for e in model['elements'] if e['name']=='完整4比3黑屏'];assert len(screens)==1
    a,b=np.array(screens[0]['from']),np.array(screens[0]['to']);assert abs((b[0]-a[0])/(b[1]-a[1])-4/3)<1e-12
    return {'ok':True,'bounds':[lo.tolist(),hi.tolist()],'elements':len(model['elements']),'model_sha256':sha(encoded(model)),
            'screen':[a.tolist(),b.tolist()],'screen_aspect':4/3,'sockets':[(16-x,y,z) for x,y,z in SOCKETS],'texture_sha256':TEXTURE_HASHES,
            'limits':['Offline actual model preview, not Minecraft gameplay.','New independent one-cell TV; old TV resources unchanged.']},textures

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');args=p.parse_args();model=build();report,textures=audit(model)
    if args.write:
        canvas=Image.new('RGB',(1540,825),'#e3ded5');d=ImageDraw.Draw(canvas);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23)
        d.text((22,14),'红棕旋钮老电视 · 一格机身 / 提手与收起天线 / 双旋钮 / 4:3屏',font=font,fill='#342c28')
        for i,view in enumerate(((.65,.32,-1.6),(-.75,.36,1.65))):
            pic,_=render_view(collect_quads(model),textures,view,size=(740,680),supersample=2);canvas.paste(pic.convert('RGB'),(15+770*i,72))
        d.text((22,775),'实际模型与原纯色纹理的离线预览；未运行 Minecraft，原有电视和贴图不变。',font=font,fill='#342c28')
        outputs={MODEL:encoded(model)}
        for ext in ('jpg','png'):
            b=io.BytesIO();canvas.save(b,format='JPEG' if ext=='jpg' else 'PNG',**({'quality':92} if ext=='jpg' else {}));outputs[OUT/('红棕老电视_实际模型预览.'+ext)]=b.getvalue()
        outputs[OUT/'vintage-tv-audit.json']=encoded(report)
        # Only replace this task's exact first draft, saving it beside the old preview.
        replacement=outputs.pop(MODEL)
        old=MODEL.read_bytes() if MODEL.exists() else None
        if old is not None and old!=replacement:
            if sha(old)!='3E7AE6A7D34B4300942E09F13E2F6125EA868DA9C438E5601148538A02FD2E00':
                raise ValueError('Unexpected existing model; refusing replacement')
            outputs[OUT.parent/'home_vintage_tv-first-draft.json']=old
        write_new(outputs)
        if old!=replacement:MODEL.write_bytes(replacement)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
