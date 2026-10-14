"""Fail-closed, data-only final-JAR Replay API and wiring verification.

Never loads Minecraft, executes an artifact, or trusts its capability descriptor alone.
The immutable v1 method descriptors below are the compatibility contract.
"""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import re
import struct
import tomllib
import zipfile

DESCRIPTOR = 'META-INF/game-console/replay-api.properties'
PROPERTIES = {'format': '1', 'provider': 'piq_fc_arcade', 'minecraft': '1.21.1',
              'guard': '1', 'final-pcm': '1', 'world-surface': '1'}
RETRO = 'cn/piq/retro/replay/'
PRESENTATION = 'cn/piq/fcarcade/api/replay/ReplayPresentation'
GUARD = RETRO + 'ReplayGuard'
AUDIO = RETRO + 'ReplayAudio'
OUTPUT = 'cn/piq/fcarcade/audio/ReplayAudioOutput'
RESOURCE = 'Lnet/minecraft/resources/ResourceLocation;'
RENDER = 'Lnet/minecraft/client/renderer/RenderType;'
POSITION = 'Lnet/minecraft/core/BlockPos;'
LINE = 'Ljavax/sound/sampled/SourceDataLine;'
PCM = '(Ljava/lang/Object;Ljava/lang/Object;[BIIII)V'
PUBLIC_STATIC = 0x0001 | 0x0008
API = {
    GUARD: {('install', '(Ljava/util/function/BooleanSupplier;)V'): PUBLIC_STATIC,
            ('playing', '()Z'): PUBLIC_STATIC, ('enterReplay', '()V'): PUBLIC_STATIC,
            ('exitReplay', '()V'): PUBLIC_STATIC, ('epoch', '()J'): PUBLIC_STATIC,
            ('valid', '(J)Z'): PUBLIC_STATIC,
            ('registerEntryCheck', '(Ljava/lang/String;Ljava/lang/Runnable;)V'): PUBLIC_STATIC,
            ('checkEntry', '()V'): PUBLIC_STATIC},
    GUARD + '$Retirement': {('<init>', '()V'): 0x0001, ('block', '(Ljava/lang/Runnable;)Z'): 0x0001},
    AUDIO: {('install', '(L' + AUDIO + '$Service;)V'): PUBLIC_STATIC,
            ('captureToken', '(Ljava/lang/Object;)Ljava/lang/Object;'): PUBLIC_STATIC,
            ('playing', '()Z'): PUBLIC_STATIC, ('capture', PCM): PUBLIC_STATIC,
            ('reset', '(Ljava/lang/Object;)V'): PUBLIC_STATIC, ('close', '(Ljava/lang/Object;)V'): PUBLIC_STATIC},
    AUDIO + '$Service': {('captureToken', '(Ljava/lang/Object;)Ljava/lang/Object;'): 0x0401,
                        ('playing', '()Z'): 0x0401, ('capture', PCM): 0x0401,
                        ('reset', '(Ljava/lang/Object;)V'): 0x0401, ('close', '(Ljava/lang/Object;)V'): 0x0401},
    PRESENTATION: {('install', '(L' + PRESENTATION + '$Observer;)V'): PUBLIC_STATIC,
                   ('material', '(' + RESOURCE + RENDER + ')V'): PUBLIC_STATIC,
                   ('anchor', '(' + POSITION + ')V'): PUBLIC_STATIC,
                   ('clearAnchor', '()V'): PUBLIC_STATIC,
                   ('screenMaterial', '(' + RESOURCE + ')' + RENDER): PUBLIC_STATIC},
    PRESENTATION + '$Observer': {('material', '(' + RESOURCE + RENDER + ')V'): 0x0401,
                               ('anchor', '(' + POSITION + ')V'): 0x0401,
                               ('clearAnchor', '()V'): 0x0401},
    OUTPUT: {('write', '(' + LINE + '[BII)I'): PUBLIC_STATIC,
             ('flush', '(' + LINE + ')V'): PUBLIC_STATIC,
             ('close', '(' + LINE + ')V'): PUBLIC_STATIC},
}


class Reader:
    def __init__(self, data: bytes):
        self.data, self.offset = data, 0

    def take(self, size: int) -> bytes:
        if size < 0 or size > len(self.data) - self.offset:
            raise ValueError('Truncated class structure')
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        return result

    def number(self, size: int) -> int:
        return int.from_bytes(self.take(size), 'big')


