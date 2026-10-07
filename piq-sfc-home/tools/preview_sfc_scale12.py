"""Render actual post-load Java vertex transforms, never edits a model or PNG asset."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse, hashlib, json, os, subprocess, tempfile, sys, zipfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
import import_user_sfc_20260911 as base
from check_controller_pose_pipeline import display_matrix,translation,points
from build_tv_remote_model import render_gui
ROOT=base.ROOT/'piq-sfc-home'
JAVA=(java_home() / 'bin')
BASELINE=base.ROOT/'piq-fc-arcade/build/review-watch23-v1/piq_sfc-0.1.0-alpha.11.jar'

def derived_item_bytes(original,kind):
    """Exact resource diff: only GUI transform changes; all non-GUI fields retained."""
    item=json.loads(original)
    if kind=='cartridge':item['display']['gui']['scale']=[2.4]*3
    elif kind=='console':item['display']['gui']={'rotation':[30,225,0],'translation':[0.17252,3.57112,0],'scale':[0.669155]*3}
    else:raise ValueError(kind)
    return (json.dumps(item,ensure_ascii=False,separators=(',',':'))+'\n').encode('utf-8')

def actual_java():
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2'),('com.google.code.gson','2.10.1')]:
        deps.extend(f for f in (cache/group).rglob('*.jar') if version in f.parts and '-sources'not in f.name and '-javadoc'not in f.name)
    paths=[ROOT/'src/main/java/cn/piq/sfchome/client'/(n+'.java')for n in ('SfcHardwareMeshData','SfcButtonAnimation','SfcAvCableGeometry')]
    paths.append(ROOT/'src/main/java/cn/piq/sfchome/layout/SfcConsoleScale.java')
    tests=[ROOT/'src/test/java/cn/piq/sfchome/client'/(n+'.java')for n in ('SfcConsoleScaleTest','SfcAvCableGeometryTest')]
    probes=[ROOT/'tools/qa/SfcConsoleScaleProbe.java',base.ROOT/'piq-fc-arcade/tools/qa/DeviceUiTestRunner.java']
    def run(command):
        r=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
        if r.returncode:raise AssertionError(r.stdout+'\n'+r.stderr)
        return r.stdout
    with tempfile.TemporaryDirectory(prefix='sfc-scale12-java-')as folder:
        out=Path(folder)/'classes';out.mkdir();cp=os.pathsep.join(map(str,[out,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-cp',cp,'-d',out,*paths,*tests,*probes])
        actual=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcConsoleScaleProbe',base.ASSETS/'meshes/sfc_hardware.json']))
        tested=json.loads(run([JAVA/'java.exe','-cp',cp,'DeviceUiTestRunner','cn.piq.sfchome.client.SfcConsoleScaleTest','cn.piq.sfchome.client.SfcAvCableGeometryTest']))
    return actual,tested,{str(f.relative_to(base.ROOT)):base.sha(f.read_bytes()) for f in paths+tests+probes}

def quads(groups,names):
    out=[]
    for name in names:
        for part in groups[name]:
            v=np.array(part['vertices']).reshape(-1,3,8)
            for tri in v:out.append(base.Quad(tri[[0,1,2,2],:3]*16,tri[[0,1,2,2],3:5]*16,part['texture'],len(out),'mesh'))
    return out

def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--propose',action='store_true');args=p.parse_args()
    actual,tested,hashes=actual_java();names=['body','p1_docked','p2_docked','inserted']
    tex={}
    for group in actual['before'].values():
        for part in group:
            resource=part['texture'];tex[resource]=np.asarray(Image.open(base.ASSETS/resource.split(':')[1]).convert('RGBA'))
    olditems={}
    with zipfile.ZipFile(BASELINE)as jar:
        for name in ('console','cartridge'):olditems[name]=jar.read('assets/piq_sfc_home/models/item/'+name+'.json')
    console=quads(actual['after'],names[:-1]);verts=np.concatenate([q.vertices for q in console])/16
    gui=json.loads(olditems['console'])['display']['gui'];raw=dict(gui);raw['translation']=[0,0,0]
    xy=points(verts,display_matrix(raw,False)@translation(-.5,-.5,-.5))[:,:2]
    size=round(.81*.875/max(xy.max(0)-xy.min(0)),6);raw['scale']=[size]*3
    xy=points(verts,display_matrix(raw,False)@translation(-.5,-.5,-.5))[:,:2]
    raw['translation']=[round(float(-8*(xy.max(0)[i]+xy.min(0)[i])),5)for i in range(2)]+[0]
    if args.propose:print(json.dumps({'tests':tested,'proposed_console_gui':raw},ensure_ascii=False));return
    out=ROOT/'design/user-sfc-scale12';out.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    before=quads(actual['before'],names);after=quads(actual['after'],names)
    allv=np.concatenate([q.vertices for q in before+after]);lo=allv.min(0);hi=allv.max(0)
    # Transparent fitting corners impose the identical camera scale on before and after.
    tex['fit']=np.zeros((1,1,4),dtype=np.uint8)
    fit=base.Quad(np.array([[lo[0],lo[1],lo[2]],[hi[0],lo[1],lo[2]],[hi[0],hi[1],hi[2]],[lo[0],hi[1],hi[2]]]),np.zeros((4,2)),'fit',-1,'fit')
    page=Image.new('RGB',(1600,1000),'#e1e7eb');draw=ImageDraw.Draw(page)
    for row,direction in enumerate(((1,1.15,-1.65),(-1,1.05,1.5))):
        for col,(label,qs)in enumerate((('修正前 · 主机偏小',before),('修正后 · 主机与插卡 1.5× / 手柄原尺寸',after))):
            image,_=base.render_view(qs+[fit],tex,direction,size=(780,420),supersample=2);page.paste(image,(10+col*800,60+row*480),image)
            draw.text((20+col*800,20+row*480),label,font=font,fill='#29333d')
    draw.text((20,972),'实际生产 Java 顶点变换 + 原始 UV · 同比例对照 · 离线预览，不是 Minecraft 截图',font=small,fill='#495865')
    page.save(out/'console-before-after.png')
    icons=Image.new('RGB',(1600,530),'#e1e7eb');d=ImageDraw.Draw(icons);gui_bounds={};held={}
    for index,(name,version)in enumerate((('console','before'),('console','after'),('cartridge','before'),('cartridge','after'))):
        item=json.loads(olditems[name] if version=='before' else (base.ASSETS/f'models/item/{name}.json').read_bytes())
        layers=['body','p1_docked','p2_docked','slot_cover']if name=='console'else['cartridge']
        qs=quads(actual[version],layers);display=item['display']['gui'];matrix=display_matrix(display,False)@translation(-.5,-.5,-.5)
        xy=points(np.concatenate([q.vertices for q in qs])/16,matrix)[:,:2]
        if version=='after':assert np.max(np.abs(xy))<.5,(name,xy.min(0),xy.max(0))
        gui_bounds[name+'_'+version]={'display':display,'min':xy.min(0).tolist(),'max':xy.max(0).tolist()}
        icon=render_gui(qs,tex,matrix,size=380);icons.paste(icon,(index*400+10,50),icon);d.text((index*400+20,20),name+' '+version,font=font,fill='#29333d')
        if version=='after':
            for context,transform in item['display'].items():
                world=points(np.concatenate([q.vertices for q in qs])/16,display_matrix(transform,False)@translation(-.5,-.5,-.5));assert np.isfinite(world).all()and np.max(np.abs(world))<3
                held[name+'/'+context]=[world.min(0).tolist(),world.max(0).tolist()]
    d.text((20,475),'独立卡只改 GUI 1.22 → 2.4；手持/掉落/固定展示的卡带变换不动',font=font,fill='#29333d');icons.save(out/'item-gui-before-after.png')
    report={'ok':True,'tests':tested,'source_sha256':hashes,'gui_bounds':gui_bounds,'other_item_bounds':held,'body_bounds':actual['body_bounds'],'inserted_bounds':actual['inserted_bounds'],'render_bounds':actual['render_bounds'],
            'mesh_sha256':base.sha((base.ASSETS/'meshes/sfc_hardware.json').read_bytes()),'minecraft_started':False,'new_bitmap_assets':False}
    (out/'actual-java-geometry.json').write_text(json.dumps(actual,separators=(',',':')),encoding='utf-8')
    (out/'scale12-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
