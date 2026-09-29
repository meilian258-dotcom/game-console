"""Exact FC57/SFC35 freezer; retains all FC56 assets and SFC core9. No installation."""
from pathlib import Path
import copy,hashlib,importlib.util,json,re,sys,tomllib
ROOT=Path(__file__).resolve().parents[2]
prior=ROOT/'piq-fc-arcade/tools/build_private56.py'
assert hashlib.sha256(prior.read_bytes()).hexdigest().upper()=='1AF668D0BB5AFEE8AEC8CB69179121049E4B50E33EBCB2D6ECD68161981F05C6'
spec=importlib.util.spec_from_file_location('watch57_private_helpers',prior)
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p);g=p.g
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.57',piq_sfc_home='0.1.0-alpha.35')
g.b.TEST_LIMITS={'piq-fc-arcade':(2060,8),'piq-sfc-home':(460,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-watch57-v1'
g.PLAN=ROOT/'outputs/watch57/reviewed-changes.json'
g.NAMES={'fc':'game_console-0.31.0-alpha.57.jar','sfc':'game_console_sfc-0.1.0-alpha.35.jar'}
base=ROOT/'piq-fc-arcade/build/review-private56-v2'
g.BASE={'fc':(base/'game_console-0.31.0-alpha.56.jar','CE32BE38DEF1F23755789688FC0A59513A3B3423EDACF5239763112DEFA7CA16'),
        'sfc':(base/'game_console_sfc-0.1.0-alpha.34.jar','8491C2CFB56ACDEEFB60CA63DD61AE53458AB7D5F91F9E75F13391E1965C2CCF')}
g.BASE_WITNESSES={kind:(base/'build-witness.json','63D7CF8CC0A55F53DDE1B4B63943BB8D9F31270059EF693ECC67BE5B3C3EA886') for kind in g.BASE}
g.SOURCE_BEFORE=ROOT/'outputs/private56/source-witness-v2.json'
g.SOURCE_BEFORE_SHA='726477EAC55CC54F87A965835B9C2DC184D9235732142941D6FFFE2F63B7A1D1'
g.COMPANIONS=[Path(__file__).resolve(),prior,ROOT/'outputs/watch57/run_build.py',
              ROOT/'piq-fc-arcade/design/管理员设置与SFC省流旁观-FC57-SFC35-测试说明.md',
              ROOT/'piq-sfc-home/tools/check_sfc_local_watch35.py',ROOT/'piq-sfc-home/tools/qa/SfcLocalWatchWorkerProbe.java']
p.REVIEWABLE_CLASS_ROOTS={
 'fc':frozenset('cn/piq/fcarcade/'+n for n in ('config/GameConsoleAdminPolicy','config/GameConsoleAdminSettings','config/GameConsoleAdminCommands',
  'home/HomeEndpointBlockEntity','home/HomeSyncSettings','home/DeviceDebugService','world/LegacyFcArcadeBlockEntity','cabinet/CabinetSyncSettings',
  'cabinet/WatchLedger','cabinet/WatchService','client/watch/WatchClient','client/PrivateHomeClient','server/ServerArcadeSessions','network/ServerTrafficDiagnostics')),
 'sfc':frozenset('cn/piq/sfchome/'+n for n in ('SfcHomeMod','client/SfcCheckpoints','client/SfcCoreLease','client/SfcHomeClient','client/SfcLocalWatchClient',
  'client/SfcPlayback','client/SfcPrivateEngine','client/SfcPrivateProvider','client/SfcWatchClient','client/cabinet/SfcCabinetProvider',
  'client/cabinet/SfcCabinetSession','client/cabinet/SfcCabinetSyncCore','net/SfcLocalWatchNetwork','server/SfcHomeServer',
  'server/SfcLocalWatchServer','server/SfcLocalWatchTransfer','server/SfcRepairLedger','server/SfcWatchProvider'))}
p.REQUIRED_SUITES={
 'piq-fc-arcade':frozenset(('cn.piq.fcarcade.cabinet.WatchRangeTest','cn.piq.fcarcade.cabinet.WatchAuthorityTest',
  'cn.piq.fcarcade.config.GameConsoleAdminSettingsTest','cn.piq.fcarcade.config.GameConsoleAdminPolicyTest',
  'cn.piq.fcarcade.network.ServerTrafficCommandAuthorityTest','cn.piq.fcarcade.client.PrivateHomeClientTest')),
 'piq-sfc-home':frozenset(('cn.piq.sfchome.server.SfcLocalWatchTransferTest','cn.piq.sfchome.server.SfcLocalWatchHistoryTest',
  'cn.piq.sfchome.client.SfcLocalWatchSourceTest','cn.piq.sfchome.client.SfcCoreLeaseTest','cn.piq.sfchome.client.SfcPrivateEngineTest'))}
def metadata(kind,old,compiled):
 expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
 owners={m['modId']:m for m in expected['mods']}
 g.b.require(len(owners)==len(expected['mods']) and set(owners)==({'piq_fc_arcade'} if kind=='fc' else {'piq_sfc_arcade','piq_sfc_home'}),'Unexpected owners')
 owners[g.OWNER[kind]]['version']=g.b.VERSIONS[g.OWNER[kind]]
 if kind=='sfc':
  deps=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade'];g.b.require(len(deps)==1,'One FC dependency')
  deps[0]['versionRange']='[0.31.0-alpha.57,0.32.0)'
 g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
 manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
 g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Unexpected manifest delta')
g.metadata=metadata
def template(path):
 g.b.require(g.file_hash(g.SOURCE_BEFORE)==g.SOURCE_BEFORE_SHA,'Historical source witness changed')
 before=g.unique_json(g.SOURCE_BEFORE.read_bytes())['inputs'];current=g.b.snapshot()
 changes=[name for name in sorted(before.keys()|current.keys()) if any(name.startswith(pr+'/src/') or name in (pr+'/build.gradle',pr+'/gradle.properties') for pr in g.b.PROJECTS) and before.get(name)!=current.get(name)]
 roots={};suites={}
 for kind,pr in g.PROJECT.items():
  prefix=pr+'/src/main/java/';roots[kind]=sorted({n[len(prefix):-5] for n in changes if n.startswith(prefix) and n.endswith('.java')})
  prefix=pr+'/src/test/java/';suites[pr]=sorted(p.REQUIRED_SUITES[pr]|{n[len(prefix):-5].replace('/','.') for n in changes if n.startswith(prefix) and n.endswith('Test.java')})
 plan={'schema':'gc018-45-reviewed-changes-1','approved':False,'class_roots':roots,'removed_classes':{k:[] for k in roots},
  'language_changes':{k:{} for k in roots},'resource_changes':{k:{} for k in roots},'required_test_suites':suites,'observed_source_changes':changes,
  'notes':'Review before approval. Source comparison is against final FC56/SFC34 historical witness, not a newly invented pre-edit backup. All assets, WASM/core9 and Native/GBA unchanged. No real Minecraft or WAN verification.'}
 g.b.exclusive_json(path,plan);print(json.dumps({'draft':str(path),'class_roots':roots,'changes':changes},ensure_ascii=False))
g.plan_template=template
if __name__=='__main__':p.main()
