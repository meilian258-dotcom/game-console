"""Final GBA3 bundle QA: probes only, unchanged native engine, common item and old cabinet boundary."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / 'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
import verify_device_ui_alpha18 as bytecode
from diagnostic_rom import create

BASELINE = ROOT / 'build/server-v2-1/piq_gba-0.1.0-alpha.2.jar'
BASELINE_SHA = '7311F33C5DC8827EAA92672FF897295A4CAB62E9BD5BC0CC09372734D46D2F10'
FC_SHA = 'F36169E46B13CF46868851447B8518BC38C10967A03994AF26E89CE7B1E4FE00'
RUNTIME = {
    'piq-gba-helper.jar': 'AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C',
    'jna-5.14.0.jar': '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',
    'mgba_libretro.dll': 'D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B',
}
PROBES = ['GbaCoreProbe', 'GbaProcessProbe', 'GbaServerScopeProbe', 'GbaLaunchContextProbe', 'GbaHandheldCommon3Probe']


def require(value, message):
    if not value:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()


def archive(path):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), 'Duplicate archive member')
        for name in names:
            pure = PurePosixPath(name)
            require(not pure.is_absolute() and '..' not in pure.parts and '\\' not in name and ':' not in name, 'Unsafe archive entry')
        require(z.testzip() is None, 'Archive CRC failed')
        return {name: z.read(name) for name in names if not name.endswith('/')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('fc', 'bundle', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    a = parser.parse_args()
    fc = a.fc.resolve(strict=True)
    bundle = a.bundle.resolve(strict=True)
    report = a.report.resolve()
    require(report.is_relative_to(ROOT) and not report.exists(), 'New report path inside piq-gba required')
    gba = (bundle / 'piq_gba-0.1.0-alpha.3.jar').resolve(strict=True)
    rt = bundle / 'piq-gba/runtime'
    require(set(x.name for x in rt.iterdir()) == set(RUNTIME), 'Exactly old three runtime files required')
    require(digest(fc) == FC_SHA, 'FC33 frozen bytes must remain unchanged')
    require(digest(BASELINE) == BASELINE_SHA, 'Frozen GBA2 baseline changed')
    for name, sha in RUNTIME.items():
        require(digest(rt / name) == sha, 'Old runtime changed: ' + name)
    before = archive(BASELINE)
    entries = archive(gba)
    helper_entries = archive(rt / 'piq-gba-helper.jar')
    require(entries['piq-gba-runtime.properties'] == ('helper.sha256=' + RUNTIME['piq-gba-helper.jar'] + '\n').encode(), 'Exact old helper lock')
    require(not any(name.endswith(('.gba', '.sav', '.dll')) or name.startswith('com/sun/jna/') or name in ('cn/piq/gba/bridge/GbaCore.class', 'cn/piq/gba/bridge/GbaWorker.class') for name in entries), 'No user ROM/save/native engine in MOD')
    protected = {name for name in before if name.startswith('cn/piq/gba/bridge/') or name.startswith('cn/piq/gba/client/GbaCabinetBackend$')}
    for name in protected:
        require(entries.get(name) == before[name], 'Old bridge/cabinet nested bytes changed: ' + name)
    preserved_methods = bytecode.assert_methods_unchanged(BASELINE, gba, q.JAVA / 'javap.exe', 'cn.piq.gba.client.GbaCabinetBackend', [' description()'])
    sources = [ROOT / 'tools/qa' / (name + '.java') for name in PROBES]
    sources += [Path(__file__), ROOT / 'tools/diagnostic_rom.py']
    source_sha = {str(path.relative_to(ROOT)): digest(path) for path in sources}
    paths = [fc, gba, BASELINE, *(rt / name for name in RUNTIME)]
    fence = {str(path): digest(path) for path in paths}
    with tempfile.TemporaryDirectory(prefix='piq-gba-handheld3-final-') as folder:
        work = Path(folder)
        classes = work / 'probes'
        classes.mkdir()
        empty = work / 'empty'
        empty.mkdir()
        runtime = work / 'runtime'
        runtime.mkdir()
        staged = {}
        for name, path in {'fc': fc, 'gba': gba, **{name: rt / name for name in RUNTIME}}.items():
            target = work / (name + '.jar') if name in ('fc', 'gba') else runtime / name
            shutil.copyfile(path, target)
            require(digest(path) == digest(target), 'Exact copied final bytes')
            staged[name] = target
        rom = work / 'original-diagnostic.gba'
        rom.write_bytes(create())
        diagnostic_sha = digest(rom)

        def run(command, timeout=90):
            command = list(map(str, command))
            args = work / 'args.txt'
            args.write_text('\n'.join('"' + value.replace('\\', '/').replace('"', '\\"') + '"' for value in command[1:]), encoding='utf-8')
            done = subprocess.run([command[0], '@' + str(args)], cwd=work, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
            require(done.returncode == 0, 'Probe failed: ' + ' '.join(command[:2]) + '\n' + done.stdout[-6000:] + '\n' + done.stderr[-8000:])
            return done.stdout

        def probe(name, cp, arguments, timeout=90):
            source_name = name.rsplit('.', 1)[-1]
            run([q.JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-sourcepath', empty, '-cp', cp, '-d', classes, ROOT / 'tools/qa' / (source_name + '.java')])
            output = run([q.JAVA / 'java.exe', '-Xmx512m', '-Djava.awt.headless=true', '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '-cp', cp, name, *arguments], timeout)
            result = q.parse_last_json(output)
            require(result.get('ok') and result.get('production_origin') == 'final-jar-only', 'Invalid final-JAR result ' + name)
            return result

        core_cp = os.pathsep.join(map(str, [classes, staged['piq-gba-helper.jar'], staged['jna-5.14.0.jar']]))
        core = probe('cn.piq.gba.bridge.GbaCoreProbe', core_cp, [staged['mgba_libretro.dll'], rom, work, staged['piq-gba-helper.jar']], 60)
        process_cp = os.pathsep.join(map(str, [classes, staged['gba']]))
        process = probe('cn.piq.gba.bridge.GbaProcessProbe', process_cp, [runtime, rom, work / 'process-owned-saves', RUNTIME['piq-gba-helper.jar'], staged['gba']], 60)
        scope = probe('cn.piq.gba.bridge.GbaServerScopeProbe', process_cp, [work / 'scope-owned-saves', staged['gba']])
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        full_cp = os.pathsep.join(map(str, [classes, staged['gba'], staged['fc'], q.MC, resources.resolve(strict=True), *q.dependencies()]))
        launch = probe('cn.piq.gba.client.GbaLaunchContextProbe', full_cp, [staged['gba']])
        common = probe('GbaHandheldCommon3Probe', full_cp, [staged['fc'], staged['gba']])
        require(core['actual_rgb565'] and core['assertions'] >= 98434, 'Actual core regression coverage')
        require(process['assertions'] >= 27 and process['actual_save_restart'], 'Actual owned process/save restart coverage')
        require(scope['assertions'] >= 38 and launch['assertions'] >= 78, 'Old cabinet context isolation coverage')
        require(common['actual_common_registration'] and common['actual_item_registry_and_codec'] and common['client_and_native_load_attempts'] == 0, 'Actual common-side item coverage')
        compiled = {str(path.relative_to(classes)).replace('\\', '/') for path in classes.rglob('*.class')}
        require(not compiled.intersection(set(entries) | set(helper_entries) | set(archive(fc))), 'Production class was compiled')
        for name in compiled:
            stem = PurePosixPath(name).name[:-6].split('$')[0]
            require(stem in PROBES, 'Unexpected compiled QA class: ' + name)
        require(digest(staged['fc']) == digest(fc) and digest(staged['gba']) == digest(gba), 'Copied final archives changed')
        for name, sha in RUNTIME.items():
            require(digest(staged[name]) == sha, 'Copied runtime changed: ' + name)
        require(fence == {str(path): digest(path) for path in paths}, 'Final/baseline/runtime mutated during QA')
        require(source_sha == {str(path.relative_to(ROOT)): digest(path) for path in sources}, 'QA source changed during execution')
        result = {
            'schema': 'piq-gba-handheld3-final-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False, 'compiled_only_probes': True,
            'jars': {name: {'path': str(path), 'sha256': digest(path)} for name, path in [('fc', fc), ('gba', gba)]},
            'runtime': {name: {'path': str(rt / name), 'sha256': RUNTIME[name], 'bytes': (rt / name).stat().st_size} for name in RUNTIME},
            'old_backend': {'baseline': str(BASELINE), 'sha256': BASELINE_SHA, 'protected_classes': sorted(protected), 'preserved_methods': preserved_methods, 'description_only_change_allowed': True},
            'core': core, 'process': process, 'scope': scope, 'launch': launch, 'common': common,
            'source_sha256': source_sha, 'compiled_qa_classes': sorted(compiled), 'diagnostic_rom_sha256': diagnostic_sha,
            'minecraft_world_or_client_started': False, 'socket_started': False, 'user_rom_or_save_used': False,
            'unrelated_sfc_native_loaded_or_modified': False,
            'limitations': ['Original self-authored ARM diagnostic only; no commercial game or Minecraft gameplay acceptance.',
                           'Actual FML discovery/common-side restricted loading/item registration, not full ModLauncher dedicated-server boot.',
                           'Native/process and old cabinet regression do not substitute for handheld client lifecycle/render QA, which is separately owned.'],
        }
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write('\n')
    print(json.dumps({'ok': True, 'report': str(report), 'sha256': digest(report), 'assertions': {name: result[name]['assertions'] for name in ['core', 'process', 'scope', 'launch', 'common']}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
