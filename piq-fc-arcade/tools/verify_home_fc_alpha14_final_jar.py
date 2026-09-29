"""Independent alpha14 final-JAR audit; frozen alpha13 is a baseline, never impersonated.

No source production classes are compiled, no old manifest or report is rewritten.
The 68-item alpha13 appearance manifest is deliberately reused unchanged and identified
as such, while metadata, exact ZIP delta and the external-system API are alpha14 checks.
"""
from __future__ import annotations
import argparse,json,re,struct,subprocess,tempfile,zipfile
from pathlib import Path
from datetime import datetime,timezone
import verify_home_fc_alpha13_final_jar as alpha13
old=alpha13.old
VERSION='0.31.0-alpha.14'
PROTOCOL=30
ALPHA13_SHA='681D1BE79DD87B67D64034FAE18CC022E942F6C6887E335A5CB70C05C87D2342'
SERVER_API=('home/ExternalHomeConsoleBlockEntity','home/HomeSystemCalls','home/HomeSystemRegistry',
    'home/HomeSystems','home/HomeSystems$Connection','home/HomeSystems$ServerHooks','home/HomeSystems$StopReason')
NEW_CLASSES=SERVER_API+('client/HomeVideoDisplay',)
ADDED={'cn/piq/fcarcade/'+n+'.class' for n in NEW_CLASSES}
CHANGED={'META-INF/MANIFEST.MF','META-INF/neoforge.mods.toml',
    'cn/piq/fcarcade/home/HomeHardware.class','cn/piq/fcarcade/client/ArcadeBlockScreenRenderer.class',
    'cn/piq/fcarcade/client/CrtScanlineVertexConsumer.class'}
def require(value,message):
    if not value:raise ValueError(message)

def appearance_contract():
    assets=alpha13.frozen_manifest()
    require(len(assets)==68,'Expected exact inherited alpha13 68-resource contract')
    return assets

def exact_delta(archive,previous):
    before=set(previous.namelist());after=set(archive.namelist())
    require(after-before==ADDED,'Unexpected added class/resource paths')
    require(not before-after,'An inherited archive entry was removed')
    changed={n for n in before if archive.read(n)!=previous.read(n)}
    require(changed==CHANGED,'Unexpected inherited byte changes: '+str(sorted(changed^CHANGED)))
    for name in ADDED:
        raw=archive.read(name)
        require(raw[:4]==b'\xca\xfe\xba\xbe' and struct.unpack_from('>H',raw,6)[0]==65,'New API must be Java21: '+name)
    for name in SERVER_API:
        raw=archive.read('cn/piq/fcarcade/'+name+'.class')
        require(all(s not in raw for s in (b'net/minecraft/client/',b'cn/piq/fcarcade/client/',b'WasmNesCore')),
            'Server external API links a client renderer or native emulator: '+name)
    return {'added':sorted(ADDED),'changed':sorted(changed),'unchanged_entries':len(before)-len(changed),
        'server_api_class_count':7,'public_display_class_count':1}

