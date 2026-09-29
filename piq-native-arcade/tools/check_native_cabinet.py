"""Pure Java six-cell ownership/geometry and real inherited model QA. No Gradle or emulator."""
from pathlib import Path
import argparse,hashlib,io,json,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[1];FC=ROOT.parent/'piq-fc-arcade'
sys.path.insert(0,str(FC/'tools'))
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from render_rocket_arcade_preview import collect_quads,Quad,render_view
from check_controller_pose_pipeline import rotation,translation,scale,points
from import_subor_hardware import write_new
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
OUT=ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/原生机柜模块-alpha1/六格结构QA-v2'
MODEL=FC/'src/main/resources/assets/piq_fc_arcade/models/block/dual_arcade_body.json'
TEXTURE=FC/'src/main/resources/assets/piq_fc_arcade/textures/block/rocket_arcade_skin.png'
EXPECTED_MODEL='E7F0150F1E75C2DCA5D19549F8479579D4794A39F1198E718D297CEC74B1BEB8'
EXPECTED_TEXTURE='789512ED7F867C015C6666D40809845DE430E85834CCF7BA4E48BCA57DE815E8'
def sha(b):return hashlib.sha256(b).hexdigest().upper()
def java_tests():
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    jars=[next(cache.glob(p)) for p in ('org.junit.jupiter/junit-jupiter-api/5.13.4/*/*.jar','org.junit.platform/junit-platform-commons/1.13.4/*/*.jar','org.opentest4j/opentest4j/1.3.0/*/*.jar','org.apiguardian/apiguardian-api/1.1.2/*/*.jar')]
    sources=[FC/'src/main/java/cn/piq/fcarcade/layout'/n for n in ('RocketArcadeGeometry.java','DualCabinetGeometry.java','ScreenAspectFit.java')]
    sources+=[FC/'src/main/java/cn/piq/fcarcade/world/DualCabinetFootprint.java']
    sources+=[ROOT/'src/main/java/cn/piq/nativearcade/world'/('NativeCabinet'+n+'.java') for n in ('Footprint','AssemblyLedger','RemovalGate')]
    sources+=[ROOT/'src/main/java/cn/piq/nativearcade/layout/NativeCabinetLayout.java']
    sources+=list((ROOT/'src/test/java').rglob('NativeCabinet*Test.java'))
    with tempfile.TemporaryDirectory(prefix='native-cabinet-') as tmp:
        cp=';'.join(map(str,jars));p=subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',tmp,*map(str,sources)],capture_output=True,text=True,timeout=30)
        if p.returncode:raise RuntimeError(p.stderr)
        p=subprocess.run([str(JDK/'java.exe'),'-cp',tmp+';'+cp,'cn.piq.nativearcade.world.NativeCabinetFootprintTest'],capture_output=True,text=True,timeout=30)
        if p.returncode:raise RuntimeError(p.stdout+p.stderr)
    return p.stdout.strip()

def source_checks(structure,renderer):
    return [
      ('independent registry no FC server sessions','ServerArcadeSessions' not in structure and 'NativeArcadeRegistries.' in structure),
      ('placement snapshots keep exact just-created BE rollback',all(t in structure for t in ('level.captureBlockSnapshots','level.restoringBlockSnapshots','level.getBlockEntity(cell.pos()) == cell.entity()','data.ledger.cancelPlacement'))),
      ('one entitlement claimed before one drop',structure.index('data.ledger.close(')<structure.index('if (first && drop) Block.popResource(')),
      ('unloaded chunks are skipped instead of force loaded','continue; // Unloading is not dismantling.' in structure and 'getChunk(' not in structure),
      ('six-cell permission checked before and after anchor forwarding',structure.count('usePermitted(player,anchor,hit)')==2 and 'PlayerInteractEvent.RightClickBlock' in structure),
      ('registered video callback starts outside model rotation','finally{poses.popPose();}\n        // Missing video integration' in renderer and 'video.render(machine,partial,poses,buffers,light,overlay)' in renderer),
      ('BER reload cache bound to actual model identity','cached.model()!=model' in renderer),
      ('native item inherits correct reviewed recenter','poses.translate(-1,-1.175,-.5)' in renderer and 'poses.scale(.40F,.40F,.40F)' in renderer),
      ('client-only physical renderer','value=Dist.CLIENT' in renderer),
    ]

