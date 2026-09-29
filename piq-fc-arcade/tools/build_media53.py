"""FC53/SFC31 player-hosted home audiovisual streaming; no installation or publishing."""
from pathlib import Path
import importlib.util,hashlib,copy,tomllib,re
ROOT=Path(__file__).resolve().parents[2]
HELPER=ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper()=='443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec=importlib.util.spec_from_file_location('media53_fixed_helpers',HELPER)
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.53',piq_sfc_home='0.1.0-alpha.31',piq_native_arcade='0.1.1')
g.b.TEST_LIMITS={'piq-fc-arcade':(1942,8),'piq-sfc-home':(377,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-media53-v1'
g.NAMES={'fc':'game_console-0.31.0-alpha.53.jar','sfc':'game_console_sfc-0.1.0-alpha.31.jar'}
g.PLAN=ROOT/'outputs/media53/reviewed-changes.json'
g.SOURCE_BEFORE=ROOT/'outputs/media53/source-before.json'
g.SOURCE_BEFORE_SHA='B410AF0F3D8698857604D150C741274E3A9A65BFB8227A3C328668046606C721'
BASE=ROOT/'piq-fc-arcade/build/review-control52-v1'
g.BASE={'fc':(BASE/'game_console-0.31.0-alpha.52.jar','A3077B07C9B4B4A550B81B7C357482086B59A3D86B0A0BE6DD2E3978B7D11E24'),
        'sfc':(BASE/'game_console_sfc-0.1.0-alpha.30.jar','32DD99FAD6DA111C4F5B75C4502669E3EF023A315DC5E1A4301E149A33573CD8')}
g.BASE_WITNESSES={k:(BASE/'build-witness.json','EC4386DC6EDB8B435604880D3C8895350E9E7404822B235185578F5CE4EF093D') for k in g.BASE}
g.REFERENCES['native']=(ROOT/'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar','4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS=[Path(__file__).resolve(),ROOT/'outputs/media53/run_build.py',ROOT/'outputs/media53/test_freezer.py',ROOT/'piq-fc-arcade/design/玩家音画串流-FC53-SFC31-测试说明.md',ROOT/'piq-sfc-home/tools/qa/SfcPlayerMedia53Probe.java',ROOT/'piq-sfc-home/tools/check_player_media53.py',ROOT/'piq-sfc-home/tools/run_sfc_playback_multiplayer_probe.py',ROOT/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
def metadata(kind,old,compiled):
    expected=copy.deepcopy(tomllib.loads(old[g.b.META].decode()))
    matches=[m for m in expected['mods'] if m['modId']==g.OWNER[kind]]
    g.b.require(len(matches)==1,'Owner metadata');matches[0]['version']=g.b.VERSIONS[g.OWNER[kind]]
    if kind=='sfc':
        deps=[d for d in expected['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        g.b.require(len(deps)==1,'One FC dependency required');deps[0]['versionRange']='[0.31.0-alpha.53,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode())==expected,'Unexpected metadata delta')
    manifest,count=re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest,'Manifest delta')
g.metadata=metadata
if __name__=='__main__':g.main()
