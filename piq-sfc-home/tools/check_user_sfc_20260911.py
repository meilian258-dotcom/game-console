"""Compile only actual pure Java model classes; render their real pose/animation.

Does not compile Minecraft or start a game; hand volumes are diagnostic stand-ins.
"""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import json,os,subprocess,tempfile,itertools,sys
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
import import_user_sfc_20260911 as build
from check_controller_pose_pipeline import translation,rotation,scale,points,projected_bounds
from check_controller_slanted_pose import raster,FACES,hit,arm_triangles
from check_sfc_hardware_mesh import arm_vertices
from build_tv_remote_model import render_gui
from check_controller_pose_pipeline import display_matrix
ROOT=build.ROOT/'piq-sfc-home'; JAVA=(java_home() / 'bin')

def java():
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2'),('com.google.code.gson','2.10.1')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    def run(cmd):
        p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
        if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
        return p.stdout
    with tempfile.TemporaryDirectory(prefix='sfc-user-model-') as tmp:
        classes=Path(tmp)/'classes';classes.mkdir();empty=Path(tmp)/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        names=('SfcHardwareMeshData','SfcControllerPoseLayout','SfcButtonAnimation','SfcCoverGeometry','SfcAvCableGeometry')
        prod=[ROOT/'src/main/java/cn/piq/sfchome/client'/(n+'.java') for n in names]
        tests=[ROOT/'src/test/java/cn/piq/sfchome/client'/(n+'.java') for n in ('SfcHardwareMeshDataTest','SfcButtonAnimationTest','SfcCoverGeometryTest','SfcAvCableGeometryTest')]
        probes=[ROOT/'tools/qa'/(n+'.java') for n in ('SfcHardwareMeshProbe','SfcUserModelProbe','SfcUserModelTestRunner')]
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*prod,*tests,*probes])
        asset=build.ASSETS/'meshes/sfc_hardware.json'
        pose=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcHardwareMeshProbe',asset]))
        data=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcUserModelProbe',asset]))
        junit=run([JAVA/'java.exe','-cp',cp,'SfcUserModelTestRunner'])
    return pose,data,junit,{str(p.relative_to(build.ROOT)):build.sha(p.read_bytes()) for p in prod}

def animation(part,actual,mask):
    if part['name'] not in actual['controls']:return np.eye(4)
    control=actual['controls'][part['name']];b=control['binding'];t=control['states'][str(mask)]
    return translation(b['x']*16,b['y']*16+t['y']*16,b['z']*16)@rotation('x',t['pitch'])@rotation('z',t['roll'])@translation(-b['x']*16,-b['y']*16,-b['z']*16)

