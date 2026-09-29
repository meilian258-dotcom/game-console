"""New wide LCD model. The released one-cell LCD and all original PNGs are read-only."""
from __future__ import annotations
import argparse
import io
import json
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_lcd_tv_model import cube,TEXTURE_HASHES,build as build_alpha6
from import_subor_hardware import ASSETS,CATEGORY,sha,encoded,write_new
from render_rocket_arcade_preview import collect_quads,render_view

MODEL=ASSETS/'models/block/home_large_lcd_tv.json'
OUT=CATEGORY/'大液晶电视居中扩展-alpha10'
SOCKETS=((5.,4.,8.23),(8.,4.,8.23),(11.,4.,8.23))


def build():
    textures={name:'piq_fc_arcade:block/home_retro_tv_'+name for name in TEXTURE_HASHES}
    textures['particle']=textures['dark'];elements=[]
    def add(name,lo,hi,texture,faces=None):elements.append(cube(name,lo,hi,texture,faces))
    add('左薄边框',(-8,1,5.9),(-7,19.5,8),'dark')
    add('右薄边框',(23,1,5.9),(24,19.5,8),'dark')
    add('上薄边框',(-7,18.375,5.9),(23,19.5,8),'dark')
    add('下薄边框',(-7,1,5.9),(23,1.5,8),'dark')
    add('16比9纯黑液晶屏',(-7,1.5,6),(23,18.375,6.08),'screen',('north',))
    add('液晶背部封板',(-7,1.5,7.56),(23,18.375,7.64),'back')
    cuts=[(x-.4,y-.4,x+.4,y+.4) for x,y,z in SOCKETS]
    xs=sorted({-7.,23.,*(v for r in cuts for v in (r[0],r[2]))})
    ys=sorted({1.5,18.375,*(v for r in cuts for v in (r[1],r[3]))})
    for a,b in zip(xs,xs[1:]):
        for c,d in zip(ys,ys[1:]):
            x,y=(a+b)/2,(c+d)/2
            if not any(l<x<r and lo<y<hi for l,lo,r,hi in cuts):add('后板避让RCA孔',(a,c,7.8),(b,d,8),'back')
    for (x,y,z),color in zip(SOCKETS,('yellow','white','red')):
        add(color+'凹口底',(x-.4,y-.4,7.66),(x+.4,y+.4,7.68),'screen')
        add(color+'RCA左边',(x-.4,y-.4,7.8),(x-.2,y+.4,z),color)
        add(color+'RCA右边',(x+.2,y-.4,7.8),(x+.4,y+.4,z),color)
        add(color+'RCA下边',(x-.2,y-.4,7.8),(x+.2,y-.2,z),color)
        add(color+'RCA上边',(x-.2,y+.2,7.8),(x+.2,y+.4,z),color)
    add('窄后支柱',(7.1,.4,7.1),(8.9,2,8.8),'rim')
    add('薄桌面底座',(4,0,5),(12,.4,11),'dark')
    add('底边电源键',(21.7,1.16,5.86),(22.4,1.36,5.9),'rim')
    return {'credit':'PIQ alpha10 new centered 2-wide LCD, 30x16.875 true 16:9 glass, original stand thickness and unchanged CRT PNGs.',
            'ambientocclusion':True,'textures':textures,'elements':elements}


def audit(model):
    textures={}
    for key,expected in TEXTURE_HASHES.items():
        p=ASSETS/('textures/block/home_retro_tv_'+key+'.png')
        if sha(p.read_bytes())!=expected:raise ValueError('Original CRT texture changed')
        textures[model['textures'][key]]=np.array(Image.open(p).convert('RGBA'))
    q=collect_quads(model);p=np.concatenate([v.vertices for v in q]);screen=next(v for v in q if v.element_index==4)
    if not np.allclose([p.min(0),p.max(0)],[[-8,0,5],[24,19.5,11]]):raise ValueError('Wide LCD bounds wrong')
    if any(min(e['from'])<-16 or max(e['to'])>32 for e in model['elements']):raise ValueError('Illegal vanilla coordinates')
    if not np.all(textures[model['textures']['screen']]==[0,0,0,255]):raise ValueError('Glass must be black')
    if sha(encoded(build_alpha6()))!=sha((ASSETS/'models/block/home_lcd_tv.json').read_bytes()):raise ValueError('Old single LCD changed')
    return {'ok':True,'model_sha256':sha(encoded(model)),'bounds':[p.min(0).tolist(),p.max(0).tolist()],
            'screen_quad_units':screen.vertices.tolist(),'screen_aspect':16/9,'rca_socket_units':SOCKETS,
            'elements':len(model['elements']),'texture_sha256':TEXTURE_HASHES,
            'legacy_lcd_sha256':sha(encoded(build_alpha6())),
            'limits':['New block, six reserved cells with half-width sides and clipped short upper tier; old single-cell LCD remains unchanged',
                      'Actual cube/UV offline rendering, not Minecraft execution']},textures


def preview(model,textures):
    canvas=Image.new('RGB',(1540,830),'#19222c');d=ImageDraw.Draw(canvas)
    bold=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',25);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    d.text((24,17),'宽屏薄LCD · 2格宽 / 点击格居中 / 16:9物理屏 / 原厚度底座与三色AV口',font=bold,fill='#eef5fa')
    for i,(label,view) in enumerate((('正面：屏幕30×16.875，默认游戏4:3留黑边',(1,.3,-2)),('背面：黄白红三口随屏幕重新居中',(-1,.3,2)))):
        pic,_=render_view(collect_quads(model),textures,view,size=(740,655),supersample=2)
        canvas.paste(pic.convert('RGB'),(10+770*i,100));d.text((20+770*i,67),label,font=font,fill='#d7eaf7')
    d.text((24,782),'已有CRT纯色贴图未改。新旧LCD为独立物品，旧存档不扩占。离线模型QA，不是Minecraft截图。',font=font,fill='#b9cdda')
    out={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        out['大液晶电视_实际模型预览.'+suffix]=b.getvalue()
    return out


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    model=build();report,textures=audit(model)
    if args.write:
        outputs={MODEL:encoded(model),**{OUT/n:v for n,v in preview(model,textures).items()}}
        report['output_sha256']={str(p):sha(v) for p,v in outputs.items()};outputs[OUT/'large-lcd-model-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))


if __name__=='__main__':main()
