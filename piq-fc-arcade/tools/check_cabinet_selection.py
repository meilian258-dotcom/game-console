"""Compile actual configured-cabinet flow and run bounded store/intent tests, without Minecraft."""
import argparse,json,os,re,shutil,tempfile,hashlib
from pathlib import Path
from check_confirmation_screens import ROOT,WORKSPACE,JAVA,MC,FC,require,run,code,methods,method

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path);args=parser.parse_args()
    if args.report:args.report.resolve().relative_to(WORKSPACE.resolve());require(not args.report.exists(),'Never overwrite a report')
    source_root=ROOT/'src/main/java/cn/piq/fcarcade'
    names=['cabinet/CabinetBackends','cabinet/CabinetEmulator','cabinet/CabinetRomBindings','cabinet/CabinetConfigureIntent','cabinet/ServerCabinets',
           'client/cabinet/CabinetClientBackends','client/cabinet/CabinetCleanup','client/cabinet/CabinetGameSelection',
           'client/cabinet/CabinetMenuScreen','client/cabinet/CabinetSetupScreen','client/ui/DeviceScreen']
    sources=[source_root/(name+'.java') for name in names]+sorted((WORKSPACE/'piq-retro-platform/src/main/java/cn/piq/retro/api').glob('*.java'))
    tests=[ROOT/'src/test/java/cn/piq/fcarcade/cabinet'/(name+'.java') for name in ['CabinetConfigureIntentTest','CabinetRomBindingsTest']]
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');all_jars=list(cache.rglob('*.jar'))
    dependencies=[p for p in all_jars if re.match(r'(junit-jupiter-(api|engine)-5\.11\.4|junit-platform-(launcher|engine|commons)-1\.11\.4|apiguardian-api-1\.1\.2|opentest4j-1\.3\.0)\.jar$',p.name)]
    manifest=json.loads(Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    for library in manifest['libraries']:
        parts=library['name'].split(':')
        if len(parts)==3:dependencies.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    dependencies += [p for p in all_jars if not any(t in p.name for t in ('-sources','-javadoc','-userdev'))]
    with tempfile.TemporaryDirectory(prefix='piq-cabinet-selection-') as td:
        temp=Path(td);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir();baseline=temp/'fc.jar';shutil.copyfile(FC,baseline)
        cp=os.pathsep.join(map(str,[out,baseline,MC,*dependencies]));argfile=temp/'args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,ROOT/'tools/qa/CabinetSelectionTestRunner.java'])
        result=json.loads(run([JAVA/'java.exe','@'+str(argfile),'CabinetSelectionTestRunner']))
        host=code(out,'cn.piq.fcarcade.client.cabinet.CabinetClientBackends');menu=code(out,'cn.piq.fcarcade.client.cabinet.CabinetMenuScreen');selection=code(out,'cn.piq.fcarcade.client.cabinet.CabinetGameSelection')
        checks={}
        checks['normal_launch_uses_saved_game_without_picker']=all(s in method(host,' openBackend(') for s in ('CabinetConfigureIntent.consume','CabinetGameSelection.key','Method startGame:','CabinetSetupScreen'))
        checks['only_explicit_picker_confirms_binding']=all(s in method(host,' start(') for s in ('Field configureSelection:Z','CabinetSetupScreen','iconst_1','Method startGame:'))
        checks['background_load_validate_then_remember_before_factory']=False
        for signature,body in methods(host).items():
            if 'lambda$startGame$' in signature and 'CabinetGameSelection.load' in body:
                checks['background_load_validate_then_remember_before_factory']=all(s in body for s in ('CabinetGameSelection.validate','CabinetGameSelection.remember','RetroEmulatorFactory.open','Field generation:I','Field shuttingDown:Z')) and body.index('CabinetGameSelection.validate')<body.index('CabinetGameSelection.remember')<body.index('RetroEmulatorFactory.open')
        checks['no_default_or_first_rom_guess']='defaultRom:' not in host and 'LocalRomLibrary.scan' not in host
        checks['exact_connection_and_physical_target']=all(s in method(host,' current(') for s in ('Field sessionConnection:','Minecraft.getConnection','CabinetTarget.matches','isAlive','isSpectator','distanceToSqr','localWorld'))
        checks['normal_and_error_exit_release_lease']=all(s in method(host,' stop(') for s in ('Field generation:','CabinetConfigureIntent.clear','AtomicReference.getAndSet','CabinetCleanup.closeRetro','Method release:'))
        checks['configuration_intent_is_before_single_use_packet']=all(s in method(menu,' choose(') for s in ('Field sent:Z','sipush        600','Field connection:','CabinetTarget.matches','CabinetClientBackends.configure','CabinetNetwork$Choose')) and method(menu,' choose(').index('CabinetClientBackends.configure')<method(menu,' choose(').index('CabinetNetwork$Choose')
        checks['menu_cancellation_drops_intent']='CabinetClientBackends.cancelConfigure' in method(menu,' onClose(') and 'CabinetClientBackends.cancelConfigure' in method(menu,' removed(')
        checks['identity_contains_world_or_server_and_backend']=all(s in method(selection,' key(') for s in ('Minecraft.getConnection','LevelResource.ROOT','Minecraft.getCurrentServer','ServerData.ip','CabinetRomBindings$Key','UUID'))
        checks['loaded_game_validates_readability_extensions_and_bios']=all(s in method(selection,' validate(') for s in ('LocalRomLibrary.validateFile','Files.isReadable','String.endsWith','Set.stream'))
        require(all(checks.values()),'Selection bytecode checks failed: '+repr([k for k,v in checks.items() if not v]))
        # Only the right-click feedback string is allowed to change on the server.
        server_old=methods(code(baseline,'cn.piq.fcarcade.cabinet.ServerCabinets'));server_new=methods(code(out,'cn.piq.fcarcade.cabinet.ServerCabinets'))
        for signature,body in server_old.items():
            normalized=body.replace('已结束街机；再次右键可选择游戏','已结束街机；再次右键启动，Shift 空手右键配置游戏')
            require(server_new.get(signature)==normalized,'Server algorithm changed: '+signature)
    report={'ok':True,'tests':result,'bytecode_checks':checks,'server_all_methods_unchanged_except_feedback':True,
            'source_sha256':{str(p.relative_to(WORKSPACE)):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in sources+tests},
            'minecraft_started':False,'core_started':False,'gradle_started':False,
            'limits':['No GUI/game or live server tested.','A Windows symbolic-link test may abort only if the OS refuses link creation.','Config paths are metadata only; providers still validate/load ROM content on their own worker.']}
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
