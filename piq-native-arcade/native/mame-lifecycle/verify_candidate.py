"""Isolated Windows x64 libretro lifecycle checks, not Java/JNI or Minecraft tests.

Python 3.11+, standard library only. Pass a reviewed DLL and its exact SHA-256.
Only receipts/*.json and summary.json are intended as public CI artifacts. Raw
native stdout/stderr are discarded; staging is private, disposable test data.
Importing this module, --help, and its unit tests never load the candidate DLL.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import ctypes as c
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import sys
import threading
import time
import zipfile

REPO = Path(__file__).resolve().parents[3]
GENERATOR = REPO / "piq-native-arcade/tools/make_diagnostic_rom.py"
HEADER = REPO / "piq-fc-arcade/native/libretro-jni/libretro.h"
GENERATOR_SHA = "664f3905f0750c5eafb1a6bf93826ec2701d7f76ee6277224fc68911acf205cb"
HEADER_SHA = "5875414c47d8af4facf118c184b40b0e311285333a46bbfe8221a853efe5ba7a"
OLD_CORE_SHA = "6172a988ab67fe68f4177a6fc8fbb82619eb2044c330930f0f572f7b1edc2301"
PROFILES = ("gcc-static", "clang64-shared")
RUNTIME_NAME = "libc++.dll"
RUNTIME_BYTES = 1659392
RUNTIME_SHA = "7344daed05388589e9bd691ed1d30c568c374da4b8b6a12e1502185948c03cd4"
# Exact names observed in the prior MAME candidate and pinned official libc++.
# Do not accept arbitrary api-* DLLs or new non-system dependencies.
SYSTEM_IMPORTS = frozenset(("kernel32.dll", "msvcrt.dll", "shell32.dll", "shlwapi.dll",
    "ws2_32.dll", "wsock32.dll", "api-ms-win-crt-convert-l1-1-0.dll",
    "api-ms-win-crt-environment-l1-1-0.dll", "api-ms-win-crt-filesystem-l1-1-0.dll",
    "api-ms-win-crt-heap-l1-1-0.dll", "api-ms-win-crt-locale-l1-1-0.dll",
    "api-ms-win-crt-math-l1-1-0.dll", "api-ms-win-crt-multibyte-l1-1-0.dll",
    "api-ms-win-crt-private-l1-1-0.dll", "api-ms-win-crt-runtime-l1-1-0.dll",
    "api-ms-win-crt-stdio-l1-1-0.dll", "api-ms-win-crt-string-l1-1-0.dll",
    "api-ms-win-crt-time-l1-1-0.dll", "api-ms-win-crt-utility-l1-1-0.dll"))
_PROCESS_RUNTIME_OWNERS = []  # OS reclaims the dependency and read lock at child exit.
CASES = ("three-cycles", "no-load", "double-deinit", "failed-double-deinit",
         "success-double-deinit")
LINGER_SECONDS = 330
SAMPLE_SECONDS = 5
SETTLE_SECONDS = 6
RUN_FRAMES = 180
MAX_WORKERS = 2
CASE_TIMEOUT_SECONDS = 600
PINS = {key: b"disabled" for key in (
    b"mame_buttons_profiles", b"mame_thread_mode", b"mame_cheats_enable",
    b"mame_throttle", b"mame_boot_to_bios", b"mame_boot_to_osd",
    b"mame_read_config", b"mame_write_config", b"mame_auto_save")}
PHASES = frozenset(("baseline", "dll-loaded", "initializing", "loading-content",
    "loaded", "expected-load-rejection", "running", "unloading-game",
    "deinit", "after-deinit", "free-library", "after-free", "linger",
    "cycle-completed", "completed", "failed"))
ERRORS = frozenset(("invalid-arguments", "invalid-sha", "wrong-platform",
    "unsafe-path", "existing-output", "core-sha-mismatch", "invalid-pe",
    "source-pin-mismatch", "invalid-launch", "content-sha-mismatch",
    "frontend-callback", "required-options", "api-version", "load-result",
    "no-media", "dll-not-unmapped", "os-observation", "resource-recovery",
    "invalid-events", "runtime-identity", "runtime-conflict", "dependency-imports", "internal-error"))
NUMBER_FIELDS = frozenset(("cycle", "elapsed_seconds", "call", "milliseconds",
    "video_callbacks", "video_frames", "audio_frames", "run_frames",
    "thread_count", "new_threads", "candidate_start_threads",
    "unmapped_start_threads", "thread_query_errors", "handle_count",
    "handle_delta", "module_count", "module_delta", "candidate_modules",
    "linger_seconds", "affinity_cpus"))
BOOL_FIELDS = frozenset(("loaded", "recovered"))


class ProbeError(Exception):
    """Only fixed, path-free error codes may leave the isolated test process."""
    def __init__(self, code):
        self.code = code if code in ERRORS else "internal-error"
        super().__init__(self.code)


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def candidate_sha(value):
    value = value.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value) or value == OLD_CORE_SHA:
        raise ProbeError("invalid-sha")
    return value


def safe_path(path):
    # Do not resolve first: that would erase symlinks/junctions being checked.
    result = Path(os.path.abspath(path))
    for part in (result, *result.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ProbeError("unsafe-path")
    return result


def profile_name(value):
    if value not in PROFILES:
        raise ProbeError("invalid-arguments")
    return value


def pe_imports(path):
    """Bounded static PE reader, reused from the existing candidate inspection.

    Reads normal and delayed import names, not native code. Final native TLS and
    linked runtime symbol checks are separate build gates, not inferred here.
    """
    size = path.stat().st_size
    try:
        with path.open("rb") as source:
            def read(offset, count):
                if offset < 0 or count < 0 or offset + count > size:
                    raise ProbeError("invalid-pe")
                source.seek(offset)
                data = source.read(count)
                if len(data) != count:
                    raise ProbeError("invalid-pe")
                return data
            nt = struct.unpack("<I", read(60, 4))[0]
            machine, sections, _, _, _, optional_bytes, flags = struct.unpack("<HHIIIHH", read(nt + 4, 20))
            optional = nt + 24
            if (read(0, 2) != b"MZ" or read(nt, 4) != b"PE\0\0" or machine != 0x8664
                    or struct.unpack("<H", read(optional, 2))[0] != 0x20b or not flags & 0x2000
                    or not 1 <= sections <= 96 or not 240 <= optional_bytes <= 4096):
                raise ProbeError("invalid-pe")
            image_base = struct.unpack("<Q", read(optional + 24, 8))[0]
            header_bytes = struct.unpack("<I", read(optional + 60, 4))[0]
            if not optional + optional_bytes + sections * 40 <= header_bytes <= size:
                raise ProbeError("invalid-pe")
            if not 14 <= struct.unpack("<I", read(optional + 108, 4))[0] <= 16:
                raise ProbeError("invalid-pe")
            mappings = []
            for index in range(sections):
                data = read(optional + optional_bytes + index * 40, 40)
                virtual_bytes, rva, disk_bytes, disk = struct.unpack_from("<IIII", data, 8)
                if disk + disk_bytes > size:
                    raise ProbeError("invalid-pe")
                mappings.append((rva, virtual_bytes, disk, disk_bytes))
            def offset(rva, count=1):
                if 0 <= rva < header_bytes and rva + count <= header_bytes:
                    return rva
                candidates = [disk + rva - start for start, _, disk, length in mappings
                              if start <= rva and rva + count <= start + length]
                if len(candidates) != 1:
                    raise ProbeError("invalid-pe")
                return candidates[0]
            def string(rva):
                data = bytearray()
                for index in range(260):
                    byte = read(offset(rva + index), 1)
                    if byte == b"\0":
                        name = data.decode("ascii").lower()
                        if not re.fullmatch(r"[a-z0-9+_.-]+\.dll", name):
                            raise ProbeError("dependency-imports")
                        return name
                    data += byte
                raise ProbeError("invalid-pe")
            result = {}
            for key, directory, width in (("imports", 1, 20), ("delayed_imports", 13, 32)):
                start, length = struct.unpack("<II", read(optional + 112 + directory * 8, 8))
                names = []
                if bool(start) != bool(length):
                    raise ProbeError("invalid-pe")
                if start:
                    for index in range(min(length // width, 512)):
                        entry = struct.unpack("<" + "I" * (width // 4), read(offset(start + index * width, width), width))
                        if not any(entry):
                            break
                        if directory == 13 and entry[0] not in (0, 1):
                            raise ProbeError("invalid-pe")
                        name_rva = entry[3] if directory == 1 else entry[1] if entry[0] else entry[1] - image_base
                        names.append(string(name_rva))
                    else:
                        raise ProbeError("invalid-pe")
                if len(names) != len(set(names)):
                    raise ProbeError("invalid-pe")
                result[key] = sorted(names)
            return result
    except (OSError, UnicodeError, struct.error) as error:
        raise ProbeError("invalid-pe") from error


def verify_core(path, expected, profile="gcc-static"):
    profile_name(profile)
    path = safe_path(path)
    if not path.is_file() or sha(path) != candidate_sha(expected):
        raise ProbeError("core-sha-mismatch")
    size = path.stat().st_size
    with path.open("rb") as stream:
        dos = stream.read(64)
        if len(dos) != 64 or dos[:2] != b"MZ":
            raise ProbeError("invalid-pe")
        offset = struct.unpack_from("<I", dos, 0x3C)[0]
        if offset < 64 or offset > min(size - 264, 1024 * 1024):
            raise ProbeError("invalid-pe")
        stream.seek(offset)
        pe = stream.read(264)
    if (pe[:4] != b"PE\0\0" or struct.unpack_from("<H", pe, 4)[0] != 0x8664
            or struct.unpack_from("<H", pe, 20)[0] < 240
            or not struct.unpack_from("<H", pe, 22)[0] & 0x2000
            or struct.unpack_from("<H", pe, 24)[0] != 0x20B):
        raise ProbeError("invalid-pe")
    image_size = struct.unpack_from("<I", pe, 24 + 56)[0]
    if not image_size or image_size > 2 ** 31:
        raise ProbeError("invalid-pe")
    result = {"machine": "amd64", "format": "pe32+", "bytes": size,
              "image_bytes": image_size}
    if profile == "clang64-shared":
        imports = pe_imports(path)
        names = set(imports["imports"]) | set(imports["delayed_imports"])
        if RUNTIME_NAME not in imports["imports"] or names - SYSTEM_IMPORTS - {RUNTIME_NAME}:
            raise ProbeError("dependency-imports")
        result.update(imports)
    return result


def verify_runtime(path):
    path = safe_path(path)
    if (path.name != RUNTIME_NAME or not path.is_file() or path.stat().st_size != RUNTIME_BYTES
            or sha(path) != RUNTIME_SHA):
        raise ProbeError("runtime-identity")
    verify_core(path, RUNTIME_SHA)
    imports = pe_imports(path)
    if not imports["imports"] or set(imports["imports"] + imports["delayed_imports"]) - SYSTEM_IMPORTS:
        raise ProbeError("dependency-imports")
    return {"file": RUNTIME_NAME, "bytes": RUNTIME_BYTES, "sha256": RUNTIME_SHA}


def save_new(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write("\n")


def make_content(root):
    if sha(GENERATOR) != GENERATOR_SHA or sha(HEADER) != HEADER_SHA:
        raise ProbeError("source-pin-mismatch")
    spec = importlib.util.spec_from_file_location("original_diagnostic_rom", GENERATOR)
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    good = root / "original" / "invaders.zip"
    generated = generator.make(good)
    missing = root / "missing" / "invaders.zip"
    missing.parent.mkdir()
    # Retain just one ORIGINAL chip; the known driver must reject the other
    # three missing chips. No commercial game or BIOS is used in either case.
    with zipfile.ZipFile(good) as source, zipfile.ZipFile(missing, "x") as target:
        entry = source.infolist()[0]
        target.writestr(entry, source.read(entry))
    return {"generator_sha256": GENERATOR_SHA, "header_sha256": HEADER_SHA,
            "original_zip_sha256": sha(good), "missing_zip_sha256": sha(missing),
            "original_firmware_sha256": generated["original_firmware_sha256"].lower()}


def prepare(core, expected, output, profile="gcc-static"):
    profile_name(profile)
    expected = candidate_sha(expected)
    core = safe_path(core)
    pe = verify_core(core, expected, profile)
    runtime = verify_runtime(core.parent / RUNTIME_NAME) if profile == "clang64-shared" else None
    output = safe_path(output)
    if output.exists():
        raise ProbeError("existing-output")
    output.mkdir(parents=True, exist_ok=False)
    (output / "receipts").mkdir()
    staging = output / "staging"
    staging.mkdir()
    shutil.copyfile(core, staging / "core.dll")
    verify_core(staging / "core.dll", expected, profile)
    if runtime:
        shutil.copyfile(core.parent / RUNTIME_NAME, staging / RUNTIME_NAME)
        verify_runtime(staging / RUNTIME_NAME)
    content = make_content(staging)
    for case in CASES:
        work = staging / case
        (work / "content").mkdir(parents=True)
        (work / "save").mkdir()
        kind = "missing" if case == "failed-double-deinit" else "original"
        shutil.copyfile(staging / kind / "invaders.zip", work / "content/invaders.zip")
        save_new(work / "launch.json", {"schema": 1, "case": case,
            "parent_pid": os.getpid(), "core_sha256": expected,
            "content_sha256": content[kind + "_zip_sha256"], "profile": profile,
            **({"runtime": runtime} if runtime else {})})
    return {"schema": 1, "scope": "native-libretro-only", "core_sha256": expected, "profile": profile,
            **({"runtime": runtime} if runtime else {}),
            "verifier_sha256": sha(Path(__file__)),
            "pe": pe, "inputs": content, "max_concurrent_children": MAX_WORKERS,
            "linger_seconds_per_close": LINGER_SECONDS, "run_frames_per_load": RUN_FRAMES,
            "java_jni_verified": False, "minecraft_verified": False}


# Signatures match the pinned libretro.h, including size_t and C bool. Decline
# the variadic logging interface: a Python CFUNCTYPE is not a C variadic shim.
class GameInfo(c.Structure):
    _fields_ = [("path", c.c_char_p), ("data", c.c_void_p),
                ("size", c.c_size_t), ("meta", c.c_char_p)]


class Variable(c.Structure):
    _fields_ = [("key", c.c_char_p), ("value", c.c_char_p)]


ENV = c.CFUNCTYPE(c.c_bool, c.c_uint, c.c_void_p)
VIDEO = c.CFUNCTYPE(None, c.c_void_p, c.c_uint, c.c_uint, c.c_size_t)
AUDIO = c.CFUNCTYPE(None, c.c_int16, c.c_int16)
BATCH = c.CFUNCTYPE(c.c_size_t, c.POINTER(c.c_int16), c.c_size_t)
POLL = c.CFUNCTYPE(None)
INPUT = c.CFUNCTYPE(c.c_int16, c.c_uint, c.c_uint, c.c_uint, c.c_uint)


class Frontend:
    def __init__(self, root):
        self.system = str(root / "content").encode("utf-8")
        self.save = str(root / "save").encode("utf-8")
        self.values = {}
        self.error = False
        self.video_callbacks = self.video_frames = self.audio_frames = 0
        self.callbacks = (ENV(self.environment), VIDEO(self.video), AUDIO(self.audio),
                          BATCH(self.batch), POLL(lambda: None), INPUT(lambda *args: 0))

    def environment(self, command, data):
        try:
            if command in (15, 16) and not data:
                return True
            if command in (9, 30, 31):
                if not data:
                    return False
                c.cast(data, c.POINTER(c.c_char_p))[0] = self.save if command == 31 else self.system
                return True
            if command == 16:
                entries = c.cast(data, c.POINTER(Variable))
                values = {}
                for index in range(1024):
                    item = entries[index]
                    if not item.key:
                        self.values = values
                        return True
                    choices = (item.value or b"").partition(b";")[2].strip().split(b"|")
                    chosen = PINS.get(item.key, choices[0])
                    if not choices[0] or chosen not in choices:
                        raise ProbeError("frontend-callback")
                    values[item.key] = chosen
                raise ProbeError("frontend-callback")
            if command == 15:
                item = c.cast(data, c.POINTER(Variable)).contents
                item.value = self.values.get(item.key)
                return True
            if not data:
                return False
            if command in (17, 0x10031):
                c.cast(data, c.POINTER(c.c_bool))[0] = False
                return True
            if command in (39, 52):
                c.cast(data, c.POINTER(c.c_uint))[0] = 0  # English, legacy options.
                return True
            if command == 3:
                c.cast(data, c.POINTER(c.c_bool))[0] = True
                return True
            if command == 10:
                return c.cast(data, c.POINTER(c.c_uint))[0] in (0, 1, 2)
            if command in (11, 18, 32, 35, 37, 55):
                return True  # Metadata, no native frontend-owned resources.
            # No hardware, VFS, async callbacks, variadic logger or disk access.
            return False
        except Exception:
            self.error = True
            return False

    def video(self, data, width, height, pitch):
        self.video_callbacks += 1
        if data == c.c_void_p(-1).value:  # RETRO_HW_FRAME_BUFFER_VALID was not negotiated.
            self.error = True
            return
        if data and width and height and pitch:
            self.video_frames += 1

    def audio(self, left, right):
        self.audio_frames += 1

    def batch(self, samples, frames):
        self.audio_frames += frames
        return frames

    def check(self):
        if self.error:
            raise ProbeError("frontend-callback")


def bind(library, name, result, args):
    function = getattr(library, name)
    function.restype, function.argtypes = result, args
    return function


def bind_core(core, frontend):
    for name, result, params in (
        ("retro_api_version", c.c_uint, []), ("retro_init", None, []),
        ("retro_deinit", None, []), ("retro_load_game", c.c_bool, [c.POINTER(GameInfo)]),
        ("retro_unload_game", None, []), ("retro_run", None, [])):
        bind(core, name, result, params)
    if core.retro_api_version() != 1:
        raise ProbeError("api-version")
    for name, signature, callback in zip(
        ("retro_set_environment", "retro_set_video_refresh", "retro_set_audio_sample",
         "retro_set_audio_sample_batch", "retro_set_input_poll", "retro_set_input_state"),
        (ENV, VIDEO, AUDIO, BATCH, POLL, INPUT), frontend.callbacks):
        bind(core, name, None, [signature])(callback)
    frontend.check()
    if not set(PINS) <= frontend.values.keys():
        raise ProbeError("required-options")


def lifecycle(api, frontend, case, game, record, pause=time.sleep, clock=time.monotonic):
    """Double deinit calls the same loaded object's native function twice."""
    if case not in CASES:
        raise ProbeError("invalid-arguments")
    record("initializing")
    api.retro_init()
    frontend.check()
    loaded = False
    if case in ("failed-double-deinit", "success-double-deinit", "three-cycles"):
        record("loading-content")
        loaded = bool(api.retro_load_game(c.byref(game)))
        frontend.check()
        if loaded != (case != "failed-double-deinit"):
            raise ProbeError("load-result")
        record("loaded" if loaded else "expected-load-rejection", loaded=loaded)
        if loaded:
            record("running")
            for _ in range(RUN_FRAMES):
                api.retro_run()
                frontend.check()
                pause(1 / 60)
            record("unloading-game", run_frames=RUN_FRAMES)
            api.retro_unload_game()
            frontend.check()
    for number in range(1, 2 if case in ("no-load", "three-cycles") else 3):
        record("deinit", call=number)
        begin = clock()
        api.retro_deinit()
        frontend.check()
        milliseconds = (clock() - begin) * 1000
        pause(SETTLE_SECONDS)
        record("after-deinit", call=number, milliseconds=milliseconds)
    if loaded and (frontend.video_frames == 0 or frontend.audio_frames == 0):
        raise ProbeError("no-media")
    return loaded


