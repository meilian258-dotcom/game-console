"""Historical alpha10 CRT QA: actual frozen JAR bytecode/assets, never active alpha11 inputs."""
import argparse,io,json,re,subprocess,tempfile
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_vintage_tv_model import MODEL,OUT,audit
from import_subor_hardware import ASSETS,sha,encoded,write_new
from check_large_lcd_presentation import runtime_frame_branch
from check_dual_aspect_preview import diagnostic
from check_dual_render_pipeline import JDK,JAVA,ROOT
from check_controller_pose_pipeline import display_matrix,translation,points
from render_rocket_arcade_preview import Quad,collect_quads,render_view,raster_triangle
from vintage_tv_alpha10_archive import release,ARCHIVE,ARCHIVE_SHA,MODEL_SHA,ITEM_SHA

SOURCES=[JAVA/'layout/RocketArcadeGeometry.java',JAVA/'home/VintageTvLayout.java']
def probe():
    release()
    with tempfile.TemporaryDirectory(prefix='piq-vintage-tv-') as temp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-cp',str(ARCHIVE),'-d',temp,str(ROOT/'tools/qa/VintageTvPresentationProbe.java')],check=True,capture_output=True,timeout=30)
        stdout=subprocess.run([str(JDK/'java.exe'),'-cp',temp+';'+str(ARCHIVE),'VintageTvPresentationProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    result={}
    for line in stdout.splitlines():
        p=line.split();d=result.setdefault(int(p[1]),{})
        if p[0]=='FRAME':d['cached']=p[-1]=='true';p=p[:-1]
        d[p[0]]=np.array([float(v) for v in p[2:]]).reshape(-1,3)
    return result,stdout

def analyze():
    released=release();model=released['model'];textures=released['textures'];data,stdout=probe();checks=[]
    checks.append({'name':'actual alpha10 archived model remains frozen','ok':sha(released['model_bytes'])==MODEL_SHA})
    for t,d in data.items():
        frame=d['FRAME'][:4];expected=np.array([[4.6,2.1,2.256],[14.6,2.1,2.256],[14.6,9.6,2.256],[4.6,9.6,2.256]])/16
        sockets=np.array([[9.5-c*2,3.1,14.04] for c in range(3)])/16
        for _ in range(t):
            expected=np.column_stack((1-expected[:,2],expected[:,1],expected[:,0]));sockets=np.column_stack((1-sockets[:,2],sockets[:,1],sockets[:,0]))
        checks.append({'name':'turn '+str(t)+' actual cached 4:3 frame on mirrored glass','ok':bool(d['cached'] and np.allclose(frame,expected,atol=1e-12) and abs(np.linalg.norm(frame[1]-frame[0])/np.linalg.norm(frame[3]-frame[0])-4/3)<1e-12)})
        checks.append({'name':'turn '+str(t)+' mirrored yellow white red socket order','ok':bool(np.allclose(d['SOCKETS'],sockets,atol=1e-12))})
    quads=collect_quads(model);screen_index=next(i for i,e in enumerate(model['elements']) if e['name']=='完整4比3黑屏')
    glass=[q for q in quads if q.element_index==screen_index]
    restored=data[0]['FRAME'][:4]-data[0]['FRAME'][4]*.0015
    checks.append({'name':'native static glass is unique and production corners match it','ok':len(glass)==1 and all(any(np.allclose(p,v/16,atol=1e-12) for v in glass[0].vertices) for p in restored)})
    item=released['item'];matrix=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)
    transformed=points(np.concatenate([q.vertices for q in quads])/16,matrix);pixels=transformed[:,:2]*[16,-16]+8
    checks.append({'name':'actual unrefitted vanilla GUI fits complete model','ok':bool(pixels.min()>=0 and pixels.max()<=16)})
    bytecode=subprocess.run([str(JDK/'javap.exe'),'-classpath',str(ARCHIVE),'-c','-p','cn.piq.fcarcade.client.ArcadeBlockScreenRenderer'],check=True,capture_output=True,text=True,timeout=30).stdout
    match=re.search(r'private static void drawFace\(.*?(?=\n  (?:private|public|protected) |\Z)',bytecode,re.S);branch=match[0] if match else ''
    checks.append({'name':'frozen alpha10 renderer calls Vintage layout and four full-source corners','ok':all(s in branch for s in ('HOME_VINTAGE_TV','VintageTvLayout.screen:','ScreenQuad.lowerMaxX:','ScreenQuad.lowerMinX:','ScreenQuad.upperMinX:','ScreenQuad.upperMaxX:'))})
    if sha(ARCHIVE.read_bytes())!=ARCHIVE_SHA:raise ValueError('Historical archive changed during execution')
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'production_stdout':stdout,'gui_bounds_pixels':[pixels.min(0).tolist(),pixels.max(0).tolist()],
            'model_sha256':MODEL_SHA,'item_sha256':ITEM_SHA,'archive_sha256':ARCHIVE_SHA,'production_sha256':released['class_sha256'],
            'limits':['Historical alpha10 JAR only: active model and active Java are not inputs.','Diagnostic grid, not game content; offline actual-model raster, not Minecraft execution.','Frozen Java production frame and actual item display matrix; no automatic GUI fitting.']},model,textures,data,matrix

def preview(model,textures,data,matrix):
    base=collect_quads(model);f=data[0]['FRAME'][:4]
    screen=Quad(f[[2,1,0,3]]*16,np.array([[0,0],[0,16],[16,16],[16,0]]),'qa',-1,'dynamic')
    canvas=Image.new('RGB',(1600,830),'#e3ded5');d=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',26);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    d.text((20,16),'红棕老电视 · 实际Java画面与物品栏检查',font=title,fill='#342c28')
    picture,_=render_view([*base,screen],{**textures,'qa':diagnostic('4:3')},(0,.035,-1),size=(960,640),supersample=2)
    canvas.paste(picture.convert('RGB'),(10,80));d.text((25,60),'完整4:3测试画面，屏幕在左、双旋钮在右',font=font,fill='#463c36')
    target=np.zeros((512,512,4),dtype=np.uint8);target[:]=(60,54,49,255);depth=np.full((512,512),-np.inf)
    for q in base:
        p=points(q.vertices/16,matrix);p[:,:2]=p[:,:2]*[512,-512]+256;p[:,2]*=512
        for ix in ((0,1,2),(0,2,3)):raster_triangle(target,depth,p[list(ix)],q.uv[list(ix)],textures[q.texture])
    canvas.paste(Image.fromarray(target).convert('RGB'),(1050,115));d.rectangle((1050,115,1562,627),outline='#ab9480',width=2)
    d.text((1050,72),'真实物品栏变换，未重新适配大小',font=font,fill='#463c36')
    d.text((20,778),'实际安装模型、原纹理、生产Java坐标；网格不是游戏。离线QA，不代表已完成游戏内实测。',font=font,fill='#463c36')
    out={}
    for ext in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':92}));out[OUT/('红棕老电视_屏幕与物品栏QA.'+ext)]=b.getvalue()
    return out

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');args=p.parse_args()
    if args.write:p.error('Historical alpha10 reports are read-only; use the alpha11 checker for new output')
    report,model,textures,data,matrix=analyze()
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
if __name__=='__main__':main()
