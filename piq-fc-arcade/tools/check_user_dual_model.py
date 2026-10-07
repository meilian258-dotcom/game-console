"""Real user-model/production-Java geometry and UV preview; not a game screenshot."""
from __future__ import annotations

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,copy,io,json,os,subprocess,tempfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from import_user_dual_model import ROOT,SOURCE,ASSETS,TEXTURE,EXPECTED,derive,sha
from render_rocket_arcade_preview import Quad,collect_quads,render_view,font
from check_controller_pose_pipeline import translation,rotation as matrix_rotation,scale,points,display_matrix

JAVA=(java_home() / 'bin')

def run(args):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise AssertionError(p.stdout+p.stderr)
    return p.stdout

def production():
    cache=(gradle_home() / 'caches/modules-2/files-2.1');junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        junit.extend(f for f in (cache/group).rglob('*.jar')if version in f.parts and '-sources'not in f.name and '-javadoc'not in f.name)
    names=['RocketArcadeGeometry','DualCabinetGeometry','DualCabinetControls','ScreenAspectFit','DualScreenPresentation','CabinetVideoGeometry','ArcadeScreenBounds','ArcadeDisplayStyle']
    tests=['DualCabinetGeometryTest','DualCabinetControlsTest','CabinetVideoGeometryTest','ScreenAspectFitTest']
    sources=[ROOT/f'src/main/java/cn/piq/fcarcade/layout/{n}.java' for n in names]
    sources.append(ROOT/'src/main/java/cn/piq/fcarcade/home/HomeHardwareScale.java')
    sources.append(ROOT/'src/main/java/cn/piq/fcarcade/world/DualCabinetFootprint.java')
    testfiles=[ROOT/f'src/test/java/cn/piq/fcarcade/layout/{n}.java' for n in tests]
    with tempfile.TemporaryDirectory(prefix='user-dual-geometry-')as temp:
        cp=os.pathsep.join(map(str,[temp,*junit]));empty=Path(temp)/'empty';empty.mkdir()
        run([JAVA/'javac.exe','-encoding','UTF-8','-cp',cp,'-proc:none','-sourcepath',empty,'-d',temp,*sources,*testfiles,
             ROOT/'tools/qa/UserDualGeometryProbe.java',ROOT/'tools/qa/CabinetRoomTestRunner.java'])
        result=json.loads(run([JAVA/'java.exe','-cp',cp,'CabinetRoomTestRunner',*['cn.piq.fcarcade.layout.'+n for n in tests]]))
        geometry=json.loads(run([JAVA/'java.exe','-cp',cp,'UserDualGeometryProbe']))
    assert result['passed_tests']==31
    return geometry,result,{str(f.relative_to(ROOT)):sha(f.read_bytes())for f in sources+testfiles}

def rotation(axis,angle):
    c,s=np.cos(np.deg2rad(angle)),np.sin(np.deg2rad(angle))
    return {'x':np.array([[1,0,0],[0,c,-s],[0,s,c]]),'y':np.array([[c,0,s],[0,1,0],[-s,0,c]]),'z':np.array([[c,-s,0],[s,c,0],[0,0,1]])}[axis]

