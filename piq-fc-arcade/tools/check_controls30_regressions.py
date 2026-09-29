"""Run existing final-JAR regression probes with bounded concurrency. Never compiles production."""
import argparse, concurrent.futures, json, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JOBS = [
    ('piq-fc-arcade', 'check_appliance28.py', 'appliance-final.json', True, []),
    ('piq-fc-arcade', 'check_home_runtime29.py', 'fc-runtime-final.json', False, []),
    ('piq-fc-arcade', 'check_zapper_stand30.py', 'stand-final.json', False, []),
    ('piq-sfc-home', 'run_sfc_playback_multiplayer_probe.py', 'sfc-runtime-final.json', True, ['--appliance']),
    ('piq-sfc-home', 'check_sfc_watch_playback.py', 'sfc-watch-final.json', True, []),
    ('piq-fc-arcade', 'check_appliance_nbt29.py', 'tv-nbt-final.json', False, []),
    ('piq-fc-arcade', 'check_controller_cable29.py', 'controller-cable-final.json', True, []),
    ('piq-fc-arcade', 'check_data_cable29.py', 'data-cable-final.json', False, []),
    ('piq-fc-arcade', 'check_cartridge_save_mode.py', 'cartridge-save-final.json', False, []),
    ('piq-sfc-home', 'check_sfc_controller_receipts.py', 'sfc-receipts-final.json', True, []),
    ('piq-fc-arcade', 'check_home29_core.py', 'gun-core-final.json', False, []),
]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', required=True, type=Path)
    args = parser.parse_args()
    stage = args.stage.resolve(strict=True)
    assert stage.is_relative_to(ROOT)
    fc = stage / 'piq_fc_arcade-0.31.0-alpha.30.jar'
    sfc = stage / 'piq_sfc-0.1.0-alpha.18.jar'
    assert fc.is_file() and sfc.is_file()
    checks = stage / 'checks'
    checks.mkdir(exist_ok=True)
    def execute(job):
        project, script, name, need_sfc, extras = job
        report = checks / name
        log = checks / (name + '.log')
        assert not report.exists() and not log.exists(), 'Refuse previous evidence overwrite'
        cmd = [sys.executable, str(ROOT/project/'tools'/script), '--fc', str(fc), '--report', str(report), *extras]
        if need_sfc:
            cmd += ['--sfc', str(sfc)]
        with log.open('x', encoding='utf-8') as output:
            result = subprocess.run(cmd, cwd=ROOT/project, stdout=output, stderr=subprocess.STDOUT, timeout=300)
        value = {'name': name, 'exit': result.returncode, 'report': str(report), 'log': str(log)}
        print(json.dumps(value), flush=True)
        return result.returncode == 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(execute, JOBS))
    if not all(results):
        raise SystemExit(1)

if __name__ == '__main__':
    main()
