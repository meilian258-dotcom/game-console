"""FC49 cable/LCD fix adapter over the immutable reviewed archive helpers.
SFC28 is rebuilt for compatibility only and must remain byte-for-byte unchanged.
"""
from pathlib import Path
import importlib.util,hashlib,copy,tomllib,re
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('cable49_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.49',piq_sfc_home='0.1.0-alpha.28',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(1869,8),'piq-sfc-home':(361,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-cable49-v1'
g.NAMES={'fc':'game_console-0.31.0-alpha.49.jar','sfc':'game_console_sfc-0.1.0-alpha.28.jar'}
g.PLAN=ROOT/'outputs/cable49/reviewed-changes.json'
g.SOURCE_BEFORE=ROOT/'outputs/cable49/source-before.json'
g.SOURCE_BEFORE_SHA='0E38D4660FAB9C0479D15765474E5256B1044CACDDF89197E1B2E6FD009D5AAC'
BASE=ROOT/'piq-fc-arcade/build/review-tvcoin48-v1'
g.BASE={'fc':(BASE/'game_console-0.31.0-alpha.48.jar','520700FAA0EA270A722009C7C9D3487296B1C9EBFBF554AB18CB5C7797AA45D6'),
        'sfc':(BASE/'game_console_sfc-0.1.0-alpha.28.jar','BC855078073292C07B075248F5A5CBCBE9B0B97CBE2DC50C11BA4D62F7CEC62C')}
g.BASE_WITNESSES={k:(BASE/'build-witness.json','E813DB2BBC560E40EA3BC5C4BC24CC129A605212BBCA7A942C676F7C04D764B2') for k in g.BASE}
g.REFERENCES['native']=(BASE/'game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[Path(__file__).resolve(),ROOT/'outputs/cable49/run_build.py',ROOT/'outputs/cable49/model_edit.py',ROOT/'outputs/cable49/preview.py',ROOT/'outputs/cable49/test_freezer.py',ROOT/'outputs/cable49/review_plan.py']
def metadata(kind,old,compiled):
    expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
    matches=[m for m in expected['mods'] if m['modId']==g.OWNER[kind]]
    g.b.require(len(matches)==1,'Owner metadata');matches[0]['version']=g.b.VERSIONS[g.OWNER[kind]]
    g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
    manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Manifest delta')
g.metadata=metadata
_overlay=g.overlay
def overlay(kind,old,compiled,plan):
    final,preserved=_overlay(kind,old,compiled,plan)
    if kind=='sfc':g.b.require(final==old,'SFC28 compatibility rebuild changed production bytes')
    return final,preserved
g.overlay=overlay
if __name__=='__main__':g.main()
