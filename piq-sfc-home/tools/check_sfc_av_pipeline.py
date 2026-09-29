"""Actual Java SFC AV mesh, six public FC television endpoints, offline geometry QA."""
from __future__ import annotations
import argparse,io,json,re,subprocess,sys,tempfile
from pathlib import Path
from import_sfc_models import PROJECT,WORKSPACE,ASSETS,sha,encoded
FC=WORKSPACE/'piq-fc-arcade';sys.path.insert(0,str(FC/'tools'))
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from render_rocket_arcade_preview import Quad,collect_quads,render_view
from import_subor_hardware import write_new
from check_av_smooth_mesh import SOURCES as FC_SOURCES

JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
JAVA=PROJECT/'src/main/java/cn/piq/sfchome/client/SfcAvCableGeometry.java'
PROBE=PROJECT/'tools/qa/SfcAvCableProbe.java'
SOURCES=FC_SOURCES+[
    FC/'src/main/java/cn/piq/fcarcade/home/LcdTvLayout.java',FC/'src/main/java/cn/piq/fcarcade/home/WideLcdTvLayout.java']
OUT=WORKSPACE/'制作Mod/03-街机模拟/PIQ-FC街机/SFC附属首版模型草案/附属AV连线QA-v2'

def compile_probe(folder):
    p=subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',str(folder),*[str(p) for p in SOURCES],str(JAVA),str(PROBE)],capture_output=True,text=True,timeout=30)
    if p.returncode:raise RuntimeError(p.stderr)
def run(folder,args):
    p=subprocess.run([str(JDK/'java.exe'),'-Xmx768m','-cp',str(folder),'SfcAvCableProbe',*map(str,args)],capture_output=True,text=True,timeout=60)
    if p.returncode:raise RuntimeError(p.stdout+'\n'+p.stderr)
    return p.stdout

def triangle_box(tri,box):
    # Independent numpy SAT; touching the exterior is allowed, crossing it is not.
    b=np.asarray(box,dtype=float);lo=b[:3]+1e-7;hi=b[3:]-1e-7;t=np.asarray(tri)-(lo+hi)/2;half=(hi-lo)/2
    edge=np.roll(t,-1,axis=0)-t;axes=[*np.eye(3),np.cross(edge[0],edge[1])]+[np.cross(e,axis) for e in edge for axis in np.eye(3)]
    for axis in axes:
        if np.linalg.norm(axis)<1e-10:continue
        dots=t@axis;radius=np.abs(axis)@half
        if dots.min()>radius or dots.max()< -radius:return False
    return True

def metrics(data):
    if not data['visible']:return {'ok':False,'rejection':data['rejection']}
    vertices=np.array([q[2:6] for q in data['quads']]);bad=[];normals=[];colors={};plug_centers=[]
    if not len(vertices) or not np.isfinite(vertices).all():return {'ok':False,'rejection':'nonfinite-or-empty-mesh'}
    for q in data['quads']:
        part,color=q[:2];v=np.array(q[2:6]);n=np.array(q[6]);normals.append(np.linalg.norm(n));colors[color]=colors.get(color,0)+1
        for endpoint in ('console','tv'):
            if part.startswith(endpoint+'-plug-') or part.startswith(endpoint+'-axial-'):continue
            if triangle_box(v[[0,1,2]],data[endpoint+'_box']) or triangle_box(v[[0,2,3]],data[endpoint+'_box']):bad.append((part,endpoint))
    for endpoint in ('console','tv'):
        for c,socket in enumerate(data[endpoint+'_sockets']):
            faces=[q for q in data['quads'] if q[0]==endpoint+'-plug-'+str(c)][:8]
            ring=np.array([q[2] for q in faces]);plug_centers.append(float(np.linalg.norm(ring.mean(0)-socket)))
    table=bool(np.allclose(np.array(data['trunk'])[:,1],.022,atol=1e-10))
    plugs=all(colors.get(c)==16 for c in (0xF2BF32,0xFFFFF6,0xC63831))
    return {'ok':bool(not bad and vertices[:,:,1].min()>=-1e-8 and max(plug_centers)<1e-8 and np.isfinite(normals).all() and max(abs(np.array(normals)-1))<1e-8 and table and plugs),
      'quads':len(data['quads']),'min_y':float(vertices[:,:,1].min()),'max_socket_error':max(plug_centers),'bad_triangle_housing_contacts':bad[:20],
      'constant_table_trunk':table,'six_colored_plugs':plugs}

def wiring_checks(renderer,hardware):
    hardware=re.sub(r'//[^\n]*|/\*.*?\*/','',hardware,flags=re.S)
    return [
      {'name':'AV validates loaded reciprocal link and complete hardware only','ok':all(t in renderer for t in ('level.hasChunkAt(pos)','level.getBlockEntity(console.getBlockPos())!=console','console.isHardwareComplete()','console.getBlockPos().equals(tv.consolePos())','console.linkId().equals(tv.linkId())','HomeTvStructure.complete(level,pos)'))},
      {'name':'AV reuses public actual TV sockets and bounds with all six styles','ok':all(t in renderer for t in ('HomeAvCableMesh.tvSockets','HomeTvStructure.centered(state),lcd,wide,large,vintage,tvTurns','HomeHardwareRenderer.tvRenderBounds(to,state).move(-from.getX(),-from.getY(),-from.getZ())'))},
      {'name':'AV keeps weak endpoint cache and static old solid texture','ok':all(t in renderer for t in ('new WeakHashMap<>()','!prior.key.equals(key)','SfcAvCableGeometry.build(key.source,key.target)','textures/block/home_retro_tv_white.png','cache.remove(console)'))},
      {'name':'AV is drawn after hardware yaw pop with full connection bounds','ok':bool(re.search(r'finally\s*\{\s*poses\.popPose\(\);\s*\}\s*avCable\.render\(console, poses\.last\(\), buffers, light, overlay\)',hardware)) and 'avCable.bounds(console)' in hardware},
    ]