def parse_class(data: bytes) -> dict:
    if not 0 < len(data) <= 2 * 1024 * 1024:
        raise ValueError('Oversized or empty class')
    reader = Reader(data)
    if reader.take(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid class magic')
    minor, major = reader.number(2), reader.number(2)
    if minor != 0 or not 45 <= major <= 65:
        raise ValueError('Unsupported class version')
    count = reader.number(2)
    pool = [None] * count
    index = 1
    while index < count:
        tag = reader.number(1)
        if tag == 1:
            value = reader.take(reader.number(2)).decode('utf-8', errors='replace')
        elif tag == 3:
            value = int.from_bytes(reader.take(4), 'big', signed=True)
        elif tag == 4:
            value = reader.take(4)
        elif tag in (5, 6):
            value = reader.take(8)
        elif tag in (7, 8, 16, 19, 20):
            value = reader.number(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (reader.number(2), reader.number(2))
        elif tag == 15:
            value = (reader.number(1), reader.number(2))
        else:
            raise ValueError('Unsupported constant-pool tag')
        pool[index] = (tag, value)
        index += 2 if tag in (5, 6) else 1

    def entry(index, expected):
        if not 0 < index < count or pool[index] is None or pool[index][0] not in expected:
            raise ValueError('Invalid constant-pool reference')
        return pool[index][1]

    def utf(index):
        return entry(index, (1,))

    def classname(index):
        return utf(entry(index, (7,)))

    def attributes(source):
        result = {}
        for _ in range(source.number(2)):
            name = utf(source.number(2))
            value = source.take(source.number(4))
            if name in result:
                raise ValueError('Duplicate class attribute')
            result[name] = value
        return result

    access = reader.number(2)
    name = classname(reader.number(2))
    reader.number(2)
    for _ in range(reader.number(2)):
        classname(reader.number(2))
    fields, methods = {}, {}
    for target in (fields, methods):
        for _ in range(reader.number(2)):
            flags, member_name, descriptor = reader.number(2), utf(reader.number(2)), utf(reader.number(2))
            key = (member_name, descriptor)
            if key in target:
                raise ValueError('Duplicate class member')
            target[key] = {'access': flags, 'attributes': attributes(reader)}
    attributes(reader)
    if reader.offset != len(data):
        raise ValueError('Trailing class bytes')
    for field in fields.values():
        constant = field['attributes'].get('ConstantValue')
        if constant is not None:
            if len(constant) != 2:
                raise ValueError('Invalid constant attribute')
            field['constant'] = entry(int.from_bytes(constant, 'big'), (3, 4, 5, 6, 8))
    for method in methods.values():
        method['calls'] = []
        raw = method['attributes'].get('Code')
        if raw is None:
            continue
        code_reader = Reader(raw)
        code_reader.take(4)
        code = code_reader.take(code_reader.number(4))
        if not 0 < len(code) <= 65535:
            raise ValueError('Invalid Code length')
        for opcode, operand in instructions(code):
            if 0xb6 <= opcode <= 0xb9:
                owner_index, member_index = entry(int.from_bytes(operand[:2], 'big'), (10, 11))
                method_name, descriptor = entry(member_index, (12,))
                method['calls'].append((opcode, classname(owner_index), utf(method_name), utf(descriptor)))
        code_reader.take(code_reader.number(2) * 8)
        attributes(code_reader)
        if code_reader.offset != len(raw):
            raise ValueError('Trailing Code bytes')
    return {'name': name, 'access': access, 'fields': fields, 'methods': methods,
            'strings': [value for item in pool if item is not None for tag, value in [item] if tag == 1]}


def instructions(code: bytes):
    """Walk JVM instructions, never byte-search operands/constant-pool strings as calls."""
    one = {0x10, 0x12, *range(0x15, 0x1a), *range(0x36, 0x3b), 0xa9, 0xbc}
    two = {0x11, 0x13, 0x14, 0x84, *range(0x99, 0xa9), *range(0xb2, 0xb9), 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7}
    offset = 0
    while offset < len(code):
        start = offset
        opcode = code[offset]
        offset += 1
        if opcode >= 0xca:
            raise ValueError('Reserved bytecode opcode')
        size = 1 if opcode in one else 2 if opcode in two else 4 if opcode in (0xb9, 0xba, 0xc8, 0xc9) else 3 if opcode == 0xc5 else 0
        if opcode in (0xaa, 0xab):
            offset += (-offset) % 4
            if offset + 8 > len(code):
                raise ValueError('Truncated switch')
            if opcode == 0xaa:
                if offset + 12 > len(code):
                    raise ValueError('Truncated tableswitch')
                low, high = struct.unpack('>ii', code[offset + 4:offset + 12])
                if high < low:
                    raise ValueError('Invalid tableswitch')
                size = 12 + (high - low + 1) * 4
            else:
                count = struct.unpack('>i', code[offset + 4:offset + 8])[0]
                if count < 0:
                    raise ValueError('Invalid lookupswitch')
                size = 8 + count * 8
        elif opcode == 0xc4:
            if offset >= len(code) or code[offset] not in one | {0x84}:
                raise ValueError('Invalid wide instruction')
            size = 5 if code[offset] == 0x84 else 3
        if size > len(code) - offset:
            raise ValueError('Truncated instruction')
        offset += size
        yield opcode, code[start + 1:offset]


def legacy_version(version: str) -> bool:
    # Narrow explicit numeric legacy grammar, not lexicographic ordering or an
    # unknown-version bypass. All unrecognised/future version families require v1.
    match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+)(?:\.(\d+))?)?', version)
    if not match:
        return False
    major, minor, patch = map(int, match.group(1, 2, 3))
    if (major, minor, patch) < (0, 31, 0):
        return True
    if (major, minor, patch) != (0, 31, 0) or match[4] is None:
        return False
    alpha, revision = int(match[4]), int(match[5] or 0)
    return (alpha, revision) < (76, 53)


