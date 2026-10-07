"""Run the current 30 model tests against final JAR classes and its own mesh bytes.

Only tests/probes are compiled; no production source directory is on javac's
source path. Does not render, launch Minecraft, or load a simulator core.
"""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,subprocess,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=(java_home() / 'bin')
def sha(data):return hashlib.sha256(data).hexdigest().upper()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--sfc',type=Path,required=True);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args();jar=args.sfc.resolve()
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2'),('com.google.code.gson','2.10.1')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    asset='assets/piq_sfc_home/meshes/sfc_hardware.json'
    with zipfile.ZipFile(jar) as z:mesh=z.read(asset)
    tests=[ROOT/'src/test/java/cn/piq/sfchome/client'/(n+'.java') for n in ('SfcHardwareMeshDataTest','SfcButtonAnimationTest','SfcCoverGeometryTest','SfcAvCableGeometryTest')]
    probes=[ROOT/'tools/qa'/(n+'.java') for n in ('SfcHardwareMeshProbe','SfcUserModelProbe','SfcUserModelTestRunner','SfcUserModelOriginProbe')]
    with tempfile.TemporaryDirectory(prefix='sfc-user-final-jar-') as tmp:
        folder=Path(tmp);classes=folder/'classes';classes.mkdir();empty=folder/'empty';empty.mkdir();work=folder/'test-work';extracted=work/'src/main/resources'/asset;extracted.parent.mkdir(parents=True);extracted.write_bytes(mesh)
        cp=os.pathsep.join(map(str,[classes,jar,*deps]))
        def run(cmd):
            p=subprocess.run(list(map(str,cmd)),cwd=work,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
            if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
            return p.stdout
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*tests,*probes])
        compiled=[str(p.relative_to(classes)).replace('\\','/') for p in classes.rglob('*.class')]
        assert all(Path(p).name.split('$')[0].removesuffix('.class') in {q.stem for q in tests+probes} for p in compiled),compiled
        origins=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcUserModelOriginProbe',jar]))
        pose=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcHardwareMeshProbe',extracted]))
        data=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcUserModelProbe',extracted]))
        junit=run([JAVA/'java.exe','-cp',cp,'SfcUserModelTestRunner'])
    report={'ok':True,'junit_tests':30,'junit_output':junit,'production_source_compiled':False,'production_origin':'final-jar-only','origin_probe':origins,
            'final_jar':str(jar),'final_jar_sha256':sha(jar.read_bytes()),'mesh_from_jar_sha256':sha(mesh),'compiled_test_probe_classes':compiled,
            'actual_pose':pose,'actual_animation_controls':data['controls'],'actual_av_quads':len(data['av']['quads']),
            'sources':{str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in tests+probes},'minecraft_or_core_started':False}
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'ok':True,'tests':30,'production_origin':'final-jar-only','report':str(args.report),'report_sha256':sha(args.report.read_bytes())}))
if __name__=='__main__':main()
