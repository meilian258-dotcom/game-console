"""Generate isolated, byte-pinned nightly profiles in a new snapshot source tree.

Never edits the checkout, old distributions, game instances or saves. The native
info query runs in a short-lived child JVM, not in Python or Minecraft; it is not
an OS sandbox. Custom netplay cores and legacy WASM identities remain unchanged.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import zipfile

OLD = {
    'mesen-windows': '2b3fbe286995c80ebbc85239fd28c8fa07b1011cc69c7f9021816429e3473885',
    'mesen-linux': '552f8ab6ac1fd08bd555f589eb999be73a469c79ccfa929f884adb2cf3366b43',
    'mesen-s-windows': '8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42',
    'genesis-plus-gx-windows': '9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7',
    'mgba-windows': 'd1ba96bc1af23997d5c8003a6f6f8be7acba9d770d4d42d14557aaeb469fa16b',
}
OLD_PROFILE = '2b5deb2b15474ad01e933ff31e3c84df03c99163bb3f05ed8d705de379d2e575'
OLD_WORKER = '7546ce0243b94a35833134a2867151b88d604ef545ffc77e5a3c7c55a21869f7'
OLD_HELPER = 'af687b20afd470992f9c02c80173356e20e9d9e98fabddcf3800979f11a4b28c'
JNA = '34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6'
ARTIFACTS = {
    'mesen-windows': ('windows-x64', 'mesen_libretro.dll'),
    'mesen-linux': ('linux-x64', 'mesen_libretro.so'),
    'mesen-s-windows': ('windows-x64', 'mesen-s_libretro.dll'),
    'genesis-plus-gx-windows': ('windows-x64', 'genesis_plus_gx_libretro.dll'),
    'mgba-windows': ('windows-x64', 'mgba_libretro.dll'),
}
UNCOMMITTED_BUILD_FILES = (
    '.github/workflows/snapshot.yml', 'source-control/snapshot-resources.gradle',
    'source-control/snapshot_build.py', 'source-control/snapshot_inputs.py',
    'source-control/snapshot_profiles.py', 'source-control/snapshot_release.py',
    'source-control/test_snapshot_build.py', 'source-control/test_snapshot_inputs.py',
    'source-control/test_snapshot_profiles.py', 'source-control/test_snapshot_release.py',
)
FC = 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/'
GBA = 'piq-gba/src/main/java/cn/piq/gba/bridge/'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_name(name: str) -> str:
    if (not isinstance(name, str) or not name or '\\' in name or ':' in name
            or PurePosixPath(name).is_absolute()
            or any(p in ('', '.', '..') for p in name.split('/'))):
        raise ValueError('Unsafe relative path')
    return name


def no_links(path: Path) -> None:
    for p in (path, *path.parents):
        if p.is_symlink() or (hasattr(p, 'is_junction') and p.is_junction()):
            raise ValueError('Linked path is not accepted')


def record(path: Path, root: Path) -> dict:
    data = path.read_bytes()
    return dict(path=path.relative_to(root).as_posix(), bytes=len(data), sha256=sha(data))


def checked(root: Path, entry: dict) -> Path:
    path = root / safe_name(entry['path'])
    no_links(path.absolute())
    if (not re.fullmatch('[0-9a-f]{64}', entry.get('sha256', ''))
            or type(entry.get('bytes')) is not int or entry['bytes'] < 1
            or not path.is_file() or path.stat().st_size != entry['bytes']
            or sha(path.read_bytes()) != entry['sha256']):
        raise ValueError('Input identity mismatch: ' + entry['path'])
    return path


def nightly_records(receipt: dict, inputs: Path) -> dict:
    if receipt.get('schema') != 1:
        raise ValueError('Unsupported receipt')
    files = {}
    for entry in receipt.get('files', []):
        name = safe_name(entry['path'])
        if name in files:
            raise ValueError('Duplicate receipted input')
        files[name] = entry
    found = {}
    for entry in receipt.get('nightly', []):
        ident = entry.get('id')
        if ident not in ARTIFACTS or ident in found:
            raise ValueError('Unexpected or duplicate nightly core')
        platform, filename = ARTIFACTS[ident]
        os_name = 'windows' if platform == 'windows-x64' else 'linux'
        expected_url = f'https://buildbot.libretro.com/nightly/{os_name}/x86_64/latest/{filename}.zip'
        if (entry.get('platform') != platform or entry.get('path') != f'nightly/{platform}/{filename}'
                or entry.get('url') != expected_url
                or not re.fullmatch('[0-9a-f]{64}', entry.get('archiveSha256', ''))):
            raise ValueError('Nightly origin/platform mismatch')
        if (entry['path'] not in files
                or any(files[entry['path']].get(k) != entry.get(k) for k in ('bytes', 'sha256'))
                or type(entry.get('bytes')) is not int or not 1 <= entry['bytes'] <= 32 * 1024 * 1024):
            raise ValueError('Nightly core is not bound to input files')
        checked(inputs, entry)
        archive_name = f'downloads/{platform}/{filename}.zip'
        archived = files.get(archive_name, {})
        if (archived.get('sha256') != entry['archiveSha256']
                or type(archived.get('bytes')) is not int or not 1 <= archived['bytes'] <= 32 * 1024 * 1024):
            raise ValueError('Nightly archive is not bound to input files')
        archive = checked(inputs, archived)
        with zipfile.ZipFile(archive) as z:
            members = z.infolist()
            if len(members) != 1:
                raise ValueError('Nightly archive must contain exactly one core')
            member = members[0]
            if (member.filename != filename or member.orig_filename != filename
                    or member.file_size != entry['bytes'] or member.flag_bits & 1
                    or stat.S_ISLNK(member.external_attr >> 16)
                    or sha(z.read(member)) != entry['sha256']):
                raise ValueError('Nightly archive member identity mismatch')
        found[ident] = entry
    if set(found) != set(ARTIFACTS):
        raise ValueError('Exactly five ordinary nightly cores are required')
    return found


def replace_exact(path: Path, old: str, new: str, count: int = 1) -> None:
    """A changed upstream template must fail rather than receive a broad rewrite."""
    data = path.read_bytes()
    before, after = old.encode(), new.encode()
    actual = data.count(before)
    if actual != count:
        raise ValueError(f'Template changed: {path.name}: expected {count}, found {actual}')
    path.write_bytes(data.replace(before, after))


def patch_profiles(root: Path, cores: dict, version: str, worker: dict, helper: dict) -> dict:
    h = {ident: e['sha256'] for ident, e in cores.items()}
    profile = root / 'piq-fc-arcade/src/main/resources/core/libretro/mesen-profile.properties'
    if sha(profile.read_bytes()) != OLD_PROFILE:
        raise ValueError('FC profile template changed')
    replace_exact(profile, 'profile=fc-mesen-libretro-v1', 'profile=fc-mesen-libretro-snapshot-v1')
    replace_exact(profile, OLD_WORKER, worker['sha256'])
    for ident in ('mesen-windows', 'mesen-linux'):
        replace_exact(profile, OLD[ident], h[ident])
    profile_sha = sha(profile.read_bytes())
    generic = root / (FC + 'core/libretro/GenericLibretroNesCore.java')
    replace_exact(generic, OLD_PROFILE, profile_sha)
    for ident in ('mesen-windows', 'mesen-linux'):
        replace_exact(generic, OLD[ident], h[ident])
    replace_exact(root / (FC + 'netplay/NetplayProfile.java'), OLD['mesen-windows'], h['mesen-windows'])
    for file, old, new in (
        ('piq-fc-arcade/src/test/java/cn/piq/fcarcade/core/libretro/GenericLibretroCompatibilitySmoke.java', OLD_PROFILE, profile_sha),
        ('piq-fc-arcade/src/test/java/cn/piq/fcarcade/netplay/NetplayProfileTest.java', OLD['mesen-windows'], h['mesen-windows']),
    ):
        replace_exact(root / file, old, new)
    smoke = root / 'piq-fc-arcade/src/test/java/cn/piq/fcarcade/core/libretro/GenericLibretroCompatibilitySmoke.java'
    replace_exact(smoke, 'PASS generic-libretro FC63 compatibility checks=', 'PASS generic-libretro snapshot same-build checks=')
    replace_exact(smoke, 'FC63 profile identity retained', 'generated snapshot profile identity pinned')
    replace_exact(smoke, 'private-play module resource stays FC63 profile', 'private-play module uses snapshot profile')

    sfc_build = 'mesen-s-snapshot-' + h['mesen-s-windows']
    sfc = root / 'piq-sfc-home/src/main/java/cn/piq/sfchome/core/LibretroSfcCore.java'
    replace_exact(sfc, 'mesen-s-piq1-8aca17e7', sfc_build)
    replace_exact(sfc, OLD['mesen-s-windows'], h['mesen-s-windows'])
    replace_exact(root / 'piq-sfc-home/src/main/java/cn/piq/sfchome/core/SfcNetplayProfile.java', OLD['mesen-s-windows'], h['mesen-s-windows'])
    for file in ('piq-md-home/build.gradle', 'piq-md-home/src/main/java/cn/piq/mdhome/client/MdProfile.java',
                 'piq-md-home/src/test/java/cn/piq/mdhome/MdNetplayContractTest.java',
                 'piq-md-home/src/test/java/cn/piq/mdhome/client/MdPureTest.java'):
        replace_exact(root / file, OLD['genesis-plus-gx-windows'], h['genesis-plus-gx-windows'])
    replace_exact(root / 'piq-md-home/src/main/java/cn/piq/mdhome/client/MdProfile.java',
                  'c2838c7dc4236fc2fe94e5dbd08b41486067918e', 'unresolved-nightly')

    catalog = root / (FC + 'runtime/RuntimeCatalog.java')
    replace_exact(catalog, f'file("piq-gba/runtime", "mgba_libretro.dll", 2955998, "{OLD["mgba-windows"].upper()}")',
                  f'file("piq-gba/runtime", "mgba_libretro.dll", {cores["mgba-windows"]["bytes"]}, "{h["mgba-windows"].upper()}")')
    replace_exact(catalog, f'file("piq-gba/runtime", "piq-gba-helper.jar", 20182, "{OLD_HELPER.upper()}")',
                  f'file("piq-gba/runtime", "piq-gba-helper.jar", {helper["bytes"]}, "{helper["sha256"].upper()}")')
    # The historical offline archive is a separate fixed input, never rebrand it
    # as containing the new GBA bytes just because STANDARD changed.
    replace_exact(catalog, ':artifact).toList();',
                  ':artifact.relativePath().equals("piq-gba/runtime/mgba_libretro.dll")'
                  f'?file("piq-gba/runtime","mgba_libretro.dll",2955998,"{OLD["mgba-windows"].upper()}")'
                  ':artifact.relativePath().equals("piq-gba/runtime/piq-gba-helper.jar")'
                  f'?file("piq-gba/runtime","piq-gba-helper.jar",20182,"{OLD_HELPER.upper()}")'
                  ':artifact).toList();')
    runtime_test = root / 'piq-fc-arcade/src/test/java/cn/piq/fcarcade/runtime/RuntimeInstallerTest.java'
    total = 437599687 - 2955998 - 20182 + cores['mgba-windows']['bytes'] + helper['bytes']
    replace_exact(runtime_test, 'assertEquals(437599687L, all.stream()', f'assertEquals({total}L, all.stream()')
    replace_exact(runtime_test,
                  'for(var component:RuntimeCatalog.standard())if(component.id()!=MAME)for(var artifact:component.files())assertTrue(legacy.contains(artifact));',
                  'for(var component:RuntimeCatalog.standard())if(component.id()!=MAME&&component.id()!=GBA)for(var artifact:component.files())assertTrue(legacy.contains(artifact));\n'
                  f'        assertTrue(legacy.contains(new RuntimeCatalog.Artifact("piq-gba/runtime/mgba_libretro.dll",2955998,"{OLD["mgba-windows"].upper()}")));\n'
                  f'        assertTrue(legacy.contains(new RuntimeCatalog.Artifact("piq-gba/runtime/piq-gba-helper.jar",20182,"{OLD_HELPER.upper()}")));\n'
                  f'        assertTrue(RuntimeCatalog.standard().stream().flatMap(c->c.files().stream()).anyMatch(a->a.relativePath().equals("piq-gba/runtime/mgba_libretro.dll")&&a.bytes()=={cores["mgba-windows"]["bytes"]}&&a.sha256().equals("{h["mgba-windows"].upper()}")));\n'
                  f'        assertTrue(RuntimeCatalog.standard().stream().flatMap(c->c.files().stream()).anyMatch(a->a.relativePath().equals("piq-gba/runtime/piq-gba-helper.jar")&&a.bytes()=={helper["bytes"]}&&a.sha256().equals("{helper["sha256"].upper()}")));')
    replace_exact(root / 'piq-gba/tools/build_current.py', OLD['mgba-windows'], h['mgba-windows'])
    replace_exact(root / 'piq-gba/tools/qa/GbaJniProbe.java', 'jni-trial-v1-mgba-e31759b', 'jni-trial-v1-mgba-snapshot-' + h['mgba-windows'])
    return dict(fcProfileSha256=profile_sha, sfcBuild=sfc_build,
                gbaProcessNamespace='mgba-snapshot-' + h['mgba-windows'],
                gbaJniNamespace='jni-trial-v1-mgba-snapshot-' + h['mgba-windows'],
                mdCoreSha256=h['genesis-plus-gx-windows'])


def patch_gba(root: Path, core: dict, version: str) -> None:
    if not re.fullmatch(r'[A-Za-z0-9 .+_-]{1,96}', version):
        raise ValueError('Unsupported native version string')
    for file in (GBA + 'GbaProcessSession.java', 'piq-gba/helper/src/main/java/cn/piq/gba/bridge/GbaCore.java'):
        replace_exact(root / file, OLD['mgba-windows'].upper(), core['sha256'].upper())
    for file in (GBA + 'GbaJniSession.java', 'piq-gba/helper/src/main/java/cn/piq/gba/bridge/GbaCore.java'):
        replace_exact(root / file, '0.11-219-e31759b', version)
    replace_exact(root / (GBA + 'GbaProcessSession.java'), '"mgba-e31759b"', '"mgba-snapshot-' + core['sha256'] + '"')
    replace_exact(root / (GBA + 'GbaJniSession.java'), '"jni-trial-v1-mgba-e31759b"', '"jni-trial-v1-mgba-snapshot-' + core['sha256'] + '"')


PROBE = r'''import com.sun.jna.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public final class SnapshotCoreInfo {
 public interface Core extends Library {int retro_api_version();void retro_get_system_info(Info out);}
 @Structure.FieldOrder({"name","version","extensions","fullpath","blockExtract"})
 public static class Info extends Structure {public Pointer name,version,extensions;public byte fullpath,blockExtract;}
 static String bounded(Pointer p) {if(p==null)throw new IllegalStateException("Null info");
  byte[] b=new byte[128];int n=0;for(;n<b.length;n++){b[n]=p.getByte(n);if(b[n]==0)break;}
  if(n==b.length)throw new IllegalStateException("Unbounded info");
  return Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(b,n));}
 public static void main(String[] a){Core c=Native.load(a[0],Core.class);int api=c.retro_api_version();
  if(api!=1)throw new IllegalStateException("Unsupported libretro API");Info i=new Info();c.retro_get_system_info(i);i.read();
  System.out.println("SNAPSHOT_CORE_INFO:"+api+":"+bounded(i.name)+":"+bounded(i.version));}
}'''


def environment(jdk: Path) -> dict:
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    env['JAVA_HOME'] = str(jdk.parent)
    env['PATH'] = str(jdk) + os.pathsep + env.get('PATH', '')
    return env


def command(args: list[str], cwd: Path, jdk: Path, timeout: int = 300) -> str:
    completed = subprocess.run(args, cwd=cwd, env=environment(jdk), text=True,
                               encoding='utf-8', errors='replace', capture_output=True, timeout=timeout)
    if completed.returncode:
        # Build log stays private and is never embedded in a public JAR/receipt.
        (cwd / 'snapshot-command-failure.log').write_text(completed.stdout + completed.stderr, encoding='utf-8')
        raise RuntimeError('Snapshot subprocess failed; inspect local snapshot-command-failure.log')
    return completed.stdout


def probe_gba(core: Path, jna: Path, temp: Path, jdk: Path) -> dict:
    temp.mkdir(parents=True, exist_ok=False)
    java = temp / 'SnapshotCoreInfo.java'
    java.write_text(PROBE, encoding='utf-8')
    command([str(jdk / 'javac.exe'), '--release', '21', '-encoding', 'UTF-8', '-proc:none', '-cp', str(jna), str(java)], temp, jdk)
    output = command([str(jdk / 'java.exe'), '-Xmx64m', '-cp', str(temp) + os.pathsep + str(jna),
                      'SnapshotCoreInfo', str(core)], temp, jdk, 20)
    lines = [line for line in output.splitlines() if line.startswith('SNAPSHOT_CORE_INFO:')]
    if len(lines) != 1:
        raise ValueError('Native information probe returned no unique result')
    _, api, name, version = lines[0].split(':')
    name = base64.b64decode(name, validate=True).decode('utf-8')
    version = base64.b64decode(version, validate=True).decode('utf-8')
    if api != '1' or name != 'mGBA' or not re.fullmatch(r'[A-Za-z0-9 .+_-]{1,96}', version):
        raise ValueError('Native mGBA API/name/version mismatch')
    return dict(api=1, name=name, version=version, processIsolated=True, romStarted=False)


def deterministic_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()):
            safe_name(name)
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


def build_helper(root: Path, jna: Path, jdk: Path) -> Path:
    work = root / 'snapshot-gba'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=False)
    sources = sorted((root / 'piq-gba/helper/src/main/java').rglob('*.java'))
    sources += [root / (GBA + 'GbaProtocol.java')]
    command([str(jdk / 'javac.exe'), '--release', '21', '-encoding', 'UTF-8', '-proc:none',
             '-cp', str(jna), '-d', str(classes), *map(str, sources)], work, jdk)
    helper = work / 'piq-gba-helper.jar'
    entries = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
    if not entries or 'cn/piq/gba/bridge/GbaWorker.class' not in entries:
        raise ValueError('Incomplete GBA helper compilation')
    deterministic_zip(helper, entries)
    return helper


def runtime_base(bootstrap: Path, output: Path, core: Path, helper: Path) -> None:
    prefix = 'native-runtime/win-x64-v1/piq-gba/runtime/'
    with zipfile.ZipFile(bootstrap) as z:
        if len(z.namelist()) != len(set(z.namelist())) or z.testzip() is not None:
            raise ValueError('Invalid GBA bootstrap archive')
        entries = {n: z.read(n) for n in z.namelist() if not n.endswith('/')}
    if sha(entries[prefix + 'mgba_libretro.dll']) != OLD['mgba-windows']:
        raise ValueError('Unexpected bootstrap mGBA identity')
    if sha(entries[prefix + 'piq-gba-helper.jar']) != OLD_HELPER:
        raise ValueError('Unexpected bootstrap GBA helper identity')
    entries[prefix + 'mgba_libretro.dll'] = core.read_bytes()
    entries[prefix + 'piq-gba-helper.jar'] = helper.read_bytes()
    # The runtime-base is an intermediate only, never a release candidate.
    entries['piq-gba-runtime.properties'] = ('helper.sha256=' + sha(helper.read_bytes()).upper() + '\n').encode()
    deterministic_zip(output, entries)


def copy_checkout(root: Path, output: Path) -> None:
    proc = subprocess.run(['git', '-C', str(root), 'ls-files', '-z'], check=True, capture_output=True)
    names = set(proc.stdout.decode('utf-8').split('\0')) - {''}
    # Allows validation before these narrowly owned build files are committed.
    for name in UNCOMMITTED_BUILD_FILES:
        if (root / name).is_file():
            names.add(name)
    for name in sorted(names):
        source = root / safe_name(name)
        no_links(source)
        if source.is_dir():  # Pinned submodules are not inputs for these native bootstrap builds.
            continue
        if not source.is_file():
            raise ValueError('Tracked source is missing: ' + name)
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def prepare(root: Path, inputs: Path, output: Path, jdk: Path) -> dict:
    root, inputs, output, jdk = [p.absolute() for p in (root, inputs, output, jdk)]
    for p in (root, inputs, output, jdk):
        no_links(p)
    if output.exists() or output == root or root.is_relative_to(output) or inputs.is_relative_to(output):
        raise ValueError('Output must be a new separate staging directory')
    receipt = json.loads((inputs / 'receipt.json').read_text(encoding='utf-8'))
    cores = nightly_records(receipt, inputs)
    # The build runner verifies the six fixed bootstrap release hashes before here.
    # Recheck them here too; this entry point must not depend on its caller's trust.
    from snapshot_build import verify_inputs
    verify_inputs(inputs)
    output.mkdir(parents=True, exist_ok=False)
    copy_checkout(root, output)
    dest = output / 'snapshot-inputs'
    dest.mkdir()
    all_records = {}
    for entry in [*receipt['files'], *receipt['bootstrap'], *receipt['nightly']]:
        name = safe_name(entry['path'])
        if name in all_records and (all_records[name]['sha256'], all_records[name]['bytes']) != (entry['sha256'], entry['bytes']):
            raise ValueError('Conflicting receipt record')
        all_records[name] = entry
    for name, entry in sorted(all_records.items()):
        source = checked(inputs, entry)
        target = dest / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    lockfile = output / 'source-control/build-inputs.json'
    lock = json.loads(lockfile.read_text(encoding='utf-8'))
    for ident in ('mesen-windows', 'mesen-linux'):
        entry = lock['inputs'][ident]
        if entry['sha256'] != OLD[ident]:
            raise ValueError('FC lock template changed')
        entry.update(sha256=cores[ident]['sha256'], bytes=cores[ident]['bytes'])
        target = dest / 'fc-cache' / entry['sha256'] / entry['filename']
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copy2(dest / cores[ident]['path'], target)
    write_json(lockfile, lock)
    for ident, name in (
        ('mesen-s-windows', 'resources/sfc-home/core/sfc-libretro/windows-x64/mesen-s_libretro.dll'),
        ('genesis-plus-gx-windows', 'resources/md/core/windows-x64/genesis_plus_gx_libretro.dll'),
    ):
        target = dest / name
        if sha(target.read_bytes()) != OLD[ident]:
            raise ValueError('Bootstrap resource template changed')
        shutil.copy2(dest / cores[ident]['path'], target)
    jna = dest / 'fc-cache' / JNA / 'jna-5.14.0.jar'
    core = dest / cores['mgba-windows']['path']
    native = probe_gba(core, jna, output / 'snapshot-native-info', jdk)
    patch_gba(output, cores['mgba-windows'], native['version'])
    helper_path = build_helper(output, jna, jdk)
    helper = record(helper_path, output)
    bootstrap = next(e for e in receipt['bootstrap'] if e['id'] == 'gba')
    runtime = output / 'snapshot-gba/runtime-base.jar'
    runtime_base(dest / bootstrap['path'], runtime, core, helper_path)

    fc = output / 'piq-fc-arcade'
    command([str(fc / 'gradlew.bat'), '--no-daemon', '--console=plain',
             '-PpiqInputsDir=' + str(dest / 'fc-cache'), 'libretroWorkerJar'], fc, jdk, 900)
    worker_path = fc / 'build/libretro-worker/worker.jar'
    worker = record(worker_path, output)
    identities = patch_profiles(output, cores, native['version'], worker, helper)

    # These notices apply to the copied nightly artifact, not the original pinned release.
    sfc_notice = output / 'piq-sfc-home/src/main/resources/core/sfc-libretro/NOTICE.txt'
    replace_exact(sfc_notice, OLD['mesen-s-windows'], cores['mesen-s-windows']['sha256'])
    md_notice = output / 'piq-md-home/src/main/resources/licenses/genesis-plus-gx/NOTICE.txt'
    old_notice = md_notice.read_text(encoding='utf-8')
    copyright_start = old_notice.index('Copyright (c)')
    copyright_end = old_notice.index('\n\nUnmodified binary')
    md_notice.write_text('Genesis Plus GX, official Libretro nightly snapshot.\n' + old_notice[copyright_start:copyright_end]
                        + '\n\nBinary source: ' + cores['genesis-plus-gx-windows']['url']
                        + '\nDLL SHA256: ' + cores['genesis-plus-gx-windows']['sha256']
                        + '\nArchive SHA256: ' + cores['genesis-plus-gx-windows']['archiveSha256']
                        + '\nExact source revision: unresolved; no old revision is claimed for this nightly.\n'
                        + 'See META-INF/game-console/snapshot-cores.json for this build identity.\n'
                        + 'No ROM, BIOS or user save is included.\n', encoding='utf-8')
    manifest = dict(schema=1, channel='nightly-snapshot', inputRoot='snapshot-inputs',
                    runtimeBase='snapshot-gba/runtime-base.jar', nightly=list(cores.values()),
                    fcWorker=worker, gbaHelper=helper, gbaNativeInfo=native, identities=identities,
                    customCorePolicy='FC r2, MD Netplay, Arcade and legacy WASM identities unchanged',
                    validation='Build/test snapshot only; Minecraft and SFC nightly Netplay acceptance pending')
    # The runner stamps this portable manifest only after all component builds.
    # Keeping one stamping owner avoids duplicate resources in the SFC merger.
    receipt['files'] = [record(p, dest) for p in sorted(dest.rglob('*')) if p.is_file()]
    receipt['snapshotProfiles'] = identities
    write_json(dest / 'receipt.json', receipt)
    write_json(output / 'snapshot-profile-receipt.json', manifest)
    result = dict(schema=1, sourceRoot=str(output), inputsRoot=str(dest), gbaRuntimeBase=str(runtime),
                  manifest=str(output / 'snapshot-profile-receipt.json'))
    write_json(output / 'prepare-result.json', result)
    # Input bytes may not change during a native query or compiler invocation.
    for entry in receipt['nightly']:
        checked(dest, entry)
    return result


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('root', 'inputs', 'output', 'jdk'):
        p.add_argument('--' + key, type=Path, required=True)
    a = p.parse_args()
    print(json.dumps(prepare(a.root, a.inputs, a.output, a.jdk)))


if __name__ == '__main__':
    main()
