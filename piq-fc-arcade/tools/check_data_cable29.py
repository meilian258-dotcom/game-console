"""Narrow source wiring contracts or final-JAR real registered item compatibility; no game."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path);p.add_argument('--report',type=Path,required=True);a=p.parse_args();assert not a.report.exists()
    tests=ROOT/'src/test/java/cn/piq/fcarcade/home/DataCableCompatibilityTest.java'
    pure=ROOT/'src/main/java/cn/piq/fcarcade/registry/CreativeTabCatalog.java'
    probe=ROOT/'tools/qa/DataCableItemProbe.java';before=None;actual=None
    if a.fc:before,entries=q.archive(a.fc)
    with tempfile.TemporaryDirectory(prefix='piq-data-cable29-')as temp:
        temp=Path(temp);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir();jar=temp/'fc.jar'
        if a.fc:shutil.copyfile(a.fc,jar);assert q.digest(jar.read_bytes())==before
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,*([jar]if a.fc else[]),q.MC,resources,*q.dependencies()]));af=temp/'cp.args';af.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,
            *([]if a.fc else[pure]),tests,ROOT/'tools/qa/CabinetRoomTestRunner.java',*([probe]if a.fc else[])],ROOT)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'CabinetRoomTestRunner','cn.piq.fcarcade.home.DataCableCompatibilityTest'],ROOT))
        if a.fc:
            actual=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'cn.piq.fcarcade.home.DataCableItemProbe',jar],ROOT))
            with zipfile.ZipFile(jar)as z:assert not any(c.relative_to(out).as_posix()in z.namelist()for c in out.rglob('*.class'))
            assert actual['ok'] and actual['production_compiled']is False and q.digest(a.fc.read_bytes())==before
    report={'ok':True,'mode':'final-jar-only'if a.fc else'source-contracts','source_contracts':result,'actual_items':actual,
        'production_compiled':not bool(a.fc),'compiled_only_tests_and_probe':bool(a.fc),'minecraft_started':False,
        'limitations':['Source text checks lock route/permission call sites only; they are not a running protection callback/server/world test.',
        'With --fc, actual NeoForge registry/ItemStack NBT/useOn inheritance/tooltip dispatch comes only from supplied final jar.'],
        'source_sha256':{str(path.relative_to(ROOT)):q.digest(path.read_bytes())for path in [tests,pure,probe]}}
    if a.fc:report.update(jar=str(a.fc.resolve()),sha256=before)
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'ok':True,'source_contracts':result['passed_tests'],'actual_assertions':actual['assertions']if actual else 0,'report':str(a.report)}))
if __name__=='__main__':main()