class ThreadEntry(c.Structure):
    _fields_ = [(name, kind) for name, kind in (
        ("size", c.c_uint32), ("usage", c.c_uint32), ("tid", c.c_uint32),
        ("pid", c.c_uint32), ("base", c.c_int32), ("delta", c.c_int32),
        ("flags", c.c_uint32))]


class ModuleInfo(c.Structure):
    _fields_ = [("base", c.c_void_p), ("size", c.c_uint32), ("entry", c.c_void_p)]


class WindowsObserver:
    """Observe only this isolated child. Never terminate a thread or remote PID."""
    def __init__(self, core_path):
        self.core_path = os.path.normcase(str(core_path))
        kernel = c.WinDLL("kernel32", use_last_error=True)
        psapi = c.WinDLL("psapi", use_last_error=True)
        ntdll = c.WinDLL("ntdll", use_last_error=True)
        H, U, B = c.c_void_p, c.c_uint32, c.c_int
        self.current = bind(kernel, "GetCurrentProcess", H, [])()
        self.handles = bind(kernel, "GetProcessHandleCount", B, [H, c.POINTER(U)])
        self.snapshot = bind(kernel, "CreateToolhelp32Snapshot", H, [U, U])
        self.first = bind(kernel, "Thread32First", B, [H, c.POINTER(ThreadEntry)])
        self.next = bind(kernel, "Thread32Next", B, [H, c.POINTER(ThreadEntry)])
        self.open_thread = bind(kernel, "OpenThread", H, [U, B, U])
        self.close = bind(kernel, "CloseHandle", B, [H])
        self.query_thread = bind(ntdll, "NtQueryInformationThread", c.c_int32,
                                 [H, U, H, U, H])
        self.enum_modules = bind(psapi, "EnumProcessModulesEx", B,
                                 [H, c.POINTER(H), U, c.POINTER(U), U])
        self.module_info = bind(psapi, "GetModuleInformation", B,
                                [H, H, c.POINTER(ModuleInfo), U])
        self.module_name = bind(psapi, "GetModuleFileNameExW", U, [H, H, c.c_wchar_p, U])
        self.free = bind(kernel, "FreeLibrary", B, [H])
        self.mapped = bind(kernel, "GetModuleHandleW", H, [c.c_wchar_p])
        # Suppress native crash dialogs only inside the disposable child.
        bind(kernel, "SetErrorMode", U, [U])(0x8003)
        process_mask, system_mask = c.c_size_t(), c.c_size_t()
        if not bind(kernel, "GetProcessAffinityMask", B,
                    [H, c.POINTER(c.c_size_t), c.POINTER(c.c_size_t)])(
                        self.current, c.byref(process_mask), c.byref(system_mask)):
            raise ProbeError("os-observation")
        mask = limited_affinity(process_mask.value)
        if not bind(kernel, "SetProcessAffinityMask", B, [H, c.c_size_t])(self.current, mask):
            raise ProbeError("os-observation")
        self.affinity_cpus = mask.bit_count()
        self.baseline = None
        self.candidate_ranges = []

    def modules(self):
        array, needed = (c.c_void_p * 4096)(), c.c_uint32()
        if not self.enum_modules(self.current, array, c.sizeof(array), c.byref(needed), 3):
            raise ProbeError("os-observation")
        if needed.value > c.sizeof(array) or needed.value % c.sizeof(c.c_void_p):
            raise ProbeError("os-observation")
        result = []
        for module in array[:needed.value // c.sizeof(c.c_void_p)]:
            info, name = ModuleInfo(), c.create_unicode_buffer(32768)
            if not self.module_info(self.current, module, c.byref(info), c.sizeof(info)):
                raise ProbeError("os-observation")
            length = self.module_name(self.current, module, name, len(name))
            if not length or length >= len(name):
                raise ProbeError("os-observation")
            candidate = os.path.normcase(name.value) == self.core_path
            region = (info.base, info.base + info.size)
            if candidate and region not in self.candidate_ranges:
                self.candidate_ranges.append(region)
            result.append((*region, candidate))
        return result

    def runtime_paths(self):
        array, needed = (c.c_void_p * 4096)(), c.c_uint32()
        if (not self.enum_modules(self.current, array, c.sizeof(array), c.byref(needed), 3)
                or needed.value > c.sizeof(array) or needed.value % c.sizeof(c.c_void_p)):
            raise ProbeError("os-observation")
        result = []
        for module in array[:needed.value // c.sizeof(c.c_void_p)]:
            name = c.create_unicode_buffer(32768)
            length = self.module_name(self.current, module, name, len(name))
            if not length or length >= len(name):
                raise ProbeError("os-observation")
            if Path(name.value).name.lower() == RUNTIME_NAME:
                result.append(os.path.normcase(name.value))
        return result

    def take(self):
        modules = self.modules()
        threads, query_errors = {}, 0
        snapshot = self.snapshot(4, 0)
        if snapshot == c.c_void_p(-1).value or not snapshot:
            raise ProbeError("os-observation")
        try:
            entry = ThreadEntry()
            entry.size = c.sizeof(entry)
            more = self.first(snapshot, c.byref(entry))
            while more:
                if entry.pid == os.getpid():
                    thread = self.open_thread(0x40, False, entry.tid)
                    address = c.c_void_p()
                    if thread:
                        try:
                            status = self.query_thread(thread, 9, c.byref(address), c.sizeof(address), None)
                            if status != 0 or not address.value:
                                query_errors += 1
                        finally:
                            self.close(thread)
                    else:
                        query_errors += 1  # Exiting-thread race is visible, not a fake zero.
                    threads[entry.tid] = address.value or 0
                more = self.next(snapshot, c.byref(entry))
            if c.get_last_error() != 18:  # ERROR_NO_MORE_FILES, not arbitrary failure.
                raise ProbeError("os-observation")
        finally:
            self.close(snapshot)
        handles = c.c_uint32()
        if not threads or not self.handles(self.current, c.byref(handles)):
            raise ProbeError("os-observation")
        if self.baseline is None:
            self.baseline = (frozenset(threads), handles.value, len(modules))
        baseline_threads, baseline_handles, baseline_modules = self.baseline
        return {"thread_count": len(threads), "new_threads": len(threads.keys() - baseline_threads),
            "candidate_start_threads": sum(any(start <= address < end for start, end in self.candidate_ranges)
                                           for address in threads.values() if address),
            "unmapped_start_threads": sum(not any(start <= address < end for start, end, _ in modules)
                                          for address in threads.values() if address),
            "thread_query_errors": query_errors, "handle_count": handles.value,
            "handle_delta": handles.value - baseline_handles, "module_count": len(modules),
            "module_delta": len(modules) - baseline_modules,
            "candidate_modules": sum(row[2] for row in modules)}


def limited_affinity(mask):
    chosen = 0
    for _ in range(2):
        bit = mask & -mask
        chosen |= bit
        mask &= ~bit
    if not chosen:
        raise ProbeError("os-observation")
    return chosen


def recovered(sample):
    return (sample["candidate_modules"] == 0 and sample["new_threads"] == 0
            and sample["candidate_start_threads"] == 0 and sample["unmapped_start_threads"] == 0
            and sample["thread_query_errors"] == 0 and sample["handle_delta"] <= 0)


def retain(record, pause=time.sleep, clock=time.monotonic):
    begin = clock()
    while True:
        elapsed = clock() - begin
        record("linger", linger_seconds=elapsed)
        if elapsed >= LINGER_SECONDS:
            return
        pause(min(SAMPLE_SECONDS, LINGER_SECONDS - elapsed))


def validate_event(event):
    if not isinstance(event, dict) or event.get("phase") not in PHASES:
        raise ProbeError("invalid-events")
    if "error" in event and event["error"] not in ERRORS:
        raise ProbeError("invalid-events")
    if event.keys() - NUMBER_FIELDS - BOOL_FIELDS - {"phase", "error"}:
        raise ProbeError("invalid-events")
    for key, value in event.items():
        if key in NUMBER_FIELDS and (type(value) not in (int, float) or not math.isfinite(value)):
            raise ProbeError("invalid-events")
        if key in BOOL_FIELDS and type(value) is not bool:
            raise ProbeError("invalid-events")
    return event


class Recorder:
    def __init__(self, stream, observer, clock=time.monotonic):
        self.stream, self.observer, self.clock = stream, observer, clock
        self.begin, self.cycle = clock(), 0

    def __call__(self, phase, **fields):
        event = {"phase": phase, "cycle": self.cycle,
                 "elapsed_seconds": self.clock() - self.begin, **fields}
        if phase != "failed":
            event.update(self.observer.take())
        if phase == "cycle-completed":
            event["recovered"] = recovered(event)
        validate_event(event)
        self.stream.write(json.dumps(event, sort_keys=True, allow_nan=False) + "\n")
        self.stream.flush()
        return event


def retain_runtime(path, observer):
    """Own only the fixed official C++ dependency until this child process exits."""
    path = safe_path(path)
    if observer.runtime_paths():
        raise ProbeError("runtime-conflict")
    kernel = c.WinDLL("kernel32", use_last_error=True)
    create = bind(kernel, "CreateFileW", c.c_void_p,
                  [c.c_wchar_p, c.c_uint32, c.c_uint32, c.c_void_p, c.c_uint32, c.c_uint32, c.c_void_p])
    handle = create(str(path), 0x80000000, 1, None, 3, 0x80, None)
    if not handle or handle == c.c_void_p(-1).value:
        raise ProbeError("runtime-identity")
    # Do not explicitly close after a failed load: the isolated process owns
    # this read lock as well as any partially loaded runtime until it exits.
    _PROCESS_RUNTIME_OWNERS.append((kernel, handle))
    verify_runtime(path)
    library = c.CDLL(str(path), winmode=0x100 | 0x800)
    _PROCESS_RUNTIME_OWNERS.append(library)
    if observer.runtime_paths() != [os.path.normcase(str(path))]:
        raise ProbeError("runtime-conflict")
    verify_runtime(path)
    return library


def child(core, expected, work, case, profile="gcc-static"):
    profile_name(profile)
    work, core = safe_path(work), safe_path(core)
    launch = json.loads((work / "launch.json").read_text(encoding="utf-8"))
    if (Path.cwd() != work or core != work.parent / "core.dll"
            or launch.get("parent_pid") != os.getppid() or launch.get("case") != case
            or launch.get("core_sha256") != expected or launch.get("profile", "gcc-static") != profile):
        raise ProbeError("invalid-launch")
    verify_core(core, expected, profile)
    if profile == "clang64-shared" and launch.get("runtime") != verify_runtime(core.parent / RUNTIME_NAME):
        raise ProbeError("runtime-identity")
    if sha(work / "content/invaders.zip") != launch.get("content_sha256"):
        raise ProbeError("content-sha-mismatch")
    # The open event-file handle is included in the baseline and every sample.
    with (work / "events.jsonl").open("x", encoding="utf-8") as stream:
        record = None
        retained = []  # All callbacks/strings/function objects survive DLL free and the full linger.
        try:
            observer = WindowsObserver(core)
            if profile == "clang64-shared":
                retained.append(retain_runtime(core.parent / RUNTIME_NAME, observer))
            record = Recorder(stream, observer)
            record("baseline", affinity_cpus=observer.affinity_cpus)
            for cycle in range(1, 4 if case == "three-cycles" else 2):
                record.cycle = cycle
                frontend = Frontend(work)
                # Only this explicit child loads the candidate. DLL dependencies
                # search its private directory and System32, never PATH or cwd.
                library = c.CDLL(str(core), winmode=0x100 | 0x800)
                retained.append((library, frontend))
                bind_core(library, frontend)
                record("dll-loaded")
                game = GameInfo(str(work / "content/invaders.zip").encode("utf-8"), None, 0, None)
                lifecycle(library, frontend, case, game, record)
                record("free-library", video_callbacks=frontend.video_callbacks,
                       video_frames=frontend.video_frames, audio_frames=frontend.audio_frames)
                if not observer.free(library._handle) or observer.mapped(str(core)):
                    raise ProbeError("dll-not-unmapped")
                record("after-free")
                retain(record)  # 330 s AFTER each FreeLibrary, including all three reopens.
                if not record("cycle-completed")["recovered"]:
                    raise ProbeError("resource-recovery")
            record("completed")
            return 0
        except Exception as error:
            code = error.code if isinstance(error, ProbeError) else "internal-error"
            if record is None:
                stream.write(json.dumps({"phase": "failed", "cycle": 0,
                                         "elapsed_seconds": 0, "error": code}) + "\n")
                stream.flush()
            else:
                record("failed", error=code)
            # Never FreeLibrary on a partially initialized or failed lifecycle.
            # Exiting the isolated process lets Windows own final reclamation.
            return 1


def read_events(path):
    if not path.exists():
        return []
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ProbeError("invalid-events")
    lines = path.read_text(encoding="utf-8").splitlines()
    if len(lines) > 1024:
        raise ProbeError("invalid-events")
    return [validate_event(json.loads(line)) for line in lines]


def events_passed(case, events):
    """The observer independently requires a complete, timed lifecycle trail."""
    if not events or events[0]["phase"] != "baseline" or events[-1]["phase"] != "completed":
        return False
    cycles = 3 if case == "three-cycles" else 1
    if any(event["phase"] == "failed" for event in events):
        return False
    if not recovered(events[0]):
        return False
    if {event.get("cycle") for event in events} != set(range(cycles + 1)):
        return False
    for cycle in range(1, cycles + 1):
        rows = [event for event in events if event.get("cycle") == cycle]
        phases = [event["phase"] for event in rows if event["phase"] != "linger"]
        expected = ["dll-loaded", "initializing"]
        if case == "failed-double-deinit":
            expected += ["loading-content", "expected-load-rejection"]
        elif case in ("success-double-deinit", "three-cycles"):
            expected += ["loading-content", "loaded", "running", "unloading-game"]
        expected += ["deinit", "after-deinit"] * (1 if case in ("no-load", "three-cycles") else 2)
        expected += ["free-library", "after-free", "cycle-completed"]
        if cycle == cycles:
            expected += ["completed"]
        if phases != expected:
            return False
        loaded_dll = next(event for event in rows if event["phase"] == "dll-loaded")
        free = next(event for event in rows if event["phase"] == "free-library")
        if loaded_dll["candidate_modules"] != 1 or free["candidate_modules"] != 1:
            return False
        if case in ("success-double-deinit", "three-cycles"):
            ran = next(event for event in rows if event["phase"] == "unloading-game")
            if ran.get("run_frames") != RUN_FRAMES or free.get("video_frames", 0) <= 0 or free.get("audio_frames", 0) <= 0:
                return False
        after_free = next(event for event in rows if event["phase"] == "after-free")
        lingering = [event for event in rows if event["phase"] == "linger"]
        done = next(event for event in rows if event["phase"] == "cycle-completed")
        if (not lingering or lingering[-1].get("linger_seconds", 0) < LINGER_SECONDS
                or lingering[-1]["elapsed_seconds"] - after_free["elapsed_seconds"] < LINGER_SECONDS
                or done.get("recovered") is not True or not recovered(done)):
            return False
        if any(event["candidate_modules"] or event["candidate_start_threads"]
               or event["unmapped_start_threads"] for event in [after_free, *lingering]):
            return False
    return True


def supervise(process, timeout, pause=time.sleep, clock=time.monotonic, cancel=None):
    begin = clock()
    timed_out = False
    try:
        while process.poll() is None:
            if cancel is not None and cancel.is_set():
                process.kill()
                break
            if clock() - begin >= timeout:
                timed_out = True
                process.kill()  # Only the Popen object this invocation created.
                break
            pause(1)
        return process.wait(), timed_out, clock() - begin
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


def run_case(output, expected, case, cancel=None, profile="gcc-static"):
    profile_name(profile)
    cancel = cancel if cancel is not None else threading.Event()
    work = output / "staging" / case
    timeout = CASE_TIMEOUT_SECONDS * (3 if case == "three-cycles" else 1)
    receipt = {"schema": 1, "case": case, "core_sha256": expected, "profile": profile,
               "passed": False, "timeout_seconds": timeout}
    try:
        if cancel.is_set():
            receipt["cancelled"] = True
            save_new(output / "receipts" / (case + ".json"), receipt)
            return receipt
        command = [sys.executable, "-I", "-B", str(Path(__file__).resolve()),
                   "--core", str(work.parent / "core.dll"), "--sha256", expected,
                   "--output", str(work), "--profile", profile, "--_child", case]
        process = subprocess.Popen(command, cwd=work, stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS)
        code, timed_out, elapsed = supervise(process, timeout, cancel=cancel)
        events = read_events(work / "events.jsonl")
        receipt.update(exit_code=code, timed_out=timed_out, cancelled=cancel.is_set(),
                       elapsed_seconds=elapsed, events=events)
        receipt["passed"] = code == 0 and not timed_out and not cancel.is_set() and events_passed(case, events)
    except Exception as error:
        receipt["error"] = error.code if isinstance(error, ProbeError) else "internal-error"
    save_new(output / "receipts" / (case + ".json"), receipt)
    return {key: value for key, value in receipt.items() if key != "events"}


def run_cases(output, expected, profile="gcc-static"):
    profile_name(profile)
    cancel = threading.Event()
    pool = ThreadPoolExecutor(max_workers=MAX_WORKERS)
    futures = []
    try:
        # Longest route first. Ctrl+C reaches this owner thread, not the workers.
        futures = [pool.submit(run_case, output, expected, case, cancel, profile) for case in CASES]
        return [future.result() for future in futures]
    except BaseException:
        cancel.set()
        for future in futures:
            future.cancel()
        raise
    finally:
        # Running workers observe the event, kill/wait only their own Popen,
        # and return; queued cases are cancelled and cannot start another core.
        pool.shutdown(wait=True, cancel_futures=cancel.is_set())


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ProbeError("invalid-arguments")


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", choices=PROFILES, default="gcc-static")
    parser.add_argument("--_child", choices=CASES, help=argparse.SUPPRESS)
    try:
        args = parser.parse_args(argv)
        if os.name != "nt" or c.sizeof(c.c_void_p) != 8:
            raise ProbeError("wrong-platform")
        expected = candidate_sha(args.sha256)
        if args._child:
            return child(args.core, expected, args.output, args._child, args.profile)
        output = safe_path(args.output)
        summary = prepare(args.core, expected, output, args.profile)
        summary["cases"] = run_cases(output, expected, args.profile)
        summary["passed"] = all(case["passed"] for case in summary["cases"])
        save_new(output / "summary.json", summary)
        print(json.dumps({"scope": summary["scope"], "passed": summary["passed"],
                          "cases": [{"case": row["case"], "passed": row["passed"]}
                                    for row in summary["cases"]]}), flush=True)
        return 0 if summary["passed"] else 1
    except KeyboardInterrupt:
        print('{"passed":false,"cancelled":true}', flush=True)
        return 130
    except Exception as error:
        print(json.dumps({"passed": False,
              "error": error.code if isinstance(error, ProbeError) else "internal-error"}), flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
