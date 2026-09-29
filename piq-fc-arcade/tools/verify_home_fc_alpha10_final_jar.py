"""Independent alpha10 final-archive audit. Reads the real JAR, never repacks it.

The new manifest must be independently frozen before this tool can succeed.
Old alpha9/8 audit code, manifests and reports are not changed or impersonated.
--report writes exclusively to a new alpha10 preview path and refuses overwrite.
Packaged pure-Java probes run input/pose/layout code, not Minecraft or a native core.
"""
from __future__ import annotations
import argparse, hashlib, json, re, struct, subprocess, tempfile, zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
import verify_home_fc_final_jar as old
from verify_home_fc_alpha9_geometry import points

VERSION='0.31.0-alpha.10'
PROTOCOL=28
MANIFEST=old.ROOT/'tools/home-fc-alpha10-final-reviewed-assets.json'
MANIFEST_SHA='16E55B59247FC4C46A8F82E717EAF9945724165D3BBB74FF96FDCEBB4B599D0E'
ALPHA9_JAR_SHA='116D9FD994DB001DF4CA753C22DD3D2D910FECBBE7EF0BC242D622ADA14AC1C7'
ADDED={ 'assets/piq_fc_arcade/'+p for p in (
    'models/block/home_large_lcd_tv.json','models/item/large_lcd_tv.json',
    'blockstates/large_lcd_tv.json','blockstates/large_lcd_tv_part.json',
    'models/block/home_vintage_tv.json','models/item/vintage_tv.json','blockstates/vintage_tv.json') }
NEW_CLASSES=('home/LargeLcdTvLayout','home/LargeLcdTvFootprint','home/LargeLcdTvBlock',
    'home/LargeLcdTvBlockItem','home/LargeLcdTvPartBlock','home/LargeLcdTvPartBlockEntity',
    'home/LargeLcdTvAssemblyData','home/LargeLcdTvAssemblyLedger','home/LargeLcdTvRemovalGate',
    'home/LargeLcdTvStructure','layout/LargeLcdPresentation','home/VintageTvBlock','home/VintageTvLayout',
    'session/ControllerInputTransitions','client/ControllerFramePresentation')


def require(value,message):
    if not value:raise ValueError(message)


def validate_manifest(document):
    require(document.get('version')==VERSION and document.get('protocol')==PROTOCOL,'Wrong alpha10 version/protocol')
    require(old.file_sha(old.ALPHA9_MANIFEST)==old.ALPHA9_MANIFEST_SHA,'Frozen alpha9 v2 manifest changed')
    previous=json.loads(old.ALPHA9_MANIFEST.read_bytes())['assets'];assets=document.get('assets',{})
    require(isinstance(assets,dict) and len(assets)==64 and set(assets)==set(previous)|ADDED,'Expected precisely 57 inherited plus seven new appearance paths')
    require(all(isinstance(h,str) and re.fullmatch('[0-9a-fA-F]{64}',h) for h in assets.values()),'Malformed appearance hash')
    require(all(assets[n].upper()==h.upper() for n,h in previous.items()),'An inherited alpha9 appearance changed')
    return assets


def frozen_manifest(path=MANIFEST):
    require(MANIFEST_SHA is not None,'Alpha10 independent manifest has not been frozen')
    require(path.resolve()==MANIFEST.resolve(),'Must use the exact frozen alpha10 manifest path')
    require(old.file_sha(path)==MANIFEST_SHA,'Alpha10 manifest SHA differs from immutable review')
    return validate_manifest(json.loads(path.read_bytes()))


def television_geometry(model,vintage=False):
    quads,v=points(model)
    count=161 if vintage else 42
    bounds=[[.25,0,1.82],[15.75,14.15,14.04]] if vintage else [[-8,0,5],[24,19.5,11]]
    low=[4.6,2.1,2.28] if vintage else [-7,1.5,6]
    high=[14.6,9.6,2.3] if vintage else [23,18.375,6.08]
    require(len(model['elements'])==count,'Unexpected TV element count')
    require(np.allclose([v.min(0),v.max(0)],bounds,atol=1e-8,rtol=0),'Unexpected actual TV vertex bounds')
    candidates=[e for e in model['elements'] if np.allclose(e['from'],low,atol=1e-8,rtol=0) and np.allclose(e['to'],high,atol=1e-8,rtol=0)]
    require(len(candidates)==1 and set(candidates[0]['faces'])=={'north'},'Physical screen must be one exact front-only quad')
    require(all(-16<=n<=32 for e in model['elements'] for k in ('from','to') for n in e[k]),'Model exceeds vanilla JSON element range')
    require(all(q.uv.min()>=0 and q.uv.max()<=16 for q in quads),'Invalid TV UV coordinates')
    ratio=(high[0]-low[0])/(high[1]-low[1]);require(abs(ratio-(4/3 if vintage else 16/9))<1e-12,'TV physical screen aspect changed')
    return {'elements':count,'actual_bounds':bounds,'screen_from':low,'screen_to':high,'physical_screen_aspect':ratio,'front_only_screen':True}


