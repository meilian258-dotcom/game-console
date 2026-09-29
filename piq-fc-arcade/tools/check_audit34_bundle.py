"""Matched final JAR FML discovery and unchanged GBA native/process regressions."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from freeze_fc_core_alpha19 import read_jar, digest, require, safe_path
import verify_retro_alpha19 as q
from build_audit34 import NAMES, VERSIONS

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'piq-gba/tools'))
from diagnostic_rom import create
from check_gba_handheld3 import RUNTIME, PROBES


def sha(path): return digest(path.read_bytes())


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True); p.add_argument('--runtime', type=Path, required=True)
    p.add_argument('--report', type=Path, required=True); a = p.parse_args()
    bundle = a.bundle.resolve(strict=True); rt = a.runtime.resolve(strict=True); report = safe_path(a.report)
    require(not report.exists(), 'New report required')
    paths = {k: bundle / n for k, n in NAMES.items()}
    paths.update({k: rt / k for k in RUNTIME})
    fence = {k: sha(path) for k, path in paths.items()}
    for n, pin in RUNTIME.items(): require(fence[n] == pin, 'Frozen GBA runtime changed ' + n)
    _, _, gba_entries = read_jar(paths['gba'])
    require(gba_entries['piq-gba-runtime.properties'] == ('helper.sha256=' + RUNTIME['piq-gba-helper.jar'] + '\n').encode(), 'GBA helper pin')
    require(not any(n.endswith(('.gba', '.sav', '.dll')) or n.startswith('com/sun/jna/') for n in gba_entries), 'No user files/native duplicates in GBA mod')
    probes = [ROOT / 'piq-gba/tools/qa' / (n + '.java') for n in PROBES]
    source_sha = {str(x.relative_to(ROOT)): sha(x) for x in [Path(__file__), *probes, ROOT / 'piq-gba/tools/diagnostic_rom.py']}
    with tempfile.TemporaryDirectory(prefix='piq-audit34-final-bundle-') as folder:
        work = Path(folder); classes = work / 'qa'; classes.mkdir(); empty = work / 'empty'; empty.mkdir()
        runtime = work / 'runtime'; runtime.mkdir(); staged = {}
        for kind, path in paths.items():
            target = work / (kind + '.jar') if kind in NAMES else runtime / kind
            shutil.copyfile(path, target); require(sha(target) == fence[kind], 'Exact copied input'); staged[kind] = target
        rom = work / 'original-arm.gba'; rom.write_bytes(create()); diagnostic_sha = sha(rom)
        def run(command, timeout=90):
            args = work / 'args.txt'
            args.write_text('\n'.join('"' + str(v).replace('\\', '/').replace('"', '\\"') + '"' for v in command[1:]), encoding='utf-8')
            done = subprocess.run([str(command[0]), '@' + str(args)], cwd=work, capture_output=True, text=True,
                                  encoding='utf-8', errors='replace', timeout=timeout)
            require(done.returncode == 0, done.stdout[-6000:] + '\n' + done.stderr[-8000:]); return done.stdout
        def probe(name, cp, args, timeout=90):
            source_name = name.rsplit('.', 1)[-1]
            run([q.JAVA / 'javac.exe', '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-sourcepath', empty,
                 '-cp', cp, '-d', classes, ROOT / 'piq-gba/tools/qa' / (source_name + '.java')])
            result = q.parse_last_json(run([q.JAVA / 'java.exe', '-Xmx512m', '-Djava.awt.headless=true',
                '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '-cp', cp, name, *args], timeout))
            require(result.get('ok') and result.get('production_origin') == 'final-jar-only', 'Invalid final probe ' + name)
            return result
        core_cp = os.pathsep.join(map(str, [classes, staged['piq-gba-helper.jar'], staged['jna-5.14.0.jar']]))
        results = {'core': probe('cn.piq.gba.bridge.GbaCoreProbe', core_cp,
            [staged['mgba_libretro.dll'], rom, work, staged['piq-gba-helper.jar']], 60)}
        bridge_cp = os.pathsep.join(map(str, [classes, staged['gba']]))
        results['process'] = probe('cn.piq.gba.bridge.GbaProcessProbe', bridge_cp,
            [runtime, rom, work / 'owned-process-saves', RUNTIME['piq-gba-helper.jar'], staged['gba']], 60)
        results['scope'] = probe('cn.piq.gba.bridge.GbaServerScopeProbe', bridge_cp, [work / 'owned-scope-saves', staged['gba']])
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        full_cp = os.pathsep.join(map(str, [classes, *(staged[k] for k in NAMES), q.MC, resources.resolve(strict=True), *q.dependencies()]))
        results['launch'] = probe('cn.piq.gba.client.GbaLaunchContextProbe', full_cp, [staged['gba']])
        results['common'] = probe('GbaHandheldCommon3Probe', full_cp,
            [staged['fc'], staged['gba'], VERSIONS['piq_gba'], staged['sfc'], staged['native']])
        require(results['core']['assertions'] >= 98434 and results['core']['actual_rgb565'], 'Actual native regression')
        require(results['process']['assertions'] >= 27 and results['process']['actual_save_restart'], 'Actual save/restart')
        require(results['scope']['assertions'] >= 38 and results['launch']['assertions'] >= 78, 'Server scope/launch boundary')
        require(len(results['common']['discovery']) == 4 and results['common']['client_and_native_load_attempts'] == 0, 'Four-mod discovery/common isolation')
        compiled = {x.relative_to(classes).as_posix() for x in classes.rglob('*.class')}
        production = set().union(*(set(read_jar(staged[k])[2]) for k in NAMES), set(read_jar(staged['piq-gba-helper.jar'])[2]))
        require(not compiled & production and all(Path(n).name[:-6].split('$')[0] in PROBES for n in compiled), 'Only QA compiled')
        require(fence == {k: sha(path) for k, path in paths.items()} == {k: sha(path) for k, path in staged.items()}, 'JAR/runtime changed')
        require(source_sha == {str(x.relative_to(ROOT)): sha(x) for x in [Path(__file__), *probes, ROOT / 'piq-gba/tools/diagnostic_rom.py']}, 'QA changed')
    data = {'schema': 'piq-audit34-bundle-final-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
            'jars': {k: {'path': str(paths[k]), 'sha256': fence[k]} for k in NAMES},
            'runtime': {k: {'path': str(paths[k]), 'sha256': fence[k]} for k in RUNTIME},
            'results': results, 'qa_source_sha256': source_sha, 'compiled_qa_classes': sorted(compiled),
            'diagnostic_rom_sha256': diagnostic_sha, 'minecraft_started': False, 'real_network_tested': False,
            'user_rom_or_save_used': False, 'limitations': [
                'FML actual discovery/annotations/dependencies and GBA common registration, not a full dedicated-server launch.',
                'Real mGBA/helper/process tests use a self-authored ARM fixture, not commercial-game acceptance.',
                'Other final diagnostics cover changed input, permission, repair and audio code separately.']}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as f: json.dump(data, f, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'assertions': {k: v['assertions'] for k, v in results.items()}}, ensure_ascii=False))


if __name__ == '__main__': main()
