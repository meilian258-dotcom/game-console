"""Package the audited FC23/SFC12/Native7 spectator delivery, never install it.

The original scope/wire reports retain their true FC23/SFC11 provenance. A
separate visual-only audit and fresh SFC12 worker/multiplayer reports bridge
that frozen baseline to this delivery. No old report is relabelled as SFC12.
"""
from __future__ import annotations
import argparse
import copy
import io
import json
from pathlib import Path
import tomllib
import xml.etree.ElementTree as ET
import zipfile

from freeze_fc_core_alpha19 import META, MANIFEST, checked_zip, digest, require, safe_path
from package_fc_core_alpha19 import (
    PackagePlan, check_ownership, json_document, revalidate_inputs, snapshot, verify_archive,
)
import prepare_watch23_scale12 as stage

ROOT = stage.ROOT
STAGE = ROOT / 'piq-fc-arcade/build/review-watch23-scale12-v1'
OUT = ROOT / '制作Mod/03-街机模拟/自动旁观与SFC比例修正-alpha23-测试包-20260911'
HELPER = 'piq-native-arcade/runtime/piq-native-helper.jar'
GUIDE = ROOT / 'piq-fc-arcade/design/街机与SFC自动旁观-alpha23-使用说明.md'
SCOPE_SHA = 'B693A0B823C995544CF33613CA47744A0E58F8023A5631E3F55A5C696BDC3DB7'
REPORTS = {
    'stage': 'piq-fc-arcade/build/review-watch23-scale12-v1/stage-verification.json',
    'baseline-stage': 'piq-fc-arcade/build/review-watch23-v1/stage-verification.json',
    'watch23-scope-baseline-sfc11': 'piq-fc-arcade/design/watch23-final-scope-v1.json',
    'watch23-wire-fc23': 'piq-fc-arcade/design/watch23-final-wire-v1.json',
    'sfc12-visual-only': 'piq-sfc-home/design/sfc-scale12-final-audit.json',
    'sfc12-actual-worker': 'piq-sfc-home/design/watch12-final-worker-v1.json',
    'sfc12-actual-multiplayer': 'piq-sfc-home/design/watch12-final-multiplayer-v1.json',
}
PREVIEWS = {
    '预览/SFC主机与卡带比例对照.png': 'piq-sfc-home/design/user-sfc-scale12/console-before-after.png',
    '预览/物品栏图标比例对照.png': 'piq-sfc-home/design/user-sfc-scale12/item-gui-before-after.png',
}
VISUAL_ASSETS = {
    'assets/piq_sfc_home/models/item/cartridge.json',
    'assets/piq_sfc_home/models/item/console.json',
}
VISUAL_ADDITIONS = {
    'cn/piq/sfchome/layout/SfcConsoleScale.class',
    'cn/piq/sfchome/layout/SfcConsoleScale$Point.class',
    'cn/piq/sfchome/layout/SfcConsoleScale$Bounds.class',
}
EXPECTED_MODS = {
    'fc': {'piq_fc_arcade': '0.31.0-alpha.23'},
    'sfc': {'piq_sfc_arcade': '0.2.0-alpha.6', 'piq_sfc_home': '0.1.0-alpha.12'},
    'native': {'piq_native_arcade': '0.1.0-alpha.7'},
}


def bind_jar(record, artifact, label):
    require(record.get('sha256') == artifact.sha256, 'Report JAR hash differs: ' + label)
    require(safe_path(record.get('path', ''), True) == artifact.path,
            'Report does not name the audited artifact: ' + label)


def check_scope(report, baselines):
    require(report.get('ok') is True and report.get('schema') == 'piq-watch23-final-scope-1',
            'Missing frozen spectator scope audit')
    require(report.get('production_compiled') is False and report.get('installed') is False,
            'Scope must audit final JARs without installing')
    for kind, artifact in baselines.items():
        bind_jar(report['jars'][kind], artifact, 'scope baseline ' + kind)
    require(report['ownership']['duplicate_classes'] == 0
            and report['frozen_sfc6_core_entries_byte_identical'] >= 60,
            'Original core/runtime ownership was not preserved')
    require(report['old_method_protection']['old_identical_methods'] >= 277
            and report['old_method_protection']['reviewed_changed_old_methods'] == 19,
            'Missing reviewed original-method protection')
    require(report['old_network_classes_whole_byte_identical'] == {'fc': 59, 'sfc': 29}
            and report['old_room_protocol_classes_byte_identical'] == 10
            and report['old_cabinet_six_records_fields_codecs_and_instructions_identical'] is True,
            'Original wire contracts not protected')
    require(len(report['negative_controls_rejected']) >= 5, 'Scope guard negative controls missing')
    probes = report['probes']
    require(probes['production_compiled'] is False and probes['old_separate_core_on_classpath'] is False,
            'Scope probes used a substitute production build')
    fml = probes['fml_discovery']; packet = probes['sfc_real_neoforge_outer_packet_codec']
    require(fml.get('ok') is True and fml['assertions'] >= 28
            and fml['production_origin'] == 'final-jar-only', 'Missing actual FML discovery')
    require(packet.get('passed') is True and packet['assertions'] == 37
            and 0 < packet['max_upload_outer_packet_bytes'] < 32767,
            'Missing bounded old real outer-codec regression')


