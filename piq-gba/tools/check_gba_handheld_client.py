"""Compile the new GBA client against actual FC/MC APIs; pure tests, no game/native/audio device."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fc',type=Path,required=True)
    parser.add_argument('--jar','--gba',dest='gba',type=Path,help='Final GBA JAR: compile only the probe, never production')
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args();fc=args.fc.resolve(strict=True);report=args.report.resolve()
    gba=args.gba.resolve(strict=True) if args.gba else None
    jars={name:{'path':str(path),'sha256':sha(path)} for name,path in [('fc',fc),*([('gba',gba)] if gba else [])]}
    if report.exists():raise ValueError('No report overwrite')
    source_root=ROOT/'src/main/java/cn/piq/gba'
    sources=sorted((source_root/'bridge').glob('*.java'))+[source_root/'GbaMod.java',source_root/'item/GbaHandheldItem.java']
    sources += [source_root/('client/'+name+'.java') for name in ('GbaHandheldClient','GbaHandheldScreen','GbaHandheldAudio','GbaHandheldGate','GbaHandheldSelectionStore')]
    before={str(x.relative_to(ROOT)):sha(x) for x in sources}
    probe=ROOT/'tools/qa/GbaHandheldClientProbe.java'
    probe_before={str(probe.relative_to(ROOT)):sha(probe)}
    with tempfile.TemporaryDirectory(prefix='piq-gba-handheld-client-') as folder:
        work=Path(folder);classes=work/'classes';classes.mkdir()
        local_fc=work/'fc.jar';shutil.copyfile(fc,local_fc)
        local_gba=work/'gba.jar'
        if gba:shutil.copyfile(gba,local_gba)
        cp=os.pathsep.join(map(str,[classes,*([local_gba] if gba else []),local_fc,q.MC,*q.dependencies()]))
        argfile=work/'compile.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        empty=work/'empty';empty.mkdir()
        q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*([] if gba else sources),probe],work)
        if gba and {x.name for x in classes.rglob('*.class')}!={'GbaHandheldClientProbe.class','GbaHandheldClientProbe$Checked.class'}:raise AssertionError('Unexpected source compilation')
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(argfile),'cn.piq.gba.client.GbaHandheldClientProbe',work/'owned',*([local_gba] if gba else [])],work))
    if before!={str(x.relative_to(ROOT)):sha(x) for x in sources}:raise AssertionError('Source changed during check')
    if probe_before!={str(probe.relative_to(ROOT)):sha(probe)}:raise AssertionError('Probe changed during check')
    for name,path in [('fc',fc),*([('gba',gba)] if gba else [])]:
        if sha(path)!=jars[name]['sha256']:raise AssertionError('Input JAR changed')
    data={'schema':'piq-gba-handheld-client-1','ok':result['ok'],'mode':'final-jar-only' if gba else 'source-actual-api','production_compiled':not bool(gba),
          'jars':jars,'source_sha256':before if not gba else {},'probe_source_sha256':probe_before,'pure_tests':result,
          'minecraft_started':False,'native_core_started':False,'audio_device_started':False,
          'limitations':['No interactive Minecraft client/world, physical input device or UI window was started.','Pure gate/store tests do not assert a live client handshake, visible model or final native save completion.']}
    data['fc']=jars['fc']
    if gba:data['gba']=jars['gba']
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8') as out:json.dump(data,out,ensure_ascii=False,indent=2);out.write('\n')
    print(json.dumps({'ok':data['ok'],'assertions':result['assertions'],'report':str(report)},ensure_ascii=False))
if __name__=='__main__':main()
