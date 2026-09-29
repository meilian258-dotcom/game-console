"""Add the versioned coin-safe helper beside the untouched Native17 runtime tree.

The existing six payloads and six provenance/license entries still come from the
pinned runtime17 generator. This separate three-entry addition never overwrites
that tree, a player runtime or an older helper. No native code or ROM is executed.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
LEGACY = PROJECT / 'tools/prepare_embedded_runtime17.py'
LEGACY_SHA = '8049CEF7FCFD5F26B2D62E5A77CE55A46EBD8B1A306DE194BDCEC3AED52F6D6A'
if hashlib.sha256(LEGACY.read_bytes()).hexdigest().upper() != LEGACY_SHA:
    raise ValueError('Immutable runtime17 generator changed')
spec = importlib.util.spec_from_file_location('native011_runtime17', LEGACY)
legacy = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = legacy
spec.loader.exec_module(legacy)

HELPER_NAME = 'piq-native-helper-v4.jar'
HELPER_BYTES = 18858
HELPER_SHA = '51A1A6A0A326E5E855647A414DBB78894CA01E9D766627EF272CE59BC809A304'
HELPER = PROJECT / 'build/helper48' / HELPER_NAME
HELPER_RELATIVE = 'piq-native-arcade/runtime/' + HELPER_NAME
DEFAULT_OUTPUT = PROJECT / 'build/generated/embeddedRuntime011'
SOURCE_PINS = {
    'src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java': '68E6DD99A6BD0669ED390E00AB586561AB3EBE3846B9E1EEC41B0BCC3CF8EB20',
    'src/main/java/cn/piq/nativearcade/bridge/NativeInputPorts.java': 'C6190D42E3E72C2AF6C2338094990387F6EE6AA8F838381E8D636443A55F2C2B',
    'src/main/java/cn/piq/nativearcade/bridge/NativeArcadeButtons.java': 'A250D0D0728E7871217F01C5BDC018E0D63D9542B184F6EA4DAC52DF04FC7FB2',
    'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java': '7E2BD2420D74E28444277FBF2A72A022EF59B182C5606F50C129E407B0832B11',
}
NOTICE = b'''Game Console: Arcade 0.1.1 - coin-preserving input release

The six original runtime17 payloads, including the old ordinary helper, remain
byte-identical and retain their original manifest and license texts. The new
piq-native-helper-v4.jar is compiled offline from the four source files and exact
hashes listed in manifest-native011.json using Java 21 and fixed JNA 5.14.0.
Only this new helper is selected by the ordinary Native 0.1.1 Java bridge.
The MAME DLL, Neo Geo DLL/helper, both JNA copies and all GBA references are unchanged.
The versioned filename lets the existing no-overwrite installer add it beside an
older installed helper. Unknown contents at the new destination cause a conflict;
they are never overwritten. No ROM, BIOS, save or game-instance file is supplied.
The original runtime17 licensing/source-correspondence caveats still apply; this
change does not certify public redistribution or execute any native game core.
'''


def digest(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def manifest_bytes():
    manifest = json.loads(legacy.manifest_bytes(legacy.PROFILE))
    manifest['schema'] = 2
    manifest['modVersion'] = '0.1.1'
    manifest['artifacts'].append({'relativePath': HELPER_RELATIVE, 'resourcePath': legacy.PREFIX + HELPER_RELATIVE,
                                  'size': HELPER_BYTES, 'sha256': HELPER_SHA})
    manifest['selectedOrdinaryHelper'] = HELPER_RELATIVE
    manifest['legacyManifest'] = {'resourcePath': legacy.PREFIX + 'manifest.json',
                                  'sha256': digest(legacy.manifest_bytes(legacy.PROFILE))}
    manifest['helperBuild'] = {'privateProtocol': 4, 'javaRelease': 21, 'sourceSha256': SOURCE_PINS,
                              'jnaSha256': '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'}
    manifest['additionalNoticeResource'] = legacy.LICENSE_PREFIX + 'NOTICE-native011.md'
    return (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def addition_bytes(helper_raw):
    legacy.require_identity((len(helper_raw), digest(helper_raw)), HELPER_BYTES, HELPER_SHA, 'versioned helper')
    return {legacy.PREFIX + HELPER_RELATIVE: helper_raw,
            legacy.PREFIX + 'manifest-native011.json': manifest_bytes(),
            legacy.LICENSE_PREFIX + 'NOTICE-native011.md': NOTICE}


def validate_inputs(archive, license_root, helper):
    legacy.reject_link_chain(archive)
    legacy.reject_link_chain(license_root)
    legacy.reject_link_chain(helper)
    legacy.require_identity(legacy.file_identity(LEGACY), LEGACY.stat().st_size, LEGACY_SHA, 'legacy generator')
    legacy.inspect_zip(archive, legacy.PROFILE)
    legacy.inspect_licenses(license_root, legacy.PROFILE)
    for name, expected in SOURCE_PINS.items():
        path = PROJECT / name
        legacy.reject_link_chain(path)
        if digest(path.read_bytes()) != expected:
            raise legacy.VerificationError('Helper source differs from fixed v4 helper: ' + name)
    return addition_bytes(helper.read_bytes())


def verify_output(output, entries):
    legacy.reject_link_chain(output)
    if not output.is_dir():
        raise legacy.VerificationError('Native011 addition is not a directory')
    directories = {str(parent).replace('\\', '/') for name in entries for parent in Path(name).parents if str(parent) != '.'}
    actual = set()
    for path in output.rglob('*'):
        legacy.reject_link_chain(path)
        if path.is_file():
            name = path.relative_to(output).as_posix()
            if name not in entries or path.read_bytes() != entries[name]:
                raise legacy.VerificationError('Unexpected/damaged Native011 addition: ' + name)
            actual.add(name)
        elif not path.is_dir() or path.relative_to(output).as_posix() not in directories:
            raise legacy.VerificationError('Non-regular Native011 addition')
    if actual != set(entries):
        raise legacy.VerificationError('Native011 addition missing files')


def prepare(archive, license_root, helper, output):
    entries = validate_inputs(archive, license_root, helper)
    legacy.reject_link_chain(output)
    if output.exists():
        # Gradle may pre-create its declared output directory. Only that empty
        # directory can be removed, non-recursively; any content is fail-closed.
        if output.is_dir() and next(output.iterdir(), None) is None:
            output.rmdir()
        else:
            verify_output(output, entries)
            return {'reused': True, 'output': str(output), 'files': len(entries)}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.embeddedRuntime011-', dir=output.parent))
    try:
        for name, raw in entries.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as stream:
                stream.write(raw)
        verify_output(temporary, entries)
        if entries != validate_inputs(archive, license_root, helper):
            raise legacy.VerificationError('Native011 inputs changed while staging')
        if output.exists():
            raise legacy.VerificationError('Native011 output appeared while preparing; refusing overwrite')
        temporary.rename(output)
        verify_output(output, entries)
        return {'reused': False, 'output': str(output), 'files': len(entries)}
    finally:
        if temporary.exists():
            if temporary.resolve().parent != output.resolve().parent or not temporary.name.startswith('.embeddedRuntime011-'):
                raise legacy.VerificationError('Unsafe temporary cleanup target')
            shutil.rmtree(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--plan', action='store_true')
    group.add_argument('--prepare', action='store_true')
    parser.add_argument('--archive', type=Path, default=legacy.DEFAULT_ARCHIVE)
    parser.add_argument('--license-root', type=Path, default=PROJECT / 'docs/licenses')
    parser.add_argument('--helper', type=Path, default=HELPER)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    output = args.output.absolute()
    if output != DEFAULT_OUTPUT.absolute():
        raise legacy.VerificationError('Only build/generated/embeddedRuntime011 may be generated')
    if args.plan:
        entries = validate_inputs(args.archive, args.license_root, args.helper)
        result = {'read_only': True, 'additions': {name: {'bytes': len(raw), 'sha256': digest(raw)} for name, raw in entries.items()},
                  'legacy_entries_unchanged': len(legacy.expected_output(legacy.PROFILE))}
    else:
        result = prepare(args.archive, args.license_root, args.helper, output)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
