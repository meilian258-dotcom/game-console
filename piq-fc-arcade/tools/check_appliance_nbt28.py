"""Actual production TV/stand constructors + real registration and NBT, no world/window."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args();assert not a.report.exists()
    before=q.digest(a.fc.read_bytes());probe=ROOT/'tools/qa/HomeApplianceNbtProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-appliance-nbt28-')as tmp:
        tmp=Path(tmp);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jar=tmp/'production.jar';shutil.copyfile(a.fc,jar)
        assert q.digest(jar.read_bytes())==before
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,jar,q.MC,resources,*q.dependencies()]));af=tmp/'cp.args';af.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,probe],ROOT)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'cn.piq.fcarcade.home.HomeApplianceNbtProbe',jar],ROOT))
    assert q.digest(a.fc.read_bytes())==before
    report={'ok':True,'jar':str(a.fc.resolve()),'sha256':before,'probe':result,'production_compiled':False,'compiled_only_probe':True,'probe_sha256':q.digest(probe.read_bytes()),
        'limitations':['Actual registry events and production constructors/NBT in an isolated Java process. Not full FML discovery, a real server/world, packet/protection or live interaction test.']}
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'ok':True,'assertions':result['assertions'],'report':str(a.report)}))
if __name__=='__main__':main()
