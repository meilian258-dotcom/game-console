"""Compile QA only against a final FC36 archive; actual Minecraft registries/codecs, no world."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from freeze_fc_core_alpha19 import read_jar, digest, require, safe_path
import verify_retro_alpha19 as q

def main():
    sys.stdout.reconfigure(encoding='utf-8');sys.stderr.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',required=True,type=Path);p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    fc=a.fc.resolve(strict=True);report=safe_path(a.report);require(not report.exists(),'New report required');sha=read_jar(fc)[0]
    probe=Path(__file__).parent/'qa/FurnitureCommon36Probe.java';probe_sha=digest(probe.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-furniture36-common-') as folder:
        tmp=Path(folder);classes=tmp/'qa';classes.mkdir();empty=tmp/'empty';empty.mkdir();staged=tmp/'fc.jar';shutil.copyfile(fc,staged)
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[classes,staged,q.MC,resources,*q.dependencies()]));args=tmp/'cp.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','--release','21','-proc:none','-sourcepath',empty,'-d',classes,probe],tmp)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','-Xmx512m','-Djava.awt.headless=true','--add-opens=java.base/java.lang.invoke=ALL-UNNAMED','@'+str(args),'FurnitureCommon36Probe',staged],tmp))
        require(result.get('ok'),'Actual Minecraft probe');compiled={x.relative_to(classes).as_posix() for x in classes.rglob('*.class')}
        require(all(Path(x).name.split('$')[0].split('.')[0]=='FurnitureCommon36Probe' for x in compiled),'Only QA compiled')
        require(sha==read_jar(fc)[0]==read_jar(staged)[0] and probe_sha==digest(probe.read_bytes()),'Input drift')
    data={'schema':'piq-furniture36-common-1','ok':True,'mode':'final-jar-only','production_compiled':False,'sha256':sha,'jar':str(fc),'common':result,'qa_source_sha256':probe_sha,'compiled_qa_classes':sorted(compiled),'limits':result['limits'],'minecraft_started':False,'installed':False}
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8') as f:json.dump(data,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(report),'assertions':result['assertions']},ensure_ascii=False))

if __name__=='__main__':main()
