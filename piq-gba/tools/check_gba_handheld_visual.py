"""Exact source-derived model and production Java layout QA; optional final JAR, no Minecraft/core execution."""
from pathlib import Path
import argparse,hashlib,io,json,os,subprocess,sys,tempfile,zipfile
import numpy as np
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
sys.path.insert(0,str(ROOT/'tools'))
from import_gba_handheld import derive,ASSETS,OUT,PNG,sha
from render_rocket_arcade_preview import collect_quads,Quad,render_view
import verify_retro_alpha19 as q
def rotate(points,yaw,pitch,roll):
 def axis(a,axis):
  c=np.cos(np.radians(a));s=np.sin(np.radians(a))
  return [np.array([[1,0,0],[0,c,-s],[0,s,c]]),np.array([[c,0,s],[0,1,0],[-s,0,c]]),np.array([[c,-s,0],[s,c,0],[0,0,1]])][axis]
 return points@(axis(yaw,1)@axis(pitch,0)@axis(roll,2)).T
def posed(points,pose):
 x,y,z,yaw,pitch,roll,scale=pose;return rotate(points*scale,yaw,pitch,roll)+[x,y,z]
def quad_transform(quads,fn):return [Quad(fn(q.vertices),q.uv,q.texture,q.element_index,q.direction)for q in quads]
def preview(model,source,layout,output):
 original=collect_quads(model);center=np.array(layout['center']);textures={'gba:block/skin':np.asarray(Image.open(io.BytesIO(source[PNG])).convert('RGBA'))}
 textures.update({'qa:skin':np.array([[[191,145,112,255]]],dtype=np.uint8),'qa:sleeve':np.array([[[22,149,149,255]]],dtype=np.uint8)})
 def hand(right):
  side=1 if right else-1;arm=layout['arms']['right'if right else'left'];x,y,z,pitch,roll,scale=arm
  qs=[]
  for y0,y1,texture in [(0,8,'qa:sleeve'),(8,12,'qa:skin')]:
   # Standard wide-player arm dimensions/pivots; flat QA colours, not a substituted game texture.
   low=[-8 if right else 4,y0,-2];high=[-4 if right else 8,y1,2]
   faces={f:{'uv':[0,0,16,16],'texture':'#0'}for f in ('up','down','north','south','east','west')}
   cube=collect_quads({'textures':{'0':texture},'elements':[{'from':low,'to':high,'faces':faces}]})
   fn=lambda p:rotate(rotate(p/16*scale,0,pitch,0),0,0,roll)+[x,y,z]
   qs+=quad_transform(cube,fn)
  return qs
 scenes=[]
 for label,view in [('背包显示','GUI'),('第三人称物品本体','THIRD')]:
  scenes.append((label,quad_transform(original,lambda p:posed((p-center)/16,layout['poses'][view])),(0,0,1)))
 for label,two in [('第一人称双手 / 原比例',True),('另一手有物品时只绘持握手',False)]:
  item=quad_transform(original,lambda p:posed((p-center)/16,layout['poses']['FIRST'])-.5)
  arms=hand(True)+(hand(False)if two else[]);rig=layout['first_two'if two else'first_single']
  scenes.append((label,quad_transform(item+arms,lambda p:posed(p,rig)),(0,0,1)))
 sheet=Image.new('RGB',(1440,1160),'#e9edf0');draw=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
 for i,(label,quads,view)in enumerate(scenes):
  im,_=render_view(quads,textures,view,size=(705,500),supersample=2);x=(i%2)*720;y=(i//2)*565
  sheet.paste(im,(x+5,y+40),im);draw.text((x+12,y+10),label,font=font,fill='#203040')
 draw.text((15,1130),'真实 Java 布局与原模型离线预览；简化手部着色；非 Minecraft 截图',font=font,fill='#405060')
 sheet.save(output)
def main():
 sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--jar',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();assert not a.output.exists(),'New output required'
 resources,source,imported,model=derive();originals={str(ASSETS/k):sha((ASSETS/k).read_bytes())for k in resources}
 for key,value in resources.items():assert(ASSETS/key).read_bytes()==value,'Source-derived resource mismatch: '+key
 if a.jar:
  with zipfile.ZipFile(a.jar)as jar:
   assert len(jar.namelist())==len(set(jar.namelist()))and jar.testzip()is None
   for key,value in resources.items():assert jar.read('assets/piq_gba/'+key)==value,'Final JAR resource mismatch: '+key
 layout=ROOT/'src/main/java/cn/piq/gba/client/GbaHandheldLayout.java';probe=ROOT/'tools/qa/GbaHandheldLayoutProbe.java';model_probe=ROOT/'tools/qa/GbaHandheldModelProbe.java';inputs=[probe,model_probe,Path(__file__).resolve(),*([a.jar]if a.jar else[layout])];fence={str(x):sha(x.read_bytes())for x in inputs}
 with tempfile.TemporaryDirectory(prefix='piq-gba-handheld-qa-')as folder:
  classes=Path(folder);cp=os.pathsep.join(map(str,[classes,*([a.jar.resolve()]if a.jar else[])]))
  cmd=[JDK/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-cp',cp,'-d',classes,probe,*([]if a.jar else[layout])]
  built=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40);assert built.returncode==0,built.stdout+built.stderr
  ran=subprocess.run([str(JDK/'java.exe'),'-cp',cp,'cn.piq.gba.client.GbaHandheldLayoutProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40);assert ran.returncode==0,ran.stdout+ran.stderr
  result=json.loads(ran.stdout)
  actualcp=os.pathsep.join(map(str,[cp,q.MC,*q.dependencies()]));argsfile=classes/'actual.args';argsfile.write_text('-cp\n"'+actualcp.replace('\\','/')+'"\n',encoding='utf-8')
  q.run([JDK/'javac.exe','@'+str(argsfile),'-encoding','UTF-8','-proc:none','-d',classes,model_probe],classes)
  actual=q.parse_last_json(q.run([JDK/'java.exe','@'+str(argsfile),'cn.piq.gba.client.GbaHandheldModelProbe',a.jar.resolve()if a.jar else ROOT/'src/main/resources'],classes))
 assert fence=={str(x):sha(x.read_bytes())for x in inputs};assert originals=={str(ASSETS/k):sha((ASSETS/k).read_bytes())for k in resources}
 a.output.mkdir(parents=True);preview(model,source,result,a.output/'poses.png')
 report={'ok':True,'mode':'final-jar-only'if a.jar else'production-source-layout','production_compiled':not bool(a.jar),'layout':result,'actual_model_parser':actual,'import':imported,'input_sha256':fence,'minecraft_started':False,'native_core_started':False,'preview':str((a.output/'poses.png').resolve())}
 if a.jar:report['jar']={'path':str(a.jar.resolve()),'sha256':sha(a.jar.read_bytes())}
 (a.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'ok':True,'assertions':result['assertions'],'report':str((a.output/'report.json').resolve())},ensure_ascii=False))
if __name__=='__main__':main()
