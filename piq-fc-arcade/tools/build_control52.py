"""FC52/SFC30 video-socket controls, OP traffic and recess material release adapter."""
from pathlib import Path
import importlib.util,hashlib,copy,tomllib,re
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('control52_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.52',piq_sfc_home='0.1.0-alpha.30',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(1911,8),'piq-sfc-home':(364,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-control52-v1'
g.NAMES={'fc':'game_console-0.31.0-alpha.52.jar','sfc':'game_console_sfc-0.1.0-alpha.30.jar'}
g.PLAN=ROOT/'outputs/control52/reviewed-changes.json'
g.SOURCE_BEFORE=ROOT/'outputs/control52/source-before.json'
g.SOURCE_BEFORE_SHA='1D6B73E60B557971E037B9D5EC4EA4D8CE30FB3E46FFD8E0B1129A5782564A07'
BASE=ROOT/'piq-fc-arcade/build/review-cable51-v1'
g.BASE={'fc':(BASE/'game_console-0.31.0-alpha.51.jar','16EDBBACE7EC7D3F19FE072AD6BD43E6FB9AC4E18062CAEBC82932D664EE7A91'),
        'sfc':(BASE/'game_console_sfc-0.1.0-alpha.29.jar','354A31EE93383F0248A96B220AFD5E200CB36DE5A082AFDBDA45EF090109E9CB')}
g.BASE_WITNESSES={k:(BASE/'build-witness.json','5B7B3ED0CD353B4A21131315E55D1037C22C1942097AA262672A4187E5C0EB44') for k in g.BASE}
g.REFERENCES['native']=(ROOT/'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[Path(__file__).resolve(),ROOT/'outputs/control52/run_build.py',ROOT/'outputs/control52/preview.py',ROOT/'outputs/control52/test_freezer.py']
def metadata(kind,old,compiled):
    expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
    matches=[m for m in expected['mods'] if m['modId']==g.OWNER[kind]]
    g.b.require(len(matches)==1,'Owner metadata');matches[0]['version']=g.b.VERSIONS[g.OWNER[kind]]
    if kind=='sfc':
        dependencies=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        g.b.require(len(dependencies)==1,'One FC dependency required')
        dependencies[0]['versionRange']='[0.31.0-alpha.52,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
    manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Manifest delta')
g.metadata=metadata
if __name__=='__main__':g.main()
