"""Actual production API compile, JDK-only production guards and queued real-context/codec tests."""
from __future__ import annotations

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,shutil,subprocess,tempfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FC=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar'
SFC=ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC家用/0.1.0-alpha.7/piq_sfc-0.1.0-alpha.7.jar'
JAVA=(java_home() / 'bin')
MC=(gradle_home() / 'caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
def run(args):
    result=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,encoding='utf-8',errors='replace',timeout=60)
    if result.returncode:raise AssertionError(result.stdout+result.stderr)
    return result.stdout
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--report',required=True,type=Path);args=p.parse_args()
    if args.report.exists():raise ValueError('Refuse to overwrite prior evidence')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    manifest=json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    for lib in manifest['libraries']:
        parts=lib['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps.extend(p for p in cache.rglob('*.jar')if not any(s in p.name for s in ('-sources','-javadoc','-userdev')))
    junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        junit.extend(p for p in (cache/group).rglob('*.jar')if version in p.parts and not any(s in p.name for s in ('-sources','-javadoc')))
    names=['client/SfcHomeClient','client/SfcJoinClient','client/SfcJoinScreen','net/SfcHomeNetwork','net/SfcJoinNetwork',
           'server/SfcHomeServer','server/SfcHomeStartPolicy','server/SfcInputHealth','server/SfcJoinGate','client/SfcPlayback']
    # Compile related new production helpers as well, not fake versions of server/core APIs.
    candidates=['server/SfcControllerAuthority']
    sources=[ROOT/'src/main/java/cn/piq/sfchome'/(name+'.java')for name in names+candidates if (ROOT/'src/main/java/cn/piq/sfchome'/(name+'.java')).exists()]
    test=ROOT/'src/test/java/cn/piq/sfchome/client/SfcClientIdentityTest.java'
    with tempfile.TemporaryDirectory(prefix='sfc-client-identity-')as folder:
        temp=Path(folder);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir()
        fc=temp/'fc.jar';sfc=temp/'sfc.jar';shutil.copyfile(FC,fc);shutil.copyfile(SFC,sfc)
        cp=os.pathsep.join(map(str,[out,MC,fc,sfc,*deps]));arg=temp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,test,ROOT.parent/'piq-fc-arcade/tools/qa/DeviceUiTestRunner.java',ROOT/'tools/qa/SfcClientIngressProbe.java'])
        # Only actual compiled guards + JUnit are present: no Minecraft or frozen mod jars.
        purecp=os.pathsep.join(map(str,[out,*junit]));pure=json.loads(run([JAVA/'java.exe','-cp',purecp,'DeviceUiTestRunner','cn.piq.sfchome.client.SfcClientIdentityTest']))
        if pure['passed_tests']!=17:raise AssertionError('Missing production guard tests')
        ingress=json.loads(run([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.sfchome.net.SfcClientIngressProbe']))
        for name in ['SfcHomeNetwork','SfcJoinNetwork']:
            data=(out/'cn/piq/sfchome/net'/(name+'.class')).read_bytes()
            if b'net/minecraft/client/' in data:raise AssertionError('Common network class links client implementation')
        # Every preexisting record still has its actual encoding and validation bytecode.
        sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
        from check_confirmation_screens import code,methods
        import zipfile
        preserved=[]
        with zipfile.ZipFile(sfc)as jar:
            records=[n[:-6].replace('/','.')for n in jar.namelist()if n.startswith('cn/piq/sfchome/net/Sfc')and '$'in n and n.endswith('.class')and not n.endswith(('$Client.class','$ClientHandler.class'))]
        for name in records:
            if methods(code(sfc,name))!=methods(code(out,name)):raise AssertionError('Old network record behavior changed: '+name)
            preserved.append(name)
    report={'ok':True,'pure_production_guard_tests':pure['passed_tests'],'real_api_ingress_and_codec':ingress,
        'old_network_records_bytecode_preserved':preserved,'source_sha256':{str(s.relative_to(ROOT)):hashlib.sha256(s.read_bytes()).hexdigest().upper()for s in sources+[test]},
        'protocol':'4; lease-bound Ready/Leave wrappers; prior inner codecs preserved','minecraft_started':False,'gradle_started':False,'native_core_started':False,'network_socket_opened':False,
        'scope':'Actual API compilation and executable production guards/ingress; not a Minecraft multiplayer gameplay test.'}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
