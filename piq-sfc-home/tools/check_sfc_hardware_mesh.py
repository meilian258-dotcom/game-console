"""Actual SFC free-mesh + actual Java pose/parser QA, with perspective grip previews.

No Minecraft/native runtime. Arms are documented vanilla-volume skin-color stand-ins.
"""
import argparse,io,itertools,json,math,os,subprocess,tempfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
import build_sfc_hardware_mesh as build
from check_controller_pose_pipeline import translation,rotation,scale,points,projected_bounds
from check_controller_slanted_pose import raster,FACES,hit,arm_triangles,project
ROOT=build.PROJECT
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')

def java_probe(path):
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2'),('com.google.code.gson','2.10.1')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    if not any('gson' in p.name for p in deps):deps.append(next((cache/'com.google.code.gson/gson').rglob('gson-*.jar')))
    def run(cmd):
        p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
        if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
        return p.stdout
    with tempfile.TemporaryDirectory(prefix='sfc-free-mesh-qa-') as tmp:
        folder=Path(tmp);classes=folder/'classes';classes.mkdir();empty=folder/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        prod=[ROOT/'src/main/java/cn/piq/sfchome/client'/(n+'.java') for n in ('SfcHardwareMeshData','SfcControllerPoseLayout')]
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*prod,ROOT/'tools/qa/SfcHardwareMeshProbe.java',ROOT/'tools/qa/SfcHardwareMeshTestRunner.java',ROOT/'src/test/java/cn/piq/sfchome/client/SfcHardwareMeshDataTest.java'])
        data=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcHardwareMeshProbe',path]));tests=run([JAVA/'java.exe','-cp',cp,'SfcHardwareMeshTestRunner'])
    return data,tests