def require_call(classes, owner, method, descriptor, callee, name, signature, minimum=1, maximum=None, opcode=None):
    node = classes.get(owner)
    member = None if node is None else node['methods'].get((method, descriptor))
    if member is None:
        raise ValueError('Replay wiring method missing: ' + owner + '.' + method + descriptor)
    matches = [c for c in member['calls'] if c[1:] == (callee, name, signature) and (opcode is None or c[0] == opcode)]
    declaration = classes.get(callee, {}).get('methods', {}).get((name, signature))
    if callee in classes and declaration is None:
        raise ValueError('Replay called method declaration missing: ' + callee + '.' + name + signature)
    if declaration is not None and any((call[0] == 0xb8) != bool(declaration['access'] & 8) for call in matches):
        raise ValueError('Replay invocation static/instance ABI mismatch')
    count = len(matches)
    if count < minimum or maximum is not None and count > maximum:
        raise ValueError('Replay explicit call missing/duplicated: ' + owner + '.' + method + ' -> ' + callee + '.' + name)
    return count


def verify_api(classes: dict, descriptor: bytes) -> dict:
    if len(descriptor) > 4096:
        raise ValueError('Oversized Replay descriptor')
    props = {}
    for line in descriptor.decode('utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key in props:
            raise ValueError('Malformed/duplicate Replay descriptor')
        props[key] = value
    if props != PROPERTIES:
        raise ValueError('Replay descriptor differs from v1 contract')
    for name, expected in API.items():
        node = classes.get(name)
        if node is None or not node['access'] & 1:
            raise ValueError('Missing/nonpublic Replay API: ' + name)
        if name.endswith(('$Service', '$Observer')) and not node['access'] & 0x0200:
            raise ValueError('Replay callback is not an interface')
        if not name.endswith(('$Service', '$Observer')) and (node['access'] & 0x0200 or not node['access'] & 0x0010):
            raise ValueError('Replay facade/retirement must remain a final class')
        for key, required in expected.items():
            method = node['methods'].get(key)
            if method is None or method['access'] & 0x0409 != required & 0x0409 or not method['access'] & 1:
                raise ValueError('Replay API signature/access changed: ' + name + '.' + str(key))
            if not method['access'] & 0x0400 and not method['attributes'].get('Code'):
                raise ValueError('Replay API implementation missing Code')
        if name in (GUARD, AUDIO, PRESENTATION):
            field = node['fields'].get(('API_VERSION', 'I'), {})
            if field.get('constant') != 1 or field.get('access', 0) & 0x0019 != 0x0019:
                raise ValueError('Replay API_VERSION must be public static final 1')
        if name.startswith(RETRO) and any(any(prefix in text for prefix in ('net/minecraft/', 'net/neoforged/', 'javax/sound/', 'org/lwjgl/')) for text in node['strings']):
            raise ValueError('Platform Replay API is not pure Java')
    for name, node in classes.items():
        if any(any(prefix in text for prefix in ('cn/piq/replaycompat/', 'cn.piq.replaycompat.',
                                                'com/replaymod/', 'com.replaymod.', 'reforgedplaymod')) for text in node['strings']):
            raise ValueError('FC must not depend on optional Replay implementation: ' + name)
    calls = verify_wiring(classes)
    return {'apiVersion': 1, 'apiClasses': len(API), 'explicitCalls': calls}


def verify_wiring(classes: dict) -> int:
    checks = 0
    owners = [('cn/piq/fcarcade/audio/NesAudioPlayer', 'playbackLoop'),
              ('cn/piq/fcarcade/client/cabinet/CabinetAudio', 'run')]
    for owner, method in owners:
        checks += require_call(classes, owner, method, '()V', OUTPUT, 'write', '(' + LINE + '[BII)I', 1, 1, 0xb8)
        for operation in ('flush', 'close'):
            checks += require_call(classes, owner, method, '()V', OUTPUT, operation, '(' + LINE + ')V', opcode=0xb8)
    for name, node in classes.items():
        for member in node['methods'].values():
            for call in member['calls']:
                if call[1] == 'javax/sound/sampled/SourceDataLine' and call[2] in ('write', 'flush', 'close') and name != OUTPUT:
                    raise ValueError('Final FC audio bypasses public Replay output: ' + name)
    # Guard/presentation/helper contracts pin real owner methods, not marker text.
    for owner, method, descriptor, callee, name, signature in OWNER_CALLS:
        checks += require_call(classes, owner, method, descriptor, callee, name, signature)
    return checks


OWNER_CALLS = [
    (OUTPUT + '$1', 'token', '(Ljava/lang/Object;)Ljava/lang/Object;', AUDIO, 'captureToken', '(Ljava/lang/Object;)Ljava/lang/Object;'),
    (OUTPUT + '$1', 'capture', PCM, AUDIO, 'capture', PCM),
    (OUTPUT + '$1', 'reset', '(Ljava/lang/Object;)V', AUDIO, 'reset', '(Ljava/lang/Object;)V'),
    (OUTPUT + '$1', 'close', '(Ljava/lang/Object;)V', AUDIO, 'close', '(Ljava/lang/Object;)V'),
    ('cn/piq/fcarcade/audio/NesAudioPlayer', 'clear', '()V', OUTPUT, 'flush', '(' + LINE + ')V'),
    ('cn/piq/fcarcade/audio/NesAudioPlayer', 'close', '()V', OUTPUT, 'flush', '(' + LINE + ')V'),
    ('cn/piq/fcarcade/audio/NesAudioPlayer', 'close', '()V', OUTPUT, 'close', '(' + LINE + ')V'),
    (PRESENTATION, 'screenMaterial', '(' + RESOURCE + ')' + RENDER,
     'cn/piq/fcarcade/client/ScreenRenderMaterial', 'of', '(' + RESOURCE + ')' + RENDER),
    ('cn/piq/fcarcade/client/ScreenRenderMaterial', 'of', '(' + RESOURCE + ')' + RENDER,
     PRESENTATION, 'material', '(' + RESOURCE + RENDER + ')V'),
    ('cn/piq/fcarcade/client/HomeScreenRenderPose', 'apply',
     '(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;Lnet/minecraft/world/level/Level;' + POSITION + ')Z',
     PRESENTATION, 'anchor', '(' + POSITION + ')V'),
    ('cn/piq/fcarcade/client/HomeVideoDisplay', 'render',
     '(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;' + RESOURCE + POSITION + 'Ljava/util/UUID;' + POSITION + 'Ljava/util/UUID;Ljava/util/UUID;' + RESOURCE + 'D)Z',
     PRESENTATION, 'anchor', '(' + POSITION + ')V'),
    ('cn/piq/fcarcade/client/HomeVideoDisplay', 'render',
     '(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;' + RESOURCE + POSITION + 'Ljava/util/UUID;' + POSITION + 'Ljava/util/UUID;Ljava/util/UUID;' + RESOURCE + 'D)Z',
     PRESENTATION, 'clearAnchor', '()V'),
    ('cn/piq/fcarcade/client/ArcadeBlockScreenRenderer', 'render',
     '(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;Lcn/piq/fcarcade/client/ClientArcadeSession;' + POSITION + ')V',
     PRESENTATION, 'clearAnchor', '()V'),
    ('cn/piq/fcarcade/client/FcHomeWatchDisplay', 'render',
     '(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;Lcn/piq/fcarcade/cabinet/WatchDescriptor;' + RESOURCE + 'FI)V',
     PRESENTATION, 'clearAnchor', '()V'),
    ('cn/piq/fcarcade/client/PrivateHomeClient', 'render',
     '(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;)V', PRESENTATION, 'clearAnchor', '()V'),
]

# Concrete v1 owner contract. These are deliberately reviewed descriptors, not
# discovery of whichever call happens to exist in the current artifact. Internal
# helper chains are pinned at both ends so a disconnected facade cannot pass.
CLIENT = 'cn/piq/fcarcade/client/'
NETPLAY = 'cn/piq/fcarcade/netplay/'
EVENT = 'Lnet/neoforged/neoforge/client/event/'
RENDER_EVENT = '(' + EVENT + 'RenderLevelStageEvent;)V'
FUTURE = '()Ljava/util/concurrent/CompletableFuture;'


def _owner_calls(owner, methods, callee, name, signature):
    return [(owner, method, descriptor, callee, name, signature) for method, descriptor in methods]


OWNER_CALLS += _owner_calls(CLIENT + 'ClientArcadeEvents', [
    ('refreshSimulationSelection', '(Z)V'), ('updateTransfers', '()V'),
    ('applySession', '(Lcn/piq/fcarcade/ArcadeSessionPayload;)V'),
    ('applySessionReady', '(Lcn/piq/fcarcade/ArcadeSessionPayload;Ljava/util/UUID;Ljava/util/UUID;Z)V'),
    ('promptExit', '(Lcn/piq/fcarcade/ArcadeExitPromptPayload;)V'),
    ('promptResume', '(Lcn/piq/fcarcade/ArcadeResumePromptPayload;)V'),
    ('offerMultiplayer', '(Lcn/piq/fcarcade/ArcadeMultiplayerOfferPayload;)V'),
    ('requestJoinApproval', '(Lcn/piq/fcarcade/ArcadeJoinApprovalPayload;)V'),
    ('requestHomeApproval', '(Lcn/piq/fcarcade/ArcadeJoinApprovalPayload;)V'),
], GUARD, 'playing', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'ClientArcadeEvents', [
    ('onRenderFrame', '(' + EVENT + 'RenderFrameEvent$Pre;)V'),
], GUARD + '$Retirement', 'block', '(Ljava/lang/Runnable;)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'ClientArcadeSession', [
    ('join', '(' + POSITION + 'Lcn/piq/fcarcade/session/ArcadeMode;Lcn/piq/fcarcade/session/ArcadeRole;JIIIILjava/lang/String;Ljava/lang/String;IZLcn/piq/fcarcade/session/NesCoreVariant;)V'),
    ('update', '()V'), ('startWorker', '(Z)V'), ('restartLockstep', '(I)V'),
], CLIENT + 'ClientArcadeSession', 'replayBlocked', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'ClientArcadeSession', [('replayBlocked', '()Z')], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'PrivateHomeClient', [
    ('start', '(Lcn/piq/fcarcade/client/PrivateHomeClient$Target;Ljava/lang/String;Lcn/piq/retro/libretro/LibretroRuntimes$Backend;)Ljava/lang/String;'),
    ('startCartridge', '(' + RESOURCE + POSITION + 'Ljava/nio/file/Path;)Ljava/lang/String;'),
    ('stop', '(Ljava/lang/String;)V'),
], GUARD, 'playing', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'PrivateHomeClient', [
    ('tick', '(' + EVENT + 'ClientTickEvent$Pre;)V'), ('sample', '()V'),
    ('update', '()V'), ('refreshKeyboard', '()V'), ('renderOwned', RENDER_EVENT),
], GUARD + '$Retirement', 'block', '(Ljava/lang/Runnable;)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'PrivateHomeClient', [('render', RENDER_EVENT)],
                          CLIENT + 'PrivateHomeClient', 'renderOwned', RENDER_EVENT)
