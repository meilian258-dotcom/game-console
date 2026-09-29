"""Actual Sponge Mixin transformation, using a narrow classpath-only audit host.
No Minecraft class initialization, graphics, game instance, Gradle or production compilation.
"""
import argparse,json,os,tempfile,zipfile,shutil
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
MIXIN=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/net.fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/2af2f021d8e02a0220dc27a7a72b4666d66d44ca/sponge-mixin-0.15.2+mixin.0.8.7.jar')
NAME='cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class'
CONFIG='piq_fc_keyboard.mixins.json'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',required=True,type=Path)
    parser.add_argument('--fc',type=Path,help='Optional final FC JAR; otherwise exact existing build/classes is read')
    args=parser.parse_args()
    artifact=args.report.with_suffix('.transformed.class')
    assert not args.report.exists()and not artifact.exists(),'Do not replace prior evidence'
    if args.fc:
        origin=args.fc.resolve(strict=True);origin_sha=q.digest(origin.read_bytes())
        with zipfile.ZipFile(origin)as jar:mixin=jar.read(NAME);config=jar.read(CONFIG);metadata=jar.read('META-INF/neoforge.mods.toml')
        mode='final-jar-only'
    else:
        origin=ROOT/'build/classes/java/main'/NAME;mixin=origin.read_bytes();origin_sha=q.digest(mixin)
        config=(ROOT/'build/resources/main'/CONFIG).read_bytes();metadata=(ROOT/'build/resources/main/META-INF/neoforge.mods.toml').read_bytes()
        mode='existing-compiled-production-class'
    import tomllib
    assert any(entry.get('config')==CONFIG for entry in tomllib.loads(metadata.decode())['mixins'])
    settings=json.loads(config);assert settings['client']==['KeyboardHandlerMixin']and settings['required']is True
    with zipfile.ZipFile(q.MC)as jar:
        target=jar.read('net/minecraft/client/KeyboardHandler.class')
        minecraft=jar.read('net/minecraft/client/Minecraft.class')
    preferred=[p for p in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar')if '-sources'not in p.name and '-javadoc'not in p.name]
    assert len(preferred)>=4
    deps=[p for p in q.dependencies()if p!=MIXIN and not ('mixin-0.8.5' in p.name or 'sponge-mixin-' in p.name)]
    probe=ROOT/'tools/qa/KeyboardMixinTransformProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-real-mixin-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        # Generated service descriptor selects only the classpath blackboard. Production
        # mixin bytes and production config are copied exactly, never rewritten.
        (out/NAME).parent.mkdir(parents=True);(out/NAME).write_bytes(mixin);(out/CONFIG).write_bytes(config)
        descriptor=out/'META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService'
        descriptor.parent.mkdir(parents=True);descriptor.write_text('KeyboardMixinTransformProbe$Blackboard\n')
        targetfile=tmp/'KeyboardHandler.class';targetfile.write_bytes(target)
        minecraftfile=tmp/'Minecraft.class';minecraftfile.write_bytes(minecraft)
        transformed=tmp/'KeyboardHandler.transformed.class'
        # Java launcher @argfile decoding on this Windows host cannot reliably use
        # the Chinese workspace path. Use byte-identical ASCII temp copies only.
        if args.fc:
            production_classpath=tmp/'production.jar';shutil.copyfile(origin,production_classpath)
            assert q.digest(production_classpath.read_bytes())==origin_sha
        else:
            production_classpath=tmp/'production';shutil.copytree(ROOT/'build/classes/java/main',production_classpath)
        cp=os.pathsep.join(map(str,[out,*preferred,MIXIN,production_classpath,q.MC,*deps]))
        argfile=tmp/'cp.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,probe],tmp)
        java_log=q.run([q.JAVA/'java.exe','@'+str(argfile),'KeyboardMixinTransformProbe',targetfile,out/NAME,minecraftfile,transformed],tmp)
        result=q.parse_last_json(java_log)
        assert result['ok']and result['actual_mixin_transformer']
        transformed_bytes=transformed.read_bytes()
        assert result['target_sha256']==q.digest(target)and result['mixin_sha256']==q.digest(mixin)
        assert result['downstream_minecraft_sha256']==q.digest(minecraft)and result['gui_key_gates']==3
        assert result['transformed_sha256']==q.digest(transformed_bytes)
        assert (out/NAME).read_bytes()==mixin and (out/CONFIG).read_bytes()==config
    assert q.digest(origin.read_bytes())==origin_sha,'Production input changed during audit'
    report={'ok':True,'schema':'piq-real-keyboard-mixin-1','mode':mode,'production_compiled':False,
        'compiled_only_probe':True,'minecraft_started':False,'game_or_global_config_modified':False,
        'input':{'path':str(origin),'sha256':origin_sha,'mixin_class_sha256':q.digest(mixin),'mixin_config_sha256':q.digest(config)},
        'runtime':{'mixin':str(MIXIN),'sha256':q.digest(MIXIN.read_bytes()),'declared_by':'NeoForge 21.1.236 moddev-config'},
        'actual_target':{'jar':str(q.MC),'class':'net/minecraft/client/KeyboardHandler.class','sha256':q.digest(target)},
        'downstream_gui':{'jar':str(q.MC),'class':'net/minecraft/client/Minecraft.class','sha256':q.digest(minecraft),'method':'handleKeybinds()V','keys':['keyInventory','keyChat','keyCommand']},
        'probe':result,'transformed_artifact':{'path':str(artifact.resolve()),'sha256':q.digest(transformed_bytes)},
        'probe_source_sha256':q.digest(probe.read_bytes()),'compiler_output':compile_log,'transformer_output':java_log,
        'limits':['Actual Sponge transformer with classpath-only IMixinService/blackboard audit scaffolding, not a ModLauncher game boot.',
                  'No Minecraft class was defined/initialized; router and native GLFW input were not executed.',
                  'Does not prove compatibility/order with other mods\' mixins or actual in-game keyboard/GUI behavior.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with artifact.open('xb')as stream:stream.write(transformed_bytes)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(args.report),'assertions':result['assertions'],'prefix_instructions':result['prefix_instructions']}))
if __name__=='__main__':main()