def check_wire(report, fc):
    require(report.get('ok') is True and report.get('schema') == 'piq-watch23-independent-1'
            and report.get('production_compiled') is False
            and report.get('production_origin') == 'final-jar-only', 'Wrong final wire audit')
    require(report['production_sha256'] == {fc.path.name: fc.sha256}
            and safe_path(report['production_path'], True) == fc.path,
            'Wire report is not bound to the frozen FC23')
    actual = report['real_codec_and_shared_window']
    require(actual.get('ok') is True and actual['assertions'] >= 1362
            and all(actual.get(k) is True for k in ('actual_production_registration',
                'actual_neoforge_outer_codec', 'actual_connection_write_completion')),
            'Missing real codec/write-completion backpressure checks')
    require(actual['production_origin'] == 'final-jar-only'
            and actual['network_socket_opened'] is False
            and report['staged_copy_verified_before_and_after'] is True,
            'Wrong wire provenance or changed probe copy')


def check_scale(report, jars, baseline, delta):
    require(report.get('ok') is True and report.get('schema') == 'piq-sfc-scale12-independent-1'
            and report.get('visual_only') is True, 'Missing independent visual-only audit')
    for kind in ('fc', 'sfc'):
        bind_jar(report['jars'][kind], jars[kind], 'scale12 ' + kind)
    bind_jar(report['baseline_sfc11'], baseline, 'scale12 original SFC11')
    strict = report['strict_scope']
    require(strict['old_network_server_watch_playback_preserved'] is True
            and strict['standalone_controller_preserved'] is True,
            'SFC12 altered nonvisual behavior or independent controller')
    require(set(strict['changed_assets']) == VISUAL_ASSETS
            and {n for n in delta['changed'] if n.startswith('assets/')} == VISUAL_ASSETS,
            'Unexpected final asset delta')
    require(report['frozen_sfc6_entries_preserved'] >= 60
            and report['geometry']['final_jar_only'] is True,
            'Missing frozen-core protection or final-JAR geometry evidence')


def check_worker(report, jars):
    require(report.get('ok') is True and report.get('mode') == 'final-jar-only'
            and report.get('production_compiled') is False, 'Worker test must use final JARs')
    for kind in ('fc', 'sfc'):
        bind_jar(report['jars'][kind], jars[kind], 'SFC12 worker ' + kind)
    actual = report['actual']
    require(actual.get('ok') is True and actual['assertions'] >= 38
            and actual['production_origin'] == 'final-jar-only'
            and actual['production_compiled'] is False and actual['actual_playback_core_started'] is True,
            'Missing actual production playback/core regression')
    require(actual['host_frames_run_after_callback_failure'] > 0
            and actual['p2_media_calls'] == 0
            and actual['suspend_resume_media_sequence_monotonic'] is True,
            'Observer callback isolation/sequence regression')
    require(all(actual[k] is False for k in ('minecraft_started', 'network_socket_opened',
            'audio_device_opened', 'commercial_rom_used')), 'Wrong worker test scope')


def check_multiplayer(report, jars):
    require(report.get('passed') is True and report.get('mode') == 'final-jar-only'
            and report.get('production_compiled') is False, 'Multiplayer test must use final JARs')
    for kind in ('fc', 'sfc'):
        bind_jar(report['jars'][kind], jars[kind], 'SFC12 multiplayer ' + kind)
    actual = report['actual']
    require(actual.get('passed') is True and actual['actual_playback_worker_jvms'] == 2
            and actual['coordinator_assertions'] >= 782 and actual['worker_assertions'] >= 796
            and actual['actual_codec_roundtrips'] >= 315 and actual['matched_video_and_pcm_frames'] >= 184,
            'Missing two-worker/real-codec frame and audio regression')
    require(actual['queued_ready_suppressed_after_close'] is True
            and actual['host_worker_restart_count'] == 0
            and actual['actual_button_port_cases'] >= 24 and len(actual['joins']) >= 2,
            'Missing rejoin/queued-ready/port regression')
    require(all(report[k] is False for k in ('minecraft_started', 'network_socket_opened',
            'audio_device_opened', 'commercial_roms', 'installed')), 'Wrong multiplayer test scope')


