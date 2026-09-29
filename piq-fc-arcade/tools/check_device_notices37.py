"""FC37 final-JAR-only real NeoForge system-message/presentation policy QA. No game, server or core starts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def main():
    sys.stdout.reconfigure(encoding='utf-8');sys.stderr.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',type=Path,required=True);p.add_argument('--report',type=Path,required=True)
    args=p.parse_args();fc=args.fc.resolve(strict=True);report=args.report.resolve();q.require(not report.exists(),'New report required')
    before,production=q.archive(fc)
    source=ROOT/'tools/qa/DeviceNotices37Probe.java';inputs=[source,Path(__file__).resolve()]
    source_sha={str(path.relative_to(ROOT)):sha(path) for path in inputs}
    with tempfile.TemporaryDirectory(prefix='piq-device-notices37-') as name:
        work=Path(name);classes=work/'qa';classes.mkdir();empty=work/'empty';empty.mkdir();staged=work/'fc.jar';shutil.copyfile(fc,staged)
        vanilla=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        # Real Language initialization loads the vanilla en_us resource; no fake Language/Minecraft class.
        cp=os.pathsep.join(map(str,[classes,staged,q.MC,vanilla,*q.dependencies()]))
        cp_args=work/'cp.args';cp_args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        copied=work/source.name;shutil.copyfile(source,copied)
        q.run([q.JAVA/'javac.exe','@'+str(cp_args),'--release','21','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,copied],work)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','-Xmx384m','-Djava.awt.headless=true','@'+str(cp_args),'DeviceNotices37Probe',staged],work,60))
        q.require(result.get('ok') and result['actual_neoforge_system_events'],'Actual event probe failed')
        compiled=sorted(path.relative_to(classes).as_posix() for path in classes.rglob('*.class'))
        q.require(all(Path(path).stem.split('$')[0]=='DeviceNotices37Probe' for path in compiled),'Only probe compiled')
        q.require(not set(compiled)&set(production),'No production shadows')
        q.require(before==sha(fc)==sha(staged),'JAR changed')
        q.require(source_sha=={str(path.relative_to(ROOT)):sha(path) for path in inputs},'QA source changed')
    data={'schema':'piq-device-notices37-1','ok':True,'mode':'final-jar-only','production_compiled':False,
        'jar':str(fc),'sha256':before,'checks':result,'qa_source_sha256':source_sha,'compiled_qa_classes':compiled,
        'minecraft_started':False,'installed':False,'limits':['Uses real Minecraft Component and NeoForge ClientChatReceivedEvent.System, production final-JAR policy and bounded diagnostic memory.',
        'No GPU/toast frame, actual physical hit testing, in-game controller use, ROM execution or live multiplayer playtest was run.']}
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8') as out:json.dump(data,out,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(report),'checks':result},ensure_ascii=False))
if __name__=='__main__':main()
