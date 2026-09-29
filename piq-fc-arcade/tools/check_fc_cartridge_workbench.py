"""Real MC API compile, MC-free search regression and old editor safety-bytecode comparison.

No Gradle, game, emulator, sockets, world access or installation. Production is
compiled for source validation only; this is not a final packaged-JAR audit.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from check_confirmation_screens import JAVA, MC, require, run, code, methods

ROOT=Path(__file__).resolve().parents[1]
WORKSPACE=ROOT.parent
BASELINE=WORKSPACE/'制作Mod/03-街机模拟/PIQ-FC街机/alpha19-fc-core-addons/piq_fc_arcade-0.31.0-alpha.19.jar'
BASELINE_SHA='C36E5878C9957F95C7C95DFD28963FF88972EE41F5CF208603BF2612F79000C4'
NAMES=('ClientCartridgeEditor','cabinet/CabinetMenuScreen','ui/DeviceUi','ui/DeviceLayout','ui/CartridgeWorkbenchLayout')
TESTS=('ClientCartridgeWorkbenchTest','ui.CartridgeWorkbenchLayoutTest','cabinet.CabinetAppearanceEntryTest')

def normalized(raw):
    result={}
    for signature,body in methods(raw).items():
        signature=re.sub(r'(lambda\$[^$]+\$)\d+',r'\1N',signature)
        body=re.sub(r'(lambda\$[^$]+\$)\d+',r'\1N',body)
        result.setdefault(signature,[]).append(body)
    return {key:sorted(value) for key,value in result.items()}

def instruction_positions(body):
    # Adding UI constants may widen ldc to ldc_w. Preserve every opcode/operand,
    # translating byte offsets and branch targets to exact instruction indices.
    offsets={int(match.group(1)):index for index,match in enumerate(re.finditer(r'^\s*(\d+):\s+[a-z][a-z0-9_]*\b',body,re.M))}
    def replace(match):
        offset,opcode,operand=int(match.group(1)),match.group(2),match.group(3)
        if opcode.startswith('if') or opcode in ('goto','goto_w','jsr','jsr_w'):
            target=re.fullmatch(r'\s*(\d+)\s*',operand);require(target is not None,'Unparsed branch instruction')
            require(int(target.group(1)) in offsets,'Branch target is not an instruction')
            operand='@'+str(offsets[int(target.group(1))])
        if opcode=='ldc_w':opcode='ldc'
        return '@'+str(offsets[offset])+': '+opcode+' '+operand.strip()
    return re.sub(r'^\s*(\d+):\s+([a-z][a-z0-9_]*)\b([^\n]*)',replace,body,flags=re.M)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path);args=parser.parse_args()
    if args.report:require(not args.report.exists(),'Refusing to overwrite old report')
    require(hashlib.sha256(BASELINE.read_bytes()).hexdigest().upper()==BASELINE_SHA,'Frozen FC19 baseline changed')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    manifest=json.loads(Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    dependencies=[]
    for library in manifest['libraries']:
        parts=library['name'].split(':')
        if len(parts)==3:dependencies.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    dependencies += [p for p in cache.rglob('*.jar') if not any(s in p.name for s in ('-sources','-javadoc','-userdev'))]
    junit=[]
    for group,artifact,version in [('org.junit.jupiter','junit-jupiter-api','5.11.4'),('org.apiguardian','apiguardian-api','1.1.2'),('org.opentest4j','opentest4j','1.3.0'),('org.junit.platform','junit-platform-commons','1.11.4')]:
        junit.extend((cache/group/artifact/version).rglob(artifact+'-'+version+'.jar'))
    sources=[ROOT/'src/main/java/cn/piq/fcarcade/client'/(name+'.java') for name in NAMES]
    tests=[ROOT/'src/test/java/cn/piq/fcarcade/client'/(name.replace('.','/')+'.java') for name in TESTS]
    with tempfile.TemporaryDirectory(prefix='piq-fc-workbench-') as directory:
        temp=Path(directory);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir();baseline=temp/'fc19.jar';shutil.copyfile(BASELINE,baseline)
        cp=os.pathsep.join(map(str,[out,baseline,MC,*dependencies]));argfile=temp/'compile.args'
        argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,ROOT/'tools/qa/DeviceUiTestRunner.java'])
        # Intentionally no MC or FC JAR: only the real nested pure Search.class and
        # source-contract tests are loadable; initializing the outer screen fails.
        pure_cp=os.pathsep.join(map(str,[out,*junit]));result=json.loads(run([JAVA/'java.exe','-cp',pure_cp,'DeviceUiTestRunner',*['cn.piq.fcarcade.client.'+name for name in TESTS]]))
        require(result['passed_tests']==12,'Missing workbench/appearance tests')
        name='cn.piq.fcarcade.client.ClientCartridgeEditor'
        before=normalized(code(baseline,name));after=normalized(code(out,name));protected=[]
        allowed=lambda signature:any(token in signature for token in ('ClientCartridgeEditor(cn.piq.fcarcade.home.CartridgeNetwork$Reply)',
                         ' init(', ' render(', ' rebuildWidgets(', ' selectedRom(', ' selectedCover(', ' pageKeys(', 'lambda$init$'))
        for signature,body in before.items():
            if allowed(signature):continue
            require(after.get(signature)==body,'Non-UI editor safety/transfer behavior changed: '+signature);protected.append(signature)
        require(all(signature in before or allowed(signature) for signature in after),'Unexpected new outer editor method')
        name='cn.piq.fcarcade.client.cabinet.CabinetMenuScreen'
        before=normalized(code(baseline,name));after=normalized(code(out,name));menu_preserved=[]
        menu_ui=lambda signature:any(token in signature for token in ('CabinetMenuScreen(cn.piq.fcarcade.cabinet.CabinetNetwork$Menu)',
                        ' init(', ' render(', ' openAppearance(', 'lambda$init$'))
        for signature,body in before.items():
            if menu_ui(signature):continue
            require(signature in after and sorted(map(instruction_positions,after[signature]))==sorted(map(instruction_positions,body)),
                    'Original core-selection/menu lifecycle changed: '+signature);menu_preserved.append(signature)
        require(all(signature in before or menu_ui(signature) for signature in after),'Unexpected new cabinet menu method')
    report={'ok':True,'mode':'actual-source-and-real-mc-api-compile','pure_mc_free_tests':result['passed_tests'],
            'frozen_baseline_sha256':BASELINE_SHA,'non_ui_methods_bytecode_preserved':protected,
            'original_cabinet_selection_and_lifecycle_bytecode_preserved':menu_preserved,
            'cabinet_comparison_normalization':'Constant-pool indices and ldc/ldc_w encoding width normalized; every branch target retained as an exact instruction index.',
            'scope':'Nested production search class tested with no Minecraft/FC runtime on classpath; old permission, cancellation, upload and metadata methods compared as generated bytecode.',
            'minecraft_started':False,'core_started':False,'gradle_started':False,'installed':False,
            'source_sha256':{str(p.relative_to(WORKSPACE)):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in sources+tests}}
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
