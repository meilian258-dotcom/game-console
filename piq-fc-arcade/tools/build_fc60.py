"""FC60 local candidate freezer; SFC36 is rebuilt for ABI regression only.

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
spec = importlib.util.spec_from_file_location('fc60_review_helpers', PROFILE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
g = p.g
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.60', piq_sfc_home='0.1.0-alpha.36')
g.b.TEST_LIMITS = {'piq-fc-arcade': (2189, 8), 'piq-sfc-home': (468, 0)}
g.OUT = ROOT / 'piq-fc-arcade/build/review-fc60-v1'
g.PLAN = ROOT / 'outputs/fc60/reviewed-changes-v2.json'
g.NAMES = {'fc': 'game_console-0.31.0-alpha.60.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.36.jar'}
base = ROOT / 'piq-fc-arcade/build/review-fc59-v1'
g.BASE = {
    'fc': (base / 'game_console-0.31.0-alpha.59.jar', 'EB41ED3FDE18FCDA6B1E0415CA87028DE244C2D563FDFE0F3EBFC50133E68C4D'),
    'sfc': (base / 'game_console_sfc-0.1.0-alpha.36.jar', '3F1C20CB902E5A7426B92F39FA20F35468E7B19F99F3482F478DABBD0DDAF51D'),
}
g.BASE_WITNESSES = {
    kind: (base / 'build-witness.json', '00C5E45990821D83AF2C652D05E9416B7794B2D7EDB80BA598D188A4C5EE0A90')
    for kind in g.BASE
}
g.SOURCE_BEFORE = ROOT / 'outputs/fc59/source-witness-v1.json'
g.SOURCE_BEFORE_SHA = '2B6FD50C9726C3418FF5A92D0EC431E4818D1EE241F4625327D9D2EA7A2EDFF8'
g.COMPANIONS = [
    Path(__file__).resolve(), PROFILE,
    ROOT / 'outputs/fc60/run_build.py', ROOT / 'outputs/fc60/deliver.py',
    ROOT / 'outputs/fc60/verify_flash_compat.py', ROOT / 'outputs/admin58/check_flash_compat.py',
    ROOT / 'outputs/fc59/flash-compat.json',
    ROOT / 'piq-fc-arcade/design/FC60-存档设置与界面提示-测试说明.md',
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
    g.b.require(set(plan['language_changes'][kind]) <= allowed_languages, 'FC60 only permits the two FC language JSON files')
    # The established plan schema separates language_changes from resource_changes.
    # Approved zh_cn/en_us key deltas continue through original_overlay below.
    g.b.require(not plan['resource_changes'][kind], 'FC60 scope excludes model/texture/audio changes')
    result, preserved = original_overlay(kind, old, compiled, plan)
    if kind == 'sfc':
        g.b.require(result == old, 'FC-only scope: SFC36 must stay byte-for-byte identical')
    return result, preserved


g.overlay = overlay


if __name__ == '__main__':
    g.main()
