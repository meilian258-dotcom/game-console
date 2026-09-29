"""Actual dual-cabinet vertices through production world/item transforms.

Compiles only two pure production geometry classes plus a read-only probe; no
Gradle or game launch. The world preview is orthographic; GUI uses an exact fixed
16-pixel slot, never auto-fit. Diagnostic screen is a QA texture, not NES gameplay.
"""
from __future__ import annotations
import argparse
import io
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_dual_arcade_model import MODEL,TEXTURE,OUT,ASSETS,sha,encoded,write_new
from check_controller_pose_pipeline import translation,rotation,scale,points,display_matrix
from render_rocket_arcade_preview import Quad,collect_quads,render_view,raster_triangle

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'src/main/java/cn/piq/fcarcade'
REPORT_DIR=OUT/'运行时管线QA'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
BODY_SHA='E7F0150F1E75C2DCA5D19549F8479579D4794A39F1198E718D297CEC74B1BEB8'
PATHS={'renderer':JAVA/'client/DualCabinetRenderer.java','geometry':JAVA/'layout/DualCabinetGeometry.java',
       'screen_renderer':JAVA/'client/ArcadeBlockScreenRenderer.java','item':ASSETS/'models/item/dual_cabinet.json',
       'block':ASSETS/'models/block/dual_cabinet.json','blockstate':ASSETS/'blockstates/dual_cabinet.json',
       'partstate':ASSETS/'blockstates/dual_cabinet_part.json','body':MODEL,'texture':TEXTURE}


