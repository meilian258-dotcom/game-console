"""Deterministic one-block LCD geometry using unchanged existing CRT solid textures.

This is native cube modelling, not generated raster art or a Minecraft screenshot.
"""
from __future__ import annotations
import argparse
import io
import json
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from import_subor_hardware import ASSETS, CATEGORY, encoded, sha, write_new
from render_rocket_arcade_preview import collect_quads, render_view

MODEL = ASSETS/'models/block/home_lcd_tv.json'
OUT = CATEGORY/'双人街机与薄LCD-alpha6'/'LCD实际模型预览'
SOCKETS = ((5.,4.,8.23),(8.,4.,8.23),(11.,4.,8.23))
SCREEN = (1.,1.5,15.,12.,6.)
TEXTURE_HASHES = {
 'screen':'0540D603D36CF41C9D9450D646FEF6D6141546D1589C0FC82A45169F7C92B1D8',
 'dark':'73C065A5B5B4BFC6A8FF3F9987DEC63CF457CCB9C1085DC13531407C54D8ECF5',
 'rim':'E61B29CDED4CB228A59690C3C4FDFE43B01A5E7E6E258F771D6584607A2B0B51',
 'back':'294998B248DA92695C5B4149664AA6FD37DD3ECAD7A0E092A43BD2906CAF3475',
 'metal':'8CAA25DC9830B114E0EB38986FE65380925BB36645056A4B30D147C8A8208C59',
 'red':'235DD1B9AE01BFABA68461FC02CC47B9FF4F2EF857756B2E55203E2A9D760FDE',
 'white':'78272C1FFDF15EBFF7EEF25ABF98821D73FD7E7AAD53A35C181101E301CC5E78',
 'yellow':'1857D29EBB641054748B345FBD28F9E0C4299DE18DC9AC71A5BED927531882A5'}


def cube(name,low,high,texture,faces=None):
    return {'name':name,'from':list(low),'to':list(high),'faces':{
        side:{'uv':[0,0,16,16],'texture':'#'+texture}
        for side in (faces or ('north','east','south','west','up','down'))}}


def build():
    textures={name:'piq_fc_arcade:block/home_retro_tv_'+name for name in TEXTURE_HASHES}
    textures['particle']=textures['dark'];elements=[]
    def add(name,low,high,texture,faces=None):elements.append(cube(name,low,high,texture,faces))
    # Closed perimeter; the black screen is recessed 0.10 units behind the front lip.
    add('左薄边框',(0,1,5.9),(1,13,8),'dark')
    add('右薄边框',(15,1,5.9),(16,13,8),'dark')
    add('上薄边框',(1,12,5.9),(15,13,8),'dark')
    add('下薄边框',(1,1,5.9),(15,1.5,8),'dark')
    add('4比3纯黑液晶屏',(1,1.5,6),(15,12,6.08),'screen',('north',))
    add('液晶背部封板',(1,1.5,7.56),(15,12,7.64),'back')
    # Rear sheet is physically partitioned around three connector apertures.
    cuts=[(x-.4,y-.4,x+.4,y+.4) for x,y,z in SOCKETS]
    xs=sorted({1.,15.,*(v for rect in cuts for v in (rect[0],rect[2]))})
    ys=sorted({1.5,12.,*(v for rect in cuts for v in (rect[1],rect[3]))})
    for a,b in zip(xs,xs[1:]):
        for c,d in zip(ys,ys[1:]):
            x,y=(a+b)/2,(c+d)/2
            if not any(l<x<r and low<y<high for l,low,r,high in cuts):
                add('后板避让RCA孔',(a,c,7.8),(b,d,8),'back')
    for (x,y,z),color in zip(SOCKETS,('yellow','white','red')):
        add(color+'凹口底',(x-.4,y-.4,7.66),(x+.4,y+.4,7.68),'screen')
        add(color+'方像素RCA左边',(x-.4,y-.4,7.8),(x-.2,y+.4,z),color)
        add(color+'方像素RCA右边',(x+.2,y-.4,7.8),(x+.4,y+.4,z),color)
        add(color+'方像素RCA下边',(x-.2,y-.4,7.8),(x+.2,y-.2,z),color)
        add(color+'方像素RCA上边',(x-.2,y+.2,7.8),(x+.2,y+.4,z),color)
    add('窄后支柱',(7.1,.4,7.1),(8.9,2,8.8),'rim')
    add('薄桌面底座',(4,0,5),(12,.4,11),'dark')
    add('底边电源键',(13.7,1.16,5.86),(14.4,1.36,5.9),'rim')
    return {'credit':'PIQ native thin LCD geometry; existing CRT solid PNGs unchanged.',
            'ambientocclusion':True,'textures':textures,'elements':elements}


