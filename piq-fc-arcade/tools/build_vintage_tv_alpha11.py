"""Inset-face compact CRT redesign; native model geometry, unchanged existing PNGs."""
import argparse,io,json,math
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_lcd_tv_model import cube,TEXTURE_HASHES
from import_subor_hardware import ASSETS,CATEGORY,sha,encoded,write_new
from render_rocket_arcade_preview import collect_quads,render_view

MODEL=ASSETS/'models/block/home_vintage_tv.json'
OUT=CATEGORY/'一体复古小彩电-alpha11'/'闭合屏腔-v3'
PREVIOUS_SHA='8BD167B0E687795750A73B2DEE3A477B53C96C4E8A94407F980BF5BABBF83EAA'
SOCKETS=[(9.5-2*c,3.1,14.04) for c in range(3)]
SCREEN=(4.35,2.,14.55,9.65,3.35)

def build():
    textures={k:'piq_fc_arcade:block/home_retro_tv_'+k for k in TEXTURE_HASHES}
    textures['particle']=textures['red'];elements=[]
    def add(n,a,b,t,faces=None):elements.append(cube(n,a,b,t,faces))
    def ring(n,x1,y1,x2,y2,z1,z2,w,t,wy=None):
        wy=w if wy is None else wy
        add(n+'L',(x1-w,y1-wy,z1),(x1,y2+wy,z2),t)
        add(n+'R',(x2,y1-wy,z1),(x2+w,y2+wy,z2),t)
        add(n+'B',(x1,y1-wy,z1),(x2,y1,z2),t)
        add(n+'T',(x1,y2,z1),(x2,y2+wy,z2),t)
    def sheet(n,rect,z1,z2,cuts,t):
        x1,y1,x2,y2=rect
        xs=sorted({x1,x2,*(x for c in cuts for x in (c[0],c[2]))})
        ys=sorted({y1,y2,*(y for c in cuts for y in (c[1],c[3]))})
        for a,b in zip(xs,xs[1:]):
            for c,d in zip(ys,ys[1:]):
                if not any(l<(a+b)/2<r and lo<(c+d)/2<hi for l,lo,r,hi in cuts):
                    add(n,(a,c,z1),(b,d,z2),t)
    def dial(n,x,y,r,z,t):
        # Pixel-rounded face made from non-overlapping horizontal strips.
        # No intersecting coplanar diamonds / star-shaped caps.
        bands=[(-1,-.7,.70),(-.7,-.35,.93),(-.35,.35,1),(.35,.7,.93),(.7,1,.70)]
        for lo,hi,w in bands:add(n,(x-r*w,y+r*lo,z),(x+r*w,y+r*hi,z+.12),t)
    # Closed, hollow shell: the face is contained by full-depth casing rails.
    # In particular, there is NO solid front wall underneath the inset screen.
    add('shell-left',(.5,.48,2.0),(1.0,11.15,13.82),'red')
    add('shell-right',(15.0,.48,2.0),(15.5,11.15,13.82),'red')
    add('shell-bottom',(1,.48,2.0),(15,1.0,13.82),'red')
    add('shell-top',(1,10.75,2.0),(15,11.15,13.82),'red')
    add('top-soft-step',(.75,11.15,2.3),(15.25,11.43,13.5),'red')
    add('top-crown',(1.1,11.43,2.65),(14.9,11.55,13.15),'red')
    add('bottom-soft-step',(.8,.28,2.35),(15.2,.48,13.45),'red')
    for x in (1.85,12.55):add('rubber-foot',(x,0,4.4),(x+1.6,.28,11.9),'dark')
    # Quiet light fascia, inset from the red shell. The large screen opening is
    # physically cut out, not represented by a black rectangle on a solid box.
    sheet('ivory-fascia',(1.,1.,15.,10.75),2.12,2.70,[(3.94,1.58,14.96,10.07)],'white')
    ring('mouth',4.12,1.76,14.78,9.89,2.16,2.43,.18,'rim')
    ring('recess-wall',4.22,1.86,14.68,9.79,2.43,2.96,.10,'dark')
    ring('inner-shadow',4.35,2.,14.55,9.65,2.96,3.35,.13,'screen',wy=.14)
    add('完整4比3黑屏',(4.35,2.,3.35),(14.55,9.65,3.38),'screen',('north',))
    # A narrow indicator/control column balances the 4:3 glass. Knobs are small,
    # shallow and black, with a subdued silver rim rather than huge white rings.
    add('control-divider',(3.62,1.23,2.105),(3.69,10.53,2.12),'metal',('north',))
    for n,y,r in (('tuning',8.88,.78),('volume',6.63,.54)):
        dial(n+'-rim',2.28,y,r,2.01,'metal')
        dial(n+'-knob',2.28,y,r*.85,1.87,'dark')
        add(n+'-grip',(2.20,y-r*.51,1.82),(2.36,y+r*.51,1.87),'rim')
        add(n+'-indicator',(2.24,y+r*.43,1.814),(2.32,y+r*.65,1.82),'white',('north',))
    for k in range(9):
        a=math.radians(25+k*16.25);x=2.28+math.cos(a)*1.02;y=8.88+math.sin(a)*1.02
        add('channel-mark',(x-.024,y-.06,2.098),(x+.024,y+.06,2.11),'dark',('north',))
    for row in range(9):
        y=2.04+row*.31
        add('speaker-recess',(1.35,y,2.103),(3.2,y+.11,2.12),'dark',('north',))
    add('power-switch',(1.50,1.34,2.015),(2.12,1.68,2.12),'dark')
    add('power-lens',(2.82,1.42,2.085),(3.00,1.57,2.12),'red')
    add('badge-strip',(1.5,5.25,2.093),(3.05,5.59,2.12),'metal',('north',))
    for x in (1.69,1.98,2.27,2.56):add('badge-pixel',(x,5.35,2.084),(x+.14,5.48,2.092),'dark',('north',))
    # Rear sheet and raised three-colour RCA rings retain the existing cable
    # coordinates; no new wiring/save migration is required.
    add('rear-underlay',(1.0,1.0,13.55),(15.0,10.75,13.82),'dark')
    cuts=[(x-.36,y-.36,x+.36,y+.36) for x,y,z in SOCKETS]
    sheet('rear-panel',(1.,1.,15.,10.75),13.82,14.,cuts,'dark')
    for (x,y,z),color in zip(SOCKETS,('yellow','white','red')):
        ring(color+'AV接口',x-.20,y-.20,x+.20,y+.20,13.83,z,.16,color)
    for x in np.arange(2.,14.1,.65):add('rear-vent',(float(x),5.4,14.001),(float(x+.18),9.5,14.015),'screen',('south',))
    for x in (1.45,14.35):
        for y in (1.45,10.3):add('rear-screw',(x,y,14.002),(x+.13,y+.13,14.015),'metal',('south',))
    # Folded handle sits into a shallow top channel, not a tall suitcase arch.
    add('handle-pocket',(3.6,11.553,6.5),(12.4,11.56,8.0),'dark',('up',))
    add('folded-handle',(4.25,11.59,6.9),(11.75,11.83,7.6),'rim')
    for x in (3.8,11.75):add('handle-pivot',(x,11.57,6.82),(x+.45,11.91,7.72),'dark')
    add('folded-aerial',(3.0,11.61,10.1),(12.8,11.74,10.25),'metal')
    add('aerial-pivot',(2.6,11.55,9.94),(3.15,11.91,10.46),'rim')
    return {'credit':'PIQ original compact inset-face CRT, alpha11. Existing PNGs unchanged; no photo pixels.',
            'ambientocclusion':False,'textures':textures,'elements':elements}

