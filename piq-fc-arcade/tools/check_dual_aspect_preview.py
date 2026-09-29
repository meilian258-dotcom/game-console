"""Execute actual Java aspect frames and draw them on the real exported cabinet.

The diagnostic grid is not a ROM or gameplay screenshot. No fitting formula is
used by this tool: every displayed corner comes from DualScreenPresentation.
"""
from __future__ import annotations
import argparse
import io
import json
import re
import subprocess
import tempfile
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_dual_arcade_model import MODEL,TEXTURE,OUT,world_quads,sha,encoded,write_new
from check_dual_render_pipeline import JDK,JAVA,ROOT,PATHS,probe_geometry,screen_matches
from render_rocket_arcade_preview import Quad,collect_quads,render_view

JAVA_FILES=[JAVA/'layout'/name for name in ('RocketArcadeGeometry.java','DualCabinetGeometry.java','ScreenAspectFit.java','DualScreenPresentation.java')]


def probe():
    with tempfile.TemporaryDirectory(prefix='piq-dual-aspect-') as temp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',temp,*map(str,JAVA_FILES),str(ROOT/'tools/qa/DualScreenPresentationProbe.java')],check=True,capture_output=True,timeout=30)
        output=subprocess.run([str(JDK/'java.exe'),'-cp',temp,'DualScreenPresentationProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    frames=[]
    for line in output.splitlines():
        pieces=line.split();values=np.array([float(v) for v in pieces[3:-1]])
        frames.append({'aspect':pieces[1],'turn':int(pieces[2]),'frame':values[:12].reshape(4,3),'normal':values[12:15],'cached':pieces[-1]=='true'})
    if len(frames)!=12:raise ValueError('Production frame inventory incomplete')
    return frames,output


def analyze():
    frames,output=probe();physical=probe_geometry();model=json.loads(MODEL.read_bytes());checks=[];details=[]
    for result in frames:
        f=result['frame'];p=physical['turns'][result['turn']]['screen'];n=result['normal']
        ratio=np.linalg.norm(f[1]-f[0])/np.linalg.norm(f[3]-f[0]);numerator,denominator=map(float,result['aspect'].split(':'))
        center=(f[0]+f[2])/2;real_center=(p[0]+p[2])/2
        u=p[1]-p[0];u/=np.linalg.norm(u);v=p[3]-p[0];v/=np.linalg.norm(v)
        coordinates=np.column_stack(((f-p[0])@u,(f-p[0])@v))
        physical_size=np.array([np.linalg.norm(p[1]-p[0]),np.linalg.norm(p[3]-p[0])])
        contained=bool(((coordinates>=-1e-10)&(coordinates<=physical_size+1e-10)).all())
        ok=abs(ratio-numerator/denominator)<1e-10 and np.allclose(center,real_center,atol=1e-12) and contained and np.allclose(n,physical['turns'][result['turn']]['normal']) and result['cached']
        checks.append({'name':result['aspect']+' turn '+str(result['turn'])+' uses complete centered cached frame','ok':bool(ok)})
        details.append({'aspect':result['aspect'],'turn':result['turn'],'frame_blocks':f.tolist(),'normal':n.tolist(),
                        'actual_aspect':float(ratio),'left_black_bar_model_units':float(coordinates[:,0].min()*16),
                        'right_black_bar_model_units':float((physical_size[0]-coordinates[:,0].max())*16)})
    screen_source=re.sub(r'\s+','',PATHS['screen_renderer'].read_text(encoding='utf-8'))
    branch=re.search(r'if\(displayStyle==cn\.piq\.fcarcade\.layout\.ArcadeDisplayStyle\.DUAL_CABINET(?:\|\|displayStyle==cn\.piq\.fcarcade\.layout\.ArcadeDisplayStyle\.HOME_(?:WIDE_LCD_TV|LARGE_LCD_TV|VINTAGE_TV))*\)\{(.*?)return;\}',screen_source)
    branch=branch.group(1) if branch else ''
    checks.append({'name':'runtime uses actual frame helper and full 0..1 source UVs','ok':all(x in branch for x in (
        'DualScreenPresentation.frame(','ClientArcadeEvents.dualScreenAspect()',
        'rocketVertex(consumer,pose,quad.lowerMaxX(),quad.normal(),0,1);','rocketVertex(consumer,pose,quad.lowerMinX(),quad.normal(),1,1);',
        'rocketVertex(consumer,pose,quad.upperMinX(),quad.normal(),1,0);','rocketVertex(consumer,pose,quad.upperMaxX(),quad.normal(),0,0);'))})
    matches=screen_matches(collect_quads(model),physical);renderer=re.sub(r'\s+','',PATHS['renderer'].read_text(encoding='utf-8'))
    atlas=np.array(Image.open(TEXTURE).convert('RGBA'));uv=np.array(model['elements'][47]['faces']['north']['uv'])*128
    pixels=atlas[int(np.ceil(uv[1])):int(np.floor(uv[3])),int(np.ceil(uv[0])):int(np.floor(uv[2]))]
    checks.append({'name':'custom-skin glass exclusion identifies unique baked black quad','ok':len(matches)==1 and collect_quads(model)[matches[0]].element_index==47 and 'custom!=null&&!face.screen()' in renderer and bool(np.all(pixels==[0,0,0,255]))})
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'frames':details,'production_stdout':output,
            'model_sha256':sha(MODEL.read_bytes()),'production_sha256':{str(p):sha(p.read_bytes()) for p in JAVA_FILES},
            'limits':['Actual Java production frame calls, not an independently reimplemented fit algorithm',
                      'All displayed grids use the complete 0..1 UV range; colors/numbers are diagnostic QA graphics, not ROM content',
                      'Static-black glass custom-skin exclusion is verified through source and unique float32 baked-corner matching, not a live custom-skin test',
                      'Offline actual-model rasterization, not Minecraft execution']},model,frames


def diagnostic(aspect):
    width={'16:9':320,'4:3':240,'1:1':180}[aspect];height=180
    grid=Image.new('RGBA',(width,height),'#355872');d=ImageDraw.Draw(grid)
    for row in range(6):
        for col in range(8):
            lo=(col*width//8,row*height//6);hi=((col+1)*width//8-1,(row+1)*height//6-1)
            d.rectangle((*lo,*hi),fill=('#30566b' if (row+col)%2 else '#477c8d'),outline='#8bbaa9')
    font=ImageFont.truetype('C:/Windows/Fonts/consolab.ttf',17)
    for pos,text in (((4,3),'TL'),((width-27,3),'TR'),((4,height-22),'BL'),((width-27,height-22),'BR')):d.text(pos,text,font=font,fill='#fff0c0')
    d.rectangle((0,0,width-1,height-1),outline='#ffc54a',width=3)
    d.ellipse((width/2-32,height/2-32,width/2+32,height/2+32),outline='#ffc54a',width=3)
    d.text((width/2-29,height/2-10),aspect,font=font,fill='#fff0c0')
    return np.array(grid)


def preview(model,frames):
    atlas=np.array(Image.open(TEXTURE).convert('RGBA'));base=world_quads(model)
    canvas=Image.new('RGB',(1860,940),'#19222c');d=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',28);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
    d.text((22,16),'真实Java比例切换 · 16:9物理屏 / 完整网格与对称黑边',font=title,fill='#eff7ff')
    for index,aspect in enumerate(('16:9','4:3','1:1')):
        data=next(f for f in frames if f['aspect']==aspect and f['turn']==0)
        q=Quad(data['frame'][[2,1,0,3]]*16,np.array([[0,0],[0,16],[16,16],[16,0]]),'qa',-1,'dynamic')
        pic,_=render_view([*base,q],{'piq_fc_arcade:block/rocket_arcade_skin':atlas,'qa':diagnostic(aspect)},(0,.15,-1),size=(600,740),supersample=2)
        x=10+620*index;canvas.paste(pic.convert('RGB'),(x,103));d.text((x+15,67),aspect+' · Java实际输出顶点',font=title,fill='#dfedf5')
        bars={'16:9':'左右无黑边','4:3':'左右各3模型单位（每边12.5%）','1:1':'左右各5.25模型单位（每边21.875%）'}
        d.text((x+14,851),bars[aspect],font=font,fill='#b8cdda')
    d.text((22,903),'TL/TR/BL/BR全部可见，无裁剪；网格是QA画面而非游戏。使用实际模型、原贴图和生产缓存矩阵，非Minecraft截图。',font=font,fill='#b8cdda')
    outputs={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        outputs['双人屏幕比例_真实Java矩阵QA.'+suffix]=b.getvalue()
    return outputs


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    report,model,frames=analyze()
    if args.write:
        outputs={OUT/name:data for name,data in preview(model,frames).items()};report['preview_sha256']={str(p):sha(data) for p,data in outputs.items()}
        outputs[OUT/'aspect-presentation-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
