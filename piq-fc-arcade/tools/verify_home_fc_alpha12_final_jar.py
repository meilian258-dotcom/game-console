"""Independent alpha12 final-archive audit. Reads the real JAR, never repacks it.

The new manifest must be independently frozen before this tool can succeed.
Old alpha10/9/8 audit code, manifests and reports are not changed or impersonated.
--report writes exclusively to a new alpha12 preview path and refuses overwrite.
Packaged pure-Java probes run input/pose/layout code, not Minecraft or a native core.
"""
from __future__ import annotations
import argparse, hashlib, json, re, struct, subprocess, tempfile, zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
import verify_home_fc_final_jar as old
import verify_home_fc_alpha11_final_jar as alpha11
from verify_home_fc_alpha9_geometry import points
from check_vintage_alpha11_seams import inspect as inspect_seams

VERSION='0.31.0-alpha.12'
PROTOCOL=29
MANIFEST=old.ROOT/'tools/home-fc-alpha12-final-reviewed-assets.json'
MANIFEST_SHA='1C11E36CA3BE0A5E883A5F3A6C6F5870EABFBFE58D1C4249996725A996A6BB7A'
ALPHA11_JAR_SHA='7B6CD6B4D38E8E02B20F2CC021779BEECD4C3582450B40A588EFB349C650DED8'
ADDED={'assets/piq_fc_arcade/'+p for p in ('models/block/cartridge_computer.json',
    'models/item/cartridge_computer.json','blockstates/cartridge_computer.json')}
NEW_CLASSES=alpha11.NEW_CLASSES+('home/CartridgeComputerBinding','home/CartridgeComputerBlock',
    'home/CartridgeComputerBlockEntity','home/CartridgeComputerLayout')
COMPUTER_BOUNDS=[[.65,0,.6],[15,13.5,14.6]]


def require(value,message):
    if not value:raise ValueError(message)


def validate_manifest(document):
    require(document.get('version')==VERSION and document.get('protocol')==PROTOCOL,'Wrong alpha12 version/protocol')
    previous=alpha11.frozen_manifest();assets=document.get('assets',{})
    require(isinstance(assets,dict) and len(assets)==67 and set(assets)==set(previous)|ADDED,'Expected 64 unchanged alpha11 paths plus exactly three computer resources')
    require(all(isinstance(h,str) and re.fullmatch('[0-9a-fA-F]{64}',h) for h in assets.values()),'Malformed appearance hash')
    require(all(assets[n].upper()==h.upper() for n,h in previous.items()),'An inherited alpha11 appearance changed')
    return assets


def frozen_manifest(path=MANIFEST):
    require(MANIFEST_SHA is not None,'Alpha12 independent manifest has not been frozen')
    require(path.resolve()==MANIFEST.resolve(),'Must use the exact frozen alpha12 manifest path')
    require(old.file_sha(path)==MANIFEST_SHA,'Alpha12 manifest SHA differs from immutable review')
    return validate_manifest(json.loads(path.read_bytes()))


def television_geometry(model,vintage=False):
    return alpha11.television_geometry(model,vintage)


def computer_geometry(model):
    require(COMPUTER_BOUNDS is not None,'Computer geometry has not been independently frozen')
    quads,v=points(model)
    require(np.allclose([v.min(0),v.max(0)],COMPUTER_BOUNDS,atol=1e-8,rtol=0),'Final computer actual bounds changed')
    require(all(-16<=n<=32 for e in model['elements'] for k in ('from','to') for n in e[k]),'Computer JSON coordinate overflow')
    require(all(q.uv.min()>=0 and q.uv.max()<=16 for q in quads),'Computer UV outside atlas')
    allowed={'piq_fc_arcade:block/home_retro_tv_'+n for n in ('black','dark','metal','red','rim','screen','white','yellow')}
    allowed.add('minecraft:block/lime_concrete')
    require(set(q.texture for q in quads)<=allowed,'Unreviewed computer texture dependency')
    glass=[e for e in model['elements'] if e.get('name')=='4比3内凹黑玻璃']
    require(len(glass)==1 and set(glass[0]['faces'])=={'north'},'Computer needs one front-only glass surface')
    require(np.allclose([glass[0]['from'],glass[0]['to']],[[3.05,5.55,6.15],[11.95,12.225,6.25]],atol=1e-8,rtol=0),'Computer 4:3 recessed glass changed')
    glyphs=[q for q in quads if model['elements'][q.element_index].get('name','').startswith('静态终端字符-')]
    require(bool(glyphs) and all(q.direction=='north' and q.vertices[:,0].min()>=3.05 and q.vertices[:,0].max()<=11.95
        and q.vertices[:,1].min()>=5.55 and q.vertices[:,1].max()<=12.225
        and np.allclose(q.vertices[:,2],6.126,atol=1e-8,rtol=0) for q in glyphs),'Terminal glyphs must stay on the visible inset glass')
    return {'elements':len(model['elements']),'actual_quads':len(quads),'actual_bounds':COMPUTER_BOUNDS,
        'no_new_png':True,'static_terminal_glyph_quads':len(glyphs),'screen_aspect':4/3,'terminal_is_static_geometry_not_live_emulator':True}