def new_bytecode(jar,javap):
    names=['home.HomeSystems','home.HomeSystemRegistry','home.HomeSystemCalls','home.ExternalHomeConsoleBlockEntity',
        'home.HomeSystems$Connection','home.HomeSystems$ServerHooks','home.HomeHardware','client.HomeVideoDisplay',
        'client.ArcadeBlockScreenRenderer','FcNetwork','home.CartridgeNetwork']
    code={}
    for n in names:
        p=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),'cn.piq.fcarcade.'+n],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(p.returncode==0,'Cannot inspect packaged API: '+n);code[n]=p.stdout
    checks=[]
    def check(n,value):checks.append({'check':n,'ok':bool(value)})
    def method(n,s):return old.javap_method(code[n],s)
    check('both_payload_registrars_remain_protocol30',all(old.has_protocol_literal(code[n],30) for n in ('FcNetwork','home.CartridgeNetwork')))
    ext=code['home.ExternalHomeConsoleBlockEntity']
    check('external_be_public_protected_constructor_fixed_system',all(s in ext for s in ('public abstract class','protected cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity(',
        'private final net.minecraft.resources.ResourceLocation systemId','HomeSystems.NES_SYSTEM')))
    check('external_be_does_not_expose_link_setters',not re.search(r'public .*\b(attach|clearLink)\(',ext))
    notify=method('home.ExternalHomeConsoleBlockEntity','public final void notifyHardwareChanged(')
    check('external_dirty_sync_requires_exact_loaded_server_be',all(s in notify for s in ('ServerLevel','isSameThread','hasChunkAt','getBlockEntity','isRemoved','changed:')))
    registry=code['home.HomeSystemRegistry'];calls=code['home.HomeSystemCalls']
    check('registry_no_replacement_and_pre_server_lock',all(s in registry for s in ('locked','Map.containsKey','IllegalStateException')) and 'ServerAboutToStartEvent' in code['home.HomeSystems'] and 'HomeSystemRegistry.lock' in code['home.HomeSystems'])
    check('callback_exception_and_reentry_isolation',all(s in calls for s in ('IdentityHashMap','Set.add','RuntimeException','Set.remove','ThreadLocal.remove')))
    connection=method('home.HomeSystems','public static java.util.Optional<cn.piq.fcarcade.home.HomeSystems$Connection> connection(')
    check('connection_main_thread_loaded_ledger_crosscheck',all(s in connection for s in ('isSameThread','HomeHardware.loadedEndpoint','HomeHardware.connectedEndpoint','if_acmpne','HomeSystems$Connection')))
    current=code['home.HomeSystems']
    check('connection_rechecks_snapshotted_identities',all(s in current for s in ('Connection.consoleId','Connection.televisionId','Connection.linkId','Connection.systemId','if_acmpne')))
    facts=method('home.HomeSystems','private static boolean interactionFacts(')
    check('interaction_player_liveness_hand_distance_and_two_permissions',all(s in facts for s in ('isAlive','isSpectator','hasDisconnected','getItemInHand','HomeHardware.mayUse','isCurrent:')) and facts.count('mayInteract')==2)
    interact=method('home.HomeSystems','static net.minecraft.world.InteractionResult interactAt(')
    check('interaction_standard_claim_events_postfact_and_finally',all(s in interact for s in ('RightClickBlock','isCanceled','getUseBlock','getUseItem','TriState.FALSE','HomeHardware.allowAnchorInteraction','ServerHooks.onInteract','ThreadLocal.remove')) and interact.count('interactionFacts:')>=4)
    stopped=method('home.HomeSystems','static void stopped(')
    check('stop_callback_does_not_require_live_connection',all(s in stopped for s in ('isSameThread','HomeSystemCalls.invoke')) and 'connection:' not in stopped)
    check('immutable_connection_has_no_public_constructor_or_mutator','private cn.piq.fcarcade.home.HomeSystems$Connection(' in code['home.HomeSystems$Connection'] and not re.search(r'public void ',code['home.HomeSystems$Connection']))
    nes=method('home.HomeHardware','public static cn.piq.fcarcade.home.HomeConsoleBlockEntity connectedConsole(')
    check('legacy_connected_console_stays_nes_only','HomeConsoleBlockEntity' in nes and 'connectedEndpoint:' in nes)
    unload=method('home.HomeHardware','static void unloaded(')
    check('unload_stops_without_refund_or_close','UNLOADED' in unload and all(s not in unload for s in ('HomeLinkLedger.close','refund:','returnCable:')))
    removal=method('home.HomeHardware','public static void removed(')
    check('removal_once_and_provider_callback','beginRemoval' in removal and 'HomeSystems.removed' in removal)
    cable=method('home.HomeHardware','public static net.minecraft.world.InteractionResult useCable(net.minecraft.server.level.ServerPlayer, net.minecraft.core.BlockPos, net.minecraft.world.InteractionHand, net.minecraft.world.phys.BlockHitResult);')
    check('cable_ledger_and_existing_nes_start_retained',all(s in cable for s in ('HomeLinkLedger.connect','HomeEndpointBlockEntity.attach','HomeSystems.linked','ServerArcadeSessions.startHomeConsole')))
    render=method('client.HomeVideoDisplay','public static boolean render(')
    check('public_display_requires_complete_identity_matched_hardware',all(s in render for s in ('AFTER_BLOCK_ENTITIES','hasChunkAt','ExternalHomeConsoleBlockEntity.systemId','ExternalHomeConsoleBlockEntity.hardwareId','HomeTvBlockEntity.hardwareId','HomeTvBlockEntity.linkId','ExternalHomeConsoleBlockEntity.linkId','HomeTvStructure.complete','Double.isFinite')))
    check('public_display_reuses_actual_face_scanlines_and_pose_cleanup',all(s in render for s in ('ArcadeBlockScreenRenderer.drawFace','CrtScanlineVertexConsumer','HomeTvBlockEntity.scanlinesEnabled','PoseStack.popPose','HomeHardwareRenderLayout.tvOffset')))
    check('draw_face_is_package_scope_not_private','\n  static void drawFace(' in code['client.ArcadeBlockScreenRenderer'] and '\n  private static void drawFace(' not in code['client.ArcadeBlockScreenRenderer'])
    return checks

