"""Fetch bounded, identified native inputs for the seven-mod snapshot build.

Only ordinary supported libretro cores come from the two official nightly
directories. Project-specific Netplay/bridge libraries and legacy runtimes are
extracted from a SHA-pinned public release, never from a developer's machine.
No downloaded native library is executed by this preparation command.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import stat
import struct
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

from build_inputs import cache_path, load_lock, no_links, relative, verify

ROOT = Path(__file__).resolve().parents[1]
RELEASE = 'https://github.com/meilian258-dotcom/game-console/releases/download/full-test-20261007-r1/'
BOOTSTRAP = {
    'fc': ('game-console-0.31.0-alpha.76.44.jar', 39308439, '7d4db04cc8bafb3f7dc426e3e8412459ba1308dac9d33c434f99c1fcf5eac716'),
    'sfc': ('game-console-sfc-0.1.0-alpha.51.jar', 3087373, 'a9c8bdd2ae068383310ac57efcfb18df29eca67238218b12bc6da38b47cf1816'),
    'md': ('game-console-md-0.1.0-alpha.15.jar', 2371782, '9ea8727a77e2cc10fc59a2e34bb1b16466be2fcb1d23e941216b79921fc4b6df'),
    'gba': ('game-console-gba-0.1.0-alpha.15.jar', 2670862, '7e991d3151c140c01a4acc094d68f9ccaf2e285b6a0fadf6abb15cb345d40f0a'),
    'arcade': ('game-console-arcade-0.1.5.7.jar', 141792262, '575f16d2123f1e91cadba12d8ace9dead63936770fcdec4476c260c13ce5d925'),
    'pvz': ('game-console-pvz-0.1.0-prototype.12.jar', 5004879, 'e010b4185c8a9e51edcba3ef40718a81781068320681892cf869d68c3046f04a'),
}
# This repair branch is not merge/release-ready. FC76.44 has no dependency
# bundle. Review a published replacement, both snapshot bootstrap pins, and this
# inventory only after full MAME validation and explicit publication approval.
JNI_BUNDLE_INPUTS = ('libretro-jni-abi2', 'libcxx-windows')
PUBLISHED_JNI_BUNDLES = {
    '7d4db04cc8bafb3f7dc426e3e8412459ba1308dac9d33c434f99c1fcf5eac716': {
        'libretro-jni-abi2': (1375744, 'aec72d00384d89de94c214e12fe32a19c1c54c93ea80c9bc82727895be509a36'),
    },
}
NIGHTLY_BASE = {
    'windows-x64': 'https://buildbot.libretro.com/nightly/windows/x86_64/latest/',
    'linux-x64': 'https://buildbot.libretro.com/nightly/linux/x86_64/latest/',
}
NIGHTLY = {
    'mesen-windows': ('windows-x64', 'mesen_libretro.dll'),
    'mesen-linux': ('linux-x64', 'mesen_libretro.so'),
    'mesen-s-windows': ('windows-x64', 'mesen-s_libretro.dll'),
    'genesis-plus-gx-windows': ('windows-x64', 'genesis_plus_gx_libretro.dll'),
    'mgba-windows': ('windows-x64', 'mgba_libretro.dll'),
}
FC_RESOURCES = {
    'jna-5-14-0': 'core/libretro/jna-5.14.0.jar',
    'mesen-windows': 'core/libretro/windows-x64/mesen_libretro.dll',
    'mesen-linux': 'core/libretro/linux-x64/mesen_libretro.so',
    'libretro-jni-abi2': 'core/libretro-jni/windows-x64/piq-libretro-jni.dll',
    'libcxx-windows': 'core/libretro-jni/windows-x64/libc++.dll',
    'retroarch-netplay': 'core/netplay/piq-retroarch.exe',
    'mesen-jni-netplay-r2': 'core/libretro-jni-netplay/windows-x64/mesen_piq_jni_netplay_r2.dll',
    'nes-zapper-wasm': 'core/nes_zapper_v1.wasm',
    'nes-wasm': 'core/nes_rust_wasm_bg.wasm',
    'nes-mapper19-wasm': 'core/nes_mapper19_v1.wasm',
}
BUCKETS = {
    'sfc-core': ('sfc', 'piq-sfc-arcade', ('assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm', 'META-INF/piq-sfc-wasm-privacy.json')),
    'sfc-home': ('sfc', 'piq-sfc-home', ('core/sfc-libretro/',)),
    'md': ('md', 'piq-md-home', ('core/windows-x64/',)),
    'pvz': ('pvz', 'piq-pvz-addon', ('core/pvz/', 'META-INF/pvz/licenses/')),
    'arcade': ('arcade', 'piq-native-arcade', ('native-runtime/', 'META-INF/piq-native/fbneo/', 'META-INF/licenses/native-runtime17/')),
}
CURRENT_HELPER = 'native-runtime/win-x64-v1/piq-native-arcade/runtime/piq-native-helper-v4.jar'
MAX_CORE = 32 * 1024 * 1024
MAX_MEMBER = 512 * 1024 * 1024
MAX_EXPANDED = 1024 * 1024 * 1024
AGENT = 'game-console-snapshot-builder/1'


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def record(path: Path, root: Path) -> dict:
    return dict(path=path.relative_to(root).as_posix(), bytes=path.stat().st_size, sha256=digest(path))


def checked_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    hosts = {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com', 'buildbot.libretro.com'}
    if (parsed.scheme != 'https' or parsed.hostname not in hosts or parsed.username
            or parsed.password or parsed.port not in (None, 443) or parsed.fragment):
        raise ValueError('Download redirect outside trusted HTTPS hosts')
    return url


class TrustedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return super().redirect_request(req, fp, code, msg, headers, checked_url(newurl))


def fetch(url: str, destination: Path, limit: int, expected: dict | None = None) -> dict:
    """Bounded stream, exclusive destination; pinned files can be reused after verification."""
    no_links(destination)
    if destination.exists():
        if expected is None:
            raise ValueError('Nightly download destination already exists; use a fresh work directory')
        verify(destination, expected)
        return dict(url=url, reused=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(TrustedRedirect())
    request = urllib.request.Request(checked_url(url), headers={'User-Agent': AGENT, 'Accept': 'application/octet-stream'})
    temporary = None
    try:
        with opener.open(request, timeout=60) as response:
            checked_url(response.geturl())
            declared = response.headers.get('Content-Length')
            if declared is not None and (not declared.isdigit() or int(declared) > limit):
                raise ValueError('Download exceeds the declared size budget')
            with tempfile.NamedTemporaryFile(prefix='.download-', dir=destination.parent, delete=False) as out:
                temporary = Path(out.name)
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > limit:
                        raise ValueError('Download exceeds the size budget')
                    out.write(chunk)
            if size == 0 or (declared is not None and size != int(declared)):
                raise ValueError('Empty or truncated download')
            metadata = dict(url=url, lastModified=response.headers.get('Last-Modified'), reused=False)
        if expected is not None:
            verify(temporary, expected)
        # Never truncate an existing destination, including a concurrent download.
        with temporary.open('rb') as src, destination.open('xb') as dst:
            shutil.copyfileobj(src, dst)
        return metadata
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def members(archive: zipfile.ZipFile, *, maximum: int = MAX_EXPANDED) -> dict[str, zipfile.ZipInfo]:
    infos = archive.infolist()
    if len(infos) > 20000:
        raise ValueError('Archive has too many members')
    result = {}
    seen = set()
    total = 0
    for info in infos:
        original = info.orig_filename.rstrip('/') if info.is_dir() else info.orig_filename
        if '\0' in original:
            raise ValueError('NUL in archive member')
        relative(original)
        name = info.filename.rstrip('/') if info.is_dir() else info.filename
        if name != original:
            raise ValueError('Archive member name was normalized')
        relative(name)
        if name.casefold() in seen:
            raise ValueError('Duplicate/case-aliased archive member')
        seen.add(name.casefold())
        if info.flag_bits & 1 or stat.S_ISLNK(info.external_attr >> 16):
            raise ValueError('Encrypted or linked archive member')
        if info.is_dir():
            continue
        if not 0 <= info.file_size <= MAX_MEMBER:
            raise ValueError('Archive member exceeds size budget')
        total += info.file_size
        if total > maximum:
            raise ValueError('Expanded archive exceeds size budget')
        result[name] = info
    return result


def put(destination: Path, data: bytes) -> None:
    no_links(destination)
    if destination.exists():
        if destination.read_bytes() != data:
            raise ValueError('Refusing to overwrite different prepared input: ' + destination.name)
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open('xb') as out:
        out.write(data)


def check_native(data: bytes, platform: str) -> None:
    if platform == 'windows-x64':
        if len(data) < 64 or data[:2] != b'MZ':
            raise ValueError('Expected a PE library')
        offset = struct.unpack_from('<I', data, 60)[0]
        if offset + 26 > len(data) or data[offset:offset+4] != b'PE\0\0':
            raise ValueError('Invalid PE header')
        machine, = struct.unpack_from('<H', data, offset+4)
        characteristics, = struct.unpack_from('<H', data, offset+22)
        if machine != 0x8664 or not characteristics & 0x2000:
            raise ValueError('Expected an AMD64 DLL')
    elif platform == 'linux-x64':
        if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 62:
            raise ValueError('Expected a little-endian x86-64 ELF library')
    else:
        raise ValueError('Unknown native platform')


def unpack_core(archive_path: Path, destination: Path, member: str, platform: str) -> None:
    with zipfile.ZipFile(archive_path) as archive:
        index = members(archive, maximum=MAX_CORE)
        if set(index) != {member}:
            raise ValueError('Nightly archive must contain exactly the requested core')
        data = archive.read(index[member])  # verifies CRC, no extractall/path traversal
    check_native(data, platform)
    put(destination, data)


def require_published_jni_bundle(lock: dict) -> None:
    published = PUBLISHED_JNI_BUNDLES.get(BOOTSTRAP['fc'][2], {})
    requested = {ident: (lock[ident]['bytes'], lock[ident]['sha256'])
                 for ident in JNI_BUNDLE_INPUTS if ident in lock}
    if set(requested) != set(JNI_BUNDLE_INPUTS) or requested != published:
        raise ValueError('JNI dependency bundle requires a matching published bootstrap')


def extract_bootstrap(pins: dict[str, Path], output: Path, source: Path) -> None:
    lock = load_lock(source / 'source-control/build-inputs.json')
    require_published_jni_bundle(lock)
    if set(lock) != set(FC_RESOURCES):
        raise ValueError('FC build-input inventory changed; review snapshot resource mapping')
    with zipfile.ZipFile(pins['fc']) as jar:
        index = members(jar)
        for ident, resource in FC_RESOURCES.items():
            data = jar.read(index[resource])
            entry = lock[ident]
            if len(data) != entry['bytes'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Pinned bootstrap does not match FC build lock: ' + ident)
            put(cache_path(output / 'fc-cache', entry), data)
    for bucket, (ident, module, prefixes) in BUCKETS.items():
        count = 0
        with zipfile.ZipFile(pins[ident]) as jar:
            index = members(jar)
            for name in index:
                if not any(name == prefix or (prefix.endswith('/') and name.startswith(prefix)) for prefix in prefixes):
                    continue
                if name == CURRENT_HELPER:
                    continue  # the current native worker is compiled from source
                if name.endswith('.class') or 'blastem' in name.lower():
                    raise ValueError('Foreign class or retired core in native resources')
                data = jar.read(index[name])
                tracked = source / module / 'src/main/resources' / name
                if tracked.is_file():
                    no_links(tracked)
                    if tracked.read_bytes() != data:
                        raise ValueError('Bootstrap disagrees with tracked resource: ' + bucket + '/' + name)
                    continue
                put(output / 'resources' / bucket / name, data)
                count += 1
        if count == 0:
            raise ValueError('No binary resources selected for ' + bucket)


def prepare(output: Path, *, source: Path = ROOT, bootstrap_dir: Path | None = None) -> dict:
    output = no_links(output.absolute())
    # Nightly is resolved exactly once per run. Resume by invoking the build with
    # its verified receipt, not by accidentally mixing two downloads of latest.
    if (output / 'receipt.json').exists() or (output / 'nightly').exists():
        raise ValueError('Input directory already resolved; build it or choose a new directory')
    # Fail before network or even offline bootstrap copying; never silently
    # satisfy the new API from an old bridge or an unrelated nightly runtime.
    require_published_jni_bundle(load_lock(source / 'source-control/build-inputs.json'))
    output.mkdir(parents=True, exist_ok=True)
    pins, boot_records, sources = {}, [], []
    for ident, (filename, size, sha) in BOOTSTRAP.items():
        destination = output / 'bootstrap' / filename
        expected = dict(bytes=size, sha256=sha)
        if bootstrap_dir is not None:
            origin = no_links(bootstrap_dir.absolute() / filename)
            verify(origin, expected)
            put(destination, origin.read_bytes())
            sources.append(dict(id=ident, url=RELEASE+filename, reused=True))
        else:
            sources.append(dict(id=ident, **fetch(RELEASE+filename, destination, size, expected)))
        pins[ident] = destination
        boot_records.append(dict(id=ident, **record(destination, output)))
    extract_bootstrap(pins, output, source)
    nightly = []
    for ident, (platform, member) in NIGHTLY.items():
        url = NIGHTLY_BASE[platform] + member + '.zip'
        archive = output / 'downloads' / platform / (member + '.zip')
        origin = fetch(url, archive, MAX_CORE)
        destination = output / 'nightly' / platform / member
        unpack_core(archive, destination, member, platform)
        entry = dict(id=ident, platform=platform, **record(destination, output),
                     url=url, archiveSha256=digest(archive), lastModified=origin.get('lastModified'))
        nightly.append(entry)
        sources.append(dict(id=ident, **origin))
    files = []
    for path in sorted(output.rglob('*')):
        no_links(path)
        if path.is_file():
            files.append(record(path, output))
    receipt = dict(schema=1, resolvedAt=datetime.now(timezone.utc).isoformat(),
                   bootstrap=boot_records, files=files, nightly=nightly, sources=sources)
    put(output / 'receipt.json', (json.dumps(receipt, indent=2, ensure_ascii=True)+'\n').encode())
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('.snapshot-build/inputs'))
    parser.add_argument('--bootstrap-dir', type=Path, help='Optional offline copies of the six exact public release JARs')
    args = parser.parse_args()
    try:
        receipt = prepare(args.output, bootstrap_dir=args.bootstrap_dir)
        print(json.dumps(dict(ok=True, bootstrap=len(receipt['bootstrap']), nightly=len(receipt['nightly']),
                              files=len(receipt['files'])), sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print('Snapshot input preparation failed: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
