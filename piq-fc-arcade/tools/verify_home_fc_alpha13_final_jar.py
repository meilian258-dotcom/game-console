"""Independent alpha13 final-archive audit. Reads the real JAR, never repacks it.

The new manifest must be independently frozen before this tool can succeed.
Old alpha10/9/8 audit code, manifests and reports are not changed or impersonated.
--report writes exclusively to a new alpha13 preview path and refuses overwrite.
Packaged pure-Java probes run input/pose/layout code, not Minecraft or a native core.
"""
from __future__ import annotations
import argparse, hashlib, json, re, struct, subprocess, tempfile, zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
import verify_home_fc_final_jar as old
import verify_home_fc_alpha12_final_jar as alpha12
from verify_home_fc_alpha9_geometry import points
from check_vintage_alpha11_seams import inspect as inspect_seams

VERSION='0.31.0-alpha.13'
PROTOCOL=30
MANIFEST=old.ROOT/'tools/home-fc-alpha13-final-reviewed-assets.json'
MANIFEST_SHA='F226112BC14BE3589EBE103BBB986B7238AF44B739A093144A5F7299390A2122'
ALPHA12_JAR_SHA='3F450FB280528FA2DFA115CC0B52C402E4EF292412FE261AFB9019FEEEA41D31'
ADDED={'assets/piq_fc_arcade/models/item/tv_remote.json'}
NEW_CLASSES=alpha12.NEW_CLASSES+('home/TvRemotePolicy','home/TvRemoteItem','home/TvRemoteService',
    'home/TvRemoteService$Target','home/TvRemoteService$LoadedBlockView',
    'client/CrtScanlinePattern','client/CrtScanlineVertexConsumer')
REMOTE_CONTRACT_FROZEN=True


def require(value,message):
    if not value:raise ValueError(message)


def validate_manifest(document):
    require(document.get('version')==VERSION and document.get('protocol')==PROTOCOL,'Wrong alpha13 version/protocol')
    previous=alpha12.frozen_manifest();assets=document.get('assets',{})
    require(isinstance(assets,dict) and len(assets)==68 and set(assets)==set(previous)|ADDED,'Expected 67 unchanged alpha12 paths plus exactly one remote item resource')
    require(all(isinstance(h,str) and re.fullmatch('[0-9a-fA-F]{64}',h) for h in assets.values()),'Malformed appearance hash')
    require(all(assets[n].upper()==h.upper() for n,h in previous.items()),'An inherited alpha12 appearance changed')
    return assets


def frozen_manifest(path=MANIFEST):
    require(MANIFEST_SHA is not None,'Alpha13 independent manifest has not been frozen')
    require(path.resolve()==MANIFEST.resolve(),'Must use the exact frozen alpha13 manifest path')
    require(old.file_sha(path)==MANIFEST_SHA,'Alpha13 manifest SHA differs from immutable review')
    return validate_manifest(json.loads(path.read_bytes()))


television_geometry=alpha12.television_geometry
computer_geometry=alpha12.computer_geometry