def main():
    doc,audit=build.build();actualraw=(build.ASSETS/'meshes/sfc_hardware.json').read_bytes()
    assert json.loads(actualraw)==doc,'asset does not match deterministic converter'
    assert (build.ASSETS/'models/item/console.json').read_bytes()==build.console_item_bytes(),'item GUI does not match pure wrapper'
    pose,data,junit,hashes=java();audit.update({'junit_tests':30,'junit_output':junit,'actual_java_source_sha256':hashes,'mesh_sha256':build.sha(actualraw)})
    tex={resource:np.asarray(Image.open(path).convert('RGBA')) for resource,path in build.TEXTURES.values()}
    unit=rotation('x',90)@rotation('y',180)@scale([pose['controller_scale']/16]*3)@translation(-8,-8,-8)
    verts=np.concatenate([np.array(t['p']) for p in doc['groups']['controller']['parts'] for t in p['triangles']]);caps=[]
    for p in doc['groups']['controller']['parts']:
        if 'motion' in p:
            cap=np.array(p['pivot']);cap[1]=max(v[1] for t in p['triangles'] for v in t['p'])+.001;caps.append((p,cap))
    scenarios=[];occluded=[]
    for swing,(y,z,pitch) in pose['rigs'].items():
        rig=translation(0,y,z)@rotation('x',pitch)
        for fov,aspect in itertools.product((60,70),(4/3,16/9)):
            ndc,b=projected_bounds(points(verts,rig@unit),fov,aspect);assert np.max(np.abs(ndc))<1,(swing,b)
            scenarios.append({'swing':swing,'fov':fov,'aspect':aspect,'screen_ltrb':b})
        for slim,sleeve in itertools.product((False,True),(False,True)):
            arms=[arm_vertices(pose['arms']['right' if r else 'left'],r,slim,sleeve,rig) for r in (True,False)]
            triangles=[t for a in arms for t in arm_triangles(a)]
            for p,c in caps:
                target=points(np.array([c]),rig@unit)[0]
                if any(hit(target,t) for t in triangles):occluded.append([swing,slim,sleeve,p['motion']])
    assert not occluded,('hand obscures user cap',occluded)
    audit['perspective_scenarios']=scenarios;audit['unobstructed_center_rays']=len(caps)*7*4
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25)
    y,z,pitch=pose['rigs']['0.0'];rig=translation(0,y,z)@rotation('x',pitch)
    for mask in (0,1296):
        canvas=np.full((1080,1920,4),[225,231,235,255],dtype=np.uint8);depth=np.full((1080,1920),-np.inf)
        for p in doc['groups']['controller']['parts']:
            transform=rig@unit@animation(p,data,mask)
            for t in p['triangles']:raster(canvas,depth,points(np.array(t['p']),transform),np.array(t['uv']),tex[doc['materials'][p['material']]])
        for right in (True,False):
            arm=arm_vertices(pose['arms']['right' if right else 'left'],right,False,True,rig)
            for i,face in enumerate(FACES):
                texture=np.full((2,2,4),[186,141,105,255] if i==5 else [88,108,137,255],dtype=np.uint8);uv=np.array([[0,0],[0,1],[1,1],[1,0]])
                for tri in ((0,1,2),(0,2,3)):raster(canvas,depth,arm[list(face)][list(tri)],uv[list(tri)],texture)
        image=Image.fromarray(canvas).convert('RGB');d=ImageDraw.Draw(image)
        d.text((28,24),'用户 SFC 手柄 · 实际 Java 姿势 / 按钮变换 + 原 UV 网格',font=font,fill='#29333d')
        d.text((28,66),('松开' if mask==0 else '按下 A + 上 + L')+' · 手臂为原版尺寸示意，不是真实游戏截图',font=font,fill='#495865')
        image.save(build.OUT/f'grip-{mask}.png');image.crop((490,730,1430,1080)).resize((1880,700)).save(build.OUT/f'grip-detail-{mask}.png')
    # Actual Java AV triangles, with user console as spatial reference; TV is a
    # diagnostic port fixture, not a copied game model or a networking test.
    qs=build.quads(doc,['body','p1_docked','p2_docked','inserted']);textures=dict(tex)
    for q in data['av']['quads']:
        color=q['color'];key='diagnostic:'+str(color);textures[key]=np.array([[[color>>16&255,color>>8&255,color&255,255]]],dtype=np.uint8)
        p=np.array([[q[k][a] for a in ('x','y','z')] for k in ('a','b','c','d')])*16
        qs.append(build.Quad(p,np.full((4,2),8.),key,len(qs),'av'))
    im,_=build.render_view(qs,textures,(-1,1,1.5),size=(1400,600),supersample=2)
    avpage=Image.new('RGB',(1440,670),'#e1e7eb');avpage.paste(im,(20,45),im)
    ImageDraw.Draw(avpage).text((20,12),'实际 Java 动态线 · 主机 MULTI OUT / 电视端 RCA 诊断锚点（非游戏截图）',font=font,fill='#29333d')
    avpage.save(build.OUT/'multi-out-av.png')
    icons=Image.new('RGB',(1500,550),'#e1e7eb');icon_draw=ImageDraw.Draw(icons);audit['item_gui']={}
    for i,(name,layers) in enumerate({'console':['body','p1_docked','p2_docked','slot_cover'],'controller':['controller'],'cartridge':['cartridge']}.items()):
        item=json.loads((build.ASSETS/f'models/item/{name}.json').read_bytes());display=item.get('display',{}).get('gui')
        if display is None:
            namespace,parent=item['parent'].split(':');assert namespace=='piq_sfc_home'
            display=json.loads((build.ASSETS/f'models/{parent}.json').read_bytes())['display']['gui']
        matrix=display_matrix(display,False)@translation(-.5,-.5,-.5);qs=build.quads(doc,layers)
        xy=points(np.concatenate([q.vertices for q in qs])/16,matrix)[:,:2];assert np.max(np.abs(xy))<.5,(name,'GUI clipping',xy.min(0),xy.max(0))
        audit['item_gui'][name]={'display':display,'xy_bounds':[xy.min(0).tolist(),xy.max(0).tolist()]}
        icon=render_gui(qs,tex,matrix,size=480);icons.paste(icon,(i*500+10,10),icon);icon_draw.text((i*500+20,500),name+' · 游戏内物品变换',font=font,fill='#29333d')
    icons.save(build.OUT/'item-gui.png')
    audit['av_quads']=len(data['av']['quads']);audit['limits']=['Pure production Java + actual mesh/UV, no Minecraft launched.','SFC input visual getter is read-only; no networking changes.','Hand volumes are diagnostic standard/slim models, not player screenshots.']
    (build.OUT/'java-pose-animation-audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'ok':True,'tests':30,'clear_key_rays':audit['unobstructed_center_rays'],'av_quads':audit['av_quads'],'report':str(build.OUT/'java-pose-animation-audit.json')}))
if __name__=='__main__':main()