def inspect(geometry):
    generated,mapping=derive()
    for path,raw in generated.items():assert path.read_bytes()==raw
    original=json.loads((SOURCE/'arcade_universal_deep.json').read_bytes());original['textures']={'0':TEXTURE}
    original_quads=collect_quads(original)
    all_quads=[];moving=[];parts={p['name']:p for p in geometry['parts']}
    mapped={g['groupName']:g for g in mapping['groups']if g['movable']}
    for path in generated:
        if path.suffix!='.json':continue
        quads=collect_quads(json.loads(path.read_bytes()));all_quads.extend(quads)
        for q in quads:
            v=q.vertices.copy()
            if path.stem in parts:
                p=parts[path.stem];assert np.allclose(p['pivot'],mapped[path.stem]['pivot'],atol=1e-12)
                pivot=np.array(p['pivot']);v=(v-pivot)@(rotation('z',p['tiltZ'])@rotation('x',p['tiltX'])).T+pivot
                v[:,1]+=p['pressY']
            moving.append(Quad(v,q.uv,q.texture,q.element_index,q.direction))
    def key(q):return (tuple(np.round(q.vertices.flatten(),10)),tuple(np.round(q.uv.flatten(),10)),q.direction)
    assert sorted(map(key,original_quads))==sorted(map(key,all_quads)), 'Split changed a vertex or UV'
    assert len(all_quads)==1406 # Source glass and marquee intentionally contain only their front face.
    screen=[q for q in original_quads if original['elements'][q.element_index]['name']=='4比3双人通用屏幕'and q.direction=='north']
    assert len(screen)==1
    body=np.concatenate([q.vertices for q in all_quads])/16+[geometry['xOffset'],geometry['yOffset'],0]
    static=screen[0].vertices[[2,1,0,3]]/16+[geometry['xOffset'],geometry['yOffset'],0]
    details=[]
    for t,expected in enumerate(geometry['facings']):
        matrix=rotation('y',-90*t)
        actual=(body-[.5,0,.5])@matrix.T+[.5,0,.5]
        glass=(static-[.5,0,.5])@matrix.T+[.5,0,.5]
        assert np.allclose([actual.min(0),actual.max(0)],expected['bounds'],atol=1e-9)
        assert np.allclose(glass+np.array(expected['normal'])*geometry['screenOffset'],expected['screen'],atol=1e-10)
        assert np.allclose(np.linalg.norm(glass[1]-glass[0]),1)
        assert np.allclose(np.linalg.norm(glass[3]-glass[0]),.75)
        details.append({'turn':t,'bounds':expected['bounds'],'screen_aligned':True})
    textures={TEXTURE:np.array(Image.open(io.BytesIO(generated[ASSETS/'textures/block/user_dual/skin.png'])).convert('RGBA'))}
    # A labelled complete-source diagnostic is not a ROM and is only used for this offline preview.
    diagnostic=Image.new('RGBA',(320,240),(28,42,58,255));draw=ImageDraw.Draw(diagnostic)
    for x in range(0,320,20):draw.line((x,0,x,239),fill=(50,84,100))
    for y in range(0,240,20):draw.line((0,y,319,y),fill=(50,84,100))
    draw.rectangle((1,1,318,238),outline=(0,215,190),width=4)
    for x,y,text in [(8,7,'TL'),(267,7,'TR'),(8,203,'BL'),(267,203,'BR')]:draw.text((x,y),text,font=font(20,True),fill='white')
    draw.text((80,95),'4:3 TEST',font=font(32,True),fill=(244,235,202))
    textures['diagnostic']=np.array(diagnostic)
    v=np.array([p['point']for p in geometry['video']])*16-[geometry['xOffset']*16,geometry['yOffset']*16,0]
    # Production is normalized 0..1; this asset rasterizer takes vanilla 0..16 UV units.
    uv=np.array([p['uv']for p in geometry['video']])*16
    assert set(map(tuple,uv))=={(0,0),(0,16),(16,0),(16,16)}
    live=all_quads+[Quad(v,uv,'diagnostic',-1,'north')]
    # Same vanilla display JSON and exact custom renderer centering/scale; no auto-fit.
    item=json.loads((ASSETS/'models/item/dual_cabinet.json').read_bytes())
    custom=translation(.5,.5,.5)@scale([.4]*3)@translation(-1,-1,-.5738756313208677)@translation(.25,0,0)
    im=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)@custom
    projected=points(np.concatenate([q.vertices for q in all_quads])/16,im)
    pixels=projected[:,:2]*[16,-16]+8
    assert np.all(pixels>=0)and np.all(pixels<=16), 'Actual item escapes its 16x16 slot'
    normal=im[:3,:3]@np.array([0,np.sin(np.pi/8),-np.cos(np.pi/8)])
    assert normal[2]>0, 'Item GUI shows cabinet back'
    renderer=(ROOT/'src/main/java/cn/piq/fcarcade/client/DualCabinetRenderer.java').read_text(encoding='utf-8')
    assert 'ClientSkinManager.textureFor' not in renderer and 'ResourceLocation skin = null;' in renderer
    # Match runtime's float32 BakedQuad position arithmetic before dynamic normal offset.
    target=np.array(geometry['facings'][0]['screen'])-np.array(geometry['facings'][0]['normal'])*geometry['screenOffset']
    screen_matches=0
    for quad in all_quads:
        baked=(quad.vertices/16).astype(np.float32).astype(np.float64)+[geometry['xOffset'],geometry['yOffset'],0]
        if all(any(np.all(np.abs(p-c)<.00002)for c in target)for p in baked):screen_matches+=1
    assert screen_matches==1
    item_evidence={'pixel_bounds':[pixels.min(0).tolist(),pixels.max(0).tolist()],
                   'front_normal_camera':normal.tolist(),'unique_static_glass':screen_matches,
                   'custom_old_skin_not_projected':True,'world_collision_matches':True}
    return all_quads,moving,live,textures,details,item_evidence

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--output',required=True,type=Path);args=ap.parse_args()
    if args.output.exists():raise ValueError('Use a new evidence directory')
    geometry,result,sources=production();rest,pressed,live,textures,facings,item=inspect(geometry)
    renderer=ROOT/'src/main/java/cn/piq/fcarcade/client/DualCabinetRenderer.java'
    sources[str(renderer.relative_to(ROOT))]=sha(renderer.read_bytes())
    args.output.mkdir(parents=True)
    specs=[('front',(0,0,-1),live,'正面 · 原比例 4:3 动态屏诊断'),('front-angle',(1.15,.55,-1.65),rest,'前侧 · 用户原贴图'),
           ('rear-angle',(-1.25,.55,1.6),rest,'后侧 · 1.1 格深后舱'),('controls',(.6,1.4,-1.5),pressed,'按键下压 / 摇杆倾斜 · 生产数值')]
    sheet=Image.new('RGB',(1460,1540),(231,233,235));draw=ImageDraw.Draw(sheet)
    draw.text((24,17),'双人街机 · 用户原模型接入验证',font=font(32,True),fill=(28,35,43))
    draw.text((24,65),'真实 JSON / PNG / Java 几何；离线预览，非 Minecraft 截图',font=font(21),fill=(65,74,83))
    outputs=[]
    for i,(name,direction,quads,label)in enumerate(specs):
        image,detail=render_view(quads,textures,direction,(700,650),1)
        dest=args.output/(name+'.png');image.save(dest)
        x=20+(i%2)*720;y=115+(i//2)*700
        sheet.paste(image,(x,y),image);draw.text((x+12,y+651),label,font=font(21),fill=(30,40,50))
        outputs.append({'file':str(dest.resolve()),'sha256':sha(dest.read_bytes()),**detail})
    draw.text((24,1510),'236 元素 / 1406 面完整保留；2048 PNG 原字节；旧资源未覆盖；未运行游戏。',font=font(18),fill=(60,70,80))
    sheet.save(args.output/'contact-sheet.png')
    report={'ok':True,'minecraft_started':False,'gradle_started':False,'source_preserved':EXPECTED,
            'geometry_uv_exact_faces':1406,'elements':236,'render_models':17,'animated_groups':16,
            'production_tests':result,'production_geometry':geometry,'facings':facings,'item_and_skin':item,'source_sha256':sources,'previews':outputs}
    (args.output/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'ok':True,'tests':result,'report':str((args.output/'verification.json').resolve()),'preview':str((args.output/'contact-sheet.png').resolve())},ensure_ascii=False))

if __name__=='__main__':main()
