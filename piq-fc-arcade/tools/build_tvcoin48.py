"""Freeze reviewed FC48/SFC28/Native011 test packages; never build, install or publish.

Uses the immutable GC018 archive/freshness helper. The only widened resource
boundaries are two exact FC CRT loot JSONs and three fixed Native011 additions.
The old 12 Native resources, FC WASMs, both drafts and SFC core9 remain frozen.
"""
from pathlib import Path
import copy
import hashlib
import importlib.util
import inspect
import io
import re
import shutil
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'piq-fc-arcade/tools/build_gc018_45.py'
HELPER_SHA = '443EBF72074F6B17399146F44FB902EAA2CDF9F8593A37640E3476EB81987BA6'
if hashlib.sha256(HELPER.read_bytes()).hexdigest().upper() != HELPER_SHA:
    raise ValueError('Pinned GC018 archive/freshness helper changed')
spec = importlib.util.spec_from_file_location('tvcoin48_fixed_helpers', HELPER)
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)
g.__doc__ = __doc__

g.PROJECT['native'] = 'piq-native-arcade'
g.OWNER['native'] = 'piq_native_arcade'
g.PREFIXES['native'] = ('cn/piq/nativearcade/',)
g.a.PROJECT, g.a.PREFIXES = g.PROJECT, g.PREFIXES
g.b.PROJECTS.append('piq-native-arcade')
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.48', piq_sfc_home='0.1.0-alpha.28', piq_native_arcade='0.1.1')
g.b.TEST_LIMITS = {'piq-fc-arcade': (1786, 8), 'piq-sfc-home': (361, 0), 'piq-native-arcade': (95, 0)}
g.OUT = ROOT / 'piq-fc-arcade/build/review-tvcoin48-v1'
g.NAMES = {'fc': 'game_console-0.31.0-alpha.48.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.28.jar',
           'native': 'game_console_arcade-0.1.1.jar'}
g.PLAN = ROOT / 'outputs/tvcoin48/reviewed-changes.json'
g.SOURCE_BEFORE = ROOT / 'outputs/tvcoin48/source-before.json'
g.SOURCE_BEFORE_SHA = '8DF148C7E86BFDAB850065B4243483DAEF6A5843EFB36E27E742F37194B6991B'
BASE_DIR = ROOT / 'piq-fc-arcade/build/review-debug47-v1'
g.BASE = {
    'fc': (BASE_DIR / 'game_console-0.31.0-alpha.47.jar', '58AAA1C77B6EF8C6A8E160F3481E282F938E54D222C57B2CF74D2C828C43A839'),
    'sfc': (BASE_DIR / 'game_console_sfc-0.1.0-alpha.27.jar', '57D682C778775CFA3EB9082104843F45D080B0D71516C57B12E801DDE07D6DF3'),
    'native': g.REFERENCES.pop('native'),
}
g.BASE_WITNESSES = {kind: (BASE_DIR / 'build-witness.json',
    '67398905E6D22349690D5CDD631B76A6EC2841D15A273A1F380497A8CDC04571') for kind in ('fc', 'sfc')}
g.BASE_WITNESSES['native'] = (g.BASE['native'][0].parent / 'build-witness.json',
    '1FE6119059E1B900B9BDC8A3D1D01DAAC3C42AD95F581CAA8A7253FAFA540B72')
g.COMPANIONS = [Path(__file__).resolve(), ROOT / 'outputs/tvcoin48/test_build_tvcoin48.py',
                ROOT / 'outputs/tvcoin48/run_build.py']

# These are exact archive members, not a namespace/directory permission.
EXTRA_FC_JSON = frozenset({
    'data/piq_fc_arcade/loot_table/blocks/gray_crt_tv.json',
    'data/piq_fc_arcade/loot_table/blocks/red_crt_tv.json',
})
NATIVE_BEFORE = ROOT / 'outputs/tvcoin48/native011-before.json'
NATIVE_BEFORE_SHA = 'CFC6690148430911E6D79C5EAE1E0D640C0C254A64844ED77A332D0213B7AE0A'
NATIVE_TOOL = ROOT / 'piq-native-arcade/tools/prepare_embedded_runtime011.py'
NATIVE_TOOL_SHA = '64DA0627CBE736D952A39B84FD9D4A9CD4F9C31B257B72A0B10F59E5115A25D7'
HELPER_BUILD = ROOT / 'piq-native-arcade/tools/build_native_helper48.py'
HELPER_BUILD_SHA = 'B12B9CA80188CF4918B2A4974A6088982BF9606EC5E842D8D4C34C25B1A352B6'
for _path, _pin in ((NATIVE_TOOL, NATIVE_TOOL_SHA), (HELPER_BUILD, HELPER_BUILD_SHA)):
    g.b.require(g.file_hash(_path) == _pin, 'Native011 tool identity changed: ' + str(_path))