def check_metadata(entries, baseline):
    owners = {}
    for kind, values in entries.items():
        data = tomllib.loads(values[META].decode('utf-8'))
        actual = {m['modId']: m['version'] for m in data['mods']}
        require(actual == EXPECTED_MODS[kind] and len(actual) == len(data['mods']),
                'Wrong or duplicate mod IDs/versions: ' + kind)
        for mod in actual:
            require(mod not in owners, 'Duplicate mod ID across JARs'); owners[mod] = kind
        if kind != 'sfc':
            require(values[META] == baseline[kind][META] and values[MANIFEST] == baseline[kind][MANIFEST],
                    'Unchanged module metadata differs: ' + kind)
        else:
            expected = copy.deepcopy(tomllib.loads(baseline[kind][META].decode('utf-8')))
            for mod in expected['mods']:
                if mod['modId'] == 'piq_sfc_home': mod['version'] = '0.1.0-alpha.12'
            require(data == expected, 'Only SFC home version may change; all dependencies retained')
            require(values[MANIFEST] == baseline[kind][MANIFEST].replace(b'0.1.0-alpha.11', b'0.1.0-alpha.12'),
                    'Unexpected SFC manifest delta')
    require(len(owners) == 4, 'Need exactly FC/home/core/native legacy mod IDs')
    return owners


def build_counts(project, inputs):
    counts = dict(tests=0, failures=0, errors=0, skipped=0); hashes = {}
    paths = sorted((ROOT / project / 'build/test-results/test').glob('TEST-*.xml'))
    require(paths, 'Missing full test XML: ' + project)
    for path in paths:
        item = snapshot(path, 8 * 1024 * 1024); inputs.append(item); hashes[path.name] = item.sha256
        xml = ET.fromstring(item.raw); require(xml.tag == 'testsuite', 'Unexpected test XML root')
        for key in counts:
            value = int(xml.attrib[key]); require(value >= 0, 'Negative test count'); counts[key] += value
    require(counts['failures'] == counts['errors'] == 0, 'Failed full build: ' + project)
    if project == 'piq-fc-arcade':
        require((counts['tests'], counts['skipped']) == (963, 7), 'Wrong frozen FC23 test run')
    else:
        require(counts['tests'] >= 237 and counts['skipped'] == 0, 'Incomplete SFC12 test run')
    return {**counts, 'test_xml_sha256': hashes}