def bytecode(jar,javap):
    names=('FcNetwork','home.CartridgeNetwork','ArcadeInputPayload','client.ControllerPoseLayout','client.ControllerPose',
        'client.ClientArcadeEvents','client.ClientArcadeSession','client.ClientNesWorker','client.ControllerFramePresentation',
        'session.ControllerInputTransitions','session.LockstepState','client.ClientControllerAnimation',
        'server.ServerArcadeSessions$Manager','server.ServerCartridgeAssemblyService','home.FcCartridgeData',
        'registry.ModItems','registry.ModBlocks','registry.ModBlockEntities','registry.CreativeTabCatalog',
        'home.HomeTvStructure','home.LargeLcdTvStructure','home.LargeLcdTvBlock','home.VintageTvBlock',
        'client.HomeHardwareRenderer','client.ArcadeBlockScreenRenderer','layout.LargeLcdPresentation')
    def read(name):
        result=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),'cn.piq.fcarcade.'+name],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'javap failed: '+name+' '+result.stderr[:200]);return name,result.stdout
    with ThreadPoolExecutor(max_workers=4) as pool:code=dict(pool.map(read,names))
    checks=[]
    def check(name,condition,evidence):checks.append({'check':name,'ok':bool(condition),'evidence':evidence})
    pose=code['client.ControllerPoseLayout'];worker=code['client.ClientNesWorker'];session=code['client.ClientArcadeSession'];events=code['client.ClientArcadeEvents']
    check('protocol28_main_and_cartridge',old.has_protocol_literal(code['FcNetwork'],28) and old.has_protocol_literal(code['home.CartridgeNetwork'],28),'Both packaged payload registrars declare 28')
    check('explicit_lifecycle_release_on_wire',all(t in code['ArcadeInputPayload'] for t in ('boolean forceRelease','writeBoolean','readBoolean')),'Normal queued key-up remains separate from forced lifecycle release')
    check('slanted_pose_constants',all(old.has_double_constant(pose,n,v) for n,v in {'FIRST_PITCH':-60,'FIRST_ARM_PITCH':30,'FIRST_ARM_ROLL':60,'FIRST_ARM_SCALE':.82,'DEFAULT_HAND_SIZE':1.18,'FIRST_IDLE_Y':-.62,'FIRST_TWO_HAND_Z':-1.46,'FIRST_MIXED_Z':-1.58}.items()),'Only reviewed first-person angles change, not hand size or item position')
    check('shared_item_and_arm_rig',code['client.ControllerPose'].count('ControllerPoseLayout.first:')>=2 and 'ControllerPoseLayout.firstArm:' in code['client.ControllerPose'],'Item and both local arms call the same first-person rig')
    check('post_mapping_key_mouse_capture',all(t in events for t in ('InputEvent$Key','InputEvent$MouseButton$Post','ClientArcadeSession.captureInputEvent','ClientArcadeSession.suspendInput')),'Events include key, mouse post, and immediate screen-opening suspension')
    check('stream_fifo_and_completed_frame_presentation',all(t in worker for t in ('ControllerInputTransitions.offer','ControllerInputTransitions.nextFrame','ControllerInputTransitions.clear','NesCore.runFrame','ControllerFramePresentation.complete','ControllerFramePresentation.clear')),'STREAM consumes absolute-state edges per real core frame; presentation comes only from completed frames')
    check('bounded_nonmerged_input_queue',old.has_double_constant(code['session.ControllerInputTransitions'],'MAX_PENDING',32) and all(t in code['session.ControllerInputTransitions'] for t in ('ArrayDeque.addLast','ArrayDeque.removeFirst','failClosed')),'At most 32 pending edges, FIFO consumption and neutral overload handling')
    check('server_per_frame_input_fifo',all(t in code['session.LockstepState'] for t in ('ControllerInputTransitions.nextFrame','ControllerInputTransitions.offer','ControllerInputTransitions.clear','ControllerInputTransitions.failClosed')),'LOCKSTEP uses the same absolute-state edge queue and lifecycle flush')
    check('server_routes_release_and_records_effective_frames',all(t in code['server.ServerArcadeSessions$Manager'] for t in ('ArcadeInputPayload.forceRelease','LockstepState.acceptInput','LockstepState.advanceFrame','LockstepTimeline.record')),'Actual session manager forwards lifecycle release and records the per-frame effective queue output')
    check('input_suspension_and_animation_reset',all(t in session for t in ('ControllerInputCapture.suspend','ClientNesWorker.clearInput','ClientNesWorker.setControllerPresentationEnabled')) and 'ControllerButtonAnimation.clear' in code['client.ClientControllerAnimation'],'GUI/focus/pause loss clears queued local controls and presentation')
    assembly=code['server.ServerCartridgeAssemblyService']
    check('cartridge_assembly_guards_retained',assembly.count('FcCartridgeData.supportsAssembly')>=3 and all(t in assembly for t in ('CartridgeAssemblyBinding.permits','CartridgeAssemblyInventory.split','CartridgeAssemblyInventory.combine','ServerCartridgeService.cancelForAssembly')) and 'ItemStack.getComponentsPatch' in code['home.FcCartridgeData'],'Existing server-only split/combine and metadata refusal remain in actual JAR')
    check('new_tvs_separate_registration',all(t in code['registry.ModItems'] for t in ('large_lcd_tv','vintage_tv','LargeLcdTvBlockItem')) and all(t in code['registry.ModBlockEntities'] for t in ('LARGE_LCD_TV','LARGE_LCD_TV_PART','VINTAGE_TV')),'Both new TV variants are separately registered, with a new large-TV proxy')
    check('new_tv_catalog',all('// String '+t in code['registry.CreativeTabCatalog'] for t in ('large_lcd_tv','vintage_tv')),'Both new TV items are obtainable from the mod catalog')
    structure=code['home.LargeLcdTvStructure']
    check('large_tv_assembly_transaction',all(t in structure for t in ('LargeLcdTvAssemblyLedger','LargeLcdTvRemovalGate','hasChunkAt','mayInteract','restoringBlockSnapshots')) and 'getChunk:' not in structure and 'LargeLcdTvStructure' in code['home.HomeTvStructure'],'Large TV retains loaded-cell, permissions, rollback, single-drop ledger gates')
    check('vintage_single_cell',old.method_returns_constant_true(code['home.VintageTvBlock'],'singleBlockTv',''),'Vintage CRT stays a single-cell device')
    check('new_screens_use_correct_geometry',all(t in code['client.ArcadeBlockScreenRenderer'] for t in ('LargeLcdPresentation.frame','VintageTvLayout.screen')) and 'ScreenAspectFit.fit' in code['layout.LargeLcdPresentation'],'4:3 FC picture fits inside the large 16:9 panel; CRT uses its own 4:3 aperture')
    return checks