_spec = importlib.util.spec_from_file_location('tvcoin48_native011', NATIVE_TOOL)
native = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = native
_spec.loader.exec_module(native)
IMMUTABLE_NATIVE = native.legacy.expected_output(native.legacy.PROFILE)
g.b.require(len(IMMUTABLE_NATIVE) == 12, 'Expected twelve fixed legacy runtime resources')
HELPER_MEMBER = native.legacy.PREFIX + native.HELPER_RELATIVE
NATIVE_ADDITIONS = frozenset({HELPER_MEMBER, native.legacy.PREFIX + 'manifest-native011.json',
                             native.legacy.LICENSE_PREFIX + 'NOTICE-native011.md'})
NATIVE_RAW = ROOT / 'piq-native-arcade/build/libs/piq_native_arcade-0.1.1.jar'


def resource_allowed(kind, name):
    if kind == 'native':
        return name in NATIVE_ADDITIONS
    return (name.startswith('assets/' + g.OWNER[kind] + '/') and not name.endswith('.class')
            and '/lang/' not in name and name not in g.a.DRAFTS
            and name.endswith(('.json', '.png', '.ogg', '.wav'))) or (kind == 'fc' and name in EXTRA_FC_JSON)


# Rewrite only this condition from the pinned function. Execute in its original
# module globals so member safety, approval, exact hashes, classes, language and
# required-suite validation are unchanged, and PLAN's default binds the new path.
_resource_condition = """name.startswith('assets/' + OWNER[kind] + '/') and not name.endswith('.class')
                      and not '/lang/' in name and name not in a.DRAFTS
                      and name.endswith(('.json', '.png', '.ogg', '.wav'))"""
_read_plan_source = inspect.getsource(g.read_plan)
g.b.require(_read_plan_source.count(_resource_condition) == 1, 'Pinned resource validation shape changed')
g._tvcoin48_resource_allowed = resource_allowed
exec(compile(_read_plan_source.replace(_resource_condition, '_tvcoin48_resource_allowed(kind, name)'),
             str(Path(__file__).resolve()) + ':pinned_read_plan', 'exec'), g.__dict__)


def metadata(kind, old, compiled):
    expected = copy.deepcopy(tomllib.loads(old[g.b.META].decode('utf-8')))
    mods = {mod['modId']: mod for mod in expected['mods']}
    g.b.require(len(mods) == len(expected['mods']) and
                set(mods) == ({'piq_sfc_arcade', 'piq_sfc_home'} if kind == 'sfc' else {g.OWNER[kind]}),
                'Baseline mod ownership')
    mods[g.OWNER[kind]]['version'] = g.b.VERSIONS[g.OWNER[kind]]
    if kind in ('sfc', 'native'):
        dependencies = expected['dependencies'][g.OWNER[kind]]
        matching = [dependency for dependency in dependencies if dependency['modId'] == 'piq_fc_arcade']
        g.b.require(len(matching) == 1, 'Expected one FC dependency: ' + kind)
        matching[0]['versionRange'] = '[0.31.0-alpha.48,0.32.0)'
    g.b.require(tomllib.loads(compiled[g.b.META].decode('utf-8')) == expected, 'Unapproved metadata: ' + kind)
    manifest, count = re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',
        lambda match: match[1] + g.b.VERSIONS[g.OWNER[kind]].encode(), old[g.b.MANIFEST])
    g.b.require(count == 1 and compiled[g.b.MANIFEST] == manifest, 'Unapproved manifest delta: ' + kind)


g.metadata = metadata
_overlay = g.overlay


