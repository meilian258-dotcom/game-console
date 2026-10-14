"""Data-only final-archive regressions; no Minecraft, native core or network."""
from pathlib import Path
import struct
import io
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import replay_api as api


def u1(value): return struct.pack('>B', value)
def u2(value): return struct.pack('>H', value)
def u4(value): return struct.pack('>I', value)


def class_bytes(name, methods=(), calls=(), version=True, interface=False):
    """Small structurally complete class fixture; call operands are real CP refs."""
    pool = []
    def put(tag, value):
        pool.append(u1(tag) + value)
        return len(pool)
    def utf(text):
        raw = text.encode('utf-8')
        return put(1, u2(len(raw)) + raw)
    def owner(text): return put(7, u2(utf(text)))
    own, parent = owner(name), owner('java/lang/Object')
    code_name, constant_name = utf('Code'), utf('ConstantValue')
    fields = []
    if version:
        fields.append(u2(0x19) + u2(utf('API_VERSION')) + u2(utf('I')) + u2(1) +
                      u2(constant_name) + u4(2) + u2(put(3, u4(1))))
    members = []
    for method_name, descriptor, access in methods:
        body = b''
        for caller, caller_descriptor, target, operation, signature in calls:
            if (caller, caller_descriptor) != (method_name, descriptor):
                continue
            member = put(12, u2(utf(operation)) + u2(utf(signature)))
            reference = put(10, u2(owner(target)) + u2(member))
            required = api.API.get(target, {}).get((operation, signature), 9)
            body += (b'\xb8' if required & 8 else b'\xb6') + u2(reference)
        body += b'\xb1'
        code = u2(8) + u2(8) + u4(len(body)) + body + u2(0) + u2(0)
        attributes = u2(0) if access & 0x0400 else u2(1) + u2(code_name) + u4(len(code)) + code
        members.append(u2(access) + u2(utf(method_name)) + u2(utf(descriptor)) + attributes)
    return (b'\xca\xfe\xba\xbe' + u2(0) + u2(65) + u2(len(pool) + 1) + b''.join(pool) +
            u2(0x0601 if interface else 0x0031) + u2(own) + u2(parent) + u2(0) +
            u2(len(fields)) + b''.join(fields) + u2(len(members)) + b''.join(members) + u2(0))


BASELINE_CALLS = list(api.OWNER_CALLS)


def complete_fixture():
    entries = {api.DESCRIPTOR: ''.join(k + '=' + v + '\n' for k, v in api.PROPERTIES.items()).encode()}
    methods, wiring = {}, {}
    for name, expected in api.API.items():
        methods[name] = dict(expected)
    for name, method in [('cn/piq/fcarcade/audio/NesAudioPlayer', 'playbackLoop'),
                         ('cn/piq/fcarcade/client/cabinet/CabinetAudio', 'run')]:
        calls = [(method, '()V', api.OUTPUT, 'write', '(' + api.LINE + '[BII)I')]
        calls += [(method, '()V', api.OUTPUT, op, '(' + api.LINE + ')V') for op in ('flush', 'close')]
        methods[name] = {(method, '()V'): 1}
        wiring[name] = calls
    for owner, method, descriptor, callee, name, signature in BASELINE_CALLS:
        methods.setdefault(owner, {}).setdefault((method, descriptor), 9)
        wiring.setdefault(owner, []).append((method, descriptor, callee, name, signature))
    for name, expected in methods.items():
        entries[name + '.class'] = class_bytes(name, [(n, d, a) for (n, d), a in expected.items()],
            wiring.get(name, ()), version=name in (api.GUARD, api.AUDIO, api.PRESENTATION),
            interface=name.endswith(('$Service', '$Observer')))
    return entries


class ReplayApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='replay-api-contract-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jar(self, entries=None, version='0.31.0-alpha.76.53', ident='piq_fc_arcade', name='input.jar'):
        path = self.root / name
        with zipfile.ZipFile(path, 'x') as archive:
            archive.writestr('META-INF/neoforge.mods.toml', f'[[mods]]\nmodId="{ident}"\nversion="{version}"\n')
            for key, value in (entries or {}).items():
                archive.writestr(key, value)
        return path

    def test_explicit_legacy_numeric_boundaries(self):
        for version in ('0.1.0', '0.30.9', '0.31.0-alpha.75', '0.31.0-alpha.76.46', '0.31.0-alpha.76.52'):
            self.assertTrue(api.legacy_version(version), version)
        for version in ('0.31.0-alpha.76.53', '0.31.0-alpha.76.100', '0.31.0-alpha.77', '0.31.0', '0.32.0', '1.0-test', '', 'unknown'):
            self.assertFalse(api.legacy_version(version), version)

    def test_legacy_without_marker_is_accepted(self):
        self.assertTrue(api.inspect_archive(self.jar(version='0.31.0-alpha.76.52'))['legacy'])

    def test_require_rejects_even_legacy_without_marker(self):
        with self.assertRaisesRegex(ValueError, 'missing mandatory'):
            api.inspect_archive(self.jar(version='0.31.0-alpha.76.52'), require=True)

    def test_new_unknown_and_future_without_marker_are_rejected(self):
        for index, version in enumerate(('0.31.0-alpha.76.53', '0.31.0-alpha.76.100', '0.32.0', '1.0-test', 'unexpected')):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'missing mandatory'):
                api.inspect_archive(self.jar(version=version, name=str(index) + '.jar'))

    def test_marker_without_implementation_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing/nonpublic'):
            api.inspect_archive(self.jar({api.DESCRIPTOR: b'format=1\nprovider=piq_fc_arcade\nminecraft=1.21.1\nguard=1\nfinal-pcm=1\nworld-surface=1\n'}))

    def test_fixture_signatures_and_actual_code_calls_pass(self):
        result = api.inspect_archive(self.jar(complete_fixture()))
        self.assertFalse(result['legacy'])
        self.assertEqual(6 + len(BASELINE_CALLS), result['explicitCalls'])

    def test_legacy_with_partial_new_public_api_is_not_exempt(self):
        with self.assertRaisesRegex(ValueError, 'missing mandatory'):
            api.inspect_archive(self.jar({api.GUARD + '.class': b'fake'}, version='0.31.0-alpha.76.52'))

    def test_changed_method_descriptor_is_rejected(self):
        entries = complete_fixture()
        entries[api.AUDIO + '.class'] = class_bytes(api.AUDIO, [('playing', '()I', 9)])
        with self.assertRaisesRegex(ValueError, 'signature/access'):
            api.inspect_archive(self.jar(entries))

    def test_nonstatic_facade_is_rejected(self):
        entries = complete_fixture()
        methods = [(n, d, 1) for n, d in api.API[api.AUDIO]]
        entries[api.AUDIO + '.class'] = class_bytes(api.AUDIO, methods)
        with self.assertRaisesRegex(ValueError, 'signature/access'):
            api.inspect_archive(self.jar(entries))

    def test_removed_explicit_audio_call_is_rejected(self):
        entries = complete_fixture()
        name = 'cn/piq/fcarcade/audio/NesAudioPlayer'
        entries[name + '.class'] = class_bytes(name, [('playbackLoop', '()V', 1)], version=False)
        with self.assertRaisesRegex(ValueError, 'explicit call'):
            api.inspect_archive(self.jar(entries))

    def test_guard_facade_without_real_owner_call_is_rejected(self):
        entries = complete_fixture()
        name = api.NETPLAY + 'JniNetplaySession'
        methods = {(method, descriptor): 9 for owner, method, descriptor, *_ in BASELINE_CALLS if owner == name}
        calls = [(method, descriptor, callee, operation, signature)
                 for owner, method, descriptor, callee, operation, signature in BASELINE_CALLS
                 if owner == name and method != 'finishSave']
        entries[name + '.class'] = class_bytes(name, [(n, d, a) for (n, d), a in methods.items()], calls, version=False)
        with self.assertRaisesRegex(ValueError, 'explicit call.*finishSave'):
            api.inspect_archive(self.jar(entries))

    def test_disconnected_guard_helper_is_rejected(self):
        entries = complete_fixture()
        name = api.CLIENT + 'ClientArcadeSession'
        methods = {(method, descriptor): 9 for owner, method, descriptor, *_ in BASELINE_CALLS if owner == name}
        calls = [(method, descriptor, callee, operation, signature)
                 for owner, method, descriptor, callee, operation, signature in BASELINE_CALLS
                 if owner == name and method != 'replayBlocked']
        entries[name + '.class'] = class_bytes(name, [(n, d, a) for (n, d), a in methods.items()], calls, version=False)
        with self.assertRaisesRegex(ValueError, 'explicit call.*replayBlocked'):
            api.inspect_archive(self.jar(entries))

    def test_direct_audio_bypass_is_rejected(self):
        entries = complete_fixture()
        name = 'cn/piq/fcarcade/audio/OtherAudio'
        entries[name + '.class'] = class_bytes(name, [('write', '()V', 1)],
            [('write', '()V', 'javax/sound/sampled/SourceDataLine', 'write', '([BII)I')], version=False)
        with self.assertRaisesRegex(ValueError, 'bypasses'):
            api.inspect_archive(self.jar(entries))

    def test_optional_replay_dependency_is_rejected(self):
        entries = complete_fixture()
        name = 'cn/piq/fcarcade/Foreign'
        entries[name + '.class'] = class_bytes(name, [('call', '()V', 9)],
            [('call', '()V', 'cn/piq/replaycompat/api/AudioReplay', 'playing', '()Z')], version=False)
        with self.assertRaisesRegex(ValueError, 'optional Replay'):
            api.inspect_archive(self.jar(entries))

    def test_duplicate_descriptor_keys_and_unknown_version_are_rejected(self):
        for index, text in enumerate((b'format=1\nformat=1\n', b'format=2\n')):
            entries = complete_fixture()
            entries[api.DESCRIPTOR] = text
            with self.subTest(index=index), self.assertRaises(ValueError):
                api.inspect_archive(self.jar(entries, name=str(index) + '.jar'))

    def test_other_mod_cannot_supply_api(self):
        with self.assertRaisesRegex(ValueError, 'only be supplied by FC'):
            api.inspect_archive(self.jar({api.GUARD + '.class': b'fake'}, ident='piq_md_home'))

    def test_embedded_dependency_cannot_supply_public_api(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr(api.GUARD + '.class', b'counterfeit')
        with self.assertRaisesRegex(ValueError, 'embedded JAR'):
            api.inspect_archive(self.jar({'META-INF/jarjar/foreign.jar': data.getvalue()}, ident='piq_md_home'))

    def test_wrong_mod_cannot_pass_required_provider_check(self):
        with self.assertRaisesRegex(ValueError, 'Exactly one final FC'):
            api.verify_suite([self.jar(ident='piq_md_home')], require=True)

    def test_version_constant_removed_is_rejected(self):
        entries = complete_fixture()
        expected = api.API[api.AUDIO]
        entries[api.AUDIO + '.class'] = class_bytes(api.AUDIO, [(n, d, a) for (n, d), a in expected.items()], version=False)
        with self.assertRaisesRegex(ValueError, 'API_VERSION'):
            api.inspect_archive(self.jar(entries))

    def test_platform_api_cannot_link_minecraft(self):
        entries = complete_fixture()
        expected = api.API[api.GUARD]
        entries[api.GUARD + '.class'] = class_bytes(api.GUARD, [(n, d, a) for (n, d), a in expected.items()],
            [('playing', '()Z', 'net/minecraft/client/Minecraft', 'getInstance', '()Ljava/lang/Object;')])
        with self.assertRaisesRegex(ValueError, 'pure Java'):
            api.inspect_archive(self.jar(entries))

    def test_cross_jar_class_duplication_is_rejected(self):
        entries = {'cn/piq/shared/Common.class': b'legacy fixture'}
        first = self.jar(entries, version='0.31.0-alpha.76.52')
        second = self.jar(entries, ident='piq_md_home', name='md.jar')
        with self.assertRaisesRegex(ValueError, 'Duplicate public Java class'):
            api.verify_suite([first, second])

    def test_truncated_class_and_false_opcode_operands_rejected_or_ignored(self):
        raw = class_bytes('cn/piq/Fixture', [('test', '()V', 9)])
        for cut in (0, 3, 10, len(raw) - 1):
            with self.subTest(cut=cut), self.assertRaises(ValueError):
                api.parse_class(raw[:cut])
        # A call-shaped operand to sipush is data, never an invocation.
        self.assertEqual([(0x11, b'\xb8\x00'), (0xb1, b'')], list(api.instructions(b'\x11\xb8\x00\xb1')))
        with self.assertRaises(ValueError):
            list(api.instructions(b'\xb8\x00'))

    def test_owner_contract_rejects_signature_drift(self):
        with patch.object(api, 'OWNER_CALLS', [('cn/piq/fcarcade/audio/NesAudioPlayer', 'playbackLoop', '(I)V', api.GUARD, 'playing', '()Z')]):
            with self.assertRaisesRegex(ValueError, 'wiring method missing'):
                api.inspect_archive(self.jar(complete_fixture()))


if __name__ == '__main__':
    unittest.main()
