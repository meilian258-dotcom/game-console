"""Independent alpha11 final-archive audit. Reads the real JAR, never repacks it.

The new manifest must be independently frozen before this tool can succeed.
Old alpha10/9/8 audit code, manifests and reports are not changed or impersonated.
--report writes exclusively to a new alpha11 preview path and refuses overwrite.
Packaged pure-Java probes run input/pose/layout code, not Minecraft or a native core.
"""
from __future__ import annotations
import argparse, hashlib, json, re, struct, subprocess, tempfile, zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
import verify_home_fc_final_jar as old
import verify_home_fc_alpha10_final_jar as alpha10
from verify_home_fc_alpha9_geometry import points
from check_vintage_alpha11_seams import inspect as inspect_seams

VERSION='0.31.0-alpha.11'
PROTOCOL=28
MANIFEST=old.ROOT/'tools/home-fc-alpha11-final-reviewed-assets.json'
MANIFEST_SHA='45808431141DC018CEA0846CEB073AB1280058CC9783DE29B023CADF1BAE29C4'
ALPHA10_JAR_SHA='84F0EA4C5B2E944423396A0E44C92C9217F761AAD6DEF8F2D4925512B778CA4C'
CHANGED='assets/piq_fc_arcade/models/block/home_vintage_tv.json'
RETIRED=('fc_arcade','stream_fc_arcade','deluxe_fc_arcade','deluxe_stream_fc_arcade','leaderboard_panel')
NEW_CLASSES=alpha10.NEW_CLASSES+('home/HomeFeedback',)


def require(value,message):
    if not value:raise ValueError(message)


def validate_manifest(document):
    require(document.get('version')==VERSION and document.get('protocol')==PROTOCOL,'Wrong alpha11 version/protocol')
    previous=alpha10.frozen_manifest();assets=document.get('assets',{})
    require(isinstance(assets,dict) and len(assets)==64 and set(assets)==set(previous),'Expected precisely the 64 frozen alpha10 appearance paths')
    require(all(isinstance(h,str) and re.fullmatch('[0-9a-fA-F]{64}',h) for h in assets.values()),'Malformed appearance hash')
    changed={n for n,h in previous.items() if assets[n].upper()!=h.upper()}
    require(changed=={CHANGED},'Only the vintage TV body may change; all 63 others must remain exact')
    return assets


def frozen_manifest(path=MANIFEST):
    require(MANIFEST_SHA is not None,'Alpha11 independent manifest has not been frozen')
    require(path.resolve()==MANIFEST.resolve(),'Must use the exact frozen alpha11 manifest path')
    require(old.file_sha(path)==MANIFEST_SHA,'Alpha11 manifest SHA differs from immutable review')
    return validate_manifest(json.loads(path.read_bytes()))