def arm_vertices(p,right,slim,sleeve,rig):
    side=1 if right else -1;lo=(-2 if slim else -3) if right else -1;hi=lo+(3 if slim else 4);inflate=.25 if sleeve else 0
    cube=np.array(list(itertools.product((lo-inflate,hi+inflate),(-2-inflate,10+inflate),(-2-inflate,2+inflate))))
    x,y,z,pitch,roll,size=p
    # renderHand's actual vanilla age=0 arm bob is retained (SFC does not install a custom ArmPose enum).
    matrix=rig@translation(x,y,z)@rotation('z',roll)@rotation('x',pitch)@scale([size]*3)@translation(-side*5/16,2/16,0)@rotation('z',math.degrees(side*.1))@scale([1/16]*3)
    return points(cube,matrix)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=Path);args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    path=build.ASSETS/'meshes/sfc_hardware.json';raw=path.read_bytes();doc=json.loads(raw);groups={}
    for name,group in doc['groups'].items():g=build.Mesh();g.parts=group['parts'];groups[name]=g
    report=build.audit(doc,groups);assert report['ok'];actual,tests=java_probe(path);report['java_probe']=actual;report['junit_tests']=17;report['junit_output']=tests
    expected,_=build.build();assert build.encode(expected)==raw,'Production mesh must equal deterministic generator output'
    baseline_path=ROOT/'design/sfc-hardware-alpha5-v1/sfc_hardware.json'
    baseline=json.loads(baseline_path.read_bytes());assert doc['contract']==baseline['contract'],'AV, cover, insertion and button anchors are frozen'
    old_body={p['name']:p for p in baseline['groups']['body']['parts']}
    protected=[]
    for part in groups['body'].parts:
        name=part['name']
        if name.startswith('AV ') or name in ('slot end left','slot end right','slot dark bottom','slot front lining','slot rear lining'):
            old=old_body[name]
            assert [(t['p'],t['n']) for t in part['triangles']]==[(t['p'],t['n']) for t in old['triangles']],name+' physical geometry changed'
            protected.append(name)
    report['immutable_anchors']={'all_contract_fields_equal_frozen_alpha5':True,'protected_av_slot_geometry_parts':protected,'frozen_reference_sha256':build.sha(baseline_path.read_bytes())}
    allv=np.concatenate([np.array(t['p']) for p in groups['controller'].parts for t in p['triangles']]);caps=np.array(list(doc['contract']['button_centers'].values())+[[11.35,8.52,7.82],[8.82,8.38,7.77],[7.35,8.38,7.77]])
    unit=rotation('x',90)@rotation('y',180)@scale([actual['controller_scale']/16]*3)@translation(-8,-8,-8)
    scenarios=[];rays=[]
    for swing,values in actual['rigs'].items():
        y,z,pitch=values;rig=translation(0,y,z)@rotation('x',pitch);v=points(allv,rig@unit);keys=points(caps,rig@unit)
        for fov,aspect in itertools.product((60,70),(4/3,16/9)):
            ndc,bounds=projected_bounds(v,fov,aspect);assert np.max(np.abs(ndc))<1;assert bounds[1]>.65 and bounds[3]<.985
            scenarios.append({'swing':swing,'fov':fov,'aspect':aspect,'screen_ltrb':bounds})
        assert keys[4,0]<keys[2,0],'D-pad must stay left of A'
        for slim,sleeve in itertools.product((False,True),(False,True)):
            arms=[arm_vertices(actual['arms']['right' if right else 'left'],right,slim,sleeve,rig) for right in (True,False)]
            triangles=[t for a in arms for t in arm_triangles(a)]
            for i,target in enumerate(keys):
                hidden=any(hit(target,t) for t in triangles);assert not hidden,('key hidden',swing,slim,sleeve,i)
                rays.append([swing,slim,sleeve,i])
    report['perspective_scenarios']=scenarios;report['unobstructed_key_center_rays']=len(rays)
    # Slot dimensions are deliberate holes, not silhouettes over an intersecting solid deck.
    inserted=np.concatenate([np.array(t['p']) for p in groups['inserted'].parts for t in p['triangles']]);lower=inserted[inserted[:,1]<3.049]
    assert np.all((lower[:,0]>4.64)&(lower[:,0]<11.36)&(lower[:,2]>9.90)&(lower[:,2]<10.90)&(lower[:,1]>2.778))
    report['inserted_slot_clearance']=True
    # All placed layers stay inside the existing conservative AV housing box.
    for name in ('body','p1_docked','p2_docked','inserted'):
        verts=np.concatenate([np.array(t['p']) for p in groups[name].parts for t in p['triangles']]);assert verts[:,0].min()>=2.4 and verts[:,0].max()<=13.6 and verts[:,1].max()<=6.84
    tex,report['texture_sha256']=build.textures()
    # ItemRenderer applies the unchanged original display transform, then translates -0.5.
    item_groups={'console':('body','p1_docked','p2_docked','slot_cover'),'controller':('controller',),'cartridge':('cartridge',)}
    icons=Image.new('RGB',(1500,560),'#e1e7eb');icon_draw=ImageDraw.Draw(icons);report['gui']={}
    for i,(name,layers) in enumerate(item_groups.items()):
        item=json.loads((build.ASSETS/f'models/item/{name}.json').read_bytes());display=item.get('display',{}).get('gui')
        if display is None:
            namespace,parent=item['parent'].split(':');assert namespace=='piq_sfc_home'
            display=json.loads((build.ASSETS/f'models/{parent}.json').read_bytes())['display']['gui']
        mesh=build.Mesh()
        for layer in layers:mesh.extend(groups[layer])
        matrix=build.display_matrix(display,False)@translation(-.5,-.5,-.5)
        verts=np.concatenate([q.vertices for q in build.quads(mesh)])/16;v=points(verts,matrix)
        assert np.max(np.abs(v[:,:2]))<.5,(name,'item GUI clipping')
        report['gui'][name]={'actual_unchanged_display':display,'bounds_xy':[v[:,:2].min(0).tolist(),v[:,:2].max(0).tolist()]}
        icon=build.render_gui(build.quads(mesh),tex,matrix,size=480);icons.paste(icon,(10+i*500,15),icon)
        icon_draw.text((28+i*500,512),name+' - original item transform',fill='#29333d')
    icons.save(args.output/'item-gui.png')
    source=ROOT/'src/main/java/cn/piq/sfchome/client';mesh_source=(source/'SfcHardwareMesh.java').read_text();items_source=(source/'SfcHardwareItems.java').read_text();pose_source=(source/'SfcControllerPose.java').read_text();ber_source=(source/'SfcHardwareRenderer.java').read_text();card_source=(source/'SfcCartridgeRenderer.java').read_text()
    assert 'value=Dist.CLIENT' in mesh_source and 'value=Dist.CLIENT' in items_source and 'value=Dist.CLIENT' in pose_source
    assert 'ResourceManagerReloadListener' in mesh_source and 'meshes=Map.copyOf(next)' in mesh_source and 'meshes=Map.of()' in mesh_source
    assert mesh_source.index('SfcHardwareMeshData.read(reader)')<mesh_source.index('static void draw(') and '.read(' not in mesh_source[mesh_source.index('static void draw('):]
    assert 'originalModel.applyTransform(context,poses,left)' in items_source and 'isCustomRenderer(){return true;}' in items_source
    assert 'ItemStack.isSameItemSameComponents' in pose_source and 'event.isCanceled()' in pose_source and 'player.getItemInHand(event.getHand()).isEmpty()' in pose_source
    assert 'renderRightHand(' in pose_source and 'renderLeftHand(' in pose_source and '.key' not in pose_source and 'sendToServer' not in pose_source
    assert '.scale(' not in ber_source and 'SfcModelPresentation.visible(i, p1, p2, card)' in ber_source and 'avCable.render(console, poses.last(), buffers, light, overlay)' in ber_source
    assert 'SfcHardwareMesh.draw("cartridge",poses,buffers,light,overlay)' in card_source and 'SfcCoverGeometry.label(inserted)' in card_source
    report['source_wiring_contracts']={'client_only':True,'atomic_reload_no_per_frame_io':True,'actual_player_hands_scoped_to_empty_support':True,'unchanged_item_transforms':True,'unchanged_selector_and_av':True,'unchanged_dynamic_cover_plane':True,'scope':'Static source assertions, not a Minecraft integration test.'}
    y,z,pitch=actual['rigs']['0.0'];rig=translation(0,y,z)@rotation('x',pitch)
    canvas=np.full((1080,1920,4),[225,231,235,255],dtype=np.uint8);depth=np.full((1080,1920),-np.inf)
    for part in groups['controller'].parts:
        for t in part['triangles']:raster(canvas,depth,points(np.array(t['p']),rig@unit),np.array(t['uv']),tex[doc['materials'][part['material']]])
    for right in (True,False):
        verts=arm_vertices(actual['arms']['right' if right else 'left'],right,False,True,rig)
        for i,(a,b,c,d) in enumerate(FACES):
            texture=np.full((2,2,4),[186,141,105,255] if i==5 else [88,108,137,255],dtype=np.uint8);uv=np.array([[0,0],[0,1],[1,1],[1,0]])
            for indices in ((0,1,2),(0,2,3)):raster(canvas,depth,verts[[a,b,c,d]][list(indices)],uv[list(indices)],texture)
    image=Image.fromarray(canvas).convert('RGB');draw=ImageDraw.Draw(image);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25)
    draw.text((28,24),'SFC 双手浅倾握持 · 实际网格 + 实际 Java 姿势矩阵（离线构图，非游戏截图）',font=font,fill='#29333d')
    draw.text((28,66),'手臂为原版尺寸/袖口包围体示意；游戏内使用玩家自身皮肤。无新增按钮动画。',font=font,fill='#495865')
    image.save(args.output/'controller-grip.png');image.crop((520,730,1400,1080)).resize((1760,700)).save(args.output/'controller-grip-detail.png')
    report['limits']=['Offline actual mesh/parser/Java pose QA, not Minecraft gameplay.','Vanilla standard/slim hand and inflated sleeve volumes; diagnostic skin colors are not player screenshots.','Key center rays checked, not every edge texel; 60/70 degree hand FOV, 4:3/16:9.','No new press animation; input/network/gameplay code unchanged.']
    (args.output/'mesh-pose-audit.json').write_bytes(build.encode(report));print(json.dumps({'ok':True,'tests':17,'key_center_rays':len(rays),'perspective_scenarios':len(scenarios),'mesh_sha256':build.sha(raw),'output':str(args.output)},ensure_ascii=False))
if __name__=='__main__':main()
