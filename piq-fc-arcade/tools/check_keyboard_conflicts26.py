"""Actual key-routing behaviors + real Sponge/MC queue tests; never starts a game.
With --fc, compiles only tests/probes and loads production only from that JAR.
Without --fc, compiles the explicitly listed keyboard sources for bounded source QA.
"""
import argparse,json,os,shutil,tempfile,tomllib,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
MIXIN=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/net.fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/2af2f021d8e02a0220dc27a7a72b4666d66d44ca/sponge-mixin-0.15.2+mixin.0.8.7.jar')
CONFIG='piq_fc_keyboard.mixins.json'
NAMES=['KeyboardConfig','KeyboardConfigStore','KeyboardControlState','KeyboardRouting','KeyboardInput','KeyboardMappingState']
TESTS=['KeyboardConfigTest','KeyboardControlStateTest','KeyboardRoutingTest']

def check(fc=None):
    production=fc or ROOT/'build/review-controls25-v1/piq_fc_arcade-0.31.0-alpha.25.jar'
    input_sha=q.digest(production.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-keys26-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        stage=tmp/'production.jar';shutil.copyfile(production,stage);assert q.digest(stage.read_bytes())==input_sha
        preferred=[p for p in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar')if '-sources'not in p.name and '-javadoc'not in p.name]
        deps=[p for p in q.dependencies()if p!=MIXIN and 'mixin-0.8.5'not in p.name and 'sponge-mixin-'not in p.name]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,*preferred,MIXIN,stage,q.MC,resources,*deps]))
        arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[]
        if fc:
            with zipfile.ZipFile(stage)as jar:config=jar.read(CONFIG);metadata=jar.read('META-INF/neoforge.mods.toml')
            assert any(x.get('config')==CONFIG for x in tomllib.loads(metadata.decode())['mixins'])
        else:
            base=ROOT/'src/main/java/cn/piq/retro/client'
            sources=[base/(name+'.java')for name in NAMES]+[ROOT/'src/main/java/cn/piq/fcarcade/mixin'/(name+'.java')for name in ['KeyboardHandlerMixin','KeyMappingStateAccess']]
            config=(ROOT/'src/main/resources'/CONFIG).read_bytes()
        settings=json.loads(config);assert settings['client']==['KeyboardHandlerMixin','KeyMappingStateAccess']and settings['required']is True and not settings.get('mixins')
        (out/CONFIG).write_bytes(config)
        tests=[ROOT/'src/test/java/cn/piq/retro/client'/(name+'.java')for name in TESTS]
        probes=[ROOT/'tools/qa'/(name+'.java')for name in ['CabinetRoomTestRunner','KeyboardMixinTransformProbe','KeyboardMappingTransformProbe']]
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,*probes],ROOT)
        descriptor=out/'META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService';descriptor.parent.mkdir(parents=True);descriptor.write_text('KeyboardMixinTransformProbe$Blackboard\n')
        behavior_log=q.run([q.JAVA/'java.exe','@'+str(arg),'CabinetRoomTestRunner',*['cn.piq.retro.client.'+n for n in TESTS]],ROOT)
        behaviors=q.parse_last_json(behavior_log);assert behaviors['passed_tests']>=54
        with zipfile.ZipFile(q.MC)as jar:
            target=jar.read('net/minecraft/client/KeyboardHandler.class');minecraft=jar.read('net/minecraft/client/Minecraft.class')
        mixin_name='cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class'
        if fc:
            with zipfile.ZipFile(stage)as jar:mixin=jar.read(mixin_name)
        else:mixin=(out/mixin_name).read_bytes()
        targetfile=tmp/'KeyboardHandler.class';targetfile.write_bytes(target)
        mixinfile=tmp/'KeyboardHandlerMixin.class';mixinfile.write_bytes(mixin)
        minecraftfile=tmp/'Minecraft.class';minecraftfile.write_bytes(minecraft)
        head_log=q.run([q.JAVA/'java.exe','@'+str(arg),'KeyboardMixinTransformProbe',targetfile,mixinfile,minecraftfile,tmp/'head.class'],tmp)
        head=q.parse_last_json(head_log);assert head['ok']and head['actual_mixin_transformer']and head['router_before_keymapping_click']
        mapping_log=q.run([q.JAVA/'java.exe','@'+str(arg),'KeyboardMappingTransformProbe',tmp/'mapping.class'],tmp)
        mapping=q.parse_last_json(mapping_log);assert mapping['ok']and mapping['actual_keymapping_instances']and mapping['actual_toggle_failure_reproduced']
        if fc:
            with zipfile.ZipFile(stage)as jar:
                assert mapping['accessor_sha256']==q.digest(jar.read('cn/piq/fcarcade/mixin/KeyMappingStateAccess.class'))
                assert mapping['helper_sha256']==q.digest(jar.read('cn/piq/retro/client/KeyboardMappingState.class'))
                assert all(not(out/('cn/piq/retro/client/'+name+'.class')).exists()for name in NAMES)
        result={'ok':True,'mode':'final-jar-only'if fc else'explicit-source-qa','production_compiled':not bool(fc),
                'input':{'path':str(production.resolve()),'sha256':input_sha},'behavior':behaviors,'head_transform':head,'mapping_transform_and_queue':mapping,
                'mixin_config_sha256':q.digest(config),'source_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in [*sources,*tests,*probes]},
                'minecraft_started':False,'native_core_started':False,'instance_or_options_modified':False,
                'logs':{'compile':compile_log,'behavior':behavior_log,'head':head_log,'mapping':mapping_log},
                'limits':['Actual Sponge transforms cached NeoForge 21.1.236/Minecraft 1.21.1 classes, not a ModLauncher game boot or installed 21.1.250 interoperability run.',
                          'Actual transformed KeyMapping and ToggleKeyMapping instances execute production clearing. Transient click/down fields are seeded by reflection to avoid a GLFW window.',
                          'No Minecraft singleton, game window, physical keyboard/gamepad, remote server, options write, or gameplay is exercised.']}
    assert q.digest(production.read_bytes())==input_sha
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--fc',type=Path);parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    assert not args.report.exists(),'New evidence path required'
    result=check(args.fc)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(args.report),'behavior_tests':result['behavior']['passed_tests'],'mapping_assertions':result['mapping_transform_and_queue']['assertions']}))
if __name__=='__main__':main()