def probe_geometry():
    with tempfile.TemporaryDirectory(prefix='piq-dual-geometry-') as temp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',temp,
            str(JAVA/'layout/RocketArcadeGeometry.java'),str(PATHS['geometry']),
            str(ROOT/'tools/qa/DualCabinetGeometryProbe.java')],check=True,capture_output=True,timeout=30)
        result=subprocess.run([str(JDK/'java.exe'),'-cp',temp,'DualCabinetGeometryProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    lines=result.splitlines();meta=[float(v) for v in lines[0].split()[1:]];turns=[]
    for line in lines[1:]:
        values=np.array([float(v) for v in line.split()[2:]])
        turns.append({'screen':values[:12].reshape(4,3),'normal':values[12:15],'bounds':values[15:].reshape(2,3)})
    if len(turns)!=4:raise ValueError('Incomplete production geometry probe')
    return {'scale':meta[0],'offset':meta[1],'y_offset':meta[2],'turns':turns,'stdout':result}


def world_matrix(turns,model_scale,y_offset=.35):
    return translation(.5,0,.5)@rotation('y',-90*turns)@translation(-.5,0,-.5)@scale([model_scale]*3)@translation(0,y_offset,0)


def item_matrix(item):
    # ItemTransform -> ItemRenderer -0.5 -> custom renderer center/scale/recenter.
    custom=translation(.5,.5,.5)@scale([.40]*3)@translation(-1,-1.175,-.5)@translation(0,.35,0)
    return display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)@custom


def screen_matches(quads,geometry):
    target=geometry['turns'][0]['screen']-geometry['turns'][0]['normal']*geometry['offset'];matches=[]
    for i,q in enumerate(quads):
        baked=((q.vertices/16).astype(np.float32).astype(np.float64)+[0,geometry['y_offset'],0])*geometry['scale'];matched=0
        for p in baked:
            for corner,c in enumerate(target):
                if matched&(1<<corner)==0 and np.all(np.abs(p-c)<.00002):matched|=1<<corner
        if matched==15:matches.append(i)
    return matches


def analyze(probe=None,renderer_override=None,screen_override=None,item_override=None):
    raw={name:path.read_bytes() for name,path in PATHS.items()}
    source={name:data.decode('utf-8') for name,data in raw.items() if name not in ('texture',)}
    renderer=renderer_override if renderer_override is not None else source['renderer']
    screen_source=screen_override if screen_override is not None else source['screen_renderer']
    r=re.sub(r'\s+','',renderer);sr=re.sub(r'\s+','',screen_source)
    item=item_override if item_override is not None else json.loads(raw['item'])
    model=json.loads(raw['body']);quads=collect_quads(model);all_raw=np.concatenate([q.vertices for q in quads])/16
    geometry=probe if probe is not None else probe_geometry();checks=[]
    def check(name,condition,evidence):checks.append({'name':name,'ok':bool(condition),'evidence':evidence})
    check('frozen body and runtime scale',sha(raw['body'])==BODY_SHA and geometry['scale']==1 and geometry['y_offset']==.35,'227-cube body exact hash; actual compiled MODEL_SCALE=1, Y offset=.35')
    check('world transform order and anchor pivot',
          'poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90F*turns));poses.translate(-.5,0,-.5);poses.scale(DualCabinetGeometry.MODEL_SCALE,DualCabinetGeometry.MODEL_SCALE,DualCabinetGeometry.MODEL_SCALE);poses.translate(0,DualCabinetGeometry.MODEL_Y_OFFSET,0);' in r,
          'T(.5,0,.5) Ry(-90turn) T(-.5,0,-.5) S1 T(0,.35,0)')
    check('item custom transform and strict JSON',item['parent']=='builtin/entity' and
          'poses.translate(.5,.5,.5);poses.scale(.40F,.40F,.40F);poses.translate(-1,-1.175,-.5);poses.translate(0,DualCabinetGeometry.MODEL_Y_OFFSET,0);' in r,
          'Strict JSON; one .40 scale, world-body center (1,1.175,.5), then +.35 rebasing')
    dual_branch=re.search(r'if\(displayStyle==cn\.piq\.fcarcade\.layout\.ArcadeDisplayStyle\.DUAL_CABINET(?:\|\|displayStyle==cn\.piq\.fcarcade\.layout\.ArcadeDisplayStyle\.HOME_(?:WIDE_LCD_TV|LARGE_LCD_TV|VINTAGE_TV))*\)\{(.*?)return;\}',sr)
    dual=dual_branch.group(1) if dual_branch else ''
    check('runtime chooses fitted aspect frame not the whole physical glass',
          'DualScreenPresentation.frame(' in dual and 'ClientArcadeEvents.dualScreenAspect()' in dual,
          'Client aspect selection flows through the pure presentation fitting helper; physical black glass is independent')
    check('dynamic screen UV order',all(x in dual for x in (
          'rocketVertex(consumer,pose,quad.lowerMaxX(),quad.normal(),0,1);',
          'rocketVertex(consumer,pose,quad.lowerMinX(),quad.normal(),1,1);',
          'rocketVertex(consumer,pose,quad.upperMinX(),quad.normal(),1,0);',
          'rocketVertex(consumer,pose,quad.upperMaxX(),quad.normal(),0,0);')),
          'The DUAL_CABINET branch specifically uses max-X as texture left, exactly original UV handedness')
    matches=screen_matches(quads,geometry)
    check('unique float32 baked static screen',len(matches)==1 and quads[matches[0]].element_index==47,
          {'matched_faces':[(quads[i].element_index,quads[i].direction) for i in matches],'tolerance_blocks':.00002})
    check('static black glass excluded from custom skin',
          'custom!=null&&!face.screen()' in r and 'prepared.add(newFace(q,isScreen(q)))' in r,
          'Only verified static glass stays on default atlas; all other quads use current custom skin')
    check('isScreen matching arithmetic source contract',all(x in r for x in (
          'Float.intBitsToFloat(data[p])*DualCabinetGeometry.MODEL_SCALE','(Float.intBitsToFloat(data[p+1])+DualCabinetGeometry.MODEL_Y_OFFSET)*DualCabinetGeometry.MODEL_SCALE','Float.intBitsToFloat(data[p+2])*DualCabinetGeometry.MODEL_SCALE',
          'c.y()-q.normal().y()*DualCabinetGeometry.SCREEN_OFFSET','c.z()-q.normal().z()*DualCabinetGeometry.SCREEN_OFFSET','returnmatched==15;')),
          'Baked raw blocks restored +.35 Y and scaled once; remove dynamic offset before four-corner identity match')
    details=[]
    for turn,data in enumerate(geometry['turns']):
        matrix=world_matrix(turn,geometry['scale'],geometry['y_offset']);body=points(all_raw,matrix)
        glass=points(quads[matches[0]].vertices/16,matrix) if len(matches)==1 else np.zeros((4,3))
        # Probe order LL,LR,UR,UL; original quad order UR,LR,LL,UL.
        static=glass[[2,1,0,3]];delta=data['screen']-static
        n=matrix[:3,:3]@np.array([0,.3826834323650898,-.9238795325112867]);n/=np.linalg.norm(n)
        check('screen alignment and normal turn '+str(turn),np.allclose(delta,n*geometry['offset'],atol=1e-9) and np.allclose(data['normal'],n,atol=1e-12),
              'Actual compiled Java four corners vs all actual model vertices; normal-only offset '+str(geometry['offset'])+' blocks')
        check('complete body AABB turn '+str(turn),np.all(body>=data['bounds'][0]-1e-9) and np.all(body<=data['bounds'][1]+1e-9),
              {'actual':[body.min(0).tolist(),body.max(0).tolist()],'production':data['bounds'].tolist()})
        details.append({'turn':turn,'normal':n.tolist(),'screen_offset_error':float(np.max(np.abs(delta-n*geometry['offset']))),
                        'body_bounds':[body.min(0).tolist(),body.max(0).tolist()]})
    projected=points(all_raw,item_matrix(item));pixels=projected[:,:2]*[16,-16]+8
    normal=item_matrix(item)[:3,:3]@np.array([0,.3826834323650898,-.9238795325112867]);normal/=np.linalg.norm(normal)
    check('actual GUI slot bounds and front face visibility',np.all(pixels>=0) and np.all(pixels<=16) and normal[2]>0,
          {'pixel_bounds':[pixels.min(0).tolist(),pixels.max(0).tolist()],'front_normal_camera':normal.tolist()})
    states=[json.loads(raw[name]) for name in ('blockstate','partstate')]
    check('anchor and proxies have no duplicate baked shell',json.loads(raw['block']).get('elements')==[] and all(s=={'variants':{'':{'model':'piq_fc_arcade:block/dual_cabinet'}}} for s in states)
          and all('cullface' not in face for e in model['elements'] for face in e['faces'].values()),
          'Empty anchor/proxy model; standalone body has no culled faces, so null + six directional list enumeration does not duplicate source faces')
    check('full AABB and offscreen BER entry',
          'shouldRenderOffScreen(DualCabinetBlockEntitymachine){returntrue;}' in r and 'DualCabinetGeometry.bounds(' in r and '.move(machine.getBlockPos())' in r,
          'BER uses full turned box and skips anchor-section clipping')
    with zipfile.ZipFile(ROOT/'build/moddev/artifacts/neoforge-21.1.236-sources.jar') as archive:
        mapped={name:archive.read(path).decode() for name,path in {
          'gui':'net/minecraft/client/gui/GuiGraphics.java','item':'net/minecraft/client/renderer/entity/ItemRenderer.java',
          'transform':'net/minecraft/client/renderer/block/model/ItemTransform.java','model_manager':'net/minecraft/client/resources/model/ModelManager.java'}.items()}
    check('mapped Minecraft GUI chain', 'this.pose.scale(16.0F, -16.0F, 16.0F)' in mapped['gui'] and
          mapped['item'].index('handleCameraTransforms')<mapped['item'].index('translate(-0.5F, -0.5F, -0.5F)') and 'rotationXYZ' in mapped['transform'],
          'Verified against local Minecraft 1.21.1 mapped sources, not guessed GUI fit')
    check('model reload rebuilds quad and sprite cache',all(x in r for x in ('cached==null||cached.model()!=model','cached=newCached(model,List.copyOf(prepared))','custom.sprite=face.quad().getSprite()',
          'e.register(BODY);','bus.addListener(DualCabinetRenderer::models);')) and 'this.bakedRegistry = modelbakery.getBakedTopLevelModels();' in mapped['model_manager'],
          'Successful reload swaps ModelManager model instance; next draw replaces cached faces and sprite references. Source contract, not live F3T test.')
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'check_count':len(checks),'turns':details,
            'source_sha256':{name:sha(data) for name,data in raw.items()},'mapped_source_sha256':{name:sha(s.encode()) for name,s in mapped.items()},
            'gui_pixel_bounds':[pixels.min(0).tolist(),pixels.max(0).tolist()],
            'production_probe':geometry['stdout'],'limits':['Offline pure-Java geometry and source/matrix contracts, not Minecraft execution',
              'World images use an orthographic camera; GUI image uses exact 16-pixel slot transform without recenter/fit',
              'Diagnostic colored screen checks UV orientation, not NES gameplay; live lighting, shaders and F3T not playtested']},model,item,geometry