def remote_geometry(model):
    require(model.get('gui_light')=='front' and 'parent' not in model,'Remote must remain native baked geometry')
    expected={'piq_fc_arcade:block/home_retro_tv_'+t for t in ('screen','dark','rim','back','metal','white','red')}
    textures=model.get('textures',{})
    require(set(textures.values())==expected,'Remote added an unreviewed texture dependency')
    quads,vertices=points(model)
    require(len(model['elements'])==59 and len(quads)==314,'Remote element/actual face count changed')
    require(np.allclose(vertices.min(axis=0),[5.7,7.243,2.1],atol=1e-8,rtol=0)
        and np.allclose(vertices.max(axis=0),[10.3,8.67,13.9],atol=1e-8,rtol=0),'Remote actual geometry bounds changed')
    for q in quads:
        require(q.texture in expected and np.isfinite(q.uv).all() and np.min(q.uv)>=0 and np.max(q.uv)<=16,'Remote face texture/UV invalid')
    contexts={'gui','ground','fixed','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand'}
    display=model.get('display',{})
    require(set(display)==contexts,'Remote must keep all seven non-wearable display contexts')
    for context,parameters in display.items():
        for name,value in parameters.items():
            require(name in ('rotation','translation','scale') and len(value)==3 and np.isfinite(value).all(),'Nonfinite or unknown item pose')
        require(all(0<float(v)<=2 for v in parameters.get('scale',[1]*3)),'Remote item scale outside review bounds')
    require(display['firstperson_righthand']==display['firstperson_lefthand']
        and display['thirdperson_righthand']==display['thirdperson_lefthand'],'Vanilla mirrored hand poses must match')
    require(display['gui']=={'rotation':[85,18,0],'scale':[1.06]*3},'Remote GUI projection changed')
    require(display['firstperson_righthand']=={'rotation':[55,15,0],'translation':[-2,4.6,-1],'scale':[.42]*3},'Remote first-person pose changed')
    return {'elements':59,'actual_quads':314,'bounds':[vertices.min(axis=0).tolist(),vertices.max(axis=0).tolist()],
        'existing_texture_references':sorted(expected),'display_contexts':sorted(contexts),'new_png_count':0}


def unchanged_core_input_av_classes(archive,previous):
    prefixes=('cn/piq/fcarcade/core/','cn/piq/fcarcade/session/',
        'cn/piq/fcarcade/client/Controller','cn/piq/fcarcade/client/HomeAvCable')
    names=sorted(n for n in previous.namelist() if n.endswith('.class') and n.startswith(prefixes))
    require(len(names)>=20,'Frozen core/input/AV class selection unexpectedly empty')
    require(all(archive.read(n)==previous.read(n) for n in names),'CRT remote must not alter emulator, input, or AV implementation')
    return {'unchanged_classes':len(names),'class_paths':names}


