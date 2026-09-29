"""Local-only GC-018 builds with immutable full logs; no installation or launch."""
from pathlib import Path
import os
import subprocess
import argparse
import sys

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')

def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', choices=['piq-fc-arcade', 'piq-sfc-home'])
    parser.add_argument('--log', type=Path, required=True)
    args = parser.parse_args()
    log = args.log.resolve()
    if not log.is_relative_to((ROOT / 'outputs/gc018').resolve()):
        raise ValueError('Use the GC-018 evidence directory')
    log.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(JAVA))
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + env.get('PATH', '')
    command = [str(ROOT / args.project / 'gradlew.bat'), 'check', 'jar', '--offline', '--rerun-tasks', '--console=plain']
    with log.open('xb') as output:
        child = subprocess.Popen(command, cwd=ROOT / args.project, env=env,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for line in iter(child.stdout.readline, b''):
            output.write(line)
            output.flush()
            print(line.decode('utf-8', errors='replace').rstrip(), flush=True)
        code = child.wait()
    if code:
        raise SystemExit(code)

if __name__ == '__main__':
    main()
