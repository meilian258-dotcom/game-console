"""FC46/SFC27 input recovery release, reusing pinned GC018 verification primitives.

Schemas of the reviewed GC018 helper are retained; versions, baselines, input
snapshot, required tests and metadata contract below distinguish this release.
This tool freezes already verified builds; it never installs or starts Minecraft.
"""
from pathlib import Path
import copy
import hashlib
import importlib.util
import re
import tomllib

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'piq-fc-arcade/tools/build_gc018_45.py'
assert hashlib.sha256(HELPER.read_bytes()).hexdigest().upper() == '443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
spec = importlib.util.spec_from_file_location('input46_gc018_helpers', HELPER)
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.46', piq_sfc_home='0.1.0-alpha.27')
g.b.TEST_LIMITS = {'piq-fc-arcade': (1762, 8), 'piq-sfc-home': (346, 0)}
g.OUT = ROOT / 'piq-fc-arcade/build/review-input46-v1'
g.NAMES = {'fc': 'game_console-0.31.0-alpha.46.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.27.jar'}
g.PLAN = ROOT / 'outputs/input46/reviewed-changes.json'
g.SOURCE_BEFORE = ROOT / 'outputs/input46/source-before.json'
g.SOURCE_BEFORE_SHA = 'DDC5B6EB9B601535BBF6BA50A4929613724838941B6927A5FD84AA7EE3C86145'
BASE_DIR = ROOT / 'piq-fc-arcade/build/review-gc018-v1'
g.BASE = {
    'fc': (BASE_DIR / 'game_console-0.31.0-alpha.45.jar', 'D51E1E9378297A69AB58D255357B8360F1A99BA1662E7CB42C8AC528356359CA'),
    'sfc': (BASE_DIR / 'game_console_sfc-0.1.0-alpha.26.jar', '6ED92E34B0C054C067E50B4B3C5EFB10D8D4FF7CAF23A3ED6FBD3C4C2464675D'),
}
g.BASE_WITNESSES = {kind: (BASE_DIR / 'build-witness.json',
    'AD13E264D553F9DC0582D4656BB0A70A4DEF6203539019282A8FE883833DD5FF') for kind in g.PROJECT}
g.COMPANIONS = [Path(__file__), ROOT / 'outputs/input46/test_build_input46.py']

def metadata(kind, old, compiled):
    expected = copy.deepcopy(tomllib.loads(old[g.b.META].decode('utf-8')))
    mods = {m['modId']: m for m in expected['mods']}
    g.b.require(set(mods) == ({g.OWNER[kind]} if kind == 'fc' else {'piq_sfc_arcade', 'piq_sfc_home'}), 'Baseline mod ownership')
    mods[g.OWNER[kind]]['version'] = g.b.VERSIONS[g.OWNER[kind]]
    if kind == 'sfc':
        deps = [d for d in expected['dependencies']['piq_sfc_home'] if d['modId'] == 'piq_fc_arcade']
        g.b.require(len(deps) == 1, 'FC dependency count')
        deps[0]['versionRange'] = '[0.31.0-alpha.46,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode('utf-8')) == expected, 'Unapproved metadata semantics: ' + kind)
    manifest, count = re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',
        lambda m: m[1] + g.b.VERSIONS[g.OWNER[kind]].encode(), old[g.b.MANIFEST])
    g.b.require(count == 1 and compiled[g.b.MANIFEST] == manifest, 'Unapproved manifest change')

g.metadata = metadata

if __name__ == '__main__':
    g.main()
