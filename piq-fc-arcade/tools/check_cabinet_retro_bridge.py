"""Actual API compile, old-addon ABI probe and unchanged-host-bytecode checks; no game/Gradle/core."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from check_confirmation_screens import ROOT, WORKSPACE, JAVA, MC, FC, require, run, code, methods

SOURCES = [ROOT/'src/main/java/cn/piq/fcarcade'/name for name in (
    'cabinet/CabinetBackends.java','cabinet/CabinetEmulator.java',
    'client/cabinet/CabinetClientBackends.java','client/cabinet/CabinetCleanup.java')]
TESTS = ['cn.piq.fcarcade.cabinet.CabinetRetroAdapterTest',
         'cn.piq.fcarcade.cabinet.CabinetRetroRegistryTest',
         'cn.piq.fcarcade.client.cabinet.CabinetRetroCleanupTest',
         'cn.piq.fcarcade.client.cabinet.CabinetCleanupTest']

def stable_host(raw):
    normalized = {}
    for signature, body in methods(raw).items():
        signature = re.sub(r'(lambda\$[^$]+\$)\d+', r'\1N', signature)
        body = re.sub(r'(lambda\$[^$]+\$)\d+', r'\1N', body)
        for new, old in (
            ('cn/piq/retro/api/RetroEmulatorFactory','cn/piq/fcarcade/client/cabinet/CabinetBackend'),
            ('cn/piq/retro/api/RetroEmulator','cn/piq/fcarcade/cabinet/CabinetEmulator'),
            ('cn/piq/retro/api/RetroFrame','cn/piq/fcarcade/cabinet/CabinetFrame'),
            ('CabinetCleanup.closeRetro','CabinetCleanup.close')):
            body = body.replace(new, old)
            signature = signature.replace(new.replace('/','.'), old.replace('/','.'))
        normalized.setdefault(signature, []).append(body)
    return {key: sorted(value) for key,value in normalized.items()}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path);args=parser.parse_args()
    if args.report:
        args.report.resolve().relative_to(WORKSPACE.resolve());require(not args.report.exists(),'Do not overwrite a report')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    manifest=json.loads(Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    dependencies=[]
    for library in manifest['libraries']:
        parts=library['name'].split(':')
        if len(parts)==3:dependencies.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    dependencies += [p for p in cache.rglob('*.jar') if not any(s in p.name for s in ('-sources','-javadoc','-userdev'))]
    api=sorted((WORKSPACE/'piq-retro-platform/src/main/java/cn/piq/retro/api').glob('*.java'))
    tests=[ROOT/'tools/qa/CabinetRetroRegistryTest.java' if name.endswith('.CabinetRetroRegistryTest')
           else ROOT/'src/test/java'/(name.replace('.','/')+'.java') for name in TESTS]
    with tempfile.TemporaryDirectory(prefix='piq-cabinet-retro-bridge-') as td:
        temp=Path(td);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir();oldout=temp/'old-addon';oldout.mkdir()
        baseline=temp/'fc-alpha18.jar';shutil.copyfile(FC,baseline)
        # This implementation is genuinely compiled against the old, unmodified interface.
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',baseline,'-d',oldout,ROOT/'tools/qa/LegacyCabinetFixture.java'])
        old_abi=code(oldout,'LegacyCabinetFixture');require('asRetro' not in old_abi,'Old fixture accidentally compiled against new API')
        cp=os.pathsep.join(map(str,[out,baseline,MC,*dependencies]));argfile=temp/'compile.args'
        argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*api,*SOURCES,*tests,ROOT/'tools/qa/DeviceUiTestRunner.java',ROOT/'tools/qa/CabinetRetroBinaryProbe.java'])
        junit=run([JAVA/'java.exe','@'+str(argfile),'DeviceUiTestRunner',*TESTS]);result=json.loads(junit)
        require(result['passed_tests']==13,'Missing actual adapter/cleanup tests')
        binary=json.loads(run([JAVA/'java.exe','@'+str(argfile),'CabinetRetroBinaryProbe',oldout]))
        require(binary['old_binary_compatibility'] and binary['zero_copy'],'Old addon ABI or zero-copy failed')
        name='cn.piq.fcarcade.client.cabinet.CabinetClientBackends'
        old=stable_host(code(baseline,name));new=stable_host(code(out,name));preserved=[]
        for signature,body in old.items():
            if any(token in signature for token in (' register(', ' start(', 'static {};')):continue
            require(new.get(signature)==body,'Unexpected host algorithm change: '+signature);preserved.append(signature)
        allowed_new=lambda signature:any(token in signature for token in ('lambda$register$',))
        require(all(signature in old or allowed_new(signature) for signature in new),'Unexpected new host method')
        raw=code(out,name)
        require(all(t in raw for t in ('RetroFactoryRegistry.find','RetroFactoryRegistry.register','RetroEmulatorFactory.open','RetroEmulator.pollFrame','RetroFrame.abgr','CabinetEmulator.asRetro')),'Shared API not consumed by real host')
        cleanup_old=methods(code(baseline,'cn.piq.fcarcade.client.cabinet.CabinetCleanup'))
        cleanup_new=methods(code(out,'cn.piq.fcarcade.client.cabinet.CabinetCleanup'))
        for signature,body in cleanup_old.items():require(cleanup_new.get(signature)==body,'Legacy cleanup changed')
        common_raw=code(out,'cn.piq.fcarcade.cabinet.CabinetBackends')
        require('RetroBackendRegistry.register' in common_raw and 'RetroBackendRegistry.find' in common_raw,'Common registry not delegated')
        for name in ('cn/piq/fcarcade/cabinet/CabinetBackends.class','cn/piq/fcarcade/cabinet/CabinetEmulator.class','cn/piq/fcarcade/cabinet/CabinetEmulator$1.class'):
            require(b'net/minecraft/client/' not in (out/name).read_bytes(),'Common/client isolation regression')
    report={'ok':True,'actual_adapter_and_cleanup_tests':result['passed_tests'],'old_addon_binary_probe':binary,
            'host_methods_unchanged_except_api_owner_types':preserved,'legacy_cleanup_bytecode_unchanged':True,
            'host_algorithm_comparison_exclusions':['register: shared factory registration','start: shared factory lookup plus missing-factory fail-closed','static initializer: instantiate shared factory registry'],
            'source_sha256':{str(p.relative_to(WORKSPACE)):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in SOURCES+api+tests},
            'minecraft_started':False,'core_started':False,'gradle_started':False,
            'scope':'Real MC API compile; real production adapter/registry/cleanup tests, old frozen-ABI fixture, actual generated host bytecode. No game/network session/ROM launched.'}
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))

if __name__=='__main__':main()