def audit(model):
    pixels={};hashes={}
    for name,expected in TEXTURE_HASHES.items():
        path=ASSETS/('textures/block/home_retro_tv_'+name+'.png');raw=path.read_bytes()
        if sha(raw)!=expected:raise ValueError('Existing texture changed: '+name)
        image=np.array(Image.open(io.BytesIO(raw)).convert('RGBA'))
        if len(np.unique(image.reshape(-1,4),axis=0))!=1:raise ValueError('Texture no longer solid: '+name)
        pixels[name]=image;hashes[name]=sha(raw)
    if not np.all(pixels['screen']==[0,0,0,255]):raise ValueError('Screen must be opaque pure black')
    quads=collect_quads(model);points=np.concatenate([q.vertices for q in quads])
    bounds=[points.min(0).tolist(),points.max(0).tolist()]
    screen=next(q for q in quads if model['elements'][q.element_index]['name']=='4比3纯黑液晶屏')
    if not np.allclose(bounds,[[0,0,5],[16,13,11]]):raise ValueError('LCD bounds changed')
    return {'ok':True,'bounds':bounds,'screen_quad':screen.vertices.tolist(),'screen_aspect':14/10.5,
            'rca_yellow_white_red':SOCKETS,'rca_outward':[0,0,1],
            'texture_sha256':hashes,'elements':len(model['elements']),
            'limits':['Offline actual cube/UV renderer, not an in-game screenshot','Square pixel RCA bezels, with physically recessed centers']},pixels


def preview(model,textures):
    textures={model['textures'][name]:pixels for name,pixels in textures.items()}
    canvas=Image.new('RGB',(1440,760),'#19222c');draw=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',26)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    draw.text((24,15),'薄 LCD · 一格宽 / 4:3 黑屏 / 黄白红三口',font=title,fill='#eef5fa')
    for i,(label,view) in enumerate((('正面略侧 · 14×10.5 纯黑屏',(1,.4,-2)),('薄后壳 · 三路 RCA 与桌面支柱',(-1,.4,2)))):
        picture,_=render_view(collect_quads(model),textures,view,size=(700,620),supersample=2)
        canvas.paste(picture.convert('RGB'),(10+i*720,75));draw.text((20+i*720,50),label,font=font,fill='#d8e8ef')
    draw.text((24,715),'实际 JSON 顶点 + 已有 CRT 纯色纹理离线渲染；非游戏截图；未生成/修改 PNG 材质。',font=font,fill='#b9cdd8')
    result={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        result['LCD实际模型预览.'+suffix]=b.getvalue()
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    model=build();report,textures=audit(model);raw=encoded(model);report['model_sha256']=sha(raw)
    if args.check_only:
        if MODEL.read_bytes()!=raw:raise ValueError('Runtime model differs from deterministic build')
    if args.write:
        outputs={MODEL:raw,**{OUT/name:data for name,data in preview(json.loads(raw),textures).items()}}
        report['output_sha256']={str(path):sha(data) for path,data in outputs.items()}
        outputs[OUT/'LCD模型校验.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))


if __name__=='__main__':main()
