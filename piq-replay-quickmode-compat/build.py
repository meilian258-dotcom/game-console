"""Compile a client-only candidate against supplied read-only game dependencies."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = '0.1.0-test.3'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home', required=True, type=Path)
    p.add_argument('--libraries', required=True, type=Path)
    p.add_argument('--replay', required=True, type=Path)
    args = p.parse_args()
    client = args.libraries / 'net/neoforged/neoforge/21.1.250/neoforge-21.1.250-client.jar'
    universal = client.with_name('neoforge-21.1.250-universal.jar')
    for path in (client, universal, args.replay):
        if not path.is_file():
            raise SystemExit(f'Missing dependency: {path.name}')
    mapped_clients = sorted((args.libraries / 'net/minecraft/client').glob('1.21.1-*/client-1.21.1-*-srg.jar'))
    if len(mapped_clients) != 1:
        raise SystemExit('Expected exactly one Minecraft 1.21.1 mapped client')
    # NeoForge's client is patch-only. Older Minecraft classes in a shared launcher cache
    # must not shadow the version used by this candidate.
    cp = list(dict.fromkeys([client, mapped_clients[0], universal, args.replay, *sorted(args.libraries.rglob('*.jar'))]))
    build = ROOT / 'build'
    build.mkdir(exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='classes-test3-', dir=build))
    command = ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp',
               os.pathsep.join(str(v.resolve()) for v in cp), '-d', str(out),
               *map(str, sorted((ROOT / 'src/main/java').rglob('*.java')))]
    argfile = build / 'javac.args'
    argfile.write_text('\n'.join('"' + a.replace('\\', '/').replace('"', '\\"') + '"'
                                 for a in command), encoding='utf-8')
    exe = '.exe' if os.name == 'nt' else ''
    subprocess.run([str(args.java_home / 'bin' / ('javac' + exe)), '-J-Dfile.encoding=UTF-8',
                    '-J-Duser.language=en', '@' + str(argfile)], check=True)
    destination = build / 'libs'
    destination.mkdir(exist_ok=True)
    jar = destination / f'piq-replay-quickmode-compat-1.21.1-{VERSION}.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        def put(name, data):
            entry = zipfile.ZipInfo(name, (2026, 10, 11, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(entry, data)
        for base in (out, ROOT / 'src/main/resources'):
            for item in sorted(base.rglob('*')):
                if item.is_file():
                    put(item.relative_to(base).as_posix(), item.read_bytes())
        put('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\nImplementation-Version: {VERSION}\n\n'.encode())
        put('META-INF/LICENSE', (ROOT / 'LICENSE').read_bytes())
    result = {'version': VERSION, 'artifact': str(jar),
              'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'target_replay_sha256': hashlib.sha256(args.replay.read_bytes()).hexdigest(),
              'classes': str(out), 'status': 'compiled; graphical client validation pending'}
    (build / 'build-result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