def television_geometry(model,vintage=False):
    if not vintage:return alpha10.television_geometry(model,False)
    quads,v=points(model)
    low=[4.35,2.0,3.35];high=[14.55,9.65,3.38]
    # Actual faces are checked independently of the model-generator's own audit.
    candidates=[e for e in model['elements'] if np.allclose(e['from'],low,atol=1e-8,rtol=0)
        and np.allclose(e['to'],high,atol=1e-8,rtol=0)]
    require(len(candidates)==1 and set(candidates[0]['faces'])=={'north'},'New physical CRT screen must be one exact front-only quad')
    require(np.all(v.min(0)>=[.2-1e-8,0,1.8-1e-8]) and np.all(v.max(0)<=[15.8+1e-8,14.3+1e-8,14.2+1e-8]),'New CRT exceeds unchanged single-cell physics bounds')
    require(all(-16<=n<=32 for e in model['elements'] for k in ('from','to') for n in e[k]),'Model exceeds vanilla JSON element range')
    require(all(q.uv.min()>=0 and q.uv.max()<=16 for q in quads),'Invalid TV UV coordinates')
    require(abs((high[0]-low[0])/(high[1]-low[1])-4/3)<1e-12,'CRT aperture no longer 4:3')
    samples=0
    # From the north/front, no chassis triangle may cover the playable aperture.
    # Edge-inclusive sampling catches a thick frame intruding on the outer image.
    for x in np.linspace(low[0]+1e-5,high[0]-1e-5,9):
        for y in np.linspace(low[1]+1e-5,high[1]-1e-5,9):
            samples+=1
            for q in quads:
                for ids in ((0,1,2),(0,2,3)):
                    tri=q.vertices[list(ids)]
                    mat=np.column_stack((tri[1,:2]-tri[0,:2],tri[2,:2]-tri[0,:2]))
                    if abs(np.linalg.det(mat))<1e-10:continue
                    u,w=np.linalg.solve(mat,np.array([x,y])-tri[0,:2])
                    if u>=-1e-9 and w>=-1e-9 and u+w<=1+1e-9:
                        z=tri[0,2]+u*(tri[1,2]-tri[0,2])+w*(tri[2,2]-tri[0,2])
                        require(z>=low[2]-1e-8,'A model face obscures the playable CRT aperture')
    return {'elements':len(model['elements']),'actual_bounds':[v.min(0).tolist(),v.max(0).tolist()],
        'screen_from':low,'screen_to':high,'physical_screen_aspect':4/3,'front_only_screen':True,
        'collision_bounds_unchanged':True,'front_aperture_ray_samples':samples}


def registered_legacy_ids(code):
    return {name:bool(re.search(r'// String '+re.escape(name)+r'\s*(?:\n|$)',code)) for name in RETIRED}


def has_actionbar_true(code):
    return bool(re.search(r'\biconst_1\s*\n\s*\d+:\s*invokevirtual[^\n]*displayClientMessage:',code))


