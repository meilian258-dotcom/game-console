"""Offline deterministic v4 coin-safe helper; never overwrites an older/different helper."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

from pathlib import Path
import argparse
import hashlib
import io
import json
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JNA = (gradle_home() / 'caches/modules-2/files-2.1/net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar')
JNA_SHA = '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
DEFAULT_OUTPUT = ROOT / 'build/helper48/piq-native-helper-v4.jar'
SOURCES = [ROOT / 'src/main/java/cn/piq/nativearcade/bridge' / (name + '.java')
           for name in ('BridgeProtocol', 'NativeInputPorts', 'NativeArcadeButtons')]
SOURCES.append(ROOT / 'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java')


def digest(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def build(output=DEFAULT_OUTPUT, *, jna=None, java_home_path=None):
    output = Path(output).resolve()
    if not output.is_relative_to((ROOT / 'build').resolve()) or output.name != 'piq-native-helper-v4.jar':
        raise ValueError('Only the new versioned helper beneath this project build directory is allowed')
    jna = Path(jna) if jna is not None else JNA
    if digest(jna.read_bytes()) != JNA_SHA:
        raise ValueError('Fixed JNA compile dependency changed')
    jdk = java_home(java_home_path) / 'bin'
    compiler = jdk / ('javac.exe' if (jdk / 'javac.exe').is_file() else 'javac')
    before = {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in SOURCES}
    with tempfile.TemporaryDirectory(prefix='piq-helper48-build-') as folder:
        temporary = Path(folder)
        subprocess.run([str(compiler), '-encoding', 'UTF-8', '--release', '21', '-cp', str(jna),
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--jna', type=Path, default=JNA, help='Pinned JNA 5.14.0 JAR; SHA256 is always verified')
    parser.add_argument('--java-home', type=Path, help='JDK 21 home; otherwise JAVA_HOME or javac on PATH')
    args = parser.parse_args(argv)
    print(json.dumps(build(args.output, jna=args.jna, java_home_path=args.java_home), indent=2))


if __name__ == '__main__':
    main()