def bytecode(jar,javap):
    require(REMOTE_CONTRACT_FROZEN,'Remote authority/render contract has not been independently frozen')
    checks=[c for c in alpha12.bytecode(jar,javap) if c['check']!='protocol29_main_and_cartridge']
    names=('FcNetwork','home.CartridgeNetwork','home.TvRemoteService','home.TvRemoteService$LoadedBlockView',
        'home.TvRemoteItem','home.TvRemotePolicy','home.HomeTvBlockEntity','home.HomeEndpointBlockEntity',
        'client.ArcadeBlockScreenRenderer','client.CrtScanlinePattern','client.CrtScanlineVertexConsumer','registry.ModItems')
    code={}
    for name in names:
        r=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),'cn.piq.fcarcade.'+name],
            capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(r.returncode==0,'Cannot inspect remote class: '+name);code[name]=r.stdout
    def method(name,signature):return old.javap_method(code[name],signature)
    def check(name,ok,evidence):checks.append({'check':name,'ok':bool(ok),'evidence':evidence})
    check('protocol30_main_and_cartridge',old.has_protocol_literal(code['FcNetwork'],30)
        and old.has_protocol_literal(code['home.CartridgeNetwork'],30),'Both actual payload registrars use 30')
    check('remote_separate_item_registration','// String tv_remote' in code['registry.ModItems']
        and 'home/TvRemoteItem' in code['registry.ModItems'],'Reusable remote is a separate registered item')
    item=code['home.TvRemoteItem']
    check('remote_all_use_entries_delegate_server_authority',all('activate:' in method('home.TvRemoteItem',s) for s in (
        'public net.minecraft.world.InteractionResult onItemUseFirst(',
        'public net.minecraft.world.InteractionResult useOn(',
        'public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use('))
        and 'TvRemoteService.use:' in item and 'UseAnim.NONE' in item,
        'Block-first, direct block and air use all invoke the same service; the hold latch has no eating animation')
    use=method('home.TvRemoteService','public static net.minecraft.world.InteractionResult use(')
    check('remote_attempt_cooldown_latch_and_finally_guard',all(t in use for t in ('TvRemotePolicy.canActivate',
        'addCooldown','startUsingItem','stillHolding:','permitted:','HomeTvBlockEntity.setScanlinesEnabled','ThreadLocal.remove'))
        and use.find('permitted:')<use.find('HomeTvBlockEntity.setScanlinesEnabled'),
        'Authority checked before toggle; attempts are rate-limited, held-use is latched and recursion guard always cleared')
    target=method('home.TvRemoteService','private static cn.piq.fcarcade.home.TvRemoteService$Target target(')
    check('remote_server_eye_first_hit_loaded_complete_tv',all(t in target for t in ('getEyePosition','getLookAngle',
        'finite:','ClipContext$Block.OUTLINE','LoadedBlockView.clip','HitResult$Type.BLOCK','TvRemotePolicy.inRange',
        'hasChunkAt','HomeHardware.loadedEndpoint','HomeTvStructure.complete','HomeTvBlockEntity.hardwareId'))
        and target.count('isWithinBounds')>=2,
        'Only the server eight-block first hit can resolve a complete loaded TV, with both hit and anchor inside world border')
    view=code['home.TvRemoteService$LoadedBlockView']
    check('remote_unloaded_chunks_block_ray',all('hasChunkAt' in method('home.TvRemoteService$LoadedBlockView',s) for s in (
        'public net.minecraft.world.level.block.state.BlockState getBlockState(',
        'public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(',
        'public net.minecraft.world.level.material.FluidState getFluidState('))
        and 'Blocks.BARRIER' in view,
        'Guarded BlockGetter treats missing chunks as barrier, including shape-neighbor queries')
    permitted=method('home.TvRemoteService','private static boolean permitted(')
    check('remote_claim_events_postfact_rechecks',all(t in permitted for t in ('RightClickBlock','isCanceled','getUseBlock',
        'getUseItem','TriState.FALSE','HomeHardware.allowAnchorInteraction')) and permitted.count('sameTarget:')==3,
        'Clicked and anchor protection are consulted, with three independent target/line-of-sight rechecks')
    same=method('home.TvRemoteService','private static boolean sameTarget(')
    check('remote_exact_target_identity_and_dual_permissions',all(t in same for t in ('stillHolding:','target:',
        'Target.identity','Target.clickedEntity','Target.tv','Target.level','if_acmpne')) and same.count('mayInteract')==2,
        'Recheck compares exact TV/clicked entity and hardware identity, dimension, held stack and both location permissions')
    setter=method('home.HomeTvBlockEntity','boolean setScanlinesEnabled(')
    constructor=method('home.HomeTvBlockEntity','public cn.piq.fcarcade.home.HomeTvBlockEntity(')
    check('scanlines_default_off_and_server_owned',all(t in setter for t in ('ServerLevel','isSameThread','isRemoved',
        'hasChunkAt','getBlockEntity','changed:')) and 'scanlinesEnabled' not in constructor,
        'New instances default false; only the exact loaded server instance can mutate its own flag')
    loaded=method('home.HomeTvBlockEntity','protected void loadAdditional(')
    saved=method('home.HomeTvBlockEntity','protected void saveAdditional(')
    endpoint=code['home.HomeEndpointBlockEntity']
    check('scanlines_persist_and_sync_vanilla_be',all(t in loaded for t in ('String CrtScanlines','CompoundTag.getBoolean','scanlinesEnabled'))
        and all(t in saved for t in ('String CrtScanlines','CompoundTag.putBoolean','scanlinesEnabled'))
        and all(t in endpoint for t in ('sendBlockUpdated','saveWithoutMetadata','ClientboundBlockEntityDataPacket.create')),
        'Per-TV NBT boolean and existing block-entity update path preserve state across load and synchronize viewers')
    rendering=method('client.ArcadeBlockScreenRenderer','static void render(')
    check('scanlines_only_selected_tv_at_draw_boundary',all(t in rendering for t in ('getBlockEntity','HomeTvBlockEntity',
        'HomeTvBlockEntity.scanlinesEnabled','CrtScanlineVertexConsumer','getProjectionMatrix','getWidth','getHeight','drawFace:'))
        and rendering.find('HomeTvBlockEntity.scanlinesEnabled')<rendering.find('CrtScanlineVertexConsumer'),
        'Only a HomeTvBlockEntity whose own flag is enabled wraps the original drawFace consumer')
    pattern=code['client.CrtScanlinePattern']
    check('scanline_sampling_and_brightness_constants',all(old.has_double_constant(pattern,k,v) for k,v in (
        ('SOURCE_ROWS',240),('MAX_DARKEN',.28),('MIN_PROJECTED_HEIGHT',240),('FULL_PROJECTED_HEIGHT',480))),
        'Static 240-row pattern fades from 240 to 480 projected pixels with maximum 28 percent darkening')
    shading=code['client.CrtScanlineVertexConsumer']
    flush=method('client.CrtScanlineVertexConsumer','private void flush(')
    check('scanline_single_layer_geometry_and_uv_shading',all(t in shading for t in ('projectedHeight:',
        'CrtScanlinePattern.strength','CrtScanlinePattern.brightness','CrtScanlinePattern.top','CrtScanlinePattern.bottom',
        'VertexConsumer.addVertex','VertexConsumer.setColor','VertexConsumer.setUv','VertexConsumer.setNormal'))
        and 'emit:' in flush and all(t not in shading for t in ('WasmNesCore','NesCore.runFrame','DynamicTexture','FrameDigest')),
        'Adapter replaces the original quad with shaded strips and does not mutate emulator pixels, textures or state')
    return checks