def bytecode(jar,javap):
    checks=alpha10.bytecode(jar,javap)
    for registry in ('ModItems','ModBlocks'):
        result=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),
            'cn.piq.fcarcade.registry.'+registry],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Cannot inspect legacy registry: '+registry)
        present=registered_legacy_ids(result.stdout)
        checks.append({'check':'retired_ids_still_registered_'+registry,'ok':all(present.values()),'evidence':present})
    result=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),
        'cn.piq.fcarcade.registry.ModCreativeTabs'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
    require(result.returncode==0,'Cannot inspect actual creative page callback')
    checks.append({'check':'actual_creative_callback_uses_reviewed_catalog',
        'ok':all(t in result.stdout for t in ('CreativeTabCatalog.itemPaths','displayItems','getOptional','Output.accept')),
        'evidence':'Real displayItems callback takes its item paths from the probed catalog and resolves registered items'})
    names=('home.HomeFeedback','home.HomeHardware','home.HomeControllerService',
        'server.ServerArcadeSessions','server.ServerArcadeSessions$Manager')
    code={}
    for name in names:
        result=subprocess.run([str(javap),'-p','-c','-constants','-classpath',str(jar),
            'cn.piq.fcarcade.'+name],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Cannot inspect actual home feedback: '+name);code[name]=result.stdout
    writer=old.javap_method(code['home.HomeFeedback'],'public static void show(')
    checks.append({'check':'home_actionbar_writer_true','ok':has_actionbar_true(writer) and 'Component.translatable' in writer,
        'evidence':'Packaged HomeFeedback.show passes true as the overlay argument to displayClientMessage'})
    hardware=old.javap_method(code['home.HomeHardware'],'private static net.minecraft.world.InteractionResult message(')
    controller=old.javap_method(code['home.HomeControllerService'],'private static void message(')
    checks.append({'check':'actual_hardware_and_controller_feedback_routes',
        'ok':all('HomeFeedback.show' in m and 'displayClientMessage' not in m for m in (hardware,controller)),
        'evidence':'Both existing helper methods delegate to the packaged action-bar writer'})
    sessions=code['server.ServerArcadeSessions'];manager=code['server.ServerArcadeSessions$Manager']
    checks.append({'check':'home_tv_session_prompts_actionbar',
        'ok':all('// String '+n in sessions for n in ('home_playback_not_ready','home_edit_cartridge'))
            and '// String home_swap_cartridge' in manager and 'HomeFeedback.show' in sessions and 'HomeFeedback.show' in manager,
        'evidence':'Home TV playback, edit and cartridge-change prompts use the brief feedback path'})
    release=old.javap_method(sessions,'public static void releaseHomeController(')
    checks.append({'check':'physical_return_suppresses_duplicate_leave_chat',
        'ok':'homeConsole' in release and bool(re.search(r'\biconst_0\s*\n\s*\d+:\s*invokevirtual[^\n]*Manager.leave:',release)),
        'evidence':'Home-controller return calls leave(player,false) before its single P1/P2 return result'})
    return checks


def packaged_probe(jar,javap):
    jdk=javap.parent;source=old.ROOT/'tools/probes/Alpha11PackagedProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-alpha11-probe-') as directory:
        result=subprocess.run([str(jdk/'javac.exe'),'-cp',str(jar),'-d',directory,str(source)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged probe compilation failed: '+result.stderr[:1000])
        result=subprocess.run([str(jdk/'java.exe'),'-cp',directory+';'+str(jar),'cn.piq.fcarcade.client.Alpha11PackagedProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'Packaged pure-Java input/pose/layout probe failed: '+result.stderr[:1500])
        return json.loads(result.stdout)


def inspect(jar,expected_jar,javap):
    require(re.fullmatch('[0-9a-fA-F]{64}',expected_jar),'Expected final JAR SHA256 required')
    assets=frozen_manifest();digest=old.file_sha(jar);require(digest==expected_jar.upper(),'Final JAR hash differs from supplied candidate')
    require(jar.name=='piq_fc_arcade-'+VERSION+'.jar','Incorrect alpha11 artifact filename')
    prior=old.DELIVERY/'piq_fc_arcade-0.31.0-alpha.10.jar';baseline=old.DELIVERY/'piq_fc_arcade-0.30.0-beta.3.jar'
    require(old.file_sha(prior)==ALPHA10_JAR_SHA,'Frozen alpha10 archive changed');require(old.file_sha(baseline)==old.BETA3_SHA,'Protected beta3 baseline changed')
    report={'schema':1,'version':VERSION,'protocol':28,'validated_at_utc':datetime.now(timezone.utc).isoformat(),'jar_path':str(jar.resolve()),'jar_sha256':digest,'jar_bytes':jar.stat().st_size,'manifest_sha256':MANIFEST_SHA,'frozen_alpha10_jar_sha256':ALPHA10_JAR_SHA}
    with zipfile.ZipFile(jar) as archive,zipfile.ZipFile(baseline) as base,zipfile.ZipFile(prior) as previous:
        require(not old.duplicate_entries(archive),'Duplicate ZIP paths');report['jar_entry_count']=len(archive.infolist())
        report['assets']=old.asset_checks(archive,assets);require(all(t['ok'] for t in report['assets']),'Reviewed resource missing or changed')
        inherited=alpha10.frozen_manifest();require(all(archive.read(n)==previous.read(n) for n in inherited if n!=CHANGED),'One of 63 unchanged alpha10 resources changed')
        require(archive.read(CHANGED)!=previous.read(CHANGED),'Vintage TV body was not updated')
        report['unchanged_alpha10_assets']=63;report['changed_alpha10_assets']=[CHANGED]
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
        args.report.resolve().relative_to((old.DELIVERY/'家用FC-0.31.0-alpha.11-模型预览').resolve());require(not args.report.exists(),'Never overwrite an existing audit report')
    result=inspect(args.jar,args.jar_sha256,args.javap)
    if not args.check_only:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps(result,ensure_ascii=True))