def plan():
    reports = {k: snapshot(ROOT / v, 8 * 1024 * 1024) for k, v in REPORTS.items()}
    parsed = {k: json_document(v.raw) for k, v in reports.items()}
    require(reports['watch23-scope-baseline-sfc11'].sha256 == SCOPE_SHA, 'Reviewed scope v1 changed')
    jars = {k: snapshot(STAGE / name) for k, name in stage.NAMES.items()}
    baselines = {k: snapshot(stage.BASE / name) for k, (name, _) in stage.PINNED.items()}
    for kind, artifact in baselines.items():
        require(artifact.sha256 == stage.PINNED[kind][1], 'Frozen baseline SHA differs: ' + kind)
    for kind in ('fc', 'native'):
        require(jars[kind].raw == baselines[kind].raw, 'Frozen unchanged JAR differs: ' + kind)
    helper = snapshot(STAGE / HELPER); require(helper.sha256 == stage.HELPER_SHA, 'Native helper changed')
    recorded = parsed['stage']; old_stage = parsed['baseline-stage']
    require(recorded.get('schema') == 'piq-watch23-scale12-stage-1' and recorded.get('ok') is True
            and recorded.get('installed') is False and recorded.get('minecraft_started') is False,
            'Wrong scale12 stage record')
    expected_files = {stage.NAMES[k]: {'bytes': len(v.raw), 'sha256': v.sha256} for k, v in jars.items()}
    expected_files[HELPER] = {'bytes': len(helper.raw), 'sha256': helper.sha256}
    require(recorded['files'] == expected_files, 'Stage file inventory/SHA differs')
    require(recorded['baseline_sha256'] == {k: s.sha256 for k, s in baselines.items()}, 'Wrong stage baseline chain')
    require(old_stage['schema'] == 'piq-auto-watch23-stage-1', 'Wrong original spectator stage')
    for kind, artifact in baselines.items():
        require(old_stage['files'][artifact.path.name]['sha256'] == artifact.sha256, 'Original stage hash differs')
    entries = {k: stage.clean(checked_zip(s.raw)[1]) for k, s in jars.items()}
    original = {k: stage.clean(checked_zip(s.raw)[1]) for k, s in baselines.items()}
    delta = stage.classify(original['sfc'], entries['sfc'])
    require(delta == recorded['sfc_scope'] and set(delta['added']) == VISUAL_ADDITIONS and not delta['removed'],
            'Visual delta does not reproduce stage record')
    mod_owners = check_metadata(entries, original); ownership = check_ownership(entries)
    require(ownership['classes'] == recorded['unique_classes'], 'Stage class inventory differs')
    check_scope(parsed['watch23-scope-baseline-sfc11'], baselines)
    check_wire(parsed['watch23-wire-fc23'], baselines['fc'])
    check_scale(parsed['sfc12-visual-only'], jars, baselines['sfc'], delta)
    check_worker(parsed['sfc12-actual-worker'], jars)
    check_multiplayer(parsed['sfc12-actual-multiplayer'], jars)
    inputs = [*reports.values(), *jars.values(), *baselines.values(), helper]
    builds = {}
    for project, path, expected in (
        ('piq-fc-arcade', ROOT / 'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.23.jar', old_stage['mods']['fc']['source_sha256']),
        ('piq-sfc-home', stage.BUILD, recorded['source_sfc_sha256']),
    ):
        source = snapshot(path); inputs.append(source)
        require(source.sha256 == expected, 'Tested build JAR no longer matches staged source: ' + project)
        builds[project] = {**build_counts(project, inputs), 'source_jar_sha256': source.sha256}
    guide = snapshot(GUIDE, 1024 * 1024); inputs.append(guide)
    require(guide.raw.decode('utf-8-sig').strip(), 'Empty delivery guide')
    payloads = {'mods/' + item.path.name: item.raw for item in jars.values()}
    payloads[HELPER] = helper.raw; payloads['先看这里.md'] = guide.raw
    payloads.update({'checks/' + k + '.json': s.raw for k, s in reports.items()})
    for name, path in PREVIEWS.items():
        item = snapshot(ROOT / path, 16 * 1024 * 1024); inputs.append(item)
        require(item.raw.startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid offline preview PNG')
        payloads[name] = item.raw
    payloads['checks/full-build.json'] = json.dumps({'ok': True, 'projects': builds,
        'final_jars': {k: v.sha256 for k, v in jars.items()},
        'unchanged_native_not_rebuilt': jars['native'].sha256, 'unchanged_helper': helper.sha256,
        'minecraft_started': False, 'installed': False}, ensure_ascii=False, indent=2).encode('utf-8')
    payloads['SHA256.txt'] = ''.join(digest(raw) + '  ' + name + '\n'
        for name, raw in sorted(payloads.items())).encode('utf-8')
    require(sum(n.startswith('mods/') for n in payloads) == 3
            and sum(n.endswith('.jar') for n in payloads) == 4, 'Exactly three MODs and one helper required')
    summary = {'schema': 'piq-watch23-scale12-delivery-1',
        'final_jars': {k: v.sha256 for k, v in jars.items()}, 'helper_sha256': helper.sha256,
        'mod_owners': mod_owners, 'ownership': ownership,
        'evidence_chain': {'spectator_baseline_sfc11': baselines['sfc'].sha256,
            'visual_only_sfc12': jars['sfc'].sha256, 'sfc12_worker_and_multiplayer_rerun': True},
        'files': {n: {'bytes': len(v), 'sha256': digest(v)} for n, v in payloads.items()},
        'installed': False, 'minecraft_started': False, 'live_multiplayer_tested_this_turn': False,
        'offline_previews_not_game_screenshots': True}
    return PackagePlan(payloads, tuple(inputs), summary)


def build(package, check_only=False):
    folder = safe_path(OUT); archive_path = safe_path(OUT.with_suffix('.zip'))
    report_path = safe_path(OUT.with_suffix('.verification.json'))
    require(folder.is_relative_to(ROOT) and all(not x.exists() for x in (folder, archive_path, report_path)),
            'Refusing existing/outside delivery')
    revalidate_inputs(package)
    result = dict(package.summary, ok=True, path=str(archive_path), check_only=check_only)
    if check_only: return result
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, raw in sorted(package.payloads.items()):
            info = zipfile.ZipInfo(name, (2026, 9, 11, 0, 0, 0)); info.create_system = 3
            info.external_attr = 0o100644 << 16; info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
    raw = buffer.getvalue(); verify_archive(raw, package); revalidate_inputs(package)
    folder.mkdir(parents=True)
    for name, value in package.payloads.items():
        path = safe_path(folder / name); require(path.is_relative_to(folder), 'Output escaped delivery')
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as stream: stream.write(value)
        require(snapshot(path).raw == value, 'Readback failed: ' + name)
    with archive_path.open('xb') as stream: stream.write(raw)
    final = snapshot(archive_path); verify_archive(final.raw, package)
    require(final.raw == raw, 'ZIP readback differs'); revalidate_inputs(package)
    result.update(bytes=len(raw), sha256=final.sha256)
    with report_path.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps(build(plan(), args.check_only), ensure_ascii=True))
