"""Package FC31 + GBA2 only, binding immutable builds and final-JAR proofs. Never installs."""
import argparse,json,sys,tomllib,zipfile
from pathlib import Path
from build_gba_server31 import ROOT,FC,BASE,BASE_SHA,inputs
from freeze_fc_core_alpha19 import digest,require,safe_path,read_jar,META
sys.path.insert(0,str(ROOT/'piq-gba/tools'))
from package_gba_preview import snapshot,json_document,checked_zip,check_source_zip,validate_audit,LICENSES

GBA='piq_gba-0.1.0-alpha.2.jar'
RUNTIME_PIN={'piq-gba-helper.jar':'AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C',
 'jna-5.14.0.jar':'34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',
 'mgba_libretro.dll':'D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'}
COMPANIONS={'piq_sfc-0.1.0-alpha.18.jar':'7BDF3B5BAB4722B35D85303CD2F18C9A7BEA640B3DB7B00C3E9CAFF24E1B49DB',
 'piq_native_arcade-0.1.0-alpha.10.jar':'F4011CBDE8DC3F3F3FA44FD03077638421C7D3334B33A55AFFB0E78C740C2453'}

def main():
    p=argparse.ArgumentParser()
    for name in ('stage','bundle','gba-audit','room-audit','context-audit','factory-audit','compat-audit','output'):
        p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();stage=safe_path(a.stage);bundle=safe_path(a.bundle)
    require(stage.is_dir() and bundle.is_dir(),'Existing final bundle directories required')
    output=safe_path(a.output);verification=output.with_suffix('.verification.json')
    require(output.parent==safe_path(ROOT/'制作Mod/03-街机模拟') and output.suffix=='.zip','Explicit delivery directory only')
    require(not output.exists() and not verification.exists(),'Never overwrite an older package')
    sources=[]
    def take(path):
        item=snapshot(path,64*1024*1024);sources.append(item);return item
    fc=take(stage/FC);gba=take(bundle/GBA);witness=take(stage/'build-witness.json');w=json_document(witness.raw)
    old_gba=take(ROOT/'piq-gba/build/preview-v4/piq_gba-0.1.0-alpha.1.jar')
    require(old_gba.sha256=='331202E4DEA6BF0EB4C2C1BA8752A2F62886BF2007F7F3BB99902BE0D70B0A3E','GBA1 baseline changed')
    _,old_entries=checked_zip(old_gba.raw);_,new_entries=checked_zip(gba.raw)
    require(not set(old_entries)-set(new_entries),'Old GBA entry removed')
    require(set(new_entries)-set(old_entries)=={'cn/piq/gba/bridge/GbaSaveScope.class','cn/piq/gba/client/GbaCabinetBackend$Launch.class'},'Unexpected GBA additions')
    for name in old_entries:
        if old_entries[name]==new_entries[name]:continue
        require(name in (META,'META-INF/MANIFEST.MF') or name=='cn/piq/gba/GbaMod.class' or name.startswith('cn/piq/gba/client/GbaCabinetBackend'),'Unreviewed GBA delta '+name)
    require(w['ok'] and w['schema']=='piq-gba-server31-fc-build-1' and w['inputs']==inputs(),'FC build/source witness mismatch')
    require(w['fc']['sha256']==fc.sha256 and w['baseline_sha256']==BASE_SHA and not w['removed'],'FC freeze mismatch')
    for name,sha in w['test_xml'].items():require(digest((ROOT/name).read_bytes())==sha,'FC test result changed')
    runtime={n:take(bundle/'piq-gba/runtime'/n) for n in RUNTIME_PIN}
    for n,s in runtime.items():require(s.sha256==RUNTIME_PIN[n],'GBA runtime changed')
    audit=take(a.gba_audit);native=json_document(audit.raw);validate_audit(native,fc,gba,runtime)
    build=take(bundle/'build-and-process-qa.json');b=json_document(build.raw)
    require(b['ok'] and b['fc']['sha256']==fc.sha256 and b['gba']['sha256']==gba.sha256 and b['runtime']==native['runtime'],'GBA build mismatch')
    licenses={n:take(bundle/'licenses-and-source'/n) for n in LICENSES}
    source_scope=check_source_zip(licenses['piq-gba-source.zip'].raw,b)
    for name,sha in source_scope.items():require(digest((ROOT/'piq-gba'/name).read_bytes())==sha,'GBA source changed after build '+name)
    require(json_document(licenses['mgba-source-verification.json'].raw)==native['source'],'Corresponding source mismatch')
    proofs={}
    for key,path in [('room',a.room_audit),('context',a.context_audit),('factory',a.factory_audit),('compat',a.compat_audit)]:
        item=take(path);record=json_document(item.raw)
        require(record.get('ok') is True and record.get('mode')=='final-jar-only' and record.get('production_compiled') is False,'Non-final evidence '+key)
        for kind,selected in [('fc',fc),('gba',gba)]:
            require(record['jars'][kind]['sha256'].upper()==selected.sha256,'Wrong final JAR in '+key+' '+kind)
        if key=='compat':
            require(record['common']['actual_fml_reader'] and record['common']['client_and_native_load_attempts']==0,'Common-side verification missing')
            require(record['legacy_binary_compatibility']['ok'] and record['legacy_binary_compatibility']['factory_open_calls']==0,'Old adapters not verified')
            for kind,name in [('sfc','piq_sfc-0.1.0-alpha.18.jar'),('native','piq_native_arcade-0.1.0-alpha.10.jar')]:
                require(record['jars'][kind]['sha256'].upper()==COMPANIONS[name],'Wrong companion in final compatibility proof')
        if key=='context':require(record['scope']['actual_store_io'] and record['scope']['legacy_save_untouched'] and record['launch']['actual_connection'],'Save isolation proof missing')
        if key=='room':require(record['authority']['actual_outer_codecs'] and record['authority']['actual_pure_authority_ledgers'],'Room behavior proof missing')
        proofs[key]=item
    all_entries={};ids={}
    for item in [fc,gba,*[take(BASE/n) for n in COMPANIONS]]:
        if item.path.name in COMPANIONS:require(item.sha256==COMPANIONS[item.path.name],'Existing optional companion changed')
        _,entries=checked_zip(item.raw);meta=tomllib.loads(entries[META].decode())
        for mod in meta['mods']:
            require(mod['modId'] not in ids,'Duplicate mod identity');ids[mod['modId']]=mod['version']
        for name in entries:
            if name.endswith('.class'):require(name not in all_entries,'Duplicate class '+name);all_entries[name]=item.path.name
    require(ids=={'piq_fc_arcade':'0.31.0-alpha.31','piq_gba':'0.1.0-alpha.2','piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.18','piq_native_arcade':'0.1.0-alpha.10'},'Unexpected cohort')
    guide=take(ROOT/'piq-gba/design/GBA服务器版-alpha2-使用说明.md');readme=take(bundle/'README.md')
    for item in [gba,build,readme,*runtime.values(),*licenses.values()]:
        require(native['files_sha256'].get(str(item.path))==item.sha256,'Final audit did not bind packaged input '+str(item.path))
    payload={'mods/'+FC:fc.raw,'mods/'+GBA:gba.raw,'先看这里.md':guide.raw,'docs/GBA实现说明.md':readme.raw,
             'checks/fc-build.json':witness.raw,'checks/gba-build.json':build.raw,'checks/gba-native-final.json':audit.raw}
    payload.update({'piq-gba/runtime/'+n:s.raw for n,s in runtime.items()})
    payload.update({'licenses-and-source/'+n:s.raw for n,s in licenses.items()})
    payload.update({'checks/'+n+'-final.json':s.raw for n,s in proofs.items()})
    report={'schema':'piq-gba-server31-delivery-1','ok':True,'fc_sha256':fc.sha256,'gba_sha256':gba.sha256,
            'protocol':'cabinet-room-3','fc_tests':w['tests'],'reviewed_fc_changes':w['changed'],'protected_unchanged':w['protected_unchanged'],
            'runtime_sha256':RUNTIME_PIN,'compatible_existing_optional_mods':COMPANIONS,'cohort':ids,'unique_classes':len(all_entries),
            'server_control_seats':1,'native_gba_link_multiplayer':False,'handheld_item':False,'nearby_spectator_support':True,
            'installed':False,'uploaded':False,'published':False,'live_minecraft_multiplayer_tested':False,
            'notes':['Only FC31 and GBA2 are bundled; keep existing SFC18/Native10 if desired.',
                     'Only playing Windows x64 client needs runtime. Server and viewers need no ROM/core.',
                     'Game SRAM is client-local, isolated by world/server and player; old alpha1 saves untouched.']}
    payload['checks/summary.json']=(json.dumps(report,ensure_ascii=False,indent=2)+'\n').encode()
    payload['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(payload.items())).encode()
    for s in sources:require(digest(s.path.read_bytes())==s.sha256,'Input changed during packaging')
    with zipfile.ZipFile(output,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,raw in sorted(payload.items()):
            info=zipfile.ZipInfo(name,(2026,9,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,raw)
    with zipfile.ZipFile(output) as z:
        require(z.testzip() is None and len(z.namelist())==len(payload) and set(z.namelist())==set(payload),'ZIP inventory/CRC failed')
        for name,raw in payload.items():require(z.read(name)==raw,'ZIP readback differs')
    for s in sources:require(digest(s.path.read_bytes())==s.sha256,'Input changed while writing package')
    require(w['inputs']==inputs(),'FC source fence changed')
    report.update(path=str(output),bytes=output.stat().st_size,sha256=digest(output.read_bytes()),files={n:digest(raw) for n,raw in payload.items()})
    with verification.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'path':str(output),'bytes':report['bytes'],'sha256':report['sha256'],'fc':fc.sha256,'gba':gba.sha256}))
if __name__=='__main__':main()