OWNER_CALLS += _owner_calls(CLIENT + 'PrivateHomeClient', [('retireForReplay', '()V')],
                          CLIENT + 'privateplay/PrivateEngine', 'stopWithoutSave', FUTURE)
OWNER_CALLS += _owner_calls(CLIENT + 'watch/WatchClient', [
    ('tick', '(' + EVENT + 'ClientTickEvent$Post;)V'), ('render', RENDER_EVENT),
], GUARD + '$Retirement', 'block', '(Ljava/lang/Runnable;)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'watch/WatchClient', [
    ('prepareNext', '()V'), ('dispatchDemand', '(Lcn/piq/fcarcade/cabinet/WatchNetwork$HostDemand;)V'),
    ('admit', '(Lcn/piq/fcarcade/cabinet/WatchNetwork$Start;Lcn/piq/fcarcade/cabinet/WatchNetwork$NetplayStart;)Lcn/piq/fcarcade/client/watch/WatchClient$Receiver;'),
], GUARD, 'playing', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'watch/WatchClient', [
    ('live', '(Lcn/piq/fcarcade/client/watch/WatchClient$Receiver;)Z'),
], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'cabinet/CabinetClientBackends', [
    ('tick', '(' + EVENT + 'ClientTickEvent$Post;)V'), ('render', RENDER_EVENT),
], GUARD + '$Retirement', 'block', '(Ljava/lang/Runnable;)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'cabinet/CabinetClientBackends', [
    ('startGame', '(Ljava/nio/file/Path;Z)V'),
    ('startConfiguredServer', '(Lcn/piq/fcarcade/cabinet/CabinetNetwork$Launch;)V'),
], GUARD, 'playing', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'cabinet/CabinetClientBackends', [('current', '()Z')], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'NetplaySaveClient$Channel', [
    ('save', '([B)Ljava/util/concurrent/CompletableFuture;'), ('finish', FUTURE),
    ('message', '(III[BLjava/lang/String;)V'), ('receive', '(Lcn/piq/fcarcade/netplay/NetplaySaveNetwork$Message;)V'),
], CLIENT + 'NetplaySaveClient$Channel', 'replayBlocked', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'NetplaySaveClient$Channel', [('replayBlocked', '()Z')], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(NETPLAY + 'NetplaySaveNetwork', [
    ('send', '(Lnet/minecraft/network/Connection;Lcn/piq/fcarcade/netplay/NetplaySaveNetwork$Message;Z)V'),
], GUARD, 'playing', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'privateplay/FcPrivateEngine', [
    ('stopWithoutSave', FUTURE),
], CLIENT + 'privateplay/FcPrivateEngine', 'stopAndSave', FUTURE)
OWNER_CALLS += _owner_calls(CLIENT + 'privateplay/FcPrivateEngine', [
    ('stopAndSave', FUTURE),
    ('run', '(Ljava/nio/file/Path;Lcn/piq/fcarcade/client/privateplay/PrivateSaveStore;Lcn/piq/fcarcade/client/privateplay/FcPrivateEngine$CoreFactory;Ljava/lang/String;Ljava/lang/String;)V'),
], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'privateplay/PrivateSaveStore', [
    ('save', '(Lcn/piq/fcarcade/client/privateplay/PrivateSaveStore$Key;[B[B)V'),
], CLIENT + 'privateplay/PrivateSaveStore', 'requireLive', '()V')
OWNER_CALLS += _owner_calls(CLIENT + 'privateplay/PrivateSaveStore', [('requireLive', '()V')], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(NETPLAY + 'NetplayProcess', [
    ('checkpoint', FUTURE), ('saveNow', FUTURE), ('start', '()V'), ('run', '()V'),
    ('closeInternal', '()V'),
], NETPLAY + 'NetplayProcess', 'replayValid', '()Z')
OWNER_CALLS += _owner_calls(NETPLAY + 'NetplayProcess', [('closeWithoutSave', '()V')],
                          NETPLAY + 'NetplayProcess', 'close', '()V')
OWNER_CALLS += _owner_calls(NETPLAY + 'NetplayProcess', [('replayValid', '()Z'), ('close', '()V')], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(NETPLAY + 'JniNetplaySession', [
    ('start', '()V'), ('checkpoint', FUTURE), ('saveNow', FUTURE),
    ('receive', '(Lcn/piq/fcarcade/netplay/NetplayChunk;I)V'), ('close', '()V'),
    ('run', '()V'), ('handleCapture', '()V'), ('finishSave', '()V'),
], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'ClientNesWorker', [
    ('slice', '()Z'), ('offer', '(Lcn/piq/fcarcade/client/ClientNesWorker$Command;)Z'),
], GUARD, 'valid', '(J)Z')
OWNER_CALLS += _owner_calls(CLIENT + 'ClientRomTransfers', [
    ('submitIo', '(Ljava/util/concurrent/Callable;Ljava/util/function/BiConsumer;)V'),
], GUARD, 'playing', '()Z')
OWNER_CALLS += _owner_calls(CLIENT + 'ClientRomTransfers', [
    ('submitIo', '(Ljava/util/concurrent/Callable;Ljava/util/function/BiConsumer;)V'),
], GUARD, 'valid', '(J)Z')


def inspect_archive(path: Path, require=False) -> dict:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP members in Replay provider')
        # A loader-visible nested dependency must not supply a second API. Raw
        # worker/runtime resources are not ModLauncher classpath dependencies.
        for name in names:
            if not name.startswith('META-INF/jarjar/') or not name.endswith('.jar'):
                continue
            if jar.getinfo(name).file_size > 32 * 1024 * 1024:
                raise ValueError('Oversized embedded JAR during Replay ownership check')
            with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                nested_names = nested.namelist()
                if len(nested_names) > 50000 or len(nested_names) != len(set(nested_names)):
                    raise ValueError('Invalid embedded JAR entry count')
                if any(n.startswith('META-INF/jarjar/') and n.endswith('.jar') for n in nested_names):
                    raise ValueError('Nested dependency depth exceeds Replay ownership check')
                if any(n.startswith(RETRO) or n.startswith('cn/piq/fcarcade/api/replay/') or n == DESCRIPTOR for n in nested_names):
                    raise ValueError('Replay public API must not be bundled inside embedded JAR')
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode('utf-8-sig'))
        mods = metadata.get('mods', [])
        fc = [m for m in mods if m.get('modId') == 'piq_fc_arcade']
        public = [name for name in names if name.endswith('.class') and (name.startswith(RETRO) or name.startswith('cn/piq/fcarcade/api/replay/'))]
        if not fc:
            if public or DESCRIPTOR in names:
                raise ValueError('Replay public classes/descriptor may only be supplied by FC')
            return {'legacy': True, 'provider': False, 'classes': names}
        if len(fc) != 1:
            raise ValueError('Duplicate FC identity')
        if any(item.get('modId') in ('piq_replay_compat', 'reforgedplaymod', 'replaymod')
               for item in metadata.get('dependencies', {}).get('piq_fc_arcade', [])):
            raise ValueError('FC metadata must not depend on optional Replay implementation')
        needed = require or not legacy_version(fc[0].get('version', '')) or bool(public) or DESCRIPTOR in names
        if not needed:
            return {'legacy': True, 'provider': True, 'classes': names}
        if DESCRIPTOR not in names:
            raise ValueError('Final FC JAR missing mandatory Replay API descriptor')
        classes = {}
        for name in names:
            if name.startswith('cn/piq/') and name.endswith('.class'):
                if jar.getinfo(name).file_size > 2 * 1024 * 1024:
                    raise ValueError('Oversized FC class')
                node = parse_class(jar.read(name))
                if node['name'] + '.class' != name:
                    raise ValueError('Class path/name mismatch')
                classes[node['name']] = node
        result = verify_api(classes, jar.read(DESCRIPTOR))
        return dict(result, legacy=False, provider=True, classes=names)


def verify_suite(paths: list[Path], require=False) -> list[dict]:
    seen = set()
    results = []
    for path in paths:
        result = inspect_archive(path, require=require)
        own = {name for name in result.pop('classes') if name.startswith('cn/piq/') and name.endswith('.class')}
        if seen & own:
            raise ValueError('Duplicate public Java class ownership across JARs')
        seen.update(own)
        results.append({'file': path.name, **result})
    if require and sum(result['provider'] for result in results) != 1:
        raise ValueError('Exactly one final FC Replay provider is required')
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, action='append', required=True)
    parser.add_argument('--require', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify_suite(args.jar, args.require), sort_keys=True))


if __name__ == '__main__':
    main()
