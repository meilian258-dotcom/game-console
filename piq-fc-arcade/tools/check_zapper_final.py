"""Run the original 61 real-core assertions against final JAR classes/resources only."""
import argparse,hashlib,json,os,subprocess,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
OLD='110711E30B64444414A8BE2D0A3B1AB45A442CC9B9452AC7D74DAAB508C933FF'
def sha(data):return hashlib.sha256(data).hexdigest().upper()
def run(args):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--fc',required=True,type=Path);ap.add_argument('--report',required=True,type=Path);a=ap.parse_args();jar=a.fc.resolve()
    if a.report.exists():raise ValueError('Refusing to overwrite previous evidence')
    before=sha(jar.read_bytes())
    with zipfile.ZipFile(jar)as z:
        old=sha(z.read('core/nes_rust_wasm_bg.wasm'));new=sha(z.read('core/nes_zapper_v1.wasm'))
        assert old==OLD,'Legacy WASM changed'
        assert not any('zapper-private' in name for name in z.namelist()),'Private game-derived evidence in JAR'
    with tempfile.TemporaryDirectory(prefix='piq-zapper-final-')as folder:
        out=Path(folder)/'classes';empty=Path(folder)/'empty';out.mkdir();empty.mkdir()
        cp=os.pathsep.join(map(str,[out,jar]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',out,ROOT/'tools/qa/ZapperCoreProbe.java',ROOT/'tools/qa/ZapperFinalOriginProbe.java'])
        output=run([JAVA/'java.exe','-cp',cp,'ZapperFinalOriginProbe',jar])
    lines=[json.loads(line)for line in output.splitlines()if line.startswith('{')]
    assert len(lines)==2 and lines[0]['origin_assertions']==4 and lines[1]['assertions']==61
    assert sha(jar.read_bytes())==before,'Final JAR changed during probe'
    report={'ok':True,'mode':'final-jar-only','production_compiled':False,'fc_sha256':before,'jars':{'fc':{'path':str(jar),'sha256':before}},'legacy_module_sha256':old,'zapper_module_sha256':new,
            'origin':lines[0],'diagnostic':lines[1],'minecraft_started':False,'commercial_rom_used':False,
            'limits':['Original diagnostic ROM and actual independent WASM/native runtime are exercised without a Minecraft client.',
                      'Does not test game-item aiming, network/permissions or physical light-gun hardware.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
