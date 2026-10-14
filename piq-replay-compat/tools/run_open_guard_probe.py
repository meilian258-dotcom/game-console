"""Run the real Replay open-guard transform against a completed build's exact inputs.

No Minecraft or native core is started. The real descriptor-gating plugin stays enabled.
Each invocation preserves fresh local logs and a path-free verification.json under build-dir.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--build-dir', required=True, type=Path)
    args = parser.parse_args()
    build = args.build_dir.resolve(strict=True)
    if not str(build).isascii() or (os.name == 'nt' and build.drive.upper() == os.environ.get('SystemDrive', 'C:').upper()):
        raise SystemExit('Use the completed build on an ASCII non-system work drive')
    argfile = build / 'javac.args'
    values = [line.strip().removeprefix('"').removesuffix('"') for line in argfile.read_text(encoding='utf-8').splitlines()]
    classes = Path(values[values.index('-d') + 1]).resolve(strict=True)
    if not classes.is_relative_to(build):
        raise SystemExit('Compiled output is outside the selected build directory')
    dependencies = values[values.index('-cp') + 1].split(os.pathsep)
    expected = {
        'open_mixin': 'cn/piq/replaycompat/mixin/ReplayOpenGuardMixin.class',
        'stop_mixin': 'cn/piq/replaycompat/mixin/ReplayForceStopMixin.class',
        'plugin': 'cn/piq/replaycompat/mixin/CompatMixinPlugin.class',
        'bridge': 'cn/piq/replaycompat/replay/ReplayBridge.class',
    }
    for relative in expected.values():
        if not (classes / relative).is_file():
            raise SystemExit('Run the current build.py first: a required compiled class is missing')
    out = Path(tempfile.mkdtemp(prefix='open-guard-transform-', dir=build))
    temp = out / 'tmp'; temp.mkdir()
    env = dict(os.environ, TEMP=str(temp), TMP=str(temp), TMPDIR=str(temp))
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    extension = '.exe' if os.name == 'nt' else ''
    java = args.java_home.resolve(strict=True) / 'bin'
    cp = os.pathsep.join(dict.fromkeys([str(classes), *dependencies]))
    probe = ROOT / 'tools/ReplayOpenGuardTransformProbe.java'
    host = ROOT.parent / 'piq-fc-arcade/tools/qa/KeyboardMixinTransformProbe.java'
    command = [str(java / ('javac' + extension)), '-J-Dfile.encoding=UTF-8', '-J-Djava.io.tmpdir=' + str(temp),
               '--release', '21', '-proc:none', '-encoding', 'UTF-8', '-cp', cp, '-d', str(out), str(host), str(probe)]
    run = [str(java / ('java' + extension)), '-ea', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str(temp),
           '-Djna.tmpdir=' + str(temp), '-XX:ErrorFile=' + str(temp / 'hs_err_pid%p.log'),
           '-cp', os.pathsep.join([str(ROOT / 'tools/mixin-probe-resources'), str(out), cp]),
           'ReplayOpenGuardTransformProbe', str(out / 'behavior.json')]
    for stage, invocation in [('compile', command), ('transform', run)]:
        result = subprocess.run(invocation, cwd=out, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        (out / (stage + '-private.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode:
            (out / 'verification.json').write_text(json.dumps({'ok': False, 'stage': stage, 'exit': result.returncode}, indent=2), encoding='utf-8')
            raise SystemExit(f'Open guard probe failed during {stage}; evidence preserved: {out}')
    result = json.loads((out / 'behavior.json').read_text(encoding='utf-8'))
    if not result.get('ok') or not result.get('production_api_plugin_enabled'):
        raise SystemExit(f'Missing required success assertions: {out}')
    result.update(javac_args_sha256=sha(argfile), probe_source_sha256=sha(probe), host_source_sha256=sha(host),
                  **{key + '_class_sha256': sha(classes / relative) for key, relative in expected.items()})
    (out / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    print(f'Evidence: {out}')


if __name__ == '__main__':
    main()
