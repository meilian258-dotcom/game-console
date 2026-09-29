"""Execute pure production copy/revision guards; no Minecraft, core, game files or Gradle."""
import argparse, hashlib, json, os, subprocess, sys, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser();p.add_argument('--report',required=True,type=Path);args=p.parse_args()
    if args.report.exists():raise ValueError('Choose a new evidence path')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        junit.extend(f for f in (cache/group).rglob('*.jar') if version in f.parts and not any(x in f.name for x in ('-sources','-javadoc')))
    pure=[ROOT/'src/main/java/cn/piq/sfchome/client'/(n+'.java') for n in ('SfcWatchFrames','SfcWatchDemand')]
    pure.append(ROOT.parent/'piq-retro-platform/src/main/java/cn/piq/retro/api/RetroFrame.java')
    names=['SfcWatchFramesTest','SfcWatchDemandTest','SfcWatchSourceTest']
    tests=[ROOT/'src/test/java/cn/piq/sfchome/client'/(n+'.java') for n in names]
    runner=ROOT.parent/'piq-fc-arcade/tools/qa/DeviceUiTestRunner.java'
    hashes={str(f.relative_to(ROOT.parent)):hashlib.sha256(f.read_bytes()).hexdigest().upper() for f in pure+tests+[runner,Path(__file__).resolve()]}
    with tempfile.TemporaryDirectory(prefix='piq-sfc-watch-pure-') as temp:
        out=Path(temp)/'classes';out.mkdir()
        cp=os.pathsep.join(map(str,[out,*junit]))
        def run(command):
            r=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
            if r.returncode:raise AssertionError(r.stdout+r.stderr)
            return r.stdout
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-cp',cp,'-d',out,*pure,*tests,runner])
        result=json.loads(run([JAVA/'java.exe','-cp',cp,'DeviceUiTestRunner',*['cn.piq.sfchome.client.'+n for n in names]]))
        if result['passed_tests']!=18:raise AssertionError(result)
    report={'ok':True,'tests':result,'source_sha256':hashes,'minecraft_started':False,'native_core_started':False,
            'network_socket_opened':False,'gradle_started':False,'scope':'Executed pure frame copy/demand guards plus source architecture contracts; not actual multiplayer.'}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as output:json.dump(report,output,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
