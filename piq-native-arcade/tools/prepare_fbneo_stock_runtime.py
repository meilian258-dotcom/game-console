"""Stage the reviewed, unmodified FBNeo stock-v1 core and independent notices.

Offline only. Never downloads, recompiles a core, or modifies input files.
The original official binary's source mapping is not reproducibility-verified.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import stat

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / 'design/fbneo-study/vendor'
CORE_SHA = '52343F65453C0E1FC06B3AA818B48CA4FED18361F7F336A684E9E3CFFA5F5F3C'
CORE_BYTES = 61913629
LICENSE_SHA = 'BB2369F1B75F42242968A78191B47EE90F85682224D3AD1EF63044E244B3E202'
SOURCE_NAME = 'FBNeo-a251c76229f1637e433b93e29845039752771b6d.zip'
SOURCE_SHA = '55CC0F5BF305D8953FA0A20C3598164D39EFC03EF3740C7B01E7EBF143CD4D7A'
SOURCE_BYTES = 29731166
RESOURCE = 'native-runtime/win-x64-fbneo-stock-v1/fbneo_libretro.dll'
NOTICE = 'META-INF/piq-native/fbneo-stock-v1'


def no_links(path):
    """Reject symbolic links and Windows junction/reparse components before resolving."""
    path = Path(os.path.abspath(path))
    for component in (path, *path.parents):
        try:
            info = component.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Linked path is not an approved build input/output')
    return path


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def identity(path, expected_size, expected_sha):
    path = no_links(path)
    before = path.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size != expected_size:
        raise ValueError('FBNeo fixed input size/type mismatch: ' + path.name)
    actual = digest(path)
    after = path.stat()
    before_key = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    after_key = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    if before_key != after_key or actual != expected_sha:
        raise ValueError('FBNeo fixed input identity mismatch: ' + path.name)
    return before_key


def validate(core, source_archive, license_path):
    return {
        Path(core): identity(core, CORE_BYTES, CORE_SHA),
        Path(source_archive): identity(source_archive, SOURCE_BYTES, SOURCE_SHA),
        Path(license_path): identity(license_path, 11249, LICENSE_SHA),
    }


def provenance():
    return dict(
        schema='piq-fbneo-stock-v1', variant='retroarch-stock-v1',
        core='FinalBurn Neo v1.0.0.03 260904 GITa251c76',
        resource='/' + RESOURCE, sha256=CORE_SHA, bytes=CORE_BYTES,
        upstream='https://buildbot.libretro.com/nightly/windows/x86_64/latest/fbneo_libretro.dll.zip',
        upstreamArchiveSha256='934FF65DC9A3229E62F39A8F26630B54473995BE631FCF9D277996765B2643AB',
        upstreamLastModified='Fri, 11 Sep 2026 23:02:07 GMT',
        source='https://github.com/libretro/FBNeo/tree/a251c76229f1637e433b93e29845039752771b6d',
        sourceArchive=SOURCE_NAME, sourceArchiveSha256=SOURCE_SHA,
        sourceArchiveUrl='https://codeload.github.com/libretro/FBNeo/zip/a251c76229f1637e433b93e29845039752771b6d',
        sourceDistribution='Distribute the verified source archive alongside the installation artifact.',
        modified=False, reproducibleBuildVerified=False,
        binaryToSourceExactMappingVerified=False, licenseSha256=LICENSE_SHA,
        notice='Separate FBNeo non-commercial license, NOT GPL relicensing. '
               'Experimental RetroArch Netplay variant; persistence is not enabled. '
               'No ROM/BIOS. Legacy JNI core, resources and saves retain their original identities.')


def copy_pinned(source, destination, size, sha):
    no_links(destination)
    if destination.exists():
        identity(destination, size, sha)
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as original, destination.open('xb') as staged:
        shutil.copyfileobj(original, staged, 1024 * 1024)
    identity(destination, size, sha)


def prepare(core, source_archive, license_path, output):
    core, source_archive, license_path = map(Path, (core, source_archive, license_path))
    before = validate(core, source_archive, license_path)
    output = no_links(output).resolve()
    build = no_links(ROOT / 'build').resolve()
    if output == build or not output.is_relative_to(build):
        raise ValueError('Output must be a child of this project build directory')
    files = {
        RESOURCE: (core, CORE_BYTES, CORE_SHA),
        NOTICE + '/LICENSE.txt': (license_path, 11249, LICENSE_SHA),
    }
    receipt_path = output / NOTICE / 'provenance.json'
    receipt_bytes = (json.dumps(provenance(), indent=2) + '\n').encode('utf8')
    expected = set(files) | {NOTICE + '/provenance.json'}
    if output.exists():
        for existing in output.rglob('*'):
            no_links(existing)
            if existing.is_file() and existing.relative_to(output).as_posix() not in expected:
                raise ValueError('Unexpected file in stock runtime output')
        for name, (_, size, sha) in files.items():
            destination = output / name
            if destination.exists():
                identity(destination, size, sha)
        if receipt_path.exists() and receipt_path.read_bytes() != receipt_bytes:
            raise ValueError('Existing stock provenance mismatch; use a fresh output')
    for name, (source, size, sha) in files.items():
        copy_pinned(source, output / name, size, sha)
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    if not receipt_path.exists():
        with receipt_path.open('xb') as stream:
            stream.write(receipt_bytes)
    if validate(core, source_archive, license_path) != before:
        raise ValueError('FBNeo input changed while staging')
    return provenance()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core', type=Path, default=VENDOR / 'fbneo_libretro.dll')
    parser.add_argument('--source-archive', type=Path, default=VENDOR / SOURCE_NAME)
    parser.add_argument('--license', type=Path, default=VENDOR / 'license.txt')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.core, args.source_archive, args.license, args.output)


if __name__ == '__main__':
    main()
