"""Strict frozen FC21/SFC9/Native7 scope and final-JAR-only production behavior audit."""
from __future__ import annotations
import argparse,hashlib,json,os,shutil,subprocess,sys,tempfile
from pathlib import Path
import prepare_cabinet_multiplayer21 as stage
import verify_retro_alpha19 as compat
ROOT=stage.ROOT
TOOLS=Path(__file__).resolve().parent
EXPECTED={'fc':'0147D49C542E82DDF2DD37CDAFFBDB135CDA20E11907B4060B471A2FBC0D3F93','sfc':'F951146515D7F35257585A9DA7536453F1DFFA70285F51EDD612D65C771DF951','native':'8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A'}
def classes(prefix,names):return {prefix+n+'.class' for n in names.split()}
FC='cn/piq/fcarcade/';SFC='cn/piq/sfchome/';NATIVE='cn/piq/nativearcade/'
ADDED={
'fc':classes(FC+'cabinet/',"""CabinetLinkCableItem CabinetLinkLedger CabinetLinkLedger$End CabinetLinkLedger$Pair CabinetLinks CabinetLinks$Data CabinetLinks$Pending CabinetMediaCodec CabinetMediaCodec$Encoded CabinetMediaPacket CabinetMediaSender CabinetMediaSender$1 CabinetRoomLedger CabinetRoomLedger$Change CabinetRoomLedger$Member CabinetRoomLedger$Room CabinetRoomLedger$Window CabinetRoomMedia CabinetRoomMedia$Part CabinetRoomMedia$Pending CabinetRoomNetwork CabinetRoomNetwork$Assignment CabinetRoomNetwork$Buttons CabinetRoomNetwork$ClientSink CabinetRoomNetwork$Input CabinetRoomNetwork$Media CabinetRoomNetwork$Ready CabinetRoomNetwork$Reset CabinetRoomNetwork$Seat CabinetRoomNetwork$Stream CabinetRooms CabinetRooms$State CabinetSendWindow CabinetSendWindow$Ticket""")
|classes(FC+'client/cabinet/',"""CabinetAssignmentHistory CabinetMediaAssembler CabinetMediaAssembler$Complete CabinetMediaStream CabinetPcmBuffer CabinetPcmBuffer$Chunk CabinetPeerInputs""")
|{'assets/piq_fc_arcade/models/item/cabinet_link_cable.json'},
'sfc':set(),'native':classes(NATIVE+'bridge/','NativeInputPorts')}
CHANGED={
'fc':classes(FC,"""FcArcadeMod cabinet/CabinetBackends cabinet/CabinetBackends$Entry cabinet/CabinetEmulator cabinet/CabinetEmulator$1 cabinet/CabinetNetwork cabinet/CabinetNetwork$ClientSink cabinet/ServerCabinets cabinet/ServerCabinets$Binding cabinet/ServerCabinets$MenuBinding cabinet/ServerCabinets$State client/cabinet/CabinetClientBackends client/cabinet/CabinetClientBackends$Setup registry/CreativeTabCatalog registry/ModItems world/DualCabinetBlock world/DualCabinetPartBlock world/FcArcadeBlock world/LegacyFcArcadeBlock""")
|classes('cn/piq/retro/api/','RetroEmulator')|{'assets/piq_fc_arcade/lang/en_us.json','assets/piq_fc_arcade/lang/zh_cn.json'},
'sfc':classes(SFC,'SfcHomeMod client/cabinet/SfcCabinetInputs client/cabinet/SfcCabinetProvider client/cabinet/SfcCabinetSession'),
'native':classes(NATIVE,'NativeArcadeMod bridge/BridgeProtocol bridge/NativeProcessSession bridge/NativeProcessSession$Input client/NativeCabinetBackend$1')}
for allowed in CHANGED.values():allowed.update({stage.META,stage.MANIFEST})
def require(ok,why):
    if not ok:raise AssertionError(why)
def run(args,cwd):
    done=subprocess.run(list(map(str,args)),cwd=cwd,capture_output=True,encoding='utf-8',errors='replace',timeout=60)
    if done.returncode:raise AssertionError(done.stdout+done.stderr)
    return done.stdout
def parsed(text):return compat.parse_last_json(text)
def scope(kind,before,after):
    added=set(after)-set(before);removed=set(before)-set(after);changed={n for n in before.keys()&after.keys()if before[n]!=after[n]}
    require(not removed,'Forbidden deleted entries '+kind+': '+repr(removed))
    require(added==ADDED[kind],'Unexpected/missing explicit additions '+kind+': '+repr(added^ADDED[kind]))
    require(changed<=CHANGED[kind],'Unauthorized byte changes '+kind+': '+repr(changed-CHANGED[kind]))
    result=stage.classify(kind,before,after);stage.metadata(kind,after)
    result['explicit_allowlist_enforced']=True
    for name,data in after.items():
        if name.startswith(FC+'cabinet/')and name.endswith('.class'):require(b'net/minecraft/client/'not in data,'Common links client implementation: '+name)
    return result
