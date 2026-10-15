"""Bounded FC-JAR production loader check; reuses an original shared mock, not MAME."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile
from run_production_runtime_load import HERE, digest, no_links, copy_verified, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('jar', 'mock', 'observer', 'output', 'jdk'):
        parser.add_argument('--' + key, type=Path, required=True)
    for key in ('jar', 'mock', 'observer', 'bridge', 'runtime'):
        parser.add_argument('--' + key + '-sha256', required=True)
    args = parser.parse_args()
    output = no_links(args.output)
    if os.name != 'nt' or output.exists() or not str(output).isascii():
        raise ValueError('New ASCII Windows output required')
    source = no_links(args.jar)
    if not source.is_file() or not 0 < source.stat().st_size <= 100 * 1024 * 1024 or digest(source) != args.jar_sha256.lower():
        raise ValueError('Candidate JAR identity mismatch')
    output.mkdir()
    jar = output / 'candidate.jar'
    with source.open('rb') as incoming, jar.open('xb') as outgoing:
        shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
    if digest(jar) != args.jar_sha256.lower():
        raise ValueError('Staged JAR identity mismatch')
    prefix = 'core/libretro-jni/'
    receipt = dict(scope='FC candidate JAR with original shared mock only; not MAME or Minecraft',
                   jar_sha256=digest(jar), jar_bytes=jar.stat().st_size, entries={}, runs=[], passed=False)
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive entries')
        licenses = {'libcxx-LICENSE.txt', 'libcxxabi-LICENSE.txt', 'libunwind-LICENSE.txt', 'NOTICE.md'}
        expected = {prefix + name for name in ('README.md', 'runtime.properties', 'windows-x64/piq-libretro-jni.dll', 'windows-x64/libc++.dll')}
        expected.update(prefix + 'licenses/' + name for name in licenses)
        if {n for n in names if n.startswith(prefix) and not n.endswith('/')} != expected:
            raise ValueError('Unexpected native bundle file set')
        for name in sorted(expected):
            info = archive.getinfo(name)
            if not 0 < info.file_size <= 16 * 1024 * 1024:
                raise ValueError('Bundle entry exceeds budget')
            data = archive.read(name)
            receipt['entries'][name] = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        for name in licenses:
            if receipt['entries'][prefix + 'licenses/' + name]['sha256'] != digest(HERE.parent / 'licenses' / name):
                raise ValueError('Bundled license differs from reviewed source')
        manifest = archive.read(prefix + 'runtime.properties').decode('ascii')
        properties = {}
        for line in manifest.splitlines():
            key, separator, value = line.partition('=')
            if not separator or key in properties:
                raise ValueError('Noncanonical or duplicate manifest property')
            properties[key] = value
        bridge = receipt['entries'][prefix + 'windows-x64/piq-libretro-jni.dll']
        runtime = receipt['entries'][prefix + 'windows-x64/libc++.dll']
        expected_properties = {'abi': '2', 'runtime-dependency-api': '1', 'windows-x64.runtime.count': '1',
                               'windows-x64.sha256': args.bridge_sha256.lower(), 'windows-x64.runtime.0.name': 'libc++.dll',
                               'windows-x64.runtime.0.sha256': args.runtime_sha256.lower(), 'windows-x64.runtime.0.bytes': str(runtime['bytes'])}
        if properties != expected_properties or bridge['sha256'] != args.bridge_sha256.lower() or runtime['sha256'] != args.runtime_sha256.lower():
            raise ValueError('Pinned manifest/runtime mismatch')
        for name in ('cn/piq/retro/libretro/jni/NativeLibretroBridge.class',
                     'cn/piq/retro/libretro/LibretroJniRuntime.class',
                     'cn/piq/retro/libretro/jni/RuntimeDependencyManifest.class'):
            receipt['entries'][name] = dict(sha256=hashlib.sha256(archive.read(name)).hexdigest())
    resources = output / 'mock-resources'
    copy_verified(no_links(args.mock), resources / 'core/shared-runtime-mock.dll', args.mock_sha256)
    observer = output / 'observer.dll'
    copy_verified(no_links(args.observer), observer, args.observer_sha256)
    instance, classes = output / 'instance', output / 'probe-classes'
    instance.mkdir(); classes.mkdir()
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    def save():
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    def execute(label, command):
        result = run(command, output, output / (label + '.log'), 60, env)
        receipt['runs'].append(dict(label=label, **result)); save()
        if result['exit_code']:
            raise ValueError(label + ' failed; inspect private log')
    execute('compile-probe', [str(args.jdk / 'bin/javac.exe'), '--release', '21', '-encoding', 'UTF-8', '-proc:none',
                             '-cp', str(jar), '-d', str(classes), str(HERE / 'RuntimeDependencyProbe.java'),
                             str(HERE / 'SharedRuntimeProductionProbe.java')])
    if any(p.name in ('NativeLibretroBridge.class', 'LibretroJniRuntime.class') for p in classes.rglob('*.class')):
        raise ValueError('Shadow production class')
    execute('public-jar', [str(args.jdk / 'bin/java.exe'), '-Xmx128m', '-Xcheck:jni', '-XX:ErrorFile=hs_err_pid%p.log',
                           '-cp', os.pathsep.join(map(str, (jar, classes, resources))),
                           'cn.piq.retro.libretro.jni.SharedRuntimeProductionProbe', str(instance), str(observer),
                           args.mock_sha256.lower(), str(jar)])
    if 'SHARED_PRODUCTION_OK cycles=3 frames=9 realCoreUnloads=3 runtimeStable=true workspaceClean=true' not in (output / 'public-jar.log').read_text():
        raise ValueError('Completed lifecycle checks missing')
    if list(output.glob('hs_err_pid*.log')) or digest(jar) != args.jar_sha256.lower():
        raise ValueError('JVM crash or changed candidate evidence')
    receipt.update(passed=True, mock_sha256=args.mock_sha256.lower(), cycles=3, frames=9,
                   core_unloads=3, slots_after_each_close=4, runtime_path_stable=True, core_workspaces_deleted=True)
    save()
    print(json.dumps({'passed': True, 'jar_sha256': receipt['jar_sha256'], 'cycles': 3, 'frames': 9}))


if __name__ == '__main__':
    main()
