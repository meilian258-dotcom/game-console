"""Offline deterministic v4 coin-safe helper; never overwrites an older/different helper."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
JNA = Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar')
JNA_SHA = '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
DEFAULT_OUTPUT = ROOT / 'build/helper48/piq-native-helper-v4.jar'
SOURCES = [ROOT / 'src/main/java/cn/piq/nativearcade/bridge' / (name + '.java')
           for name in ('BridgeProtocol', 'NativeInputPorts', 'NativeArcadeButtons')]
SOURCES.append(ROOT / 'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java')


def digest(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def build(output=DEFAULT_OUTPUT):
    output = Path(output).resolve()
    if not output.is_relative_to((ROOT / 'build').resolve()) or output.name != 'piq-native-helper-v4.jar':
        raise ValueError('Only the new versioned helper beneath this project build directory is allowed')
    if digest(JNA.read_bytes()) != JNA_SHA:
        raise ValueError('Fixed JNA compile dependency changed')
    before = {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in SOURCES}
    with tempfile.TemporaryDirectory(prefix='piq-helper48-build-') as folder:
        temporary = Path(folder)
        subprocess.run([str(JDK / 'javac.exe'), '-encoding', 'UTF-8', '--release', '21', '-cp', str(JNA),
                        '-d', str(temporary), *map(str, SOURCES)], check=True, timeout=30)
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(temporary.rglob('*.class')):
                member = zipfile.ZipInfo(path.relative_to(temporary).as_posix(), (2026, 9, 17, 0, 0, 0))
                member.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(member, path.read_bytes())
        raw = data.getvalue()
    if before != {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in SOURCES}:
        raise ValueError('Helper source changed during compilation')
    if output.exists():
        if output.is_symlink() or output.read_bytes() != raw:
            raise ValueError('Existing v4 helper differs; no file was overwritten')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('xb') as stream:
            stream.write(raw)
    return {'path': str(output), 'sha256': digest(raw), 'bytes': len(raw), 'private_protocol': 4,
            'source_sha256': before, 'jna_sha256': JNA_SHA, 'dll_loaded': False, 'rom_read': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    print(json.dumps(build(parser.parse_args().output), indent=2))
