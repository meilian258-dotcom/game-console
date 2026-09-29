"""FC54/SFC32 save and stream reliability; no installation or publishing."""
from pathlib import Path
import importlib.util,hashlib,copy,tomllib,re
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('reliability54_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.54',piq_sfc_home='0.1.0-alpha.32',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(1962,8),'piq-sfc-home':(408,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-reliability54-v1'
g.NAMES={'fc':'game_console-0.31.0-alpha.54.jar','sfc':'game_console_sfc-0.1.0-alpha.32.jar'}
g.PLAN=ROOT/'outputs/reliability54/reviewed-changes.json'
g.SOURCE_BEFORE=ROOT/'outputs/reliability54/source-before.json'
g.SOURCE_BEFORE_SHA='EDDF2B378F5A948C95E2F9CCF0AFA0B7151240C01CA4E9AB2A54D62965E06806'
BASE=ROOT/'piq-fc-arcade/build/review-media53-v1'
g.BASE={'fc':(BASE/'game_console-0.31.0-alpha.53.jar','84BCFD93136E6867FB4DF8619872F4B6B7996CF541D52109991179FF31CA2AC9'),
        'sfc':(BASE/'game_console_sfc-0.1.0-alpha.31.jar','AC632EC212D1CB82A9481F577806B7BF14BBD092CB82AF80309B940A7FA64565')}
g.BASE_WITNESSES={k:(BASE/'build-witness.json','9C4982C93A05573B0E94C9AE2A83869F9D551ED2677409B09B1D6AA346F02825') for k in g.BASE}
g.REFERENCES['native']=(ROOT/'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[Path(__file__).resolve(),ROOT/'outputs/reliability54/run_build.py',ROOT/'outputs/reliability54/test_freezer.py',ROOT/'piq-fc-arcade/design/可靠性修复-FC54-SFC32-测试说明.md',ROOT/'piq-sfc-home/tools/qa/SfcPlayerMedia53Probe.java',ROOT/'piq-sfc-home/README.md',ROOT/'piq-sfc-home/tools/check_player_media53.py',ROOT/'piq-sfc-home/tools/run_sfc_playback_multiplayer_probe.py',ROOT/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
def metadata(kind,old,compiled):
    expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
    matches=[m for m in expected['mods'] if m['modId']==g.OWNER[kind]]
    g.b.require(len(matches)==1,'Owner metadata');matches[0]['version']=g.b.VERSIONS[g.OWNER[kind]]
    if kind=='sfc':
        deps=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        g.b.require(len(deps)==1,'One FC dependency required');deps[0]['versionRange']='[0.31.0-alpha.54,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
    manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Manifest delta')
g.metadata=metadata
if __name__=='__main__':g.main()
