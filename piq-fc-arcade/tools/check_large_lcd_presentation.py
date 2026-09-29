"""Actual production Java LCD screen frame on installed cubes; no independent fitting algorithm."""
import argparse,io,json,re,subprocess,tempfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_large_lcd_tv_model import MODEL,OUT,audit,SOCKETS
from import_subor_hardware import ASSETS,sha,encoded,write_new
from check_dual_aspect_preview import diagnostic
from check_dual_render_pipeline import JDK,JAVA,ROOT
from check_controller_pose_pipeline import display_matrix,translation,points
from render_rocket_arcade_preview import Quad,collect_quads,render_view,raster_triangle

SOURCES=[JAVA/'layout'/s for s in ('RocketArcadeGeometry.java','ScreenAspectFit.java','LargeLcdPresentation.java')]+[JAVA/'home/LargeLcdTvLayout.java']

def runtime_frame_branch(source,style='HOME_LARGE_LCD_TV',frame='LargeLcdPresentation.frame('):
    """Require the selected TV frame and uncropped UVs in its own rendering branch."""
    source=re.sub(r'/\*.*?\*/|//[^\r\n]*','',source,flags=re.S)
    source=re.sub(r'\s+','',source)
    # Alpha14 exposes drawFace at package scope for the public external-TV API.
    # Access does not change its geometry contract; only inspect this exact method.
    declaration=re.search(r'(?:private|public|protected)?staticvoiddrawFace\([^{}]*\)\{',source)
    if declaration is None:return False
    start=declaration.end();depth=1;end=start
    while end<len(source) and depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    if depth:return False
    method=source[start:end-1]
    branches=re.finditer(r'if\(([^{}]*?)\)\{(.*?)return;\}',method)
    for branch in branches:
        if 'displayStyle==cn.piq.fcarcade.layout.ArcadeDisplayStyle.'+style not in branch[1]:
            continue
        body=branch[2]
        return bool(frame in body and all(s in body for s in (
            'rocketVertex(consumer,pose,quad.lowerMaxX(),quad.normal(),0,1);',
            'rocketVertex(consumer,pose,quad.lowerMinX(),quad.normal(),1,1);',
            'rocketVertex(consumer,pose,quad.upperMinX(),quad.normal(),1,0);',
            'rocketVertex(consumer,pose,quad.upperMaxX(),quad.normal(),0,0);')))
    return False

def probe():
    with tempfile.TemporaryDirectory(prefix='piq-large-lcd-') as temp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',temp,*map(str,SOURCES),str(ROOT/'tools/qa/LargeLcdPresentationProbe.java')],check=True,capture_output=True,timeout=30)
        stdout=subprocess.run([str(JDK/'java.exe'),'-cp',temp,'LargeLcdPresentationProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    frames={}
    for line in stdout.splitlines():
        p=line.split();t=int(p[1]);d=frames.setdefault(t,{})
        if p[0]=='FRAME':d['cached']=p[-1]=='true';p=p[:-1]
        d[p[0]]=np.array([float(v) for v in p[2:]]).reshape(-1,3)
    return frames,stdout

def analyze():
    model=json.loads(MODEL.read_bytes());model_report,textures=audit(model);data,stdout=probe();checks=[]
    for t,d in data.items():
        frame,glass=d['FRAME'][:4],d['GLASS'][:4];u=glass[1]-glass[0];u/=np.linalg.norm(u)
        margin=(frame-glass[0])@u
        width=np.linalg.norm(frame[1]-frame[0]);height=np.linalg.norm(frame[3]-frame[0])
        checks.append({'name':'turn '+str(t)+' actual cached 4:3 frame with symmetric 3.75-unit black bars','ok':bool(d['cached'] and abs(width/height-4/3)<1e-12 and np.allclose(frame.mean(0),glass.mean(0)) and abs(margin.min()*16-3.75)<1e-10 and abs((30/16-margin.max())*16-3.75)<1e-10 and np.allclose(d['FRAME'][4],d['GLASS'][4]))})
        sockets=np.array(SOCKETS)/16
        for _ in range(t):sockets=np.column_stack((1-sockets[:,2],sockets[:,1],sockets[:,0]))
        checks.append({'name':'turn '+str(t)+' actual three ports match baked model rotation','ok':bool(np.allclose(sockets,d['SOCKETS'],atol=1e-12))})
    glass=data[0]['GLASS'][:4]-data[0]['GLASS'][4]*.0015
    real=collect_quads(model)[next(i for i,q in enumerate(collect_quads(model)) if q.element_index==4)]
    checks.append({'name':'actual glass is unique 16:9 baked plane','ok':all(any(np.allclose(p,q/16) for q in real.vertices) for p in glass)})
    item=json.loads((ASSETS/'models/item/large_lcd_tv.json').read_bytes());matrix=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)
    transformed=points(np.concatenate([q.vertices for q in collect_quads(model)])/16,matrix);pixels=transformed[:,:2]*[16,-16]+8
    checks.append({'name':'actual unrefitted vanilla GUI vertices fit 16px item slot','ok':bool(pixels.min()>=0 and pixels.max()<=16)})
    source=(JAVA/'client/ArcadeBlockScreenRenderer.java').read_text(encoding='utf-8')
    checks.append({'name':'runtime renderer selects production frame and complete UV range in Large LCD branch','ok':runtime_frame_branch(source)})
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'production_stdout':stdout,'gui_bounds_pixels':[pixels.min(0).tolist(),pixels.max(0).tolist()],
            'model_sha256':sha(MODEL.read_bytes()),'production_sha256':{str(p):sha(p.read_bytes()) for p in SOURCES},
            'limits':['Diagnostic grid, not NES gameplay','Actual Java cached frame call; fixed vanilla GUI matrix without auto fit','Offline raster of installed model and original textures, not Minecraft execution']},model,textures,data,matrix

