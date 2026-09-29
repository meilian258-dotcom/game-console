"""Actual production geometry/input tests, real FML enum transform and raw-UV offline preview.
No Minecraft window, user world, ROM, install, Gradle, or full-project build.
"""
import argparse,io,json,os,shutil,tempfile,zipfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
import verify_retro_alpha19 as q
from import_zapper_item26 import production,ASSETS
from render_rocket_arcade_preview import collect_quads,Quad,render_view,font,raster_triangle
from check_controller_pose_pipeline import translation,rotation,scale,points
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'build/review-controls25-v1/piq_fc_arcade-0.31.0-alpha.25.jar'

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--fc',type=Path,help='Final JAR: compile tests/probes only, never production sources');args=parser.parse_args()
    assert not args.output.exists(),'New independent evidence directory required'
    assets=production()
    for path,raw in assets.items():assert (ASSETS/path).read_bytes()==raw
    names=['cn.piq.fcarcade.layout.ZapperPoseLayoutTest','cn.piq.fcarcade.layout.ZapperAimGeometryTest','cn.piq.fcarcade.client.zapper.ZapperInputStateTest']
    sources=[ROOT/'src/main/java'/Path(n.replace('.','/')+'.java')for n in ('cn.piq.fcarcade.layout.ZapperPoseLayout','cn.piq.fcarcade.layout.ZapperAimGeometry','cn.piq.fcarcade.client.zapper.ZapperInputState')]
    tests=[ROOT/'src/test/java'/Path(n.replace('.','/')+'.java')for n in names]
    enum=ROOT/'src/main/resources/META-INF/piq-fc-controller-enumextensions.json'
    config_bytes=enum.read_bytes();final_hash=None;final_path=None
    if args.fc:
        final_path=args.fc.resolve();assert final_path.is_file();final_hash=q.digest(final_path.read_bytes())
        with zipfile.ZipFile(final_path)as jar:
            assert jar.testzip()is None and len(jar.namelist())==len(set(jar.namelist()))
            for path,raw in assets.items():assert jar.read('assets/piq_fc_arcade/'+path)==raw,path
            config_bytes=jar.read('META-INF/piq-fc-controller-enumextensions.json')
            assert config_bytes==enum.read_bytes(),'Final enum configuration must match reviewed configuration'
    preferred=[p for p in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar')if '-sources'not in p.name and '-javadoc'not in p.name]
    fml=next(q.CACHE.glob('net.neoforged.fancymodloader/loader/4.0.43/*/loader-4.0.43.jar'))
    with tempfile.TemporaryDirectory(prefix='piq-zapper-visual26-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();base=tmp/'production.jar';shutil.copyfile(final_path or BASE,base)
        if final_path:assert q.digest(base.read_bytes())==final_hash
        cp=os.pathsep.join(map(str,[out,base,*preferred,fml,q.MC,*q.dependencies()]))
        af=tmp/'cp.args';af.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        probes=[ROOT/'tools/qa'/n for n in ('ZapperEnumTransformProbe.java','ZapperPoseProbe.java','CabinetRoomTestRunner.java')]
        compiler=q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*([]if final_path else sources),*tests,*probes],ROOT)
        pure=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'CabinetRoomTestRunner',*names],ROOT))
        with zipfile.ZipFile(q.MC)as jar:target=jar.read('net/minecraft/client/model/HumanoidModel$ArmPose.class')
        targetfile=tmp/'ArmPose.class';targetfile.write_bytes(target);config=tmp/'enum.json';config.write_bytes(config_bytes);transformed=tmp/'ArmPose.transformed.class'
        enums=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'ZapperEnumTransformProbe',config,targetfile,transformed],tmp))
        pose=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'ZapperPoseProbe',*([base]if final_path else[])],tmp));transformed_bytes=transformed.read_bytes()
    body=collect_quads(json.loads(assets['models/item/zapper/body.json']));trigger=collect_quads(json.loads(assets['models/item/zapper/trigger.json']))
    texture=np.array(Image.open(io.BytesIO(assets['textures/item/zapper/skin.png'])).convert('RGBA'));textures={'piq_fc_arcade:item/zapper/skin':texture}
    pivot=np.array([pose['trigger_pivot'][k]for k in ('x','y','z')]);rz=rotation('z',pose['trigger_degrees'])[:3,:3]
    pressed=body+[Quad((p.vertices-pivot)@rz.T+pivot,p.uv,p.texture,p.element_index,p.direction)for p in trigger]
    natural,_=render_view(body+trigger,textures,(-1,.45,-1),size=(520,420),supersample=1)
    down,_=render_view(pressed,textures,(-1,.45,-1),size=(520,420),supersample=1)
    sheet=Image.new('RGB',(1040,780),(236,234,225));draw=ImageDraw.Draw(sheet)
    sheet.paste(natural,(0,50));sheet.paste(down,(520,50))
    draw.text((20,12),'Original model / trigger released',font=font(22),fill=(30,35,40));draw.text((540,12),'Same model / local Z +8 degrees',font=font(22),fill=(30,35,40))
    details={}
    for index,view in enumerate(('FIRST_LEFT','FIRST_RIGHT')):
        p=pose['poses'][view];origin=p['origin'];m=translation(p['x'],p['y'],p['z'])@rotation('y',p['yaw'])@rotation('x',p['pitch'])@rotation('z',p['roll'])@scale([p['scale']]*3)@translation(-origin['x']/16,-origin['y']/16,-origin['z']/16)
        hand=pose['first_left'if index==0 else'first_right'];m=translation(hand['x'],hand['y'],hand['z'])@translation(-.5,-.5,-.5)@m
        canvas=np.zeros((280,520,4),dtype=np.uint8);canvas[:,:,:]=[51,61,70,255];depth=np.full((280,520),-np.inf)
        for quad in body+trigger:
            v=points(quad.vertices/16,m);assert np.all(v[:,2]<0)
            f=140/np.tan(np.deg2rad(70/2));projected=np.c_[260+v[:,0]/-v[:,2]*f,140-v[:,1]/-v[:,2]*f,v[:,2]]
            for tri in ((0,1,2),(0,2,3)):raster_triangle(canvas,depth,projected[list(tri)],quad.uv[list(tri)],texture)
        image=Image.fromarray(canvas,'RGBA').convert('RGB');pen=ImageDraw.Draw(image);pen.line((253,140,267,140),fill='white');pen.line((260,133,260,147),fill='white')
        pen.text((12,10),view+' / 70deg vertical FOV',font=font(17),fill='white');sheet.paste(image,(index*520,470))
        details[view]={'hand':hand,'gun_unit_scale':p['scale'],'perspective_camera':'eye-based, no muzzle offset'}
    draw.text((20,756),'OFFLINE: original UV + actual production transforms. Not a Minecraft screenshot.',font=font(17),fill=(50,50,50))
    args.output.mkdir(parents=True);sheet.save(args.output/'zapper-preview.png');(args.output/'ArmPose.transformed.class').write_bytes(transformed_bytes)
    if final_path:assert q.digest(final_path.read_bytes())==final_hash,'Final JAR changed during audit'
    report={'ok':True,'schema':'piq-zapper-visual26-1','mode':'final-jar-only'if final_path else'source-pure-only',
        'jar':str(final_path)if final_path else None,'sha256':final_hash,'production_compiled':not bool(final_path),
        'production_sources_compiled_only_pure':not bool(final_path),'minecraft_started':False,'not_a_game_screenshot':True,
        'pure':pure,'actual_enum_transform':enums,'actual_pose_matrix':pose,'first_person':details,'compiler_output':compiler,
        'assets':{n:q.digest(v)for n,v in assets.items()},'enum_resource_sha256':q.digest(config_bytes),
        'transformed_enum_sha256':q.digest(transformed_bytes),
        'source_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in sources+tests+probes},
        'limits':['No actual player renderer/GL/window/input device was started.','Real FML parses and transforms ArmPose bytes but does not initialize the enum.','Preview retains source geometry and UV; actual gameplay is tested separately.']}
    (args.output/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'ok':True,'pure_tests':pure['passed_tests'],'fml_assertions':enums['assertions'],'pose_assertions':pose['assertions'],'report':str(args.output/'verification.json')}))
if __name__=='__main__':main()