def packaged_probe(jar,javap):
    jdk=javap.parent;source=old.ROOT/'tools/probes/Alpha13PackagedProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-alpha13-probe-') as directory:
        result=subprocess.run([str(jdk/'javac.exe'),'-cp',str(jar),'-d',directory,str(source)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged probe compilation failed: '+result.stderr[:1000])
        result=subprocess.run([str(jdk/'java.exe'),'-cp',directory+';'+str(jar),'cn.piq.fcarcade.client.Alpha13PackagedProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged pure-Java input/pose/layout probe failed: '+result.stderr[:1500])
        return json.loads(result.stdout)


def packaged_vertex_probe(jar,javap):
    """Compile only QA capture code; both production shading classes come from the final JAR."""
    dependencies=[
        Path('C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar'),
        Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.5/22566d58af70ad3d72308bab63b8339906deb649/joml-1.10.5.jar')]
    require(all(p.is_file() for p in dependencies),'Actual cached Minecraft/JOML API dependencies required')
    require(old.file_sha(dependencies[0])=='8D45CC055677C4BCEC93E4831F510691989E4A413DBF6CC6DB57817BC2B96333','Minecraft test API changed')
    cp=';'.join(str(p) for p in [jar,*dependencies])
    source=old.ROOT/'tools/qa/CrtScanlineVertexConsumerTest.java'
    with tempfile.TemporaryDirectory(prefix='piq-alpha13-vertices-') as directory:
        command=[str(javap.parent/'javac.exe'),'-encoding','UTF-8','-proc:none','-sourcepath',directory,
            '-cp',cp,'-d',directory,str(source)]
        result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Actual vertex probe compilation failed: '+result.stderr[:1800])
        require(not (Path(directory)/'cn/piq/fcarcade/client/CrtScanlineVertexConsumer.class').exists(),
            'Probe must not compile replacement production classes')
        result=subprocess.run([str(javap.parent/'java.exe'),'-cp',str(jar)+';'+directory+';'+';'.join(map(str,dependencies)),
            'cn.piq.fcarcade.client.CrtScanlineVertexConsumerTest'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Actual final-JAR vertex probe failed: '+result.stderr[:1800])
        match=re.fullmatch(r'CRT actual vertex consumer: (\d+) cases / (\d+) assertions passed\s*',result.stdout)
        require(match is not None and int(match[1])==4 and int(match[2])>16000,'Unexpected vertex probe coverage')
        return {'ok':True,'cases':int(match[1]),'assertions':int(match[2]),'source':'Final JAR production classes; actual Minecraft VertexConsumer/JOML',
            'probe_sha256':old.file_sha(source),'dependencies':{str(p):old.file_sha(p) for p in dependencies}}


def inspect(jar,expected_jar,javap):
    require(re.fullmatch('[0-9a-fA-F]{64}',expected_jar),'Expected final JAR SHA256 required')
    assets=frozen_manifest();digest=old.file_sha(jar);require(digest==expected_jar.upper(),'Final JAR hash differs from supplied candidate')
    require(jar.name=='piq_fc_arcade-'+VERSION+'.jar','Incorrect alpha13 artifact filename')
    prior=old.DELIVERY/'piq_fc_arcade-0.31.0-alpha.12.jar';baseline=old.DELIVERY/'piq_fc_arcade-0.30.0-beta.3.jar'
    require(old.file_sha(prior)==ALPHA12_JAR_SHA,'Frozen alpha12 archive changed');require(old.file_sha(baseline)==old.BETA3_SHA,'Protected beta3 baseline changed')
    report={'schema':1,'version':VERSION,'protocol':30,'validated_at_utc':datetime.now(timezone.utc).isoformat(),'jar_path':str(jar.resolve()),'jar_sha256':digest,'jar_bytes':jar.stat().st_size,'manifest_sha256':MANIFEST_SHA,'frozen_alpha12_jar_sha256':ALPHA12_JAR_SHA}
    with zipfile.ZipFile(jar) as archive,zipfile.ZipFile(baseline) as base,zipfile.ZipFile(prior) as previous:
        require(not old.duplicate_entries(archive),'Duplicate ZIP paths');report['jar_entry_count']=len(archive.infolist())
        report['assets']=old.asset_checks(archive,assets);require(all(t['ok'] for t in report['assets']),'Reviewed resource missing or changed')
        inherited=alpha12.frozen_manifest();require(all(archive.read(n)==previous.read(n) for n in inherited),'One of 67 unchanged alpha12 resources changed')
        report['unchanged_alpha12_assets']=67;report['added_appearance_assets']=sorted(ADDED)
        protected={n:old.sha(base.read(n)) for n in base.namelist() if old.appearance(n,True) and n not in assets};require(len(protected)==43,'Protected baseline path count changed')
        report['other_appearance']=old.asset_checks(archive,protected);require(all(t['ok'] for t in report['other_appearance']),'Protected beta3 appearance changed')
        unexpected=[n for n in archive.namelist() if old.appearance(n,True) and n not in assets and n not in protected];require(not unexpected,'Unreviewed appearance in JAR: '+str(unexpected))
        metadata=archive.read('META-INF/neoforge.mods.toml');require(old.mod_version(metadata)==VERSION,'Mod metadata version mismatch')
        report['enum_extensions']=old.validate_enum_extensions(metadata,json.loads(archive.read(old.ENUM_EXTENSIONS)))
        required=old.REQUIRED_CLASSES+old.ALPHA4_CLASSES+old.ALPHA5_CLASSES+old.ALPHA6_CLASSES+old.ALPHA8_CLASSES+old.ALPHA9_CLASSES+NEW_CLASSES
        for name in required:
            raw=archive.read('cn/piq/fcarcade/'+name+'.class');require(raw[:4]==b'\xca\xfe\xba\xbe' and struct.unpack_from('>H',raw,6)[0]==65,'Missing/invalid Java21 class: '+name)
        report['required_java21_classes']=len(required)
        report['runtime_blobs']=old.asset_checks(archive,old.RUNTIME)
        report['unchanged_core_input_av']=unchanged_core_input_av_classes(archive,previous)
        require(all(t['ok'] for t in report['runtime_blobs']),'DLL/WASM bytes changed')
        require(old.pe_x64(archive.read('natives/windows-x86_64/wasmtime4j.dll')) and archive.read('core/nes_rust_wasm_bg.wasm')[:8]==b'\0asm\x01\0\0\0','Native header invalid')
        report['new_tv_geometry']={}
        for name,vintage in [('large_lcd_tv',False),('vintage_tv',True)]:
            prefix='assets/piq_fc_arcade/';model=json.loads(archive.read(prefix+'models/block/home_'+name+'.json'))
            report['new_tv_geometry'][name]=television_geometry(model,vintage)
            if vintage:
                report['new_tv_geometry'][name]['seams']=inspect_seams(model)
                require(report['new_tv_geometry'][name]['seams']['ok'],'CRT actual-quad seam check failed')
            item=json.loads(archive.read(prefix+'models/item/'+name+'.json'));require(item['parent']=='piq_fc_arcade:block/home_'+name,'TV item parent mismatch')
            expected={'variants':{'facing='+d:dict({'model':'piq_fc_arcade:block/home_'+name},**({'y':i*90} if i else {})) for i,d in enumerate(('north','east','south','west'))}}
            require(json.loads(archive.read(prefix+'blockstates/'+name+'.json'))==expected,'TV baked orientation route mismatch')
        computer=json.loads(archive.read('assets/piq_fc_arcade/models/block/cartridge_computer.json'))
        report['computer_geometry']=computer_geometry(computer)
        report['remote_geometry']=remote_geometry(json.loads(archive.read('assets/piq_fc_arcade/models/item/tv_remote.json')))
        require(json.loads(archive.read('assets/piq_fc_arcade/models/item/cartridge_computer.json'))['parent']=='piq_fc_arcade:block/cartridge_computer','Computer item route wrong')
        expected={'variants':{'facing='+d:dict({'model':'piq_fc_arcade:block/cartridge_computer'},**({'y':i*90} if i else {})) for i,d in enumerate(('north','east','south','west'))}}
        require(json.loads(archive.read('assets/piq_fc_arcade/blockstates/cartridge_computer.json'))==expected,'Computer four-facing baked route wrong')
        computer_loot=json.loads(archive.read('data/piq_fc_arcade/loot_table/blocks/cartridge_computer.json'))
        require(any(e.get('name')=='piq_fc_arcade:cartridge_computer' for p in computer_loot.get('pools',[]) for e in p.get('entries',[])),'Computer single-block drop missing')
        require(json.loads(archive.read('assets/piq_fc_arcade/blockstates/large_lcd_tv_part.json'))=={'variants':{'':{'model':'piq_fc_arcade:block/dual_cabinet'}}},'Large TV proxy must remain empty')
        for name in ('large_lcd_tv','large_lcd_tv_part'):
            require(json.loads(archive.read('data/piq_fc_arcade/loot_table/blocks/'+name+'.json')).get('pools')==[],'Large TV loot must be owned only by assembly ledger')
        vintage_loot=json.loads(archive.read('data/piq_fc_arcade/loot_table/blocks/vintage_tv.json'))
        require(any(e.get('name')=='piq_fc_arcade:vintage_tv' for p in vintage_loot.get('pools',[]) for e in p.get('entries',[])),'Single CRT item drop missing')
    report['bytecode_checks']=bytecode(jar,javap);require(all(c['ok'] for c in report['bytecode_checks']),'Packaged bytecode checks failed: '+str([c['check'] for c in report['bytecode_checks'] if not c['ok']]))
    report['packaged_pure_java_probe']=packaged_probe(jar,javap)
    report['packaged_actual_vertex_probe']=packaged_vertex_probe(jar,javap)
    require(old.file_sha(jar)==digest,'JAR changed during independent audit')
    report['ok']=True;report['limits']=['No Minecraft gameplay, screenshots or native core execution in this audit','Native runtime checks cover immutable bytes/headers only; root supplies DLL/WASM smoke','Pure Java probes load classes from the final JAR, never current build/classes or source implementations']
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--jar-sha256',required=True)
    parser.add_argument('--javap',type=Path,default=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe'));parser.add_argument('--report',type=Path);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    if not args.check_only:
        require(args.report is not None,'Use --report or --check-only')
        args.report.resolve().relative_to((old.DELIVERY/'家用FC-0.31.0-alpha.13-模型预览').resolve());require(not args.report.exists(),'Never overwrite an existing audit report')
    result=inspect(args.jar,args.jar_sha256,args.javap)
    if not args.check_only:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps(result,ensure_ascii=True))
