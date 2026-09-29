"""Strict alpha.2 artifact audit; alpha.1 validator and frozen pins remain unchanged."""
from pathlib import Path
import argparse,json,re,tomllib
import verify_native_delivery as q

ROOT=q.ROOT
RELEASE=ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.2'
OLD=ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.1'
JAR_SHA='B3DC23F4DFAE53CD87730FEBB7668D470D111ADDCEE577803B09351CE34EFA56'
HELPER_SHA='D1A360A8C300FDBC402A1CEFCF63314A045E0C1D9183E7BABA6E40EF9B33F75D'
SOURCE_PINS={
 'src/main/java/cn/piq/nativearcade/NativeArcadeMod.java':'DAA002A4746A53B6E37009517A8762D0952DFEC498EB8D84D8E0B7C9A35834C2',
 'src/main/java/cn/piq/nativearcade/client/NativeCabinetBackend.java':'6482FFC691924B87859B403F5FF0659025C21EF01CCE27729D8EBE9A7F81CF2F',
 'src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java':'93AB5F601865F38FC02695EF6EEEE0DBCDE042CC6755FCF3A9BD8DE37A4FA3EB',
 'src/main/java/cn/piq/nativearcade/bridge/NativeRomStaging.java':'315D30C565408FEA123837D2C6C32F9D8680BB004BDC3F3A173B8DDA01AA63F6',
 'src/main/java/cn/piq/nativearcade/bridge/NativeProcessSession.java':'F03828A8767B1A1CBB9F92D7AB0C01BA73CB166C3FD516B84E53FA4F3723D6EE',
 'src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java':q.SOURCE_PINS['src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java'],
 'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java':q.SOURCE_PINS['helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java'],
 'src/main/templates/META-INF/neoforge.mods.toml':'43298B65B19388BE8E165255913B135712C8504A52735AC3A4D77F555B5570BF',
}

def metadata(entries):
    meta=tomllib.loads(entries['META-INF/neoforge.mods.toml'].decode('utf-8'))
    mods=meta.get('mods',[])
    q.require(len(mods)==1 and mods[0].get('modId')=='piq_native_arcade' and mods[0].get('version')=='0.1.0-alpha.2','wrong alpha.2 mod metadata')
    expected={'piq_fc_arcade':'[0.31.0-alpha.15,0.32.0)','minecraft':'[1.21.1,1.21.2)','neoforge':'[21.1.236,22)'}
    deps=meta.get('dependencies',{}).get('piq_native_arcade',[])
    q.require(len(deps)==len(expected),'unexpected alpha.2 dependencies')
    for name,version in expected.items():
        found=[d for d in deps if d.get('modId')==name]
        q.require(len(found)==1 and found[0].get('versionRange')==version and found[0].get('type')=='required' and found[0].get('side')=='BOTH','wrong alpha.2 dependency: '+name)
    q.require('Implementation-Version: 0.1.0-alpha.2' in entries['META-INF/MANIFEST.MF'].decode(),'wrong alpha.2 implementation version')
    return expected

def sources(root=ROOT):
    for name,expected in SOURCE_PINS.items():
        q.require(q.file_sha(root/name)==expected,'alpha.2 reviewed source drift: '+name)
    return dict(SOURCE_PINS)

def spi_bytecode(jar,entries):
    common=q.javap(jar,['cn.piq.nativearcade.NativeArcadeMod'])
    q.check_tokens(common,['CabinetBackends.register','String mame','iconst_1'],'common local-only MAME registration')
    q.require(re.search(r'iconst_1\s+\d+: invokestatic[^\n]+CabinetBackends.register',common),'MAME backend must register localOnly=true')
    q.require('CreativeModeTab' not in common and 'TABS' not in common,'independent Native creative tab returned')
    backend=q.javap(jar,['cn.piq.nativearcade.client.NativeCabinetBackend','cn.piq.nativearcade.client.NativeCabinetBackend$1','cn.piq.nativearcade.client.NativeArcadeClient','cn.piq.nativearcade.client.NativeArcadeClient$Setup'])
    q.check_tokens(backend,['CabinetClientBackends.register','implements cn.piq.fcarcade.client.cabinet.CabinetBackend','NativeProcessSession','CabinetFrame','NativeProcessSession.clearInput','NativeProcessSession.close'],'client backend adapter')
    for name,data in entries.items():
        if name.endswith('.class') and not name.startswith(q.PREFIX+'client/'):
            q.require(not any('cn/piq/fcarcade/client/' in text for text in q.class_info(data)['utf']),'common FC client SPI link: '+name)
    staging=q.javap(jar,['cn.piq.nativearcade.bridge.NativeRomStaging'])
    parent=q.javap(jar,['cn.piq.nativearcade.bridge.NativeProcessSession'])
    q.check_tokens(parent,['NativeRomStaging.stage'],'packaged parent uses reviewed staging')
    q.check_tokens(staging,['neogeo.zip','qsound_hle.zip','long 134217728l','long 67108864l','LinkOption.NOFOLLOW_LINKS','StandardOpenOption.CREATE_NEW','BasicFileAttributes.fileKey','BasicFileAttributes.lastModifiedTime'],'packaged bounded staging')
    for forbidden in ('Files.walk','Files.list','ZipInputStream','ZipFile','FileSystems.newFileSystem'):
        q.require(forbidden not in staging,'staging must not scan/extract archives: '+forbidden)
    return {'local_only_true':True,'client_factory_registered':True,'frame_adapter':True,'no_independent_tab':True,'no_common_client_link':True,'auxiliary_allowlist':['neogeo.zip','qsound_hle.zip'],'per_file_mib':64,'total_mib':128,'javap_sha256':{'common':q.sha(common.encode()),'client':q.sha(backend.encode()),'staging':q.sha(staging.encode())}}

def verify():
    jar=RELEASE/'piq_native_arcade-0.1.0-alpha.2.jar';runtime=OLD/'piq-native-arcade/runtime';helper=runtime/'piq-native-helper.jar'
    q.require(q.file_sha(jar)==JAR_SHA,'unreviewed alpha.2 final JAR')
    q.require(q.file_sha(helper)==HELPER_SHA,'frozen helper changed')
    entries=q.checked_zip(jar);old=q.checked_zip(OLD/'mods/piq_native_arcade-0.1.0-alpha.1.jar')
    q.require({n for n in old if n.endswith('.class')}<={n for n in entries if n.endswith('.class')},'legacy Native class removed')
    result={'status':'passed','jar_sha256':JAR_SHA,'metadata':metadata(entries),'source_sha256':sources(),'main':q.validate_main(entries),'spi':spi_bytecode(jar,entries),'runtime':q.validate_runtime(runtime),'helper_sha256':HELPER_SHA,'helper':q.validate_helper(entries,q.checked_zip(helper),q.compare_protocols(jar,helper)),'pure_final_jar_protocol_uv':q.pure_probe(jar)}
    q.require(q.file_sha(jar)==JAR_SHA and q.file_sha(helper)==HELPER_SHA,'artifact changed during audit')
    result['limits']='Read-only artifact/bytecode/source and pure protocol/uncropped-UV checks. No Minecraft world or ROM execution here; actual MAME/KOF97 evidence is separate. Private ROM fixtures are excluded from delivery.'
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    q.require(not args.report.exists(),'refusing to overwrite audit')
    result=verify();args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=True,indent=2))
