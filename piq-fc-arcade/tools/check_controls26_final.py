"""Final-JAR-only controls26 regression, real FML discovery, outer codecs and Mixin contracts.

Only tests/probes are compiled. Supplied JARs are copied to ASCII temporary names
with before/after SHA checks; no source production class or old core is added.
"""
import argparse,json,os,shutil,tempfile,tomllib,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
import check_keyboard_conflicts26 as keyboard_real

ROOT=Path(__file__).resolve().parents[1]
TESTS={
 'keyboard': ['cn.piq.retro.client.KeyboardControlStateTest','cn.piq.retro.client.KeyboardConfigTest','cn.piq.retro.client.KeyboardRoutingTest','cn.piq.retro.client.KeyboardPresentationTest'],
 'gamepad_and_layout': ['cn.piq.retro.client.GamepadConfigStoreTest','cn.piq.retro.client.GamepadMixerTest','cn.piq.retro.client.ControlPanelLayoutTest'],
 'geometry': ['cn.piq.fcarcade.layout.'+n for n in ('DualCabinetGeometryTest','DualCabinetControlsTest','CabinetVideoGeometryTest','ScreenAspectFitTest','ScreenSurfaceGeometryTest','ScreenRayMappingTest')],
}
ORIGIN_FAMILIES={
 'fc': ['cn/piq/retro/client/KeyboardConfig','cn/piq/retro/client/KeyboardConfigStore','cn/piq/retro/client/KeyboardControlState','cn/piq/retro/client/KeyboardRouting','cn/piq/retro/client/KeyboardInput','cn/piq/retro/client/KeyboardMappingState','cn/piq/retro/client/KeyboardPresentation','cn/piq/retro/client/ControlHubLayout','cn/piq/fcarcade/mixin/KeyMappingStateAccess','cn/piq/retro/client/GamepadConfig','cn/piq/retro/client/GamepadConfigStore','cn/piq/retro/client/GamepadMixer','cn/piq/retro/client/ControlPanelLayout','cn/piq/retro/client/ControlLabels','cn/piq/retro/input/GamepadState','cn/piq/retro/input/InputMappings','cn/piq/retro/input/InputOwnership','cn/piq/retro/input/InputProfile','cn/piq/retro/input/LocalInputSession','cn/piq/retro/input/RetroButtons','cn/piq/retro/input/StickDeadzone','cn/piq/fcarcade/client/ClientArcadeSession','cn/piq/fcarcade/client/cabinet/CabinetClientBackends'],
 'sfc': ['cn/piq/sfchome/client/SfcHomeClient','cn/piq/sfchome/client/SfcHomeKeys'],
 'native': ['cn/piq/nativearcade/client/NativeArcadeClient'],
}
ORIGIN_FAMILIES['fc'] += ['cn/piq/fcarcade/layout/'+n for n in ('RocketArcadeGeometry','DualCabinetGeometry','DualCabinetControls','ScreenAspectFit','DualScreenPresentation','CabinetVideoGeometry','ArcadeScreenBounds','ArcadeDisplayStyle','ScreenSurfaceGeometry','ScreenRayMapping','WideLcdPresentation','LargeLcdPresentation')]
ORIGIN_FAMILIES['fc'] += ['cn/piq/fcarcade/home/'+n for n in ('HomeHardwareScale','VintageTvLayout','WideLcdTvLayout','LargeLcdTvLayout')]
ORIGIN_FAMILIES['fc'].append('cn/piq/fcarcade/world/DualCabinetFootprint')

