"""Read-only final-artifact audit. Does NOT load the DLL, launch Minecraft, or rebuild the helper.

The only executed Java is a pure protocol/UV consumer compiled against the supplied final JAR.
Native process/audio evidence belongs to check_native_bridge.py's separate real-child report.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
PREFIX = 'cn/piq/nativearcade/'
PROTOCOL = PREFIX + 'bridge/BridgeProtocol.class'
WORKER = PREFIX + 'bridge/NativeCoreWorker'
VERSION = '0.1.0-alpha.1'
CORE_SHA = '6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'
JNA_SHA = '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
RAW_SHA = '554C673E65D5957DA0BC1DF0CEB986CBAC5500D407AE2D780FEAB4F8D2EED810'
SOURCE_PINS = {
    'src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java': 'F59113F4F6D2C72EEEBECD778B5ACCDC82A2EF564A792C5B9795EE8C55628250',
    'src/main/java/cn/piq/nativearcade/bridge/NativeProcessSession.java': '38A82E76F94C90D0CF218E4891399B6AEDBE615312A3250A743D68879F7D29AD',
    'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java': '904BC021AC52922C0ED3E9C4EF8CA9D39BD27F5A0E9AC02B3DB202949247E8F7',
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest().upper()


def checked_zip(path, max_member=16 * 1024 * 1024):
    """No extraction, duplicate ambiguity, traversal entries, nested executables or zip bombs."""
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        require(len(names) == len(set(names)), 'duplicate ZIP entry')
        for info in infos:
            name = info.orig_filename  # ZipInfo.filename silently normalizes backslashes on Windows.
            require('\\' not in name and not name.startswith('/') and ':' not in name
                    and '..' not in PurePosixPath(name).parts, 'unsafe ZIP path: ' + name)
            require(not (info.flag_bits & 1), 'encrypted ZIP entry')
            require(0 <= info.file_size <= max_member, 'oversized ZIP entry')
        require(sum(i.file_size for i in infos) <= 128 * 1024 * 1024, 'oversized ZIP total')
        return {i.filename: archive.read(i) for i in infos if not i.is_dir()}


def class_info(data):
    """Minimal JVMS constant pool reader; separates a ProcessBuilder class-name string from a link."""
    require(len(data) >= 10 and data[:4] == b'\xca\xfe\xba\xbe', 'invalid Java class')
    major, count = struct.unpack_from('>HH', data, 6)
    pos, index, utf, refs = 10, 1, {}, []
    lengths = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4,
               12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    try:
        while index < count:
            tag = data[pos]
            pos += 1
            if tag == 1:
                size = struct.unpack_from('>H', data, pos)[0]
                pos += 2
                require(pos + size <= len(data), 'truncated Java UTF')
                utf[index] = data[pos:pos + size].decode('utf-8', errors='replace')
                pos += size
            elif tag == 7:
                refs.append(struct.unpack_from('>H', data, pos)[0])
                pos += 2
            else:
                require(tag in lengths, 'unknown Java constant tag')
                pos += lengths[tag]
            index += 2 if tag in (5, 6) else 1
        require(pos <= len(data) and all(i in utf for i in refs), 'truncated Java pool')
        return {'major': major, 'utf': set(utf.values()), 'classes': {utf[i] for i in refs}}
    except (IndexError, struct.error) as error:
        raise ValueError('truncated Java pool') from error


def validate_metadata(entries):
    meta = tomllib.loads(entries['META-INF/neoforge.mods.toml'].decode('utf-8'))
    mods = meta.get('mods', [])
    require(len(mods) == 1 and mods[0].get('modId') == 'piq_native_arcade'
            and mods[0].get('version') == VERSION, 'wrong mod id/version')
    dependencies = meta.get('dependencies', {}).get('piq_native_arcade', [])
    expected = {'piq_fc_arcade': '[0.31.0-alpha.14,0.32.0)',
                'minecraft': '[1.21.1,1.21.2)', 'neoforge': '[21.1.236,22)'}
    require(len(dependencies) == len(expected), 'unexpected dependency list')
    for name, version in expected.items():
        matched = [d for d in dependencies if d.get('modId') == name]
        require(len(matched) == 1 and matched[0].get('type') == 'required'
                and matched[0].get('versionRange') == version
                and matched[0].get('side') == 'BOTH', 'wrong dependency: ' + name)
    manifest = entries['META-INF/MANIFEST.MF'].decode('utf-8')
    require('Implementation-Version: ' + VERSION in manifest, 'wrong implementation version')
    return {'mod_id': mods[0]['modId'], 'version': VERSION, 'dependencies': expected}


def validate_main(entries):
    classes = {n: class_info(b) for n, b in entries.items() if n.endswith('.class')}
    require(classes and PROTOCOL in classes, 'missing main classes/protocol')
    for name, info in classes.items():
        require(name.startswith(PREFIX), 'foreign class embedded: ' + name)
        require(info['major'] == 65, 'main class is not Java 21: ' + name)
        require(not name.startswith(WORKER), 'native worker embedded in main JAR')
        require(not any('com/sun/jna' in u or 'com.sun.jna' in u or 'Native.load' in u
                        for u in info['utf']), 'JNA linked from main class: ' + name)
        require(not any(WORKER in c for c in info['classes']), 'worker typed link from main class')
    require(not any(n.lower().endswith(('.dll', '.jar', '.so', '.dylib', '.zip', '.exe'))
                    for n in entries), 'embedded runtime/archive in main JAR')
    # Common classes may emit events, but must never link a Minecraft client symbol.
    common = [n for n in classes if not n.startswith(PREFIX + 'client/')]
    for n in common:
        require(not any('net/minecraft/client/' in u or 'cn/piq/nativearcade/client/' in u
                        for u in classes[n]['utf']), 'common class client link: ' + n)
    return {'own_java21_classes': len(classes), 'common_classes_checked': len(common),
            'class_sha256': {n: sha(entries[n]) for n in sorted(classes)}}


def validate_helper(main, entries, semantic_proof=None):
    classes = {n: class_info(b) for n, b in entries.items() if n.endswith('.class')}
    require(PROTOCOL in entries, 'helper protocol missing')
    identical = main[PROTOCOL] == entries[PROTOCOL]
    require(identical or (semantic_proof is not None
            and semantic_proof['main_sha256'] == sha(main[PROTOCOL])
            and semantic_proof['helper_sha256'] == sha(entries[PROTOCOL])
            and semantic_proof['semantic_match']), 'helper/main protocol bytes differ without semantic proof')
    require(WORKER + '.class' in classes, 'helper main class missing')
    for n, info in classes.items():
        require(n == PROTOCOL or n == WORKER + '.class' or n.startswith(WORKER + '$'), 'foreign helper class')
        require(info['major'] == 65, 'helper class is not Java 21')
    require(not any(n.lower().endswith(('.dll', '.jar', '.so', '.exe', '.zip')) for n in entries),
            'embedded executable/archive in helper')
    info = classes[WORKER + '.class']
    require({'main', '([Ljava/lang/String;)V', 'com/sun/jna/Native', 'load'} <= info['utf'],
            'helper executable/JNA entry missing')
    return {'java21_classes': len(classes), 'entry': WORKER.replace('/', '.'),
            'protocol_bytes_identical': identical,
            'protocol_main_sha256': sha(main[PROTOCOL]), 'protocol_helper_sha256': sha(entries[PROTOCOL]),
            'protocol_semantics': semantic_proof,
            'class_sha256': {n: sha(entries[n]) for n in sorted(classes)}}


def validate_diagnostic(entries, manifest):
    chips = manifest['chips']
    require(len(chips) == 4 and len({c['name'] for c in chips}) == 4, 'wrong diagnostic manifest')
    require(manifest['original_firmware_sha256'] == RAW_SHA, 'unreviewed diagnostic manifest')
    require(set(entries) == {c['name'] for c in chips}, 'diagnostic must have exactly four original chips')
    for chip in chips:
        data = entries[chip['name']]
        require(len(data) == chip['bytes'] == 2048 and sha(data) == chip['sha256'],
                'non-original or corrupt diagnostic chip: ' + chip['name'])
    raw = b''.join(entries[c['name']] for c in chips)
    require(sha(raw) == RAW_SHA, 'diagnostic firmware is not original PoC content')
    return {'original_firmware_sha256': RAW_SHA, 'chips': chips,
            'commercial_game_code_included': False, 'provenance': manifest['source']}


def validate_runtime(runtime):
    runtime = Path(runtime)
    files = {}
    for name, expected in [('mame_libretro.dll', CORE_SHA), ('jna-5.14.0.jar', JNA_SHA)]:
        file = runtime / name
        require(file.is_file() and not file.is_symlink(), 'missing/linked runtime: ' + name)
        actual = file_sha(file)
        require(actual == expected, 'runtime SHA mismatch: ' + name)
        files[name] = {'sha256': actual, 'bytes': file.stat().st_size}
    with (runtime / 'mame_libretro.dll').open('rb') as stream:
        header = stream.read(4096)
    require(header[:2] == b'MZ', 'DLL DOS signature')
    pe = struct.unpack_from('<I', header, 0x3c)[0]
    require(header[pe:pe + 4] == b'PE\0\0' and struct.unpack_from('<H', header, pe + 4)[0] == 0x8664,
            'DLL is not Windows x64')
    return files


def javap(path, classes, verbose=False):
    result = subprocess.run([str(JDK / 'javap.exe'), '-c', '-p', '-s', '-constants',
                            *(['-verbose'] if verbose else []), '-classpath', str(path), *classes],
                            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
    require(result.returncode == 0, 'javap failed: ' + result.stderr)
    return result.stdout


def canonical_protocol_verbose(text):
    """Compare the WHOLE verbose class body, not selected substrings.

    Retains all field constants/descriptors/flags, methods, every bytecode instruction and resolved
    operand, exceptions, stack limits/maps, and non-debug attributes. Only provenance, pool index
    numbering/definitions (references are resolved by javap), and line/local-variable tables differ.
    Literal strings in comments/ConstantValue are never whitespace-normalized or redacted.
    """
    result, lines, i = [], text.splitlines(), 0
    while i < len(lines):
        line = lines[i]; stripped = line.strip()
        if stripped.startswith(('Classfile ', 'Last modified ', 'SHA-256 checksum ')):
            i += 1; continue
        if stripped == 'Constant pool:':
            i += 1
            while i < len(lines) and lines[i].strip() != '{': i += 1
            require(i < len(lines), 'missing verbose class body')
            continue
        if stripped in ('LineNumberTable:', 'LocalVariableTable:', 'LocalVariableTypeTable:'):
            indent = len(line) - len(line.lstrip()); i += 1
            while i < len(lines) and (not lines[i].strip()
                    or len(lines[i]) - len(lines[i].lstrip()) > indent): i += 1
            continue
        if re.match(r'\d+:', stripped) or stripped.startswith(('this_class:', 'super_class:')):
            before, separator, comment = stripped.partition('//')
            before = re.sub(r'#\d+', '#CP', before)
            before = re.sub(r'\s+', ' ', before).strip()
            stripped = before + (' // ' + comment.strip() if separator else '')
        if stripped: result.append(stripped)
        i += 1
    require(any(s.startswith('public static void frameBounds(') for s in result)
            and any(s.startswith('descriptor: (IIFII)V') for s in result), 'missing protocol method/descriptors')
    return '\n'.join(result)


def compare_protocols(jar, helper):
    name = 'cn.piq.nativearcade.bridge.BridgeProtocol'
    left = canonical_protocol_verbose(javap(jar, [name], verbose=True))
    right = canonical_protocol_verbose(javap(helper, [name], verbose=True))
    require(left == right, 'helper/main protocol semantic class differs (constants/signatures/instructions/attributes)')
    a, b = checked_zip(jar)[PROTOCOL], checked_zip(helper)[PROTOCOL]
    return {'semantic_match': True, 'bytes_identical': a == b, 'main_sha256': sha(a), 'helper_sha256': sha(b),
            'canonical_full_class_sha256': sha(left.encode()),
            'difference': 'none' if a == b else 'debug-only: main includes LocalVariableTable; full non-debug class representation matches',
            'comparison': 'Complete javap verbose class equality with all constants, descriptors, instructions, resolved operands and non-debug attributes; excludes provenance, constant-pool indices/definitions and line/local-variable tables.'}


def check_tokens(text, tokens, label):
    for token in tokens:
        require(token in text, label + ' missing ' + token)


def bytecode_checks(jar, helper):
    parent = javap(jar, ['cn.piq.nativearcade.bridge.NativeProcessSession'])
    parent_tokens = ['java/lang/Runtime.addShutdownHook', 'java/lang/Process.destroyForcibly',
                         'java/lang/Process.waitFor', 'java/lang/ProcessBuilder.start',
                         'piq-native-helper.jar', 'cn.piq.nativearcade.bridge.NativeCoreWorker',
                         '-Djna.nosys=true', 'java/nio/file/Files.createTempDirectory',
                         'java/nio/file/LinkOption.NOFOLLOW_LINKS', 'verify:',
                         'java/util/concurrent/atomic/AtomicBoolean.compareAndSet',
                         'java/util/concurrent/ArrayBlockingQueue.offer', 'frameBounds:',
                         CORE_SHA, JNA_SHA]
    check_tokens(parent, parent_tokens, 'packaged parent isolation')
    worker = javap(helper, ['cn.piq.nativearcade.bridge.NativeCoreWorker'])
    check_tokens(worker, ['public static void main(java.lang.String[])', 'com/sun/jna/Native.load',
                         'retro_api_version', 'Cannot isolate native stdout'], 'packaged private worker')
    client = javap(jar, ['cn.piq.nativearcade.client.NativeArcadeClient',
                        'cn.piq.nativearcade.client.NativeArcadePlayScreen'])
    client_tokens = ['getSingleplayerServer', 'isPublished', 'Windows', 'os.arch',
                         'GameShuttingDownEvent', 'shutdownNow', 'NativeProcessSession.close',
                         'NativeVideoPresentation.textureUv', 'NativeVideoPresentation.displayAspect',
                         'DynamicTexture.upload', 'setPixelRGBA', 'NativeProcessSession.clearInput',
                         'isWindowActive', 'isPauseScreen', 'NativeArcadeAudio.offer']
    check_tokens(client, client_tokens, 'packaged client integration')
    return {'parent_isolation_tokens': len(parent_tokens), 'private_worker_main_and_native_load': True,
            'singleplayer_gate_shutdown_input_video_tokens': len(client_tokens),
            'javap_sha256': {'parent': sha(parent.encode()), 'helper': sha(worker.encode()),
                            'client': sha(client.encode())}}


def source_checks():
    sources = {}
    for name, expected in SOURCE_PINS.items():
        require(file_sha(ROOT / name) == expected, 'reviewed bridge source drift: ' + name)
        sources[name] = expected
    name = 'src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java'
    client = (ROOT / name).read_text(encoding='utf-8')
    clean = re.sub(r'\s+', '', re.sub(r'//[^\n]*|/\*.*?\*/', '', client, flags=re.S))
    check_tokens(clean, ['server!=null&&!server.isPublished()', 'float[][]uv={{0,1},{1,1},{1,0},{0,0}}',
                         'for(inty=0;y<f.height();y++)for(intx=0;x<f.width();x++)',
                         'p.x(),(float)p.y(),(float)p.z()', 'session.pollFrame()',
                         'token!=generation', 'PENDING.getAndSet(null)'], 'reviewed client source')
    sources[name] = file_sha(ROOT / name)
    return {'source_sha256': sources, 'uncropped_uv_and_pixel_copy': True,
            'scope': 'Source contracts plus actual packaged bytecode; not Minecraft gameplay or screen capture.'}


def pure_probe(jar):
    source = ROOT / 'tools/qa/NativeDeliveryProbe.java'
    with tempfile.TemporaryDirectory(prefix='native-delivery-probe-') as temp:
        subprocess.run([str(JDK / 'javac.exe'), '--release', '21', '-encoding', 'UTF-8',
                        '-cp', str(jar), '-d', temp, str(source)], check=True, timeout=30)
        result = subprocess.run([str(JDK / 'java.exe'), '-cp', str(jar) + ';' + temp,
                                 'NativeDeliveryProbe'], capture_output=True, text=True, timeout=20)
    require(result.returncode == 0 and 'NATIVE_DELIVERY_PROBE=' in result.stdout
            and ' PASS' in result.stdout, 'packaged pure probe failed: ' + result.stdout + result.stderr)
    return {'stdout': result.stdout.strip(), 'probe_sha256': file_sha(source),
            'native_library_loaded': False, 'minecraft_started': False}


def verify(jar, runtime, diagnostic):
    jar, runtime, diagnostic = map(lambda p: Path(p).resolve(), (jar, runtime, diagnostic))
    require(jar.is_file() and not jar.is_symlink(), 'main JAR missing/linked')
    require(diagnostic.is_file() and not diagnostic.is_symlink(), 'diagnostic ZIP missing/linked')
    require(diagnostic.name == 'invaders.zip', 'diagnostic filename must match hardware driver')
    main = checked_zip(jar)
    helper = runtime / 'piq-native-helper.jar'
    require(helper.is_file() and not helper.is_symlink(), 'private helper missing/linked')
    manifest_file = ROOT / 'tools/native-diagnostic-original-manifest.json'
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
    result = {'ok': True, 'jar': str(jar), 'jar_sha256': file_sha(jar),
              'jar_bytes': jar.stat().st_size, 'jar_file_entries': len(main),
              'metadata': validate_metadata(main), 'main': validate_main(main),
              'runtime': validate_runtime(runtime),
              'helper': validate_helper(main, checked_zip(helper), compare_protocols(jar, helper)),
              'diagnostic': validate_diagnostic(checked_zip(diagnostic, 2048), manifest),
              'bytecode': bytecode_checks(jar, helper), 'source': source_checks(),
              'pure_final_jar_probe': pure_probe(jar)}
    result['helper'].update(path=str(helper), sha256=file_sha(helper), bytes=helper.stat().st_size)
    result['diagnostic'].update(path=str(diagnostic), zip_sha256=file_sha(diagnostic),
                                manifest_sha256=file_sha(manifest_file))
    result['limits'] = ['No Minecraft gameplay/audio-device or third-party ROM compatibility claim.',
                        'Private child process is not an operating-system security sandbox.',
                        'Shutdown bytecode is inspected here; actual child termination is tested in the separate real bridge report.',
                        'Commercial ROMs, CHD, multiplayer emulation and persistent save states are not validated.']
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jar', 'runtime', 'diagnostic', 'report'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    report = Path(args.report).resolve()
    require(not report.exists(), 'refusing to overwrite existing audit report')
    try:
        result = verify(args.jar, args.runtime, args.diagnostic)
    except Exception as error:
        result = {'ok': False, 'error': type(error).__name__ + ': ' + str(error)}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write('\n')
    print(json.dumps(result, ensure_ascii=True, indent=2))
    raise SystemExit(0 if result['ok'] else 1)


if __name__ == '__main__':
    main()