def analyze():
    renderer=JAVA.with_name('SfcAvCableRenderer.java');hardware=JAVA.with_name('SfcHardwareRenderer.java')
    snapshots={str(p):sha(p.read_bytes()) for p in SOURCES+[JAVA,PROBE,renderer,hardware]};checks=wiring_checks(renderer.read_text(encoding='utf-8'),hardware.read_text(encoding='utf-8'));samples={};summary=[]
    with tempfile.TemporaryDirectory(prefix='piq-sfc-av-') as temp:
        folder=Path(temp);compile_probe(folder);matrix=run(folder,[]).strip();edge=run(folder,['--edge']).strip()
        for kind in range(6):
            data=json.loads(run(folder,[kind,0,0,-4,0,0]));result=metrics(data);summary.append({'tv':kind,**result});samples[kind]=data
            checks.append({'name':'TV '+str(kind)+' actual triangles endpoints and tabletop','ok':result['ok'] and result['constant_table_trunk'] and result['six_colored_plugs']})
    checks.append({'name':'384 separated placements all visible finite and table-height','ok':'good=384 blocked=0 bad=0' in matrix})
    checks.append({'name':'960 near overlapping or mixed-height cases fail closed or remain valid','ok':'EDGE total=960' in edge and 'bad=0' in edge})
    checks.append({'name':'source unchanged throughout audit','ok':all(sha(Path(p).read_bytes())==h for p,h in snapshots.items())})
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'matrix':matrix,'edge_matrix':edge,'source_sha256':snapshots,'samples':summary,
      'limits':['Only the two known hardware housings are avoided; no arbitrary block pathfinding.','Equal block-base heights infer a tabletop at base+0.022; unequal bases form a conservative bridge above the higher base, not terrain following.',
      'Only the short axial socket exit can traverse its own conservative envelope (LCD feet extend behind its socket panel). All drops/trunk triangles avoid both full housing envelopes.',
      'Actual Java eight-sided mesh and independent numpy triangle-box SAT, offline preview; not Minecraft gameplay.']},samples

def preview(data):
    # Selected old CRT and actual installed SFC assembly share one constant-scale scene.
    tex={};objects=[]
    models=[(ASSETS/'models/block/sfc_console_inventory.json',np.array([0,0,0])),
      (FC/'src/main/resources/assets/piq_fc_arcade/models/block/home_retro_tv.json',np.array([-64,0,0]))]
    mc=Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar')
    import zipfile
    with zipfile.ZipFile(mc) as jar:
        for path,offset in models:
            for q in collect_quads(json.loads(path.read_bytes())):
                objects.append(Quad(q.vertices+offset,q.uv,q.texture,q.element_index,q.direction))
                if q.texture in tex:continue
                namespace,name=q.texture.split(':')
                raw=jar.read('assets/minecraft/textures/'+name+'.png') if namespace=='minecraft' else (FC/'src/main/resources/assets/piq_fc_arcade/textures'/ (name+'.png')).read_bytes()
                tex[q.texture]=np.array(Image.open(io.BytesIO(raw)).convert('RGBA'))
    cable=[]
    # Existing white solid texel modulates colors in the actual renderer.
    white=np.array([189,189,176],float)/255
    for i,q in enumerate(data['quads']):
        name='cable:'+str(q[1]);rgb=np.array([(q[1]>>16)&255,(q[1]>>8)&255,q[1]&255]);tex[name]=np.array([[list(np.round(rgb*white).astype(int))+[255]]],np.uint8)
        cable.append(Quad(np.array(q[2:6])*16,np.zeros((4,2)),name,i,'reference'))
    tex['qa:table']=np.array([[[124,131,130,255]]],np.uint8)
    table=Quad(np.array([[-75,-.02,-7],[-75,-.02,53],[25,-.02,53],[25,-.02,-7]]),np.zeros((4,2)),'qa:table',-1,'reference')
    sheet=Image.new('RGB',(1440,1400),'#e7ebeb');d=ImageDraw.Draw(sheet);title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',28);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
    d.text((24,16),'SFC 附属 AV · 实际模型 / Java 八边实体管面',font=title,fill='#263640')
    for i,(label,direction) in enumerate((('后上方：两端三色头与桌面主线',(.4,.85,1.35)),('桌面低角度：连接端下垂，主线贴桌',(0,.12,1)))):
        image,_=render_view(objects+cable+[table],tex,direction,size=(1380,555),supersample=2);sheet.paste(image,(25,80+i*635),image);d.text((25,648+i*635),label,font=font,fill='#384852')
    d.text((24,1355),'只避让两台设备；同高桌面，无额外寻路/假进度。直接读取工程模型和真实 Java 管面，非游戏截图。',font=font,fill='#52616b')
    out={}
    for ext in ('png','jpg'):
        raw=io.BytesIO();sheet.save(raw,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':91}));out[OUT/('SFC_AV实际模型连线.'+ext)]=raw.getvalue()
    return out

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');a=p.parse_args();report,samples=analyze();print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
    if a.write:
        out=preview(samples[0]);out[OUT/'java-mesh-crt.json']=encoded(samples[0]);report['output_sha256']={str(k):sha(v) for k,v in out.items()};out[OUT/'sfc-av-pipeline.json']=encoded(report);write_new(out)
if __name__=='__main__':main()