def origin_entries(entries,families):
    for family in families:
        if family+'.class'not in entries:raise AssertionError('Missing production class '+family)
    return sorted(n for n in entries if n.endswith('.class')and any(n==family+'.class'or n.startswith(family+'$')for family in families))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('fc','sfc','native'):p.add_argument('--'+key,required=True,type=Path)
    p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    if a.report.exists():raise ValueError('Evidence must be newly created')
    paths={key:getattr(a,key).resolve(strict=True)for key in ('fc','sfc','native')}
    jars={};hashes={}
    for key,path in paths.items():hashes[key],jars[key]=q.archive(path)
    if len(set(paths.values()))!=3:raise ValueError('Expected three distinct final JARs')
    allClasses=[n for jar in jars.values()for n in jar if n.endswith('.class')]
    if len(set(allClasses))!=len(allClasses):raise ValueError('Duplicate production class ownership')
    expected={'fc':{'piq_fc_arcade':'0.31.0-alpha.26'},'sfc':{'piq_sfc_home':'0.1.0-alpha.15','piq_sfc_arcade':'0.2.0-alpha.6'},'native':{'piq_native_arcade':'0.1.0-alpha.10'}}
    for key,entries in jars.items():
        meta=tomllib.loads(entries[q.META].decode());mods={m['modId']:m['version']for m in meta['mods']}
        if mods!=expected[key]:raise ValueError('Unexpected final version set '+key+': '+str(mods))
    report={'ok':True,'schema':'piq-controls26-final-1','jars':{k:{'path':str(paths[k]),'sha256':hashes[k]}for k in paths},'production_compiled':False,'minecraft_or_native_core_started':False,'installed':False}
    with tempfile.TemporaryDirectory(prefix='piq-controls26-final-')as folder:
        temp=Path(folder);out=temp/'tests';out.mkdir();empty=temp/'empty';empty.mkdir();copies={};originLines=[]
        for key,path in paths.items():
            copy=temp/(key+'.jar');shutil.copyfile(path,copy)
            if q.digest(copy.read_bytes())!=hashes[key]or q.digest(path.read_bytes())!=hashes[key]:raise ValueError('Copy mismatch '+key)
            copies[key]=copy
            originLines.extend(str(copy)+'\t'+n[:-6].replace('/','.')for n in origin_entries(jars[key],ORIGIN_FAMILIES[key]))
        origin=temp/'origins.tsv';origin.write_text('\n'.join(originLines),encoding='utf-8')
        deps=q.dependencies();deps=[d for d in deps if not ('org.junit.platform'in d.parts and '1.13.4'not in d.parts)and not ('org.junit.jupiter'in d.parts and '5.13.4'not in d.parts)]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,*copies.values(),q.MC,resources,*deps]));arg=temp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[ROOT/'src/test/java'/Path(n.replace('.','/')+'.java')for names in TESTS.values()for n in names]
        runner=ROOT/'tools/qa/Controls26FinalProbe.java'
        q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,runner],temp)
        report['tests']={}
        for group,names in TESTS.items():
            result=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'Controls26FinalProbe',origin,*names],temp))
            if not result.get('ok')or result.get('production_origin')!='final-jar-only':raise AssertionError('Probe contract '+group)
            if group=='keyboard'and(result['tests_found']!=59 or result['tests_succeeded']!=59):raise AssertionError('Incomplete keyboard cases')
            if group=='gamepad_and_layout'and(result['tests_found']!=19 or result['tests_succeeded']<18):raise AssertionError('Incomplete gamepad/layout cases')
            if group=='geometry'and(result['tests_found']!=38 or result['tests_succeeded']!=38):raise AssertionError('Incomplete final geometry cases')
            if result['skipped_names']or any(name!='symlinkConfigCannotReadOrOverwriteAnotherFile()'for name in result['aborted_names']):raise AssertionError('Unexpected skipped/aborted test')
            report['tests'][group]=result
        for compiled in out.rglob('*.class'):
            rel=compiled.relative_to(out).as_posix()
            if any(rel in entries for entries in jars.values()):raise AssertionError('Production was accidentally compiled '+rel)
        fc=jars['fc'];config=json.loads(fc['piq_fc_keyboard.mixins.json']);meta=tomllib.loads(fc[q.META].decode())
        if config.get('required')is not True or config.get('client')!=['KeyboardHandlerMixin','KeyMappingStateAccess']or config.get('mixins')or config.get('package')!='cn.piq.fcarcade.mixin':raise AssertionError('Mixin config weakened')
        if sum(m.get('config')=='piq_fc_keyboard.mixins.json'for m in meta.get('mixins',[]))!=1:raise AssertionError('Mixin registration missing/duplicate')
        target=q.run([q.JAVA/'javap.exe','-p','-s','-classpath',q.MC,'net.minecraft.client.KeyboardHandler'],temp)
        if 'void keyPress(long, int, int, int, int);'not in target or 'descriptor: (JIIII)V'not in target:raise AssertionError('Actual MC target signature changed')
        mixin=q.run([q.JAVA/'javap.exe','-p','-v','-classpath',copies['fc'],'cn.piq.fcarcade.mixin.KeyboardHandlerMixin'],temp)
        for value in ('Lnet/minecraft/client/KeyboardHandler;','org.spongepowered.asm.mixin.injection.Inject','keyPress','HEAD','cancellable=true','(JIIIILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V','KeyboardInput.intercept'):
            if value not in mixin:raise AssertionError('Final Mixin descriptor/annotation missing '+value)
        report['mixin']={'target':'keyPress(JIIII)V','client_only':True,'registered_exactly_once':True,'head_cancellable':True,'real_target_class_sha256':q.digest(zipfile.ZipFile(q.MC).read('net/minecraft/client/KeyboardHandler.class')),'live_transform_tested':False}
        report['temporary_copies']={key:{'path':str(copy),'sha256':hashes[key],'byte_identical_before_and_after':q.digest(copy.read_bytes())==hashes[key]and q.digest(paths[key].read_bytes())==hashes[key]}for key,copy in copies.items()}
        if not all(v['byte_identical_before_and_after']for v in report['temporary_copies'].values()):raise ValueError('Final artifact changed')
        report['test_source_sha256']={str(f.relative_to(ROOT.parent)):q.digest(f.read_bytes())for f in sources+[runner]}
        report['geometry_scope']={'real_final_jar_behavior_tests':38,'original_source_qa_total':41,'excluded_source_only_contracts':3,'screen_ray_mapping':True,'screen_surface_geometry':True,'dual_geometry_controls_video_aspect':True}
    report['compatibility']=q.java_probes(paths)
    report['actual_keyboard_transform_and_queue']=keyboard_real.check(paths['fc'])
    if any(q.digest(path.read_bytes())!=hashes[key]for key,path in paths.items()):raise ValueError('Final artifacts changed after compatibility tests')
    report['limits']=['Tests and actual NeoForge discovery/ASM scanning/outer codecs only, not a complete FML or live Mixin bootstrap.','No live GLFW window, physical controller, Minecraft client/server, commercial ROM or emulator core was started.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'jars':hashes,'tests':report['tests']},ensure_ascii=True))

if __name__=='__main__':main()

