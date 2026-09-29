"""Narrow audio regression; no Minecraft, native core or physical sound device.

Default compiles only new probes against supplied final JARs. --source compiles
exactly the three owned changed production sources in an isolated output tree.
"""
from pathlib import Path
import argparse, hashlib, json, os, sys
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[2]
OLD_NATIVE=ROOT/'piq-fc-arcade/build/review-sync33-v1/piq_native_arcade-0.1.0-alpha.11.jar'
OLD_SHA='F582314F64A2DC556FAC1704719C04EE9E5A05154C146FA1C49FCAD9615E2C5E'
SOURCES=[ROOT/'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet'/p for p in ['CabinetAudio.java','CabinetClientBackends.java']]+[ROOT/'piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java']
TEST=ROOT/'piq-fc-arcade/src/test/java/cn/piq/fcarcade/client/cabinet/CabinetAudioGenerationTest.java'
PROBE=ROOT/'piq-fc-arcade/tools/qa/CabinetAudioFixProbe.java'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    sys.stdout.reconfigure(encoding='utf8')
    parser=argparse.ArgumentParser();parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--native',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--source',action='store_true');a=parser.parse_args()
    fc=a.fc.resolve();native=a.native.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    frozen={str(p):sha(p)for p in [fc,native,OLD_NATIVE]};assert sha(OLD_NATIVE)==OLD_SHA
    source={str(p.relative_to(ROOT)):sha(p)for p in SOURCES+[TEST,PROBE,Path(__file__).resolve()]}
    classes=out/'probe';classes.mkdir();empty=out/'empty';empty.mkdir();production=out/'production'
    if a.source:production.mkdir()
    deps=q.dependencies()
    junit=[p for p in deps if ('junit-' in p.name and ('1.13.4' in p.name or '5.13.4' in p.name)) or p.name.startswith(('opentest4j-','apiguardian-api-'))]
    deps=list(dict.fromkeys(junit+deps))
    def argfile(name,paths):
        path=out/name;cp=os.pathsep.join(os.path.relpath(p,out)if p.is_relative_to(ROOT)else str(p)for p in paths)
        path.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf8');return '@'+str(path)
    build=argfile('compile.args',[production,fc,native,q.MC,*deps] if a.source else [fc,native,q.MC,*deps])
    logs={}
    if a.source:logs['production_compile']=q.run([q.JAVA/'javac.exe',build,'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',production,*SOURCES],out)
    logs['probe_compile']=q.run([q.JAVA/'javac.exe',build,'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,TEST,PROBE],out)
    run=argfile('run.args',[classes,*([production]if a.source else []),fc,native,q.MC,*deps])
    logs['probe']=q.run([q.JAVA/'java.exe','-Djava.awt.headless=true',run,'cn.piq.fcarcade.client.cabinet.CabinetAudioFixProbe',production if a.source else fc,production if a.source else native,OLD_NATIVE],out)
    result=q.parse_last_json(logs['probe']);assert result['ok']
    assert all(sha(Path(p))==v for p,v in frozen.items())
    assert all(sha(ROOT/p)==v for p,v in source.items())
    report={'schema':'piq-cabinet-audio-fixes-1','ok':True,'mode':'isolated-source'if a.source else 'final-jar-only','production_compiled':a.source,
            'jars':{k:{'path':str(p),'sha256':sha(p)}for k,p in [('fc',fc),('native',native)]},'negative_control':{'path':str(OLD_NATIVE),'sha256':OLD_SHA},
            'source_sha256':source,'probe':result,'logs':logs,
            'limits':['No physical sound device or live Minecraft was started. Device write/open/flush races use a controlled SourceDataLine with real production worker code.',
                      'A reset is nonblocking; an already executing driver write must return before its old device buffer can be flushed. Already audible samples cannot be recalled.',
                      'Global Native pump registration and single-consumption wiring are checked in bytecode; real NeoForge event constants and front/back frustum tests are exercised, not an in-game visual test.']}
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'ok':True,'report':str(out/'report.json'),'sha256':sha(out/'report.json'),'probe':result},ensure_ascii=False))
if __name__=='__main__':main()
