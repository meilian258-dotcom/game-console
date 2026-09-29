"""Derive exact original GBA Java-model parts. Never execute code from the user's ZIP."""
from pathlib import Path
import argparse,copy,hashlib,io,json,sys,zipfile
import numpy as np
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
ZIP=ROOT.parent/'piq-fc-arcade/design/refined-dual-zapper-20260911/original.zip'
ZIP_SHA='8E4B502BA203892D7580D9B3A0DB4308737C70AA7543905AD42AFB8DA62C3FC7'
PREFIX='03_GBA_经典横版/'
MODEL='GBA_经典横版_单张UV.bbmodel'
PNG='GBA_完整UV.png'
PNG_SHA='376FB935DEB9D6F5F4682A24FC4DF94D5EF9A5793D14B4255F573FE6FF921BCC'
OUT=ROOT/'design/gba-handheld-model-20260912'
ASSETS=ROOT/'src/main/resources/assets/piq_gba'
MOVING=('dpad','button_a','button_b','button_select','button_start','shoulder_l','shoulder_r')
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
from render_rocket_arcade_preview import collect_quads,Quad,render_view
def sha(b):return hashlib.sha256(b).hexdigest().upper()
def encoded(v):return (json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n').encode()
def put(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists():
  if path.read_bytes()!=data:raise ValueError('Refuse overwrite changed artifact: '+str(path))
 else:
  with path.open('xb')as out:out.write(data)
def derive():
 assert sha(ZIP.read_bytes())==ZIP_SHA
 with zipfile.ZipFile(ZIP)as z:
  assert len(z.namelist())==len(set(z.namelist()))and z.testzip()is None
  wanted=[MODEL,'gba.json',PNG,'按键映射.json','使用说明.md','皮肤区域.json','预览_俯视.png','预览_正侧.png','预览_背面.png']
  source={name:z.read(PREFIX+name)for name in wanted}
 assert sha(source[PNG])==PNG_SHA
 bb=json.loads(source[MODEL]);model=json.loads(source['gba.json']);groups={g['uuid']:g for g in bb['groups']};by_uuid={e['uuid']:e for e in bb['elements']};owners={}
 def walk(node,owner=None):
  if isinstance(node,str):
   assert node in by_uuid and node not in owners;owners[node]=owner;return
  group=groups[node['uuid']];assert group['rotation']==[0,0,0]
  for child in node['children']:walk(child,group['name'])
 for node in bb['outliner']:walk(node)
 assert len(model['elements'])==len(bb['elements'])==len(owners)==735
 parts={name:[]for name in ('body','screen',*MOVING)}
 for element,original in zip(model['elements'],bb['elements']):
  assert element['name']==original['name']and element['from']==original['from']and element['to']==original['to']
  owner=owners[original['uuid']];target=owner if owner in parts else'body'
  parts[target].append(copy.deepcopy(element))
 assert len(parts['screen'])==1 and parts['screen'][0]['name']=='可替换游戏画面'
 assert all(parts.values())and sum(map(len,parts.values()))==735
 resources={}
 for name,elements in parts.items():
  resources[f'models/item/handheld/{name}.json']=encoded({'ambientocclusion':False,'textures':{'0':'piq_gba:item/handheld','particle':'#0'},'elements':elements})
 resources['textures/item/handheld.png']=source[PNG]
 resources['models/item/handheld.json']=encoded({'parent':'builtin/entity','textures':{'particle':'piq_gba:item/handheld'}})
 quads=collect_quads(model);all_vertices=np.concatenate([q.vertices for q in quads]);bounds=[all_vertices.min(0).tolist(),all_vertices.max(0).tolist()]
 assert np.allclose(bounds,[[3.75,0,5.56],[12.25,1.443,10.482]],atol=1e-7)
 image=Image.open(io.BytesIO(source[PNG]));assert image.size==(2048,2048)and image.mode=='RGBA'
 report={'ok':True,'zip_sha256':ZIP_SHA,'source_sha256':{k:sha(v)for k,v in source.items()},'elements':735,'rotated_elements':sum('rotation'in e for e in model['elements']),
   'faces':len(quads),'triangles':len(quads)*2,'bounds_units':bounds,'part_elements':{k:len(v)for k,v in parts.items()},'texture_bytes_preserved':True,
   'resources_sha256':{k:sha(v)for k,v in resources.items()},'screen':{'uuid':'6503a6c7-1ecc-438e-aefd-8729c45892ff','x':[6.2,9.8],'z':[6.61,9.01],'y':1.301,'normal':[0,1,0],'top_direction':[0,0,-1],'resolution':[240,160]},
   'limitations':['Model/UV conversion only, not Minecraft gameplay.','Original static screen remains its own part; runtime texture overlays only the exact held local device.','No core, input, network, original ZIP or bitmap generation changes.']}
 return resources,source,report,model
def preview(model,source):
 texture=np.asarray(Image.open(io.BytesIO(source[PNG])).convert('RGBA'));quads=collect_quads(model)
 textures={'gba:block/skin':texture};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
 sheet=Image.new('RGB',(1440,660),'#e9edf0');d=ImageDraw.Draw(sheet)
 for i,(label,view)in enumerate([('原始俯视 · 3:2 屏 / 原 PNG',(0,1,.005)),('原始斜视 · 保留厚度 / 按键分件',(.6,1,1.1)),('背面 · 原电池盖 / 接口',(.45,-1,.8))]):
  image,_=render_view(quads,textures,view,size=(470,535),supersample=2);sheet.paste(image,(i*480+5,55),image);d.text((i*480+12,18),label,font=font,fill='#182633')
 d.text((15,612),'用户真实 735 元素 / 2048 PNG 的离线几何预览；不是 Minecraft 截图',font=font,fill='#334455')
 result=io.BytesIO();sheet.save(result,format='PNG');return result.getvalue()
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--write',action='store_true');p.add_argument('--preview',action='store_true');a=p.parse_args()
 resources,source,report,model=derive()
 if a.write:
  for name,data in source.items():put(OUT/'source'/name,data)
  for name,data in resources.items():put(ASSETS/name,data)
  put(OUT/'import-audit.json',(json.dumps(report,ensure_ascii=False,indent=2)+'\n').encode())
 if a.preview:put(OUT/'model-reference-v2.png',preview(model,source))
 print(json.dumps(report,ensure_ascii=False))
if __name__=='__main__':main()