def api_probe(jar,javap):
    source=old.ROOT/'tools/probes/Alpha14HomeSystemsProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-alpha14-api-') as directory:
        p=subprocess.run([str(javap.parent/'javac.exe'),'-encoding','UTF-8','-proc:none','-sourcepath',directory,'-cp',str(jar),'-d',directory,str(source)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(p.returncode==0,'Packaged API probe compile failed: '+p.stderr[:1200])
        require(not (Path(directory)/'cn/piq/fcarcade/home/HomeSystemRegistry.class').exists(),'Never compile a replacement production class')
        p=subprocess.run([str(javap.parent/'java.exe'),'-cp',str(jar)+';'+directory,'cn.piq.fcarcade.home.Alpha14HomeSystemsProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(p.returncode==0,'Packaged API probe failed: '+p.stderr[:1200])
        result=json.loads(p.stdout);require(result.get('ok') and result.get('assertions')>=16,'API probe coverage changed');return result

def inspect(jar,expected,javap):
    require(re.fullmatch('[0-9a-fA-F]{64}',expected),'Expected SHA256 is mandatory')
    require(jar.name=='piq_fc_arcade-'+VERSION+'.jar','Wrong alpha14 filename')
    digest=old.file_sha(jar);require(digest==expected.upper(),'Final JAR differs from supplied SHA256')
    previous=old.DELIVERY/'piq_fc_arcade-0.31.0-alpha.13.jar';baseline=old.DELIVERY/'piq_fc_arcade-0.30.0-beta.3.jar'
    require(old.file_sha(previous)==ALPHA13_SHA,'Frozen alpha13 changed');require(old.file_sha(baseline)==old.BETA3_SHA,'Protected beta3 changed')
    assets=appearance_contract()
    report={'schema':1,'version':VERSION,'protocol':PROTOCOL,'jar_path':str(jar.resolve()),'jar_sha256':digest,'jar_bytes':jar.stat().st_size,
        'validated_at_utc':datetime.now(timezone.utc).isoformat(),'inherited_appearance_manifest':str(alpha13.MANIFEST),
        'inherited_appearance_manifest_sha256':alpha13.MANIFEST_SHA,'frozen_alpha13_sha256':ALPHA13_SHA}
    with zipfile.ZipFile(jar) as archive,zipfile.ZipFile(previous) as prior,zipfile.ZipFile(baseline) as base:
        require(not old.duplicate_entries(archive),'Duplicate ZIP entries');report['jar_entry_count']=len(archive.infolist())
        report['exact_archive_delta']=exact_delta(archive,prior)
        require(old.mod_version(archive.read('META-INF/neoforge.mods.toml'))==VERSION,'Metadata version differs')
        report['enum_extensions']=old.validate_enum_extensions(archive.read('META-INF/neoforge.mods.toml'),json.loads(archive.read(old.ENUM_EXTENSIONS)))
        report['assets']=old.asset_checks(archive,assets);require(all(x['ok'] for x in report['assets']),'Inherited 68 appearances changed')
        protected={n:old.sha(base.read(n)) for n in base.namelist() if old.appearance(n,True) and n not in assets}
        require(len(protected)==43,'Protected appearance set changed');report['protected_assets']=old.asset_checks(archive,protected)
        require(all(x['ok'] for x in report['protected_assets']),'A protected beta3 asset changed')
        require(not [n for n in archive.namelist() if old.appearance(n,True) and n not in assets and n not in protected],'Unexpected appearance asset')
        report['unchanged_core_input_av']=alpha13.unchanged_core_input_av_classes(archive,prior)
        report['runtime_blobs']=old.asset_checks(archive,old.RUNTIME);require(all(x['ok'] for x in report['runtime_blobs']),'Immutable native runtime changed')
        require(old.pe_x64(archive.read('natives/windows-x86_64/wasmtime4j.dll')) and archive.read('core/nes_rust_wasm_bg.wasm')[:8]==b'\0asm\x01\0\0\0','Native header invalid')
        report['geometry']={name:alpha13.television_geometry(json.loads(archive.read('assets/piq_fc_arcade/models/block/home_'+name+'.json')),vintage) for name,vintage in [('large_lcd_tv',False),('vintage_tv',True)]}
        report['geometry']['computer']=alpha13.computer_geometry(json.loads(archive.read('assets/piq_fc_arcade/models/block/cartridge_computer.json')))
        report['geometry']['remote']=alpha13.remote_geometry(json.loads(archive.read('assets/piq_fc_arcade/models/item/tv_remote.json')))
    report['inherited_bytecode_checks']=alpha13.bytecode(jar,javap)
    report['new_api_bytecode_checks']=new_bytecode(jar,javap)
    failed=[x['check'] for x in report['inherited_bytecode_checks']+report['new_api_bytecode_checks'] if not x['ok']]
    require(not failed,'Bytecode assertions failed: '+str(failed))
    report['packaged_pure_geometry_input_probe']=alpha13.packaged_probe(jar,javap)
    report['packaged_external_registry_callback_probe']=api_probe(jar,javap)
    require(old.file_sha(jar)==digest and old.file_sha(previous)==ALPHA13_SHA,'An archive changed during audit')
    report['ok']=True;report['limits']=['Offline final-JAR byte/geometry/API checks, not Minecraft or native-core execution.',
        'Root separately runs actual aspect/vertex consumer probe; this tool does not duplicate it.',
        'No old checker constants, manifests or reports are modified.']
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--jar-sha256',required=True)
    p.add_argument('--javap',type=Path,default=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe'));p.add_argument('--report',type=Path);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    if not a.check_only:
        require(a.report is not None,'Specify a new report or --check-only');a.report.resolve().relative_to((old.DELIVERY/'家用FC-0.31.0-alpha.14-模型预览').resolve());require(not a.report.exists(),'Never overwrite an audit report')
    result=inspect(a.jar,a.jar_sha256,a.javap)
    if not a.check_only:
        a.report.parent.mkdir(parents=True,exist_ok=True)
        with a.report.open('x',encoding='utf-8') as output:json.dump(result,output,ensure_ascii=False,indent=2);output.write('\n')
    print(json.dumps(result,ensure_ascii=True))