def overlay(kind, old, compiled, plan):
    # Exact SHA checks remain in the original overlay. The two newly authorized
    # paths additionally require actual UTF-8 JSON objects, not merely .json names.
    for name in plan['resource_changes'][kind]:
        g.member_name(name)
        g.b.require(resource_allowed(kind, name), 'Resource path/type not authorized: ' + name)
        if name in EXTRA_FC_JSON:
            g.b.require(name in compiled, 'CRT loot resource missing: ' + name)
            value = g.unique_json(compiled[name].decode('utf-8'))
            g.b.require(isinstance(value, dict), 'CRT loot resource must be a UTF-8 JSON object: ' + name)
            g.json.dumps(value, allow_nan=False)  # JSON constants NaN/Infinity are not valid loot JSON.
    if kind == 'native':
        expected = native_additions()
        g.b.require(set(plan['resource_changes'][kind]) == NATIVE_ADDITIONS,
                    'Native011 permits exactly three fixed additions')
        for name, raw in expected.items():
            g.b.require(name not in old and compiled.get(name) == raw, 'Native011 addition differs: ' + name)
        for name in IMMUTABLE_NATIVE:
            g.b.require(name in old and compiled.get(name) == old[name], 'Immutable Native runtime changed: ' + name)
    return _overlay(kind, old, compiled, plan)


g.overlay = overlay
_inputs = g.inputs


def inputs(plan_path):
    g.b.require(g.file_hash(HELPER) == HELPER_SHA, 'Pinned GC018 helper changed after import')
    result = _inputs(plan_path)
    for path, wanted in ((NATIVE_BEFORE, NATIVE_BEFORE_SHA), (NATIVE_TOOL, NATIVE_TOOL_SHA),
                         (HELPER_BUILD, HELPER_BUILD_SHA), (native.LEGACY, native.LEGACY_SHA),
                         (native.HELPER, native.HELPER_SHA)):
        actual = g.file_hash(path)
        g.b.require(actual == wanted, 'Native011 fixed input changed: ' + str(path))
        result[g.rel(path)] = actual
    native_additions()  # Source-to-helper manifest identity, not just a filename.
    g.b.require(g.file_hash(HELPER) == HELPER_SHA, 'Pinned GC018 helper changed during input audit')
    return result


g.inputs = inputs


def native_additions():
    for name, wanted in native.SOURCE_PINS.items():
        g.b.require(g.file_hash(native.PROJECT / name) == wanted, 'Native helper source changed: ' + name)
    raw = g.b.safe(native.HELPER, True).read_bytes()
    additions = native.addition_bytes(raw)
    # Fixed helper has only our four source-owned class families, never nested code/resources.
    helper_entries(raw)
    return additions


def helper_entries(raw):
    g.b.require(len(raw) == native.HELPER_BYTES and g.b.sha(raw) == native.HELPER_SHA,
                'Versioned helper identity differs')
    entries, folded, total = {}, set(), 0
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        g.b.require(len(archive.infolist()) <= 64, 'Versioned helper entry budget')
        for info in archive.infolist():
            name = info.orig_filename
            g.member_name(name)
            g.b.require(name == info.filename and name.casefold() not in folded and not info.flag_bits & 1
                        and not g.stat.S_ISLNK(info.external_attr >> 16), 'Unsafe versioned helper entry')
            folded.add(name.casefold())
            total += info.file_size
            g.b.require(total <= 256 * 1024, 'Versioned helper expansion budget')
            entries[name] = archive.read(info)
    roots = tuple('cn/piq/nativearcade/bridge/' + n for n in
                  ('BridgeProtocol', 'NativeInputPorts', 'NativeArcadeButtons', 'NativeCoreWorker'))
    g.b.require(entries and all(n.endswith('.class') and any(n == p + '.class' or n.startswith(p + '$')
                for p in roots) for n in entries), 'Unexpected versioned helper member')
    g.b.require(all(p + '.class' in entries for p in roots), 'Missing helper source family')
    return entries


_snapshot = g.b.snapshot