def preview(model,textures,data,matrix):
    base=collect_quads(model);frame=data[0]['FRAME'][:4]
    screen=Quad(frame[[2,1,0,3]]*16,np.array([[0,0],[0,16],[16,16],[16,0]]),'qa',-1,'dynamic')
    canvas=Image.new('RGB',(1660,850),'#19222c');d=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',26);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    d.text((20,14),'大液晶LCD实际Java渲染QA · 真16:9屏 / 默认完整4:3画面',font=title,fill='#edf5fc')
    pic,_=render_view([*base,screen],{**textures,'qa':diagnostic('4:3')},(0,.05,-1),size=(1030,625),supersample=2)
    canvas.paste(pic.convert('RGB'),(15,97));d.text((22,63),'左右黑边各3.75模型单位；四角标记均可见，不裁切ROM画面',font=font,fill='#c4ddec')
    item=np.zeros((512,512,4),dtype=np.uint8);item[:]=(35,42,52,255);depth=np.full((512,512),-np.inf)
    for q in base:
        p=points(q.vertices/16,matrix);p[:,:2]=p[:,:2]*[512,-512]+256;p[:,2]*=512
        for indices in ((0,1,2),(0,2,3)):raster_triangle(item,depth,p[list(indices)],q.uv[list(indices)],textures[q.texture])
    canvas.paste(Image.fromarray(item).convert('RGB'),(1115,120));d.rectangle((1115,120,1627,632),outline='#aac4d5',width=2)
    d.text((1120,73),'真实16像素物品栏变换 ×32',font=font,fill='#c4ddec')
    d.text((1120,653),'未自动缩放或重新居中',font=font,fill='#c4ddec')
    d.text((20,786),'实际安装模型+原贴图+生产Java输出。网格是QA画面，不是游戏；离线预览不是Minecraft截图。',font=font,fill='#bdcedd')
    out={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}));out['大液晶电视_屏幕黑边与物品栏QA.'+suffix]=b.getvalue()
    others=[]
    for file,offset in (('home_lcd_tv.json',44),('home_wide_lcd_tv.json',10),('home_large_lcd_tv.json',-30)):
        for q in collect_quads(json.loads((ASSETS/'models/block'/file).read_bytes())):
            others.append(Quad(q.vertices+[offset,0,0],q.uv,q.texture,q.element_index,q.direction))
    comparison=Image.new('RGB',(1490,620),'#19222c');label=ImageDraw.Draw(comparison)
    label.text((20,15),'三款液晶 · 同一个镜头与比例 · 原型号全部保留',font=title,fill='#edf5fc')
    picture,_=render_view(others,textures,(0,0,-1),size=(1450,450),supersample=2)
    comparison.paste(picture.convert('RGB'),(20,72))
    for x,text in ((82,'原小液晶：1格宽'),(450,'原宽屏液晶：1.5格宽'),(1030,'新增大液晶：2格宽')):
        label.text((x,510),text,font=font,fill='#d2e6f3')
    label.text((20,568),'共用实际世界单位，不逐台自动适配大小；新款底座与点击方块中心重合。离线模型QA，非游戏截图。',font=font,fill='#b9d0e0')
    for suffix in ('png','jpg'):
        b=io.BytesIO();comparison.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}));out['三款液晶电视_真实同尺度对比.'+suffix]=b.getvalue()
    return out

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');args=p.parse_args()
    report,model,textures,data,matrix=analyze()
    if args.write:
        outputs={OUT/name:b for name,b in preview(model,textures,data,matrix).items()};report['preview_sha256']={str(p):sha(b) for p,b in outputs.items()};outputs[OUT/'large-lcd-presentation-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)

if __name__=='__main__':main()