def players_success_is_status(setting):
    start=setting.find('ServerRomLibrary.setMaxPlayers:')
    if start<0:return False
    tail=setting[start:]
    return bool(re.search(r'aload_2\s+\d+:\s+iconst_1\s+\d+:\s+ldc(?:_w)?\s+[^\n]*// String\s*\n',tail)
        and 'ServerRomLibrary.catalog:' in tail and 'Method send:' in tail)


def bytecode(jar,javap):
    # Alpha11 stays immutable. Reuse only its 22 network-independent predicates,
    # and separately check both actual alpha12 registrars against protocol 29.
    checks=[c for c in alpha11.bytecode(jar,javap) if c['check']!='protocol28_main_and_cartridge']
    names=('FcNetwork','home.CartridgeNetwork','home.CartridgeComputerBinding','home.CartridgeComputerBlock',
        'home.CartridgeComputerBlockEntity','home.FcCartridgeItem','server.ServerCartridgeService',
        'server.ServerCartridgeService$State','server.ServerCartridgeService$Session','server.ServerRomLibrary',
        'client.ClientCartridgeEditor','registry.ModBlocks','registry.ModItems','registry.ModBlockEntities')
    code={}
    for name in names:
        result=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),
            'cn.piq.fcarcade.'+name],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Cannot inspect computer authority: '+name);code[name]=result.stdout
    def check(name,ok,evidence):checks.append({'check':name,'ok':bool(ok),'evidence':evidence})
    def method(name,signature):return old.javap_method(code[name],signature)
    service=code['server.ServerCartridgeService'];state=code['server.ServerCartridgeService$State']
    check('protocol29_main_and_cartridge',old.has_protocol_literal(code['FcNetwork'],29)
        and old.has_protocol_literal(code['home.CartridgeNetwork'],29),'Both final payload registrars declare 29')
    check('computer_registries_retained_and_separate',all('CARTRIDGE_COMPUTER' in code['registry.'+n]
        for n in ('ModBlocks','ModItems','ModBlockEntities')),'New workstation has separate block/item/BE registries')
    opened=method('server.ServerCartridgeService','public static void openAt(')
    check('computer_open_guard_before_card_identity',all(t in opened for t in ('loadedComputer:','computerPermitted:',
        'FcCartridgeData.supportsAssembly','uniqueCard:')) and opened.find('FcCartridgeData.supportsAssembly')<opened.find('FcCartridgeData.ensureIdentity'),
        'Opening checks live computer permission and supported metadata before creating/normalizing identity')
    loaded=method('server.ServerCartridgeService','private static cn.piq.fcarcade.home.CartridgeComputerBlockEntity loadedComputer(')
    permission=method('server.ServerCartridgeService','private static boolean computerPermitted(')
    check('computer_permission_loaded_instance_and_claim_event',all(t in loaded for t in ('isSameThread','hasChunkAt','isRemoved'))
        and loaded.find('hasChunkAt')<loaded.find('getBlockEntity')
        and all(t in permission for t in ('RightClickBlock','isCanceled','getUseBlock','getUseItem','TriState.FALSE','ThreadLocal.remove'))
        and permission.count('computerFacts:')>=2,
        'No forced chunk load; standard claim event cancellation/deny checked, facts rechecked, recursion guard finally cleared')
    valid=method('server.ServerCartridgeService$State','boolean valid(')
    matching=method('server.ServerCartridgeService$State','boolean cardMatches(')
    unique=method('server.ServerCartridgeService','private static boolean uniqueCard(')
    check('held_stack_snapshot_unique_and_post_event_recheck',valid.count('cardMatches:')>=2 and 'computerPermitted' in valid
        and all(t in matching for t in ('FcCartridgeData.supportsAssembly','ItemStack.isSameItemSameComponents','uniqueCard','CartridgeEditBinding.permits'))
        and all(t in unique for t in ('IdentityHashMap','getContainerSize','getCarried')),
        'Original stack/token/slot plus component snapshot and personal-inventory uniqueness, checked again after public events')
    commit=method('server.ServerCartridgeService$State','void commit(')
    check('final_card_commit_rechecks_authority_and_rom',all(t in commit for t in ('valid:','ServerRomLibrary.find','FcCartridgeData.write','expectedContents','ItemStack.copy'))
        and commit.find('valid:')<commit.find('FcCartridgeData.write')
        and commit.find('ServerRomLibrary.find')<commit.find('FcCartridgeData.write'),
        'Final main-thread commit rejects revoked station/card or removed ROM and updates only its own snapshot after writing')
    async_headers=re.findall(r'^  private [^\n]*lambda\$(?:write|finish)\$[^\n]*;',state,re.MULTILINE)
    async_commits=[old.javap_method(state,h) for h in async_headers if 'commit:' in old.javap_method(state,h)]
    check('asynchronous_completions_revalidate_before_commit',len(async_commits)==2 and all('valid:' in m and m.find('valid:')<m.find('commit:') for m in async_commits)
        and 'MinecraftServer.execute' in state,
        'Both uploaded-file and restored-cover completion callbacks return to the server thread and revalidate before commit')
    be=code['home.CartridgeComputerBlockEntity']
    check('computer_removed_or_unloaded_revokes_editor','ServerCartridgeService.computerRemoved' in be
        and 'invalidateEditors:' in method('home.CartridgeComputerBlockEntity','public void setRemoved(')
        and 'invalidateEditors:' in method('home.CartridgeComputerBlockEntity','public void onChunkUnloaded('),
        'Removing or unloading the precise workstation instance cancels its editor leases')
    item_air=method('home.FcCartridgeItem','public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(')
    check('air_editor_removed_real_computer_entry_only',bool(item_air) and 'ServerCartridgeService.open' not in item_air
        and 'ServerCartridgeService.openAt' in code['home.CartridgeComputerBlock']
        and 'ServerCartridgeService.open:' not in code['home.FcCartridgeItem'],
        'Air-use cannot mint editor tokens; the physical workstation invokes openAt')
    setting=method('server.ServerCartridgeService$State','void setPlayers(')
    check('server_set_players_bounded_authorized_and_not_card_write',old.has_double_constant(code['home.CartridgeNetwork'],'SET_PLAYERS',8)
        and all(t in setting for t in ('CartridgeComputerBinding.permitsPlayersSetting','valid:','ServerRomLibrary.setMaxPlayers','CartridgeNetwork$Request.data','FcCartridgeData.romSha','String.equals'))
        and setting.find('valid:')<setting.find('ServerRomLibrary.setMaxPlayers') and 'FcCartridgeData.write' not in setting,
        'Operation 8 changes only a real ROM shared setting with exact 1/2 policy and a valid workstation/card lease')
    check('players_success_status_unlocks_and_refreshes_catalog',players_success_is_status(setting),
        'After saving players, the actual send invocation uses STATUS=1 with refreshed catalog, never OPEN=0')
    saving=method('server.ServerRomLibrary','void setMaxPlayers(')
    check('players_metadata_save_failure_restores_previous',all(t in saving for t in ('Properties.getProperty','Properties.remove','saveProperties:','java/lang/RuntimeException','athrow'))
        and saving.count('Properties.setProperty')==2,
        'Failed persistent metadata write restores the previous memory property or removes a newly introduced key')
    client_toggle=method('client.ClientCartridgeEditor','private void togglePlayers(')
    check('client_same_rom_players_control','currentGame:' in client_toggle and 'RomCatalogEntry.sha256' in client_toggle
        and 'RomCatalogEntry.maxPlayers' in client_toggle and 'CartridgeNetwork$Request' in client_toggle
        and 'cartridgeComputerTabs' in code['client.ClientCartridgeEditor'],
        'Actual client button targets its currently written ROM and toggles one/two players')
    return checks


