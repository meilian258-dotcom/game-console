"""Bounded real Java tests + actual Minecraft API compilation, without a game or network."""
import argparse,json,os,tempfile,tomllib,zipfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'build/review-interaction24-v1/piq_fc_arcade-0.31.0-alpha.24.jar'
NATIVE=ROOT.parent/'piq-native-arcade'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',required=True,type=Path);p.add_argument('--pure',action='store_true');a=p.parse_args()
    if a.report.exists():raise ValueError('Evidence must be newly created')
    prod=ROOT/'src/main/java/cn/piq/retro/client'
    pure=[prod/(n+'.java')for n in ('KeyboardConfig','KeyboardControlState','KeyboardConfigStore','KeyboardRouting')]
    tests=[ROOT/'src/test/java/cn/piq/retro/client'/(n+'.java')for n in ('KeyboardControlStateTest','KeyboardConfigTest','KeyboardRoutingTest')]
    report={'ok':True,'mode':'explicit-source-qa','production_compiled':True,'minecraft_started':False,'native_core_started':False,'network_socket_opened':False,'options_file_touched':False}
    with tempfile.TemporaryDirectory(prefix='piq-keyboard-qa-')as d:
        temp=Path(d);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir();deps=q.dependencies()
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,q.MC,resources,BASE,*deps]));args=temp/'cp.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*pure,*tests,ROOT/'tools/qa/CabinetRoomTestRunner.java'],ROOT)
        text=q.run([q.JAVA/'java.exe','@'+str(args),'CabinetRoomTestRunner','cn.piq.retro.client.KeyboardControlStateTest','cn.piq.retro.client.KeyboardConfigTest','cn.piq.retro.client.KeyboardRoutingTest'],ROOT)
        report['behavior']=q.parse_last_json(text)
        if report['behavior']['passed_tests']!=47:raise AssertionError('Missing expected behavior tests')
        sources=[*pure,*tests]
        hosts=ROOT/'src/test/java/cn/piq/fcarcade/client/cabinet/ImmersiveClientSafetyTest.java'
        ownership=[ROOT/'src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientOwner.java',ROOT.parent/'piq-retro-platform/src/main/java/cn/piq/retro/input/InputOwnership.java']
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*ownership,hosts],ROOT)
        report['host_contracts_and_ownership']=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(args),'CabinetRoomTestRunner','cn.piq.fcarcade.client.cabinet.ImmersiveClientSafetyTest'],ROOT))
        if report['host_contracts_and_ownership']['passed_tests']!=16:raise AssertionError('Missing host/ownership regressions')
        report['host_contracts_and_ownership'].update(mode='14-source-wiring-contracts-and-2-actual-ownership-behaviors',source_wiring_tests=14,behavior_tests=2)
        sources.extend([hosts,*ownership])
        if not a.pure:
            api=[prod/'KeyboardInput.java',ROOT/'src/main/java/cn/piq/fcarcade/mixin/KeyboardHandlerMixin.java',ROOT/'src/main/java/cn/piq/fcarcade/client/ArcadeKeyMappings.java',ROOT/'src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java']
            q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*api],ROOT)
            nativeJar=ROOT/'build/review-interaction24-v1/piq_native_arcade-0.1.0-alpha.8.jar';nativeSource=NATIVE/'src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java'
            args.write_text('-cp\n"'+(cp+os.pathsep+str(nativeJar)).replace('\\','/')+'"\n',encoding='utf-8')
            q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,nativeSource],ROOT)
            sources+=api+[nativeSource];report['real_api_compile']={'minecraft':'1.21.1','neoforge':'21.1.236','explicit_sources':[str(f.relative_to(ROOT.parent))for f in api+[nativeSource]],'mixin_apply_in_game_tested':False}
            target=q.run([q.JAVA/'javap.exe','-p','-s','-classpath',q.MC,'net.minecraft.client.KeyboardHandler'],ROOT)
            if 'void keyPress(long, int, int, int, int);'not in target or 'descriptor: (JIIII)V'not in target:raise AssertionError('Actual target descriptor changed')
            mixin=q.run([q.JAVA/'javap.exe','-p','-v','-classpath',out,'cn.piq.fcarcade.mixin.KeyboardHandlerMixin'],ROOT)
            for expected in ('Lnet/minecraft/client/KeyboardHandler;','org.spongepowered.asm.mixin.injection.Inject','keyPress','HEAD','cancellable=true','(JIIIILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V','KeyboardInput.intercept'):
                if expected not in mixin:raise AssertionError('Missing real compiled Mixin contract '+expected)
            configPath=ROOT/'src/main/resources/piq_fc_keyboard.mixins.json';config=json.loads(configPath.read_text())
            assert config['required']is True and config['client']==['KeyboardHandlerMixin']and not config.get('mixins')and config['package']=='cn.piq.fcarcade.mixin'
            meta=tomllib.loads((ROOT/'src/main/templates/META-INF/neoforge.mods.toml').read_text().replace('${mod_id}','piq_fc_arcade'))
            assert sum(entry.get('config')=='piq_fc_keyboard.mixins.json'for entry in meta['mixins'])==1
            report['mixin_contract']={'actual_keyboard_handler_sha256':q.digest(zipfile.ZipFile(q.MC).read('net/minecraft/client/KeyboardHandler.class')),'target':'keyPress(JIIII)V','head_cancellable':True,'client_only_registration':True,'settings_without_session':True,'live_mixin_transform_tested':False}
        report['source_sha256']={str(f.relative_to(ROOT.parent)):q.digest(f.read_bytes())for f in sources}
    report['limits']=['Real pure state/configuration behavior plus cached Minecraft API compilation; no live GLFW window, mixin transformation, dedicated server, physical device, or multiplayer gameplay was executed.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))

if __name__=='__main__':main()
