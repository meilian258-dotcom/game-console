"""FC61 local candidate freezer; SFC36 is rebuilt for ABI regression only.

Prepare/review the plan after all editors finish, then capture, build and freeze.
Importing this module does not capture, build, install, launch or publish anything.
"""
from pathlib import Path
import hashlib
import importlib.util

ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT / 'piq-fc-arcade/tools/build_admin58.py'
if hashlib.sha256(PROFILE.read_bytes()).hexdigest().upper() != '5D9CDAFDDD9895C6668218DCDE6267FD7F9C2BBB74CF26CEBA77553AA63F1263':
    raise ValueError('Pinned FC58 review helper changed')
spec = importlib.util.spec_from_file_location('fc61_review_helpers', PROFILE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
g = p.g
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.61', piq_sfc_home='0.1.0-alpha.36')
g.b.TEST_LIMITS = {'piq-fc-arcade': (2205, 8), 'piq-sfc-home': (468, 0)}
g.OUT = ROOT / 'piq-fc-arcade/build/review-fc61-v1'
g.PLAN = ROOT / 'outputs/fc61/reviewed-changes-v1.json'
g.NAMES = {'fc': 'game_console-0.31.0-alpha.61.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.36.jar'}
base = ROOT / 'piq-fc-arcade/build/review-fc60-v1'
g.BASE = {
    'fc': (base / 'game_console-0.31.0-alpha.60.jar', '455F8D741099958CAEA615A1709FD02CA5E909B7A4E8D56ECD59AB39D0A0F7BA'),
    'sfc': (base / 'game_console_sfc-0.1.0-alpha.36.jar', '3F1C20CB902E5A7426B92F39FA20F35468E7B19F99F3482F478DABBD0DDAF51D'),
}
g.BASE_WITNESSES = {
    kind: (base / 'build-witness.json', 'BF00677BAA51B715C2BBB884B29722FB05FA8873E6B35A59D1B1CA5DE6DC10B5')
    for kind in g.BASE
}
g.SOURCE_BEFORE = ROOT / 'outputs/fc60/source-witness-v2.json'
g.SOURCE_BEFORE_SHA = 'BE460A81BC5A7AB9BF6075296833C4A5C61F7958C1A4B4AB02309D4CCD92BD3B'
g.COMPANIONS = [
    Path(__file__).resolve(), PROFILE,
    ROOT / 'outputs/fc61/run_build.py', ROOT / 'outputs/fc61/deliver.py',
    ROOT / 'outputs/fc61/verify_flash_compat.py', ROOT / 'outputs/admin58/check_flash_compat.py',
    ROOT / 'outputs/fc60/flash-compat.json',
    ROOT / 'piq-fc-arcade/design/FC61-卡带存档与加入设置-测试说明.md',
    ROOT / 'piq-flash-box/build.gradle',
    ROOT / 'piq-flash-box/src/main/templates/META-INF/neoforge.mods.toml',
    ROOT / 'piq-flash-box/README.md',
]
g.COMPANIONS = list(dict.fromkeys([
    *g.COMPANIONS,
    *(path for path in sorted((ROOT / 'piq-flash-box/src').rglob('*')) if path.is_file()),
]))

original_overlay = g.overlay


def overlay(kind, old, compiled, plan):
    allowed_languages = {'assets/piq_fc_arcade/lang/zh_cn.json', 'assets/piq_fc_arcade/lang/en_us.json'} if kind == 'fc' else set()
    g.b.require(set(plan['language_changes'][kind]) <= allowed_languages, 'FC61 only permits the two FC language JSON files')
    # The established plan schema separates language_changes from resource_changes.
    # Approved zh_cn/en_us key deltas continue through original_overlay below.
    g.b.require(not plan['resource_changes'][kind], 'FC61 scope excludes model/texture/audio changes')
    result, preserved = original_overlay(kind, old, compiled, plan)
    if kind == 'sfc':
        g.b.require(result == old, 'FC-only scope: SFC36 must stay byte-for-byte identical')
    return result, preserved


g.overlay = overlay


if __name__ == '__main__':
    g.main()