def final_probes(path,entries):
    deps=compat.dependencies();junit=[p for p in deps if any(s in str(p)for s in ('org.junit.','org.apiguardian','org.opentest4j'))]
    tests=[ROOT/'piq-fc-arcade/src/test/java/cn/piq/fcarcade/cabinet'/(name+'.java')for name in ('CabinetRoomLedgerTest','CabinetRoomMediaTest','CabinetSendWindowTest')]
    probes=[TOOLS/'qa'/(name+'.java')for name in ('CabinetRoomTestRunner','CabinetRoomIngressProbe','CabinetSendBackpressureProbe','CabinetLinksDataProbe','CabinetFinalOriginProbe')]
    raw=path.read_bytes();expected=compat.digest(raw)
    with tempfile.TemporaryDirectory(prefix='cabinet-final21-')as folder:
        tmp=Path(folder);out=tmp/'probes';out.mkdir();empty=tmp/'no-source';empty.mkdir();jar=tmp/'final-fc21.jar';jar.write_bytes(raw)
        require(compat.digest(jar.read_bytes())==expected,'Probe jar copy mismatch')
        resources=compat.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,compat.MC,resources,jar,*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([compat.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*tests,*probes],tmp)
        compiled={f.relative_to(out).as_posix()for f in out.rglob('*.class')}
        require(not(compiled&set(entries)),'Probe compile produced a production class')
        names=[n[:-6].replace('/','.')for n in entries if n.startswith(FC+'cabinet/')and n.endswith('.class')]
        origin=parsed(run([compat.JAVA/'java.exe','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetFinalOriginProbe',jar,*names],tmp))
        purecp=os.pathsep.join(map(str,[out,jar,*junit]))
        pure=parsed(run([compat.JAVA/'java.exe','-cp',purecp,'CabinetRoomTestRunner','cn.piq.fcarcade.cabinet.CabinetRoomLedgerTest','cn.piq.fcarcade.cabinet.CabinetRoomMediaTest','cn.piq.fcarcade.cabinet.CabinetSendWindowTest'],tmp))
        require(pure['passed_tests']==42,'Final pure tests incomplete')
        output={}
        for key,name in [('ingress','CabinetRoomIngressProbe'),('backpressure','CabinetSendBackpressureProbe'),('saved_data','CabinetLinksDataProbe')]:
            output[key]=parsed(run([compat.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.cabinet.'+name],tmp));require(output[key].get('ok'),key+' failed')
        require(output['ingress']['assertions']==52 and output['backpressure']['assertions']==1018 and output['saved_data']['assertions']==920,'Missing final assertions')
        require(compat.digest(path.read_bytes())==expected and compat.digest(jar.read_bytes())==expected,'Final jar changed during probes')
    return {'production_compiled':False,'production_origin':origin,'pure':pure,**output,'compiled_probe_files':[str(p.relative_to(ROOT))for p in tests+probes],'byte_identical_temporary_jar':True}
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    require(not a.report.exists(),'Report already exists');directory=a.stage.resolve(strict=True)
    paths={key:directory/name for key,name in stage.NAMES.items()};archives={};hashes={};scopes={};bases={}
    for key,path in paths.items():
        hashes[key],archives[key]=compat.archive(path);require(hashes[key]==EXPECTED[key],'Frozen final hash mismatch '+key)
        baseline,sha=stage.BASELINES[key];found,bases[key]=compat.archive(baseline);require(found==sha,'Frozen baseline mismatch '+key)
        scopes[key]=scope(key,bases[key],archives[key])
    owners=compat.unique_ownership(archives)
    corepath,coreshash=compat.BASELINES['core'];actual,core=compat.archive(corepath);require(actual==coreshash,'Old SFC6 core baseline changed')
    protected=[n for n in core if n not in (stage.META,stage.MANIFEST)]
    require(all(archives['sfc'].get(n)==core[n]for n in protected),'Frozen SFC6 core/wasm/AT changed')
    helper=directory/'piq-native-arcade/runtime/piq-native-helper.jar';require(compat.digest(helper.read_bytes())==stage.HELPER_SHA,'Native helper mismatch')
    # Negative controls exercise this audit's exact allowlist, not the staging tool's directory prefixes.
    negative=[]
    for name,mutate in [('old_texture',lambda d:d.__setitem__('assets/piq_fc_arcade/textures/item/controller_twohand_skin.png',b'bad')),
                        ('unexpected_test_class',lambda d:d.__setitem__(FC+'cabinet/InjectedTest.class',b'bad')),
                        ('unexpected_rom',lambda d:d.__setitem__('test.nes',b'NES')),
                        ('removed_old_class',lambda d:d.pop(FC+'cabinet/CabinetTarget.class'))]:
        altered=dict(archives['fc']);mutate(altered);failed=False
        try:scope('fc',bases['fc'],altered)
        except (AssertionError,ValueError):failed=True
        require(failed,'Negative scope mutation accepted: '+name);negative.append(name)
    room=final_probes(paths['fc'],archives['fc'])
    fml=compat.java_probes(paths)
    require(all(compat.digest(path.read_bytes())==hashes[key]for key,path in paths.items()),'Frozen jar changed during audit')
    result={'ok':True,'schema':'piq-final-linked21-independent-1','production_compiled':False,'jars':{key:{'path':str(path),'sha256':hashes[key]}for key,path in paths.items()},
            'strict_scope':scopes,'ownership':owners,'frozen_sfc6_entries_preserved':len(protected),'native_helper_sha256':stage.HELPER_SHA,'negative_controls':negative,
            'room_final_jar_probes':room,'compatibility_final_jar_probes':fml,'installed':False,'minecraft_or_native_core_started':False,'network_socket_opened':False,
            'limits':['Actual codecs, Connection/EmbeddedChannel completion backpressure, NBT/SavedData and FML metadata discovery, not a live four-player gameplay test.',
                      'No mod entry point, user client/server, ROM or native emulator was launched.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as output:json.dump(result,output,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'pure':42,'room_codecs':52,'backpressure':1018,'saved_data':920,'fml':fml['fml_discovery']['assertions'],'legacy_sfc_packets':37},ensure_ascii=False))
if __name__=='__main__':main()
