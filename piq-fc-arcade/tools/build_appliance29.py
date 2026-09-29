"""Offline build with an immutable before/after source fence; never installs anything."""
import argparse, json, os, subprocess
from pathlib import Path
from freeze_fc_core_alpha19 import digest, require, safe_path

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')

def inputs():
    paths=[]
    for name in ('piq-fc-arcade','piq-sfc-home'):
        project=ROOT/name
        paths+=list((project/'src').rglob('*'))
        paths+=list((project/'gradle').rglob('*'))
        paths += [project/n for n in ('gradle.properties','build.gradle','settings.gradle','gradlew.bat')]
    return {p.relative_to(ROOT).as_posix():digest(p.read_bytes()) for p in sorted(set(paths)) if p.is_file()}

def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    report=safe_path(a.report);require(report.is_relative_to(ROOT)and not report.exists(),'New workspace report required')
    before=inputs();env=dict(os.environ,JAVA_HOME=str(JAVA))
    for project in ('piq-fc-arcade','piq-sfc-home'):
        subprocess.run(['cmd.exe','/d','/c','gradlew.bat','check','jar','--offline'],cwd=ROOT/project,env=env,check=True)
    require(inputs()==before,'Build inputs changed while building; freeze rejected')
    builds={n:digest((ROOT/n).read_bytes())for n in (
        'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.29.jar',
        'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.17.jar')}
    xml={p.relative_to(ROOT).as_posix():digest(p.read_bytes())for project in ('piq-fc-arcade','piq-sfc-home')
         for p in (ROOT/project/'build/test-results/test').glob('TEST-*.xml')}
    data={'ok':True,'inputs':before,'builds':builds,'test_xml':xml,'installed':False,'minecraft_started':False}
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8')as f:json.dump(data,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(report),'builds':builds},ensure_ascii=True))

if __name__=='__main__':main()