def packaged_probe(jar,javap):
    jdk=javap.parent;source=old.ROOT/'tools/probes/Alpha10PackagedProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-alpha10-probe-') as directory:
        result=subprocess.run([str(jdk/'javac.exe'),'-cp',str(jar),'-d',directory,str(source)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged probe compilation failed: '+result.stderr[:1000])
        result=subprocess.run([str(jdk/'java.exe'),'-cp',directory+';'+str(jar),'cn.piq.fcarcade.client.Alpha10PackagedProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged pure-Java input/pose/layout probe failed: '+result.stderr[:1500])
        return json.loads(result.stdout)


def inspect(jar,expected_jar,javap):
    require(re.fullmatch('[0-9a-fA-F]{64}',expected_jar),'Expected final JAR SHA256 required')
    assets=frozen_manifest();digest=old.file_sha(jar);require(digest==expected_jar.upper(),'Final JAR hash differs from supplied candidate')
    require(jar.name=='piq_fc_arcade-'+VERSION+'.jar','Incorrect alpha10 artifact filename')
    prior=old.DELIVERY/'piq_fc_arcade-0.31.0-alpha.9.jar';baseline=old.DELIVERY/'piq_fc_arcade-0.30.0-beta.3.jar'
    require(old.file_sha(prior)==ALPHA9_JAR_SHA,'Frozen alpha9 archive changed');require(old.file_sha(baseline)==old.BETA3_SHA,'Protected beta3 baseline changed')
    report={'schema':1,'version':VERSION,'protocol':28,'validated_at_utc':datetime.now(timezone.utc).isoformat(),'jar_path':str(jar.resolve()),'jar_sha256':digest,'jar_bytes':jar.stat().st_size,'manifest_sha256':MANIFEST_SHA,'frozen_alpha9_jar_sha256':ALPHA9_JAR_SHA}
    with zipfile.ZipFile(jar) as archive,zipfile.ZipFile(baseline) as base,zipfile.ZipFile(prior) as previous:
        require(not old.duplicate_entries(archive),'Duplicate ZIP paths');report['jar_entry_count']=len(archive.infolist())
        report['assets']=old.asset_checks(archive,assets);require(all(t['ok'] for t in report['assets']),'Reviewed resource missing or changed')
        inherited=json.loads(old.ALPHA9_MANIFEST.read_bytes())['assets'];require(all(archive.read(n)==previous.read(n) for n in inherited),'Inherited alpha9 resource bytes changed')
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
        require(all(t['ok'] for t in report['runtime_blobs']),'DLL/WASM bytes changed')
        require(old.pe_x64(archive.read('natives/windows-x86_64/wasmtime4j.dll')) and archive.read('core/nes_rust_wasm_bg.wasm')[:8]==b'\0asm\x01\0\0\0','Native header invalid')
        report['new_tv_geometry']={}
        for name,vintage in [('large_lcd_tv',False),('vintage_tv',True)]:
            prefix='assets/piq_fc_arcade/';model=json.loads(archive.read(prefix+'models/block/home_'+name+'.json'))
            report['new_tv_geometry'][name]=television_geometry(model,vintage)
            item=json.loads(archive.read(prefix+'models/item/'+name+'.json'));require(item['parent']=='piq_fc_arcade:block/home_'+name,'TV item parent mismatch')
            expected={'variants':{'facing='+d:dict({'model':'piq_fc_arcade:block/home_'+name},**({'y':i*90} if i else {})) for i,d in enumerate(('north','east','south','west'))}}
            require(json.loads(archive.read(prefix+'blockstates/'+name+'.json'))==expected,'TV baked orientation route mismatch')
        require(json.loads(archive.read('assets/piq_fc_arcade/blockstates/large_lcd_tv_part.json'))=={'variants':{'':{'model':'piq_fc_arcade:block/dual_cabinet'}}},'Large TV proxy must remain empty')
        for name in ('large_lcd_tv','large_lcd_tv_part'):
            require(json.loads(archive.read('data/piq_fc_arcade/loot_table/blocks/'+name+'.json')).get('pools')==[],'Large TV loot must be owned only by assembly ledger')
        vintage_loot=json.loads(archive.read('data/piq_fc_arcade/loot_table/blocks/vintage_tv.json'))
        require(any(e.get('name')=='piq_fc_arcade:vintage_tv' for p in vintage_loot.get('pools',[]) for e in p.get('entries',[])),'Single CRT item drop missing')
    report['bytecode_checks']=bytecode(jar,javap);require(all(c['ok'] for c in report['bytecode_checks']),'Packaged bytecode checks failed: '+str([c['check'] for c in report['bytecode_checks'] if not c['ok']]))
    report['packaged_pure_java_probe']=packaged_probe(jar,javap)
    require(old.file_sha(jar)==digest,'JAR changed during independent audit')
    report['ok']=True;report['limits']=['No Minecraft gameplay, screenshots or native core execution in this audit','Native runtime checks cover immutable bytes/headers only; root supplies DLL/WASM smoke','Pure Java probes load classes from the final JAR, never current build/classes or source implementations']
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--jar-sha256',required=True)
    parser.add_argument('--javap',type=Path,default=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe'));parser.add_argument('--report',type=Path);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    if not args.check_only:
        require(args.report is not None,'Use --report or --check-only')
        args.report.resolve().relative_to((old.DELIVERY/'家用FC-0.31.0-alpha.10-模型预览').resolve());require(not args.report.exists(),'Never overwrite an existing audit report')
    result=inspect(args.jar,args.jar_sha256,args.javap)
    if not args.check_only:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps(result,ensure_ascii=True))