def snapshot():
    result = _snapshot()
    for folder in ('tools', 'docs'):
        for path in sorted((ROOT / 'piq-native-arcade' / folder).rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
                result[g.rel(path)] = g.file_hash(path)
    return result


g.b.snapshot = snapshot
_safe_archive = g.safe_archive


def safe_archive(path):
    path = g.b.safe(path, True)
    is_native = path == g.BASE['native'][0] or path == NATIVE_RAW or (
        path.name == g.NAMES['native'] and path.is_relative_to(ROOT / 'piq-fc-arcade/build'))
    if not is_native:
        return _safe_archive(path)
    pin = g.file_hash(path)
    entries, resources = g.a.native_archive(path, IMMUTABLE_NATIVE)
    # Opaque values may ONLY come from CRC/SHA-streamed immutable resources.
    entries.update({name: b'' for name in resources})
    g.b.require(g.file_hash(path) == pin, 'Native archive changed during audit')
    return pin, entries


g.safe_archive = safe_archive


def rewrite(module, function, replacements):
    source = inspect.getsource(getattr(module, function))
    for old, new in replacements:
        g.b.require(source.count(old) == 1, 'Pinned function shape changed: ' + function + ':' + old)
        source = source.replace(old, new)
    exec(compile(source, str(Path(__file__).resolve()) + ':pinned_' + function, 'exec'), module.__dict__)


# Reuse the actual compiled-JAR/source-owner verifier; only the Native archive
# reader differs. Other archives retain the historical 256 MiB expanded limit.
g.b._tvcoin48_archive = safe_archive
rewrite(g.b, 'compiled_part', [('pin, entries = load(path)',
    "pin, entries = _tvcoin48_archive(path) if project == 'piq-native-arcade' else load(path)")])
rewrite(g, 'evidence', [("== 2, 'Separate FC and SFC logs required'", "== len(PROJECT), 'Separate FC, SFC and Native logs required'"),
                        ('FC and SFC complete Gradle logs required', 'FC, SFC and Native complete Gradle logs required')])
_evidence = g.evidence


def helper_evidence(entries, log_path, captured):
    g.a.gradle_log(log_path, captured)
    text = g.b.safe(log_path, True).read_text(encoding='utf-8')
    for task in ('buildNativeHelper011', 'prepareEmbeddedRuntime17', 'prepareEmbeddedRuntime011'):
        g.b.require(re.search(r'^> Task :' + task + r'\s*$', text, re.M), 'Missing actual Native runtime build task: ' + task)
    # This JSON is emitted by the hash-pinned builder only after actual javac succeeds.
    # It may reuse identical jar bytes, but cannot skip source compilation.
    decoder = g.json.JSONDecoder()
    records = []
    for match in re.finditer(r'^\{', text, re.M):
        try:
            value, _ = decoder.raw_decode(text[match.start():])
        except ValueError:
            continue
        if isinstance(value, dict) and value.get('private_protocol') == 4:
            records.append(value)
    expected = {'path': str(native.HELPER.resolve()), 'sha256': native.HELPER_SHA,
                'bytes': native.HELPER_BYTES, 'private_protocol': 4,
                'source_sha256': {str(Path(n)): h for n, h in native.SOURCE_PINS.items()},
                'jna_sha256': '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',
                'dll_loaded': False, 'rom_read': False}
    g.b.require(records == [expected], 'Missing/mismatched fresh helper compiler evidence')
    helper = helper_entries(entries[HELPER_MEMBER])
    for name in helper:
        if not name.startswith('cn/piq/nativearcade/bridge/NativeCoreWorker'):
            # Gradle and the isolated helper javac can emit different debug tables.
            # Helper bytes are pinned to its own fresh builder/source evidence,
            # while fresh_compiler verifies the main JAR against Gradle classes.
            g.b.require(name in entries, 'Helper shared class absent from Native compiler output: ' + name)
    return expected


INSTALLER_SUITE = 'cn.piq.fcarcade.runtime.RuntimeInstallerTest'
INSTALLER_XML = ROOT / 'piq-fc-arcade/build/test-results/test' / ('TEST-' + INSTALLER_SUITE + '.xml')
INSTALLER_PLATFORM_CASE = 'symbolicLinksInTargetOrPackAreRefusedWhenPlatformPermitsFixtureLinks()'
# Require each existing case, not just an aggregate count that could hide a
# dropped regression. Additional future cases must also execute without skips.
INSTALLER_CASES = frozenset(name + '()' for name in (
    'preserveExistingGoodFileAndOnlyCountMissingInstalls',
    'cancelAfterFirstPublicationRollsBackOnlyOwnFiles',
    'missingOrWrongSizedPackMemberIsRejectedBeforeStaging',
    'concurrentProcessLockReturnsBusyWithoutExecutables',
    'truncatedAndOversizedArchivesAreRejected',
    'originalOfflinePackRetainsItsExactNineEntriesIncludingGbaAndOldHelper',
    'packNamesOutsideExactWhitelistAreRejectedIncludingBios',
    'standardCatalogHasNineExactExecutablesAndNoBiosOrRom',
    'foreignReplacementAfterPublicationIsNeverDeletedByRollback',
    'retainedParentIdentityRejectsOrdinaryDirectoryReplacement',
    'inspectAndMissingPackNeverWriteAnything',
    'validPackStagesHashesInstallsAllAndSecondClickDoesNotRewrite',
    'encryptedUnsupportedMethodAndZip64MetadataAreRejected',
    'onlyExactParentDirectoryEntriesAreAllowed',
    'unrelatedLockContentIsPreserved',
    'missingFilesDoNotInventHashedOrTransferredBytes',
    'symbolicLinksInTargetOrPackAreRefusedWhenPlatformPermitsFixtureLinks',
    'wrongExistingVersionBlocksWholeSelectionAndPreservesEveryByte',
    'sameSizeWrongExistingShaBlocksAndIsNotOverwritten',
    'zipSymlinkAndWindowsReparseMetadataAreRejected',
    'installOneRuntimeDoesNotInstallOtherAddons',
    'reportCollectionsCannotBeMutated',
    'cancelWhileStagingLeavesNoExecutableOrPartial',
    'alreadyCancelledAndInterruptedNeverTouchDisk',
    'regularFileWhereRuntimeDirectoryShouldBeIsUnsafeAndPreserved',
    'sameBytesForeignInodeAfterPublicationIsAlsoNotRollbackOwned',
    'callbackFailureAfterPublicationRollsBackRatherThanClaimingSuccess',
    'emptySelectionOrUnsupportedPlatformCannotStartInstall',
    'tamperedPackDataFailsBeforeAnyTargetAndCleansStage',
))


def validate_installer_xml(raw):
    doc = g.b.ET.fromstring(raw)
    g.b.require(doc.tag == 'testsuite' and doc.get('name') == INSTALLER_SUITE,
                'Wrong installer regression suite')
    cases = doc.findall('testcase')
    g.b.require(int(doc.get('tests', '-1')) == len(cases) >= 29
                and int(doc.get('failures', '-1')) == int(doc.get('errors', '-1')) == 0
                and not doc.findall('.//failure') and not doc.findall('.//error'),
                'Installer regression count/failure mismatch')
    names = [case.get('name') for case in cases]
    g.b.require(len(set(names)) == len(names) and INSTALLER_CASES <= set(names)
                and all(isinstance(name, str) and name for name in names)
                and all(case.get('classname') == INSTALLER_SUITE for case in cases),
                'Installer regression case missing/duplicate/foreign')
    skipped = []
    for case in cases:
        flags = case.findall('skipped')
        if flags:
            g.b.require(len(flags) == 1 and case.get('name') == INSTALLER_PLATFORM_CASE,
                        'Unexpected installer regression skip')
            flag = flags[0]
            g.b.require(flag.get('type') == 'org.opentest4j.TestAbortedException'
                        and flag.get('message', '').startswith('Assumption failed: OS account cannot create fixture symlinks: '),
                        'Installer platform skip lacks existing fixture-permission reason')
            skipped.append(case.get('name'))
    g.b.require(int(doc.get('skipped', '-1')) == len(skipped) <= 1
                and len(doc.findall('.//skipped')) == len(skipped),
                'Installer skip summary mismatch')
    return {'tests': len(cases), 'executed': len(cases) - len(skipped),
            'allowed_platform_skip': skipped,
            'required_executed_cases': sorted(INSTALLER_CASES - {INSTALLER_PLATFORM_CASE})}


def installer_evidence(captured):
    path = g.b.safe(INSTALLER_XML, True)
    g.b.require(path.stat().st_mtime_ns >= captured, 'Installer regression XML predates capture')
    raw = path.read_bytes()
    return {'path': g.rel(path), 'sha256': g.b.sha(raw), **validate_installer_xml(raw)}


def evidence(witness_path, witness_sha, plan_path, log_paths):
    proposed, old, report, fence = _evidence(witness_path, witness_sha, plan_path, log_paths)
    captured = report['source_witness']['captured_ns']
    helper = helper_evidence(proposed['native'], log_paths['native'], captured)
    installer = installer_evidence(captured)
    report['native011_helper_compiler'] = helper
    report['installer_exact_regressions'] = installer
    report['native_legacy_resources_unchanged'] = {
        name: {'bytes': identity[0], 'sha256': identity[1]} for name, identity in IMMUTABLE_NATIVE.items()}

    def native_fence():
        fence()
        g.b.require(helper_evidence(proposed['native'], log_paths['native'], captured) == helper,
                    'Native helper evidence drift')
        native_additions()
        g.b.require(installer_evidence(captured) == installer, 'Installer regression evidence drift')
    native_fence()
    return proposed, old, report, native_fence


g.evidence = evidence


def before_files():
    g.b.require(g.file_hash(NATIVE_BEFORE) == NATIVE_BEFORE_SHA, 'Supplemental Native before snapshot changed')
    # The earlier main snapshot wins; a later supplemental snapshot must not erase prior FC changes.
    return g.unique_json(NATIVE_BEFORE.read_bytes())['files'] | g.unique_json(g.SOURCE_BEFORE.read_bytes())['files']


def include_native_resources(plan):
    plan['resource_changes']['native'].update({name: {'old_sha256': None, 'new_sha256': g.b.sha(raw)}
                                               for name, raw in native_additions().items()})
    return plan


g._tvcoin48_before_files = before_files
g._tvcoin48_native_resources = include_native_resources
rewrite(g, 'plan_template', [
    ("before = unique_json(SOURCE_BEFORE.read_bytes())['files']", 'before = _tvcoin48_before_files()'),
    ("'removed_classes': {'fc': [], 'sfc': []}", "'removed_classes': {kind: [] for kind in PROJECT}"),
    ('Core9, Native and GBA are frozen;', 'Core9, old Native runtime and GBA are frozen; Native011 needs a complete fresh build;'),
    ('b.exclusive_json(path, plan)', 'b.exclusive_json(path, _tvcoin48_native_resources(plan))'),
])


def write_output(kind, entries, destination):
    if kind == 'native':
        # Never serialize the immutable-resource placeholders. Only byte-copy the
        # freshly compiled, independently streamed and reviewed complete Native JAR.
        pin, raw_entries = safe_archive(NATIVE_RAW)
        g.b.require(entries == raw_entries, 'Native overlay cannot differ from actual compiled JAR')
        with g.b.safe(NATIVE_RAW, True).open('rb') as source, destination.open('xb') as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
        g.b.require(g.file_hash(destination) == pin and g.file_hash(NATIVE_RAW) == pin, 'Native copy identity drift')
    else:
        with destination.open('xb') as stream:
            stream.write(g.b.jar_bytes(entries))


g._tvcoin48_write_output = write_output
rewrite(g, 'freeze', [
    ("{'fc': args.fc_gradle_log, 'sfc': args.sfc_gradle_log}",
     "{'fc': args.fc_gradle_log, 'sfc': args.sfc_gradle_log, 'native': args.native_gradle_log}"),
    ("with (out / NAMES[kind]).open('xb') as stream: stream.write(b.jar_bytes(entries))",
     '_tvcoin48_write_output(kind, entries, out / NAMES[kind])'),
])
rewrite(g, 'main', [
    ("parser.add_argument('--sfc-gradle-log', type=Path)",
     "parser.add_argument('--sfc-gradle-log', type=Path)\n    parser.add_argument('--native-gradle-log', type=Path)"),
    ('not any((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log))',
     'not any((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log, args.native_gradle_log))'),
    ('all((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log))',
     'all((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log, args.native_gradle_log))'),
    ('Capture and two complete build logs required', 'Capture and three complete build logs required'),
    ('run FC then SFC: check jar', 'run FC then SFC then Native: check jar'),
])

if __name__ == '__main__':
    g.main()
