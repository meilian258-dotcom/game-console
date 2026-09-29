"""Freeze FC47 debugger test package; revalidate but preserve the SFC27 companion."""
from pathlib import Path
import copy
import hashlib
import importlib.util
import re
import tomllib

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT/'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper() == '443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec = importlib.util.spec_from_file_location('debug47_helpers', HELPER)
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.47', piq_sfc_home='0.1.0-alpha.27')
g.b.TEST_LIMITS = {'piq-fc-arcade': (1764, 8), 'piq-sfc-home': (361, 0)}
g.OUT = ROOT/'piq-fc-arcade/build/review-debug47-v1'
g.NAMES = {'fc':'game_console-0.31.0-alpha.47.jar','sfc':'game_console_sfc-0.1.0-alpha.27.jar'}
g.PLAN = ROOT/'outputs/debug47/reviewed-changes.json'
g.SOURCE_BEFORE = ROOT/'outputs/debug47/source-before.json'
g.SOURCE_BEFORE_SHA = '5E132704D4D9C5F8B0F852D35570C342E095E255929CD1AA9F44F61C8BB01DF5'
BASE_DIR = ROOT/'piq-fc-arcade/build/review-input46-v1'
g.BASE = {
    'fc': (BASE_DIR/'game_console-0.31.0-alpha.46.jar','3CA443B146926B51AA7A90369D50218DB25957007F3C78F0FB0625041E9B89B4'),
    'sfc': (BASE_DIR/'game_console_sfc-0.1.0-alpha.27.jar','57D682C778775CFA3EB9082104843F45D080B0D71516C57B12E801DDE07D6DF3')}
g.BASE_WITNESSES = {kind:(BASE_DIR/'build-witness.json','242D83BAC739E9B8BDD524FDD7EF171862B4D97FDD9BA306FF473EDC1C12833E') for kind in g.PROJECT}
g.COMPANIONS = [Path(__file__), ROOT/'outputs/debug47/test_build_debug47.py']

def metadata(kind, old, compiled):
    expected = copy.deepcopy(tomllib.loads(old[g.b.META].decode('utf-8')))
    mods = {m['modId']:m for m in expected['mods']}
    g.b.require(set(mods)==({g.OWNER[kind]} if kind=='fc' else {'piq_sfc_arcade','piq_sfc_home'}), 'Baseline mod ownership')
    mods[g.OWNER[kind]]['version'] = g.b.VERSIONS[g.OWNER[kind]]
    g.b.require(tomllib.loads(compiled[g.b.META].decode('utf-8'))==expected, 'Unapproved metadata: '+kind)
    manifest, count = re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+', lambda m:m[1]+g.b.VERSIONS[g.OWNER[kind]].encode(),old[g.b.MANIFEST])
    g.b.require(count==1 and compiled[g.b.MANIFEST]==manifest, 'Unapproved manifest delta')

g.metadata = metadata
_jar_bytes = g.b.jar_bytes
def jar_bytes(entries):
    # Companion source is unchanged: enforce the same complete archive contents,
    # then retain its original bytes rather than presenting a repack as a new build.
    original = g.safe_archive(g.BASE['sfc'][0])[1]
    if entries==original:
        return g.BASE['sfc'][0].read_bytes()
    return _jar_bytes(entries)
g.b.jar_bytes = jar_bytes

if __name__=='__main__':
    g.main()