def audit(model):
    textures={}
    for k,digest in TEXTURE_HASHES.items():
        p=ASSETS/('textures/block/home_retro_tv_'+k+'.png');assert sha(p.read_bytes())==digest
        textures[model['textures'][k]]=np.asarray(Image.open(p).convert('RGBA'))
    quads=collect_quads(model);v=np.concatenate([q.vertices for q in quads]);lo,hi=v.min(0),v.max(0)
    assert (lo>=0).all() and (hi<=16).all()
    assert abs((SCREEN[2]-SCREEN[0])/(SCREEN[3]-SCREEN[1])-4/3)<1e-12
    # Ray samples across the entire game face must hit no housing before glass.
    for x in np.linspace(4.351,14.549,19):
        for y in np.linspace(2.001,9.649,15):
            for part in model['elements']:
                if part['name']=='完整4比3黑屏':continue
                a,b=part['from'],part['to']
                assert not(a[0]<x<b[0] and a[1]<y<b[1] and a[2]<3.35),part['name']
    return {'ok':True,'bounds':[lo.tolist(),hi.tolist()],'model_sha256':sha(encoded(model)),
            'elements':len(model['elements']),'screen':SCREEN,'sockets':SOCKETS,
            'minimum_shell_to_glass_depth':1.35,'front_screen_rays':285,
            'texture_sha256':TEXTURE_HASHES,'limits':['Actual native-model offline preview, not Minecraft gameplay.']},textures

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');args=p.parse_args()
    model=build();report,textures=audit(model)
    if args.write:
        old=MODEL.read_bytes()
        if old!=encoded(model) and sha(old) not in (PREVIOUS_SHA,'82DC5B63EB0C07D3A073C6582AAAA78789089C0EF33F45B8FCCCAF0FF2733110',
                'F2EC99C9C553F99C8CED03DC0AA12C66BF6DFFEBF05E8B333C0F933DCFA46C26'):
            raise ValueError('Unreviewed existing model; refusing overwrite')
        canvas=Image.new('RGB',(1660,1030),'#e7e4dc');d=ImageDraw.Draw(canvas)
        title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',28);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
        d.text((25,16),'一体复古小彩电 · 奶油色内嵌面板 / 深红机壳 / 收低提手',font=title,fill='#332d28')
        for i,view in enumerate(((.60,.33,-1.7),(-.68,.3,1.7))):
            pic,_=render_view(collect_quads(model),textures,view,size=(805,800),supersample=2)
            canvas.paste(pic.convert('RGB'),(18+i*828,85))
        d.text((25,935),'屏幕向机身内收，正面与壳体一体；原AV接口、卡带与游戏功能保留。',font=font,fill='#332d28')
        d.text((25,975),'实际模型离线预览，非游戏截图；旧alpha10模型已备份，不修改现有PNG。',font=font,fill='#534e48')
        outputs={}
        for ext in ('png','jpg'):
            b=io.BytesIO();canvas.save(b,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':92}))
            outputs[OUT/('一体复古小彩电_实际模型预览.'+ext)]=b.getvalue()
        outputs[OUT/'model-audit.json']=encoded(report)
        if old!=encoded(model):outputs[OUT/('home_vintage_tv-'+sha(old)[:8]+'-backup.json')]=old
        write_new(outputs)
        if old!=encoded(model):MODEL.write_bytes(encoded(model))
    print(json.dumps(report,ensure_ascii=True))

if __name__=='__main__':main()
