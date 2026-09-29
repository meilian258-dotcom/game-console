"""Actual source JSON/UV preview; not a Minecraft screenshot or live interaction test."""
import argparse,json,math
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from import_zapper_stand28 import derive_stand,RES
from render_rocket_arcade_preview import collect_quads,render_view,texture_path,font,Quad,sha

ROOT=Path(__file__).resolve().parents[1]
def read(name):return json.loads((RES/('assets/piq_fc_arcade/models/'+name+'.json')).read_text(encoding='utf-8'))
def gui(model,wrapper):
    tr=wrapper['display']['gui'];x,y,z=map(math.radians,tr['rotation']);assert z==0
    assert max(map(abs,tr['scale']))<=4 and max(map(abs,tr['translation']))<=80
    rx=np.array(((1,0,0),(0,math.cos(x),-math.sin(x)),(0,math.sin(x),math.cos(x))))
    ry=np.array(((math.cos(y),0,math.sin(y)),(0,1,0),(-math.sin(y),0,math.cos(y))))
    rotation=rx@ry;out=[]
    for q in collect_quads(model):
        # MC ItemTransform: translate, rotationXYZ, scale; ItemRenderer then subtracts .5.
        v=((q.vertices-8)*np.asarray(tr['scale']))@rotation.T+tr['translation']
        out.append(Quad(v,q.uv,q.texture,q.element_index,q.direction))
    v=np.concatenate([q.vertices for q in out]);low,high=v.min(axis=0),v.max(axis=0)
    assert (low[:2]>=-7.5).all()and(high[:2]<=7.5).all(),(low,high)
    assert np.abs((low+high)/2).max()<1e-4,(low,high)
    return out,{'bounds':{'min':low.tolist(),'max':high.tolist()},'inside_16px_slot':True,'center_error_units':float(np.abs((low+high)/2).max())}
def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args();assert not a.output.exists();a.output.mkdir(parents=True)
    resources=derive_stand()
    for path,raw in resources.items():assert (RES/path).read_bytes()==raw
    parts={name:read('block/zapper_stand/'+name)for name in('stand','cable','connector')}
    parts.update({name:read('item/zapper/'+name)for name in('body','trigger')})
    quads={n:collect_quads(m)for n,m in parts.items()}
    textures={q.texture:np.asarray(Image.open(texture_path(RES/'assets',q.texture)).convert('RGBA'))for qs in quads.values()for q in qs}
    full=sum((quads[n]for n in('stand','body','trigger','cable','connector')),[])
    standgui,sg=gui(parts['stand'],read('item/zapper_stand'))
    cablegui,cg=gui(read('item/zapper_stand_cable'),read('item/zapper_stand_cable'))
    specs=[('放回：原枪身 + 扳机 + 支架 + 原盘线',full,(-1,.5,-1.6)),
           ('借出：支架保留，不重复画枪',quads['stand'],(-1,.5,-1.6)),
           ('空支架物品：真实 GUI 变换',standgui,(0,0,1)),
           ('连接线物品：原盘线 + 插头 GUI 变换',cablegui,(0,0,1))]
    sheet=Image.new('RGB',(1320,1060),(230,234,239));d=ImageDraw.Draw(sheet)
    d.text((24,18),'光枪支架 · 原模型 / 原 UV 离线预览',font=font(29,True),fill=(25,32,44))
    d.text((24,62),'代码资产渲染，不是 Minecraft 截图；不含世界光照、玩家姿势与动态连线。',font=font(19),fill=(60,68,80))
    for i,(label,qs,view)in enumerate(specs):
        x=20+(i%2)*650;y=104+(i//2)*450
        image,meta=render_view(qs,textures,view,(630,395),2)
        d.rectangle((x,y,x+630,y+435),fill=(248,249,251));sheet.paste(image,(x,y),image)
        d.text((x+16,y+402),label,font=font(19,True),fill=(28,37,51))
    d.text((24,1012),'原件 85 支架 / 51 线 / 18 插头元素保留；取枪移动原物品，不生成替身。',font=font(20),fill=(47,58,72))
    target=a.output/'stand-preview.png';sheet.save(target)
    report={'ok':True,'minecraft_screenshot':False,'new_png_in_resources':0,'elements':{n:len(m['elements'])for n,m in parts.items()},'gui':{'stand':sg,'cable':cg},
        'resources':{n:sha(b)for n,b in resources.items()},'preview':{'path':str(target.resolve()),'sha256':sha(target.read_bytes())},
        'limits':['No live game, permissions or inventory simulation. GUI projection is independently checked against slot bounds; panel images are fitted for readability.']}
    (a.output/'preview-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'ok':True,'preview':str(target),'gui':report['gui']},ensure_ascii=False))
if __name__=='__main__':main()