def preview(model,item,geometry):
    atlas=np.array(Image.open(TEXTURE).convert('RGBA'));qa=Image.new('RGBA',(256,192));d=ImageDraw.Draw(qa)
    f=ImageFont.truetype('C:/Windows/Fonts/consolab.ttf',30)
    for rect,color,label in (((0,0,128,96),'#8c3f48','TL'),((128,0,256,96),'#39728f','TR'),((0,96,128,192),'#507744','BL'),((128,96,256,192),'#947e42','BR')):
        d.rectangle(rect,fill=color);d.text((rect[0]+35,rect[1]+30),label,font=f,fill='white')
    textures={'piq_fc_arcade:block/rocket_arcade_skin':atlas,'qa':np.array(qa)}
    canvas=Image.new('RGB',(1720,1130),'#19222c');draw=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',27);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
    draw.text((24,15),'双人街机 · 实际生产矩阵 / 四向动态屏与物品槽 QA',font=title,fill='#eef5fa')
    source=collect_quads(model)
    for turn,data in enumerate(geometry['turns']):
        matrix=world_matrix(turn,geometry['scale'],geometry['y_offset']);q=[Quad(points(v.vertices/16,matrix),v.uv,v.texture,v.element_index,v.direction) for v in source]
        q.append(Quad(data['screen'][[2,1,0,3]],np.array([[0,0],[0,16],[16,16],[16,0]]),'qa',-1,'dynamic'))
        camera=rotation('y',-90*turn)[:3,:3]@np.array([1,.25,-1.7]);pic,_=render_view(q,textures,camera,size=(500,485),supersample=2)
        x=15+(turn%2)*515;y=85+(turn//2)*500;canvas.paste(pic.convert('RGB'),(x,y));draw.text((x+5,y-23),['北 NORTH','东 EAST','南 SOUTH','西 WEST'][turn],font=font,fill='#d8e8ef')
    # Fixed 16x16 logical item square, magnified 32 times. No fitting/recentering.
    item_canvas=np.zeros((512,512,4),dtype=np.uint8);item_canvas[:]=(35,42,52,255);depth=np.full((512,512),-np.inf)
    matrix=item_matrix(item)
    for q in source:
        p=points(q.vertices/16,matrix);p[:,:2]=p[:,:2]*[16,-16]+8;p[:,:2]*=32
        for indices in ((0,1,2),(0,2,3)):raster_triangle(item_canvas,depth,p[list(indices)],q.uv[list(indices)],atlas)
    canvas.paste(Image.fromarray(item_canvas).convert('RGB'),(1140,175));draw.rectangle((1140,175,1652,687),outline='#8ba5b6',width=2)
    draw.text((1120,86),'物品真实 16×16 GUI 槽（放大32倍）',font=font,fill='#d8e8ef')
    for i,line in enumerate(('含 ItemTransform + ItemRenderer -0.5', '含 BEWLR .40缩放、世界中心及Y补偿', '此栏没有自动适配大小或重新居中', '左侧彩色 TL/TR/BL/BR 是物理屏诊断', '四向按锚点(8,0,8)旋转、屏幕外移', 'alpha8旧成品与报告保留，不覆盖')):
        draw.text((1110,740+i*35),line,font=font,fill='#b9cdd8')
    draw.text((24,1090),'离线实际顶点 + 编译运行的纯 Java 几何；非 Minecraft 截图，未宣称游戏光照或 F3T 实测。',font=font,fill='#b9cdd8')
    result={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}));result['双人街机运行时管线QA.'+suffix]=b.getvalue()
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    report,model,item,geometry=analyze()
    if args.write:
        outputs={REPORT_DIR/name:data for name,data in preview(model,item,geometry).items()};report['preview_sha256']={str(p):sha(data) for p,data in outputs.items()}
        outputs[REPORT_DIR/'runtime-render-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
