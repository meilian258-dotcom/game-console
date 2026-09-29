"""FC50/SFC29 shared cable gravity and read-only connection diagnostics release adapter."""
from pathlib import Path
import importlib.util,hashlib,copy,tomllib,re
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('cable50_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.50',piq_sfc_home='0.1.0-alpha.29',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(1877,8),'piq-sfc-home':(361,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-cable50-v1'
g.NAMES={'fc':'game_console-0.31.0-alpha.50.jar','sfc':'game_console_sfc-0.1.0-alpha.29.jar'}
g.PLAN=ROOT/'outputs/cable50/reviewed-changes.json'
g.SOURCE_BEFORE=ROOT/'outputs/cable50/source-before.json'
g.SOURCE_BEFORE_SHA='746910979373713EFF45B50B4B04DFFE5F06AD23DBE6E747786F8EF0BB1A35F4'
BASE=ROOT/'piq-fc-arcade/build/review-cable49-v1'
g.BASE={'fc':(BASE/'game_console-0.31.0-alpha.49.jar','2756ACEAEC2FD9C42E05923B0548C3338B7059DDE8B5DAA3E5BC4F5D81D2FF75'),
        'sfc':(BASE/'game_console_sfc-0.1.0-alpha.28.jar','BC855078073292C07B075248F5A5CBCBE9B0B97CBE2DC50C11BA4D62F7CEC62C')}
g.BASE_WITNESSES={k:(BASE/'build-witness.json','528D8213142A70560B1ED76CB8A4B06CFD734D75BD5A10B035C9FA6F7763FE20') for k in g.BASE}
g.REFERENCES['native']=(ROOT/'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[Path(__file__).resolve(),ROOT/'outputs/cable50/run_build.py',ROOT/'outputs/cable50/preview.py',ROOT/'outputs/cable50/test_freezer.py']
def metadata(kind,old,compiled):
    expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
    matches=[m for m in expected['mods'] if m['modId']==g.OWNER[kind]]
    g.b.require(len(matches)==1,'Owner metadata');matches[0]['version']=g.b.VERSIONS[g.OWNER[kind]]
    if kind=='sfc':
        dependencies=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        g.b.require(len(dependencies)==1,'One FC dependency required')
        dependencies[0]['versionRange']='[0.31.0-alpha.50,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
    manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Manifest delta')
g.metadata=metadata
if __name__=='__main__':g.main()
