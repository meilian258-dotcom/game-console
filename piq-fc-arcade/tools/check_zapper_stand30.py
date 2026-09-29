"""Run real pure production dock/link/geometry tests, preserve original model UV, no game."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
from import_zapper_stand28 import derive_stand,RES
ROOT=Path(__file__).resolve().parents[1]
def preserved(name,actual,derived):
    if name in ('assets/piq_fc_arcade/models/item/zapper_stand.json','assets/piq_fc_arcade/models/item/zapper_stand_cable.json'):
        old=json.loads(derived);new=json.loads(actual);od=old.pop('display');nd=new.pop('display')
        assert old==new and od['gui']==nd['gui'],'Only non-GUI item display may change'
    else:assert actual==derived,name

def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--fc',type=Path);a=p.parse_args();assert not a.report.exists()
    prod=['cn.piq.fcarcade.home.ZapperDock','cn.piq.fcarcade.home.ZapperStandLinks','cn.piq.fcarcade.layout.ZapperStandGeometry'];tests=['cn.piq.fcarcade.home.ZapperStandTest','cn.piq.fcarcade.layout.ZapperStandGeometryTest']
    source=[ROOT/'src/main/java'/Path(n.replace('.','/')+'.java')for n in prod];test=[ROOT/'src/test/java'/Path(n.replace('.','/')+'.java')for n in tests]
    assets=derive_stand()
    for name,raw in assets.items():preserved(name,(RES/name).read_bytes(),raw)
    nbt=None;visual=None;before=q.digest(a.fc.read_bytes())if a.fc else None
    if a.fc:
        with zipfile.ZipFile(a.fc)as jar:
            assert len(jar.namelist())==len(set(jar.namelist())) and jar.testzip()is None
            for name,raw in assets.items():preserved(name,jar.read(name),raw)
    with tempfile.TemporaryDirectory(prefix='piq-stand28-')as tmp:
        tmp=Path(tmp);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();base=tmp/'production.jar';shutil.copyfile(a.fc or ROOT/'build/review-controls26-v2/piq_fc_arcade-0.31.0-alpha.26.jar',base)
        if a.fc:assert q.digest(base.read_bytes())==before
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,base,q.MC,resources,*q.dependencies()]));af=tmp/'cp.args';af.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*([]if a.fc else source),*test,ROOT/'tools/qa/CabinetRoomTestRunner.java'],ROOT)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'CabinetRoomTestRunner',*tests],ROOT))
        if a.fc:
            q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,ROOT/'tools/qa/ZapperStandDataProbe.java',ROOT/'tools/qa/ZapperStandVisualProbe.java'],ROOT)
            nbt=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'cn.piq.fcarcade.home.ZapperStandDataProbe',base],ROOT))
            visual=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'ZapperStandVisualProbe',base,q.MC],ROOT))
            assert nbt['ok'] and visual['ok'] and nbt['production_compiled']is False and visual['production_compiled']is False
            assert q.digest(base.read_bytes())==before and q.digest(a.fc.read_bytes())==before
            with zipfile.ZipFile(base)as jar:
                assert not any(p.relative_to(out).as_posix()in jar.namelist()for p in out.rglob('*.class')),'Production class accidentally compiled'
    report={'ok':True,'mode':'final-jar-only'if a.fc else'source-pure','production_compiled':not bool(a.fc),'pure':result,'source_geometry_unchanged':True,'new_png':0,
            'derived_baseline_assets':{n:q.digest(v)for n,v in assets.items()},'source_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in source+test},'minecraft_started':False,
            'limits':['Pure state/geometry and deterministic asset derivation; supplied-JAR mode additionally executes actual Minecraft NBT and ItemTransform/PoseStack. No running world or physical interaction test.']}
    if a.fc:report.update(jar=str(a.fc.resolve()),sha256=before,actual_nbt=nbt,actual_visual=visual,compiled_only_probes_and_tests=True,
        probe_sha256={p.name:q.digest(p.read_bytes())for p in [ROOT/'tools/qa/ZapperStandDataProbe.java',ROOT/'tools/qa/ZapperStandVisualProbe.java']})
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'ok':True,'tests':result['passed_tests'],'report':str(a.report)}))
if __name__=='__main__':main()
