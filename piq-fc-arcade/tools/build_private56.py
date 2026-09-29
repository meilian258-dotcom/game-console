"""FC56/SFC34 private-local play freezer; never build, install, publish or run a game.

The approved per-release plan still lists exact class families and removals. This
adapter additionally restricts the reviewable families and rejects ALL asset and
language changes. Final counts in witnesses are real JUnit counts, not these floors.
Use --plan-template, --capture, --freeze as before; --verify is strictly read-only.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.util
import json
import re
import sys
import time
import tomllib

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'piq-fc-arcade/tools/build_gc018_45.py'
HELPER_SHA = '443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
if hashlib.sha256(HELPER.read_bytes()).hexdigest().upper() != HELPER_SHA:
    raise ValueError('Pinned exact-freeze helper changed')
_spec = importlib.util.spec_from_file_location('private56_fixed_helpers', HELPER)
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)

g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.56', piq_sfc_home='0.1.0-alpha.34', piq_native_arcade='0.1.1')
# Reviewed minimum: prior FC1984 + new private31 + public-default16;
# SFC424 + private-engine11. Complete final suites and actual counts remain witnessed.
g.b.TEST_LIMITS = {'piq-fc-arcade': (2048, 8), 'piq-sfc-home': (444, 0)}
g.OUT = ROOT / 'piq-fc-arcade/build/review-private56-v2'
g.NAMES = {'fc': 'game_console-0.31.0-alpha.56.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.34.jar'}
g.PLAN = ROOT / 'outputs/private56/reviewed-changes.json'
g.SOURCE_BEFORE = ROOT / 'outputs/private56/source-before.json'
g.SOURCE_BEFORE_SHA = 'C253AB1FFB3598D8CDD796D7B96991EF8EA062D401FB8CC078927186AB4B7D56'
BASE = ROOT / 'piq-fc-arcade/build/review-content55-v1'
g.BASE = {
    'fc': (BASE / 'game_console-0.31.0-alpha.55.jar', 'B325CF836F25EAE6BB6DFD90BFF9AF15EBB9D8FD557D8C29B2884E8D9B2CC6D0'),
    'sfc': (BASE / 'game_console_sfc-0.1.0-alpha.33.jar', 'A7BB5691049CC0E7E96DE8D69B6EE782728C26828E88C00230FF2B2D0A09E427'),
}
g.BASE_WITNESSES = {kind: (BASE / 'build-witness.json', 'BF5BF30E473484999623459337C1E7A7ADDE1BE9BAF469A4361272579F75222F') for kind in g.BASE}
g.REFERENCES['native'] = (ROOT / 'piq-fc-arcade/build/review-tvcoin48-v1/game_console_arcade-0.1.1.jar',
                          '4DE532DFA6611D5A2F360C38C4106E467DE550E5940D3A9C4250503383D59AE6')
g.COMPANIONS = [
    Path(__file__).resolve(),
    ROOT / 'piq-fc-arcade/tools/test_build_private56.py',
    ROOT / 'outputs/private56/run_build.py',
    ROOT / 'outputs/private56/check_frozen.py',
    ROOT / 'outputs/private56/test_freezer.py',
    ROOT / 'piq-fc-arcade/design/私人模式与双人默认-FC56-SFC34-测试说明.md',
    ROOT / 'piq-sfc-home/tools/qa/PrivateHome56Probe.java',
    ROOT / 'piq-sfc-home/tools/qa/SfcContentPermissionsProbe.java',
    ROOT / 'piq-sfc-home/tools/qa/SfcPlayerMedia53Probe.java',
    ROOT / 'piq-sfc-home/tools/check_player_media53.py',
    ROOT / 'piq-sfc-home/tools/run_sfc_playback_multiplayer_probe.py',
    ROOT / 'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',
    ROOT / 'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java',
    ROOT / 'piq-sfc-home/README.md',
]
REVIEWABLE_CLASS_ROOTS = {
    'fc': frozenset('cn/piq/fcarcade/' + name for name in (
        'client/privateplay/PrivateEngine', 'client/privateplay/PrivateSaveStore',
        'client/privateplay/FcPrivateEngine', 'client/PrivateHomeClient',
        'client/PrivateHomeScreen',
        'home/HomeEndpointBlockEntity', 'home/HomeControllerData', 'home/HomeHardware',
        'server/ServerRomLibrary', 'server/ServerArcadeSessions', 'server/ServerCartridgeService',
        'client/ClientCartridgeEditor', 'client/ClientArcadeEvents', 'client/HomeApplianceClient',
        'client/TelevisionTone', 'client/NetworkDiagnosticsView',
        'client/HomeSyncSettingsScreen', 'client/ClientRomTransfers', 'client/PowerIndicatorRenderer',
    )),
    'sfc': frozenset('cn/piq/sfchome/' + name for name in (
        'client/SfcPrivateEngine', 'client/SfcPrivateProvider', 'client/SfcHomeClient',
        'server/SfcHomeServer', 'server/SfcCartridgeEditorService',
    )),
}
REQUIRED_SUITES = {
    'piq-fc-arcade': frozenset((
        'cn.piq.fcarcade.client.privateplay.FcPrivateEngineTest',
        'cn.piq.fcarcade.client.privateplay.PrivateSaveStoreTest',
        'cn.piq.fcarcade.client.PrivateHomeClientTest',
        'cn.piq.fcarcade.home.HomePublicDefaultsTest',
        'cn.piq.fcarcade.home.HomeControllerReceiptTest',
        'cn.piq.fcarcade.home.HomePrivateHardwareApiTest',
        'cn.piq.fcarcade.server.ServerRomPlayersPolicyTest',
    )),
    'piq-sfc-home': frozenset((
        'cn.piq.sfchome.client.SfcPrivateEngineTest',
        'cn.piq.sfchome.server.SfcPublicDefaultsSourceTest',
        'cn.piq.sfchome.client.SfcPrivateProviderSourceTest',
    )),
}


def validate_review_scope(plan):
    for kind in g.PROJECT:
        roots = set(plan['class_roots'][kind])
        g.b.require(roots and roots <= REVIEWABLE_CLASS_ROOTS[kind],
                    'Unreviewable private56 class family: ' + kind + ' ' + repr(sorted(roots - REVIEWABLE_CLASS_ROOTS[kind])))
        g.b.require(not plan['language_changes'][kind] and not plan['resource_changes'][kind],
                    'Private56 preserves all baseline languages/models/PNG/audio/assets: ' + kind)
    for project, mandatory in REQUIRED_SUITES.items():
        g.b.require(mandatory <= set(plan['required_test_suites'][project]),
                    'Private56 regression suite omitted: ' + project)
    return plan


_base_read_plan = g.read_plan
def read_plan(path=None):
    return validate_review_scope(_base_read_plan(g.PLAN if path is None else path))
g.read_plan = read_plan


def metadata(kind, old, compiled):
    g.b.require(kind in g.PROJECT, 'Unknown private56 artifact kind')
    expected = copy.deepcopy(tomllib.loads(old[g.b.META].decode('utf-8')))
    mods = expected['mods']
    g.b.require(len({item['modId'] for item in mods}) == len(mods)
                and {item['modId'] for item in mods} == ({'piq_fc_arcade'} if kind == 'fc' else {'piq_sfc_arcade', 'piq_sfc_home'}),
                'Unexpected or duplicate baseline mod ownership')
    matches = [item for item in mods if item['modId'] == g.OWNER[kind]]
    g.b.require(len(matches) == 1, 'One owner required')
    matches[0]['version'] = g.b.VERSIONS[g.OWNER[kind]]
    if kind == 'sfc':
        deps = [item for item in expected['dependencies']['piq_sfc_home'] if item['modId'] == 'piq_fc_arcade']
        g.b.require(len(deps) == 1, 'One FC dependency required')
        deps[0]['versionRange'] = '[0.31.0-alpha.56,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode('utf-8')) == expected, 'Unexpected metadata delta')
    manifest, count = re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',
        lambda match: match[1] + g.b.VERSIONS[g.OWNER[kind]].encode(), old[g.b.MANIFEST])
    g.b.require(count == 1 and compiled[g.b.MANIFEST] == manifest, 'Unexpected manifest delta')
g.metadata = metadata


def main(argv=None):
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--plan-template', type=Path)
    group.add_argument('--capture', type=Path)
    group.add_argument('--freeze', action='store_true')
    group.add_argument('--verify', action='store_true')
    parser.add_argument('--plan', type=Path, default=g.PLAN)
    parser.add_argument('--witness', type=Path)
    parser.add_argument('--witness-sha256')
    parser.add_argument('--fc-gradle-log', type=Path)
    parser.add_argument('--sfc-gradle-log', type=Path)
    parser.add_argument('--output', type=Path, default=g.OUT)
    parser.add_argument('--build-witness-sha256')
    args = parser.parse_args(argv)
    if args.verify:
        g.b.require(args.build_witness_sha256 and not any((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log)),
                    'Verify needs only output and pinned build-witness SHA')
        print(json.dumps(g.verify_output(args.output, args.build_witness_sha256), ensure_ascii=False))
        return
    g.b.require(not args.build_witness_sha256, 'Build-witness SHA is only for read-only --verify')
    if args.plan_template:
        g.plan_template(args.plan_template)
        return
    read_plan(args.plan)
    if args.capture:
        g.b.require(not any((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log)),
                    'Capture must precede complete build/test evidence')
        started, before = time.time_ns(), g.inputs(args.plan)
        g.b.require(before == g.inputs(args.plan), 'Inputs changed while capturing')
        g.b.exclusive_json(args.capture, {
            'schema': 'gc018-45-inputs-1', 'captured_ns': started, 'versions': g.b.VERSIONS,
            'plan_path': g.rel(args.plan), 'inputs': before,
            'required_build': 'After this capture, run FC then SFC: check jar --offline --rerun-tasks; retain separate full UTF-8 logs.',
        })
        print(json.dumps({'ok': True, 'capture': str(args.capture), 'sha256': g.file_hash(args.capture)}, ensure_ascii=False))
        return
    g.b.require(all((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log)),
                'Freeze needs capture SHA and two complete fresh Gradle logs')
    g.freeze(args)


if __name__ == '__main__':
    main()