def analyze():
    stdout=java_tests();model=json.loads(MODEL.read_bytes());checks=[]
    def check(n,ok):checks.append({'name':n,'ok':bool(ok)})
    check('20 actual Java pure geometry ownership permission-gate tests',stdout=='NATIVE_CABINET_PURE_TESTS=20 PASS')
    check('frozen FC raw body unchanged',sha(MODEL.read_bytes())==EXPECTED_MODEL)
    check('frozen FC PNG unchanged',sha(TEXTURE.read_bytes())==EXPECTED_TEXTURE)
    actual=ROOT/'src/main/resources/assets/piq_native_arcade'
    check('own item only inherits old display',json.loads((actual/'models/item/cabinet.json').read_bytes())=={'parent':'piq_fc_arcade:item/dual_cabinet'})
    for p in ('cabinet','cabinet_part'):check('empty model wrapper '+p,json.loads((actual/('blockstates/'+p+'.json')).read_bytes())=={'variants':{'':{'model':'piq_fc_arcade:block/dual_cabinet'}}})
    check('no new model or bitmap copied',len(list(actual.rglob('*.png')))==0 and len(list((actual/'models').rglob('*.json')))==1)
    structure=(ROOT/'src/main/java/cn/piq/nativearcade/world/NativeCabinetStructure.java').read_text(encoding='utf-8')
    renderer=(ROOT/'src/main/java/cn/piq/nativearcade/client/NativeCabinetRenderer.java').read_text(encoding='utf-8')
    for n,ok in source_checks(structure,renderer):check(n,ok)
    for p in (ROOT/'src/main/java/cn/piq/nativearcade').rglob('*.java'):
        if 'client' not in p.parts:check('common class never imports Minecraft client '+p.name,'net.minecraft.client.' not in p.read_text(encoding='utf-8') and 'cn.piq.nativearcade.client.' not in p.read_text(encoding='utf-8'))
    raw=collect_quads(model);v=np.concatenate([q.vertices for q in raw])+np.array([0,5.6,0])
    check('actual body is 2.35 high after raw rebase',np.isclose(v[:,1].max(),37.6) and np.isclose(v[:,1].min(),0))
    check('actual body stays two wide one deep',v[:,0].min()>=0 and v[:,0].max()<=32 and v[:,2].min()>=0 and v[:,2].max()<=16)
    gui=points(v,rotation('x',20)@rotation('y',135)@scale([.85]*3)@scale([.4]*3)@translation(-16,-18.8,-8))
    check('inherited actual GUI transform remains within 16-pixel icon',np.max(np.abs(gui[:,:2]))<8)
    paths=list((ROOT/'src/main').rglob('*.java'))+list((ROOT/'src/main/resources').rglob('*.json'))
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'java':stdout,'model_sha256':sha(MODEL.read_bytes()),'texture_sha256':sha(TEXTURE.read_bytes()),'bounds_units':[v.min(0).tolist(),v.max(0).tolist()],
      'source_sha256':{str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in paths},'limits':['Native cabinet/module only; no proof of MAME or Minecraft gameplay from these tests.','No multiplayer emulator transport. No commercial ROMs.','Frozen public FC model dependency, independent six-cell saved ownership.']},raw

def preview(raw):
    quads=[Quad(q.vertices+[0,5.6,0],q.uv,q.texture,q.element_index,q.direction) for q in raw]
    tex={'piq_fc_arcade:block/rocket_arcade_skin':np.array(Image.open(TEXTURE).convert('RGBA'))}
    sheet=Image.new('RGB',(1450,900),'#e9edef');d=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
    d.text((20,18),'原生街机独立机柜 · 复用冻结外观 / 六格独立结构（离线 QA，非游戏画面）',font=font,fill='#22313c')
    for i,(name,view) in enumerate((('正面 / 黑屏待核心',(0,.20,-1)),('侧面 / 2.35 格高',(1,.12,0)),('物品显示同冻结 GUI',(-1,.5,-1)))):
        if i==2:
            m=rotation('x',20)@rotation('y',135)@scale([.85]*3)@scale([.4]*3)@translation(-16,-18.8,-8)
            qs=[Quad(points(q.vertices,m),q.uv,q.texture,q.element_index,q.direction) for q in quads];view=(0,0,1)
        else:qs=quads
        im,_=render_view(qs,tex,view,size=(450,730),supersample=2);sheet.paste(im,(25+475*i,90),im);d.text((35+475*i,836),name,font=font,fill='#314652')
    result={}
    for ext in ('png','jpg'):
        data=io.BytesIO();sheet.save(data,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':91}));result[OUT/('原生机柜实际模型预览.'+ext)]=data.getvalue()
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');a=p.parse_args();r,raw=analyze();print(json.dumps(r,ensure_ascii=True,indent=2))
    if not r['ok']:raise SystemExit(1)
    if a.write:
        out=preview(raw);r['preview_sha256']={str(p):sha(b) for p,b in out.items()};out[OUT/'native-cabinet-module-qa.json']=(json.dumps(r,ensure_ascii=False,indent=2)+'\n').encode();write_new(out)
if __name__=='__main__':main()
