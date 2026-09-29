"""Reviewed FC58/SFC36 local test freezer. No installation, publishing or game launch."""
from pathlib import Path
import copy,hashlib,importlib.util,json,re,tomllib
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('admin58_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.58',piq_sfc_home='0.1.0-alpha.36',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(2065,8),'piq-sfc-home':(463,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-admin58-v1'
g.PLAN=ROOT/'outputs/admin58/reviewed-changes.json'
g.NAMES={'fc':'game_console-0.31.0-alpha.58.jar','sfc':'game_console_sfc-0.1.0-alpha.36.jar'}
base=ROOT/'piq-fc-arcade/build/review-watch57-v1'
g.BASE={'fc':(base/'game_console-0.31.0-alpha.57.jar','4B822148D6339A160BF5FFE5E73140B30ABFF88AE20C195E010667EBCD72E142'),
        'sfc':(base/'game_console_sfc-0.1.0-alpha.35.jar','03071348671216E7B1EB66D0D678638FB87FEF762F13EDEBAD945E79BC6D669C')}
g.BASE_WITNESSES={k:(base/'build-witness.json','95C6406D0AAFBDD8598634785FC2DE1227CE234C1E792C22A230FD40FBF88580') for k in g.BASE}
g.SOURCE_BEFORE=ROOT/'outputs/watch57/source-witness-v1.json'
g.SOURCE_BEFORE_SHA='2D69F34A6CEADC3C731A5937705F95B4A948CF8303A7771C54850C7B003E1797'
g.REFERENCES['native']=(ROOT/'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[Path(__file__).resolve(),ROOT/'outputs/admin58/run_build.py',
 ROOT/'piq-fc-arcade/design/管理终端与权限整理-FC58-SFC36-测试说明.md',
 ROOT/'piq-fc-arcade/tools/rebuild_subor_keycaps58.py',ROOT/'piq-fc-arcade/tools/build_subor_reference_model.py']
def metadata(kind,old,compiled):
 expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
 owners={m['modId']:m for m in expected['mods']}
 g.b.require(len(owners)==len(expected['mods']) and set(owners)==({'piq_fc_arcade'} if kind=='fc' else {'piq_sfc_arcade','piq_sfc_home'}),'Unexpected owners')
 owners[g.OWNER[kind]]['version']=g.b.VERSIONS[g.OWNER[kind]]
 if kind=='sfc':
  deps=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade'];g.b.require(len(deps)==1,'One FC dependency')
  deps[0]['versionRange']='[0.31.0-alpha.58,0.32.0)'
 g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
 manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
 g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Unexpected manifest delta')
g.metadata=metadata
def template(path):
 g.b.require(g.file_hash(g.SOURCE_BEFORE)==g.SOURCE_BEFORE_SHA,'Historical source witness changed')
 before=g.unique_json(g.SOURCE_BEFORE.read_bytes())['inputs'];current=g.b.snapshot()
 changes=[n for n in sorted(before.keys()|current.keys()) if any(n.startswith(pr+'/src/') or n in (pr+'/build.gradle',pr+'/gradle.properties') for pr in g.b.PROJECTS) and before.get(n)!=current.get(n)]
 plan={'schema':'gc018-45-reviewed-changes-1','approved':False,'class_roots':{},'removed_classes':{'fc':[],'sfc':[]},
       'language_changes':{},'resource_changes':{},'required_test_suites':{},'observed_source_changes':changes,
       'notes':'Root review required. Compare final FC57/SFC35 baseline; retain original core9, three WASM, two excluded controller drafts and all unrelated assets. Local test candidate only; no Minecraft/WAN acceptance.'}
 for kind,pr in g.PROJECT.items():
  prefix=pr+'/src/main/java/';plan['class_roots'][kind]=sorted({n[len(prefix):-5] for n in changes if n.startswith(prefix) and n.endswith('.java')})
  prefix=pr+'/src/test/java/';plan['required_test_suites'][pr]=sorted({n[len(prefix):-5].replace('/','.') for n in changes if n.startswith(prefix) and n.endswith('Test.java') and n in current})
  plan['language_changes'][kind]={};plan['resource_changes'][kind]={}
  old=g.safe_archive(g.BASE[kind][0])[1];resources=ROOT/pr/'src/main/resources'
  for source in sorted(resources.rglob('*')):
   if not source.is_file():continue
   name=source.relative_to(resources).as_posix();raw=source.read_bytes()
   if name in (g.b.META,g.b.MANIFEST) or old.get(name)==raw:continue
   if kind=='fc' and name in g.a.DRAFTS:
    g.b.require(g.b.sha(raw)==g.a.DRAFTS[name],'Unknown controller draft');continue
   if re.fullmatch('assets/'+g.OWNER[kind]+r'/lang/(zh_cn|en_us)\.json',name):
    initial=g.b.language(old[name],name);final=g.b.language(raw,name)
    delta={k:{'old':initial.get(k),'new':final.get(k)} for k in sorted(initial.keys()|final.keys()) if initial.get(k)!=final.get(k)}
    if delta:plan['language_changes'][kind][name]=delta
   else:plan['resource_changes'][kind][name]={'old_sha256':g.b.sha(old[name]) if name in old else None,'new_sha256':g.b.sha(raw)}
 g.b.exclusive_json(path,plan);print(json.dumps(plan,ensure_ascii=False))
g.plan_template=template
if __name__=='__main__':g.main()