def packaged_probe(jar,javap):
    jdk=javap.parent;source=old.ROOT/'tools/probes/Alpha12PackagedProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-alpha12-probe-') as directory:
        result=subprocess.run([str(jdk/'javac.exe'),'-cp',str(jar),'-d',directory,str(source)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged probe compilation failed: '+result.stderr[:1000])
        result=subprocess.run([str(jdk/'java.exe'),'-cp',directory+';'+str(jar),'cn.piq.fcarcade.client.Alpha12PackagedProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged pure-Java input/pose/layout probe failed: '+result.stderr[:1500])
        return json.loads(result.stdout)


def inspect(jar,expected_jar,javap):
    require(re.fullmatch('[0-9a-fA-F]{64}',expected_jar),'Expected final JAR SHA256 required')
    assets=frozen_manifest();digest=old.file_sha(jar);require(digest==expected_jar.upper(),'Final JAR hash differs from supplied candidate')
    require(jar.name=='piq_fc_arcade-'+VERSION+'.jar','Incorrect alpha12 artifact filename')
    prior=old.DELIVERY/'piq_fc_arcade-0.31.0-alpha.11.jar';baseline=old.DELIVERY/'piq_fc_arcade-0.30.0-beta.3.jar'
    require(old.file_sha(prior)==ALPHA11_JAR_SHA,'Frozen alpha11 archive changed');require(old.file_sha(baseline)==old.BETA3_SHA,'Protected beta3 baseline changed')
    report={'schema':1,'version':VERSION,'protocol':29,'validated_at_utc':datetime.now(timezone.utc).isoformat(),'jar_path':str(jar.resolve()),'jar_sha256':digest,'jar_bytes':jar.stat().st_size,'manifest_sha256':MANIFEST_SHA,'frozen_alpha11_jar_sha256':ALPHA11_JAR_SHA}
    with zipfile.ZipFile(jar) as archive,zipfile.ZipFile(baseline) as base,zipfile.ZipFile(prior) as previous:
        require(not old.duplicate_entries(archive),'Duplicate ZIP paths');report['jar_entry_count']=len(archive.infolist())
        report['assets']=old.asset_checks(archive,assets);require(all(t['ok'] for t in report['assets']),'Reviewed resource missing or changed')
        inherited=alpha11.frozen_manifest();require(all(archive.read(n)==previous.read(n) for n in inherited),'One of 64 unchanged alpha11 resources changed')
        report['unchanged_alpha11_assets']=64;report['added_appearance_assets']=sorted(ADDED)
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
            if vintage:
                report['new_tv_geometry'][name]['seams']=inspect_seams(model)
                require(report['new_tv_geometry'][name]['seams']['ok'],'CRT actual-quad seam check failed')
            item=json.loads(archive.read(prefix+'models/item/'+name+'.json'));require(item['parent']=='piq_fc_arcade:block/home_'+name,'TV item parent mismatch')
            expected={'variants':{'facing='+d:dict({'model':'piq_fc_arcade:block/home_'+name},**({'y':i*90} if i else {})) for i,d in enumerate(('north','east','south','west'))}}
            require(json.loads(archive.read(prefix+'blockstates/'+name+'.json'))==expected,'TV baked orientation route mismatch')
        computer=json.loads(archive.read('assets/piq_fc_arcade/models/block/cartridge_computer.json'))
        report['computer_geometry']=computer_geometry(computer)
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
    require(old.file_sha(jar)==digest,'JAR changed during independent audit')
    report['ok']=True;report['limits']=['No Minecraft gameplay, screenshots or native core execution in this audit','Native runtime checks cover immutable bytes/headers only; root supplies DLL/WASM smoke','Pure Java probes load classes from the final JAR, never current build/classes or source implementations']
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--jar-sha256',required=True)
    parser.add_argument('--javap',type=Path,default=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/javap.exe'));parser.add_argument('--report',type=Path);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    if not args.check_only:
        require(args.report is not None,'Use --report or --check-only')
        args.report.resolve().relative_to((old.DELIVERY/'家用FC-0.31.0-alpha.12-模型预览').resolve());require(not args.report.exists(),'Never overwrite an existing audit report')
    result=inspect(args.jar,args.jar_sha256,args.javap)
    if not args.check_only:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps(result,ensure_ascii=True))
