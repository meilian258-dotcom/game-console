"""FC55/SFC33 authorized cartridge uploads and server libraries; no installation or publishing."""
from pathlib import Path
import importlib.util,hashlib,copy,tomllib,re
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('content55_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.55',piq_sfc_home='0.1.0-alpha.33',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(1984,8),'piq-sfc-home':(424,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-content55-v1'
g.NAMES={'fc':'game_console-0.31.0-alpha.55.jar','sfc':'game_console_sfc-0.1.0-alpha.33.jar'}
g.PLAN=ROOT/'outputs/content55/reviewed-changes.json'
g.SOURCE_BEFORE=ROOT/'outputs/content55/source-before.json'
g.SOURCE_BEFORE_SHA='8CB0D2576D46487A3C59BE216FFF833506CC94AF7E5A0EA693D9376B82E2613A'
BASE=ROOT/'piq-fc-arcade/build/review-reliability54-v1'
g.BASE={'fc':(BASE/'game_console-0.31.0-alpha.54.jar','FFA62534CEA97B8115E922522C9BB491E443FB22E13643E5CA174D71B7972876'),
        'sfc':(BASE/'game_console_sfc-0.1.0-alpha.32.jar','662879E0CD5D8CDF52C04EDEEC6086650C36D8A5E8C7C1D55B9C0FF6765510FA')}
g.BASE_WITNESSES={k:(BASE/'build-witness.json','14E75069B98B9D399DBD7944D53051CF4121A61DF663F7E664A605A21CA25978') for k in g.BASE}
g.REFERENCES['native']=(ROOT/'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[ROOT/'outputs/content55/check_frozen.py',Path(__file__).resolve(),ROOT/'outputs/content55/run_build.py',ROOT/'outputs/content55/test_freezer.py',ROOT/'piq-fc-arcade/design/玩家上传与游戏库-FC55-SFC33-测试说明.md',ROOT/'piq-sfc-home/tools/qa/SfcPlayerMedia53Probe.java',ROOT/'piq-sfc-home/README.md',ROOT/'piq-sfc-home/tools/qa/SfcContentPermissionsProbe.java',ROOT/'piq-sfc-home/tools/check_player_media53.py',ROOT/'piq-sfc-home/tools/run_sfc_playback_multiplayer_probe.py',ROOT/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
def metadata(kind,old,compiled):
    expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
    matches=[m for m in expected['mods'] if m['modId']==g.OWNER[kind]]
    g.b.require(len(matches)==1,'Owner metadata');matches[0]['version']=g.b.VERSIONS[g.OWNER[kind]]
    if kind=='sfc':
        deps=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        g.b.require(len(deps)==1,'One FC dependency required');deps[0]['versionRange']='[0.31.0-alpha.55,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
    manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Manifest delta')
g.metadata=metadata
if __name__=='__main__':g.main()
