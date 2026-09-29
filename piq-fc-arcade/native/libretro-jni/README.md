# Generic libretro JNI bridge — experimental ABI 1

This is one shared Windows x64 frontend, not an emulator, not RetroArch Netplay,
and not a separate Minecraft mod. The main mod packages the approved DLL once;
addons supply fixed, SHA-verified core resources, content rules and capabilities.
Default execution remains the existing isolated process backend.

## Safety and ownership

Every operation except `abiVersion()` and `reservationHeld()` belongs to the Java thread that called
`open()`. One active JNI core is permitted per JVM; other machines can still use
independent processes. A second call never blocks waiting for the native owner.
Handles are generation tokens, not addresses. A stale or foreign-thread call is
rejected. Core asynchronous callbacks are unsupported and become fatal session
errors. No JNI calls are made from libretro callbacks.

JNI is not a sandbox: a native core crash can terminate Minecraft. Initialization,
step and cleanup can hang inside trusted native code. The Java owner must enforce
asynchronous deadlines but must not kill its native thread, unload its library or
release its directory/slot while calls remain in flight. Failed native cleanup
keeps the native reservation. The bridge does not change process cwd, locale,
stdio, DLL search policy or Minecraft's OpenGL context.

`open()` can throw after partially initializing the core without returning a
token. If native startup cleanup then fails, `reservationHeld()` stays true.
After any attempted open, Java must use that lock-free atomic query before
releasing its active slot, loaded-library pin or workspace when its token is zero.
No token does **not** mean no active native reservation. The query never calls
core code, dereferences the session or waits for a potentially stuck owner.

Java must validate resource identity and stage a private owned workspace before
calling `open`. Native paths must be absolute, normalized, exist, and have no
reparse-point ancestor. Core loading only searches its own directory and Windows
system directory. This does not make an arbitrary user DLL trustworthy. Never
accept core paths from network packets or game content. Saving is a separate
Java policy: this bridge never chooses player, cartridge or cabinet ownership.

## ABI

Class: `cn.piq.retro.libretro.jni.NativeLibretroBridge`.

```java
static native int abiVersion(); // 1
static native boolean reservationHeld(); // atomic query, any thread, no owner lock
static native long open(String core, String content, String system, String save,
    String expectedName, boolean fullPath, int[] devices, String[] optionPairs, int features);
static native void metadata(long handle, int[] metadata, double[] timing);
static native String coreVersion(long handle);
static native int saveCapabilities(long handle); // 1 valid state, 2 valid SAVE_RAM/RTC
static native void step(long handle, ByteBuffer rgba, ByteBuffer pcm,
    int[] input, int[] keyEvents, int[] metadata, double[] timing);
static native byte[] serialize(long handle);
static native void restore(long handle, byte[] state);
static native byte[] memory(long handle, int region);
static native void restoreMemory(long handle, byte[] ram, byte[] rtc);
static native void reset(long handle);
static native void close(long handle);
```

Errors throw `IOException`. Arrays/buffers belong to the synchronous owner during
the call. A native failure is not a promise of recoverable execution. Close is
owner-only; Java should make its higher-level close idempotent.

Features: `1 WGL_COMPAT`, `2 POINTER`, `4 MOUSE`, `8 KEYBOARD`,
`16 MESEN_GUN` (explicit legacy Mesen adapter), `32 NO_GAME`,
`64 LEGACY_INLINE_OPTIONS` (explicit trusted legacy profile only). Flags are an
allowlist, not an assertion that the core implements every feature. NO_GAME
requires empty `content`, a core declaration, then calls `retro_load_game(NULL)`.
Ordinary content keeps its actual filename/extension, is at most 64 MiB, and is
passed by memory or full path exactly as the core metadata declares. A bounded
persistent content byte copy is also retained for extended game-info callbacks
(required by Mesen even in full-path mode). NO_GAME
does not authorize unbounded system files: the Java profile must bound them.

`devices` has 1–4 entries. `optionPairs` contains alternating key/value strings,
at most 256 pairs; advertised v0 options and every pinned value are validated.
Unsupported input devices/capabilities, content modes and core names are rejected.
Legacy flag 64 additionally accepts the known non-standard v0 encoding
`{key="option_key; token|label token|label", value=NULL}` used by the pinned PvZ
core; pins are still checked against strictly parsed declared values. It is never
automatically enabled and must not become a general fallback for malformed cores.

`input` has exactly 13 integers:

| Index | Meaning |
| --- | --- |
| 0–3 | Independent 16-bit RetroPad masks; unconfigured ports must be zero |
| 4 | Pointer/mouse port, -1 disabled or 0–3 |
| 5–6 | Pointer X/Y, signed -32768…32767 |
| 7 | Mouse buttons, bit 0 left / 1 right / 2 middle |
| 8–9 | Relative mouse X/Y, signed 16-bit |
| 10 | Wheel direction -1, 0 or 1 |
| 11 | Port-0 device override: -1 unchanged, or 0/1/2/3/6 with matching feature |
| 12 | Explicit Mesen legacy x8/y8/offscreen-bit16/trigger-bit17; otherwise zero |

`keyEvents` contains at most 128 groups of four integers:
`down (0/1), RETROK (0..511), Unicode scalar (0 allowed), modifiers (0..65535)`.
The callback and polled keyboard state both run on the native owner.

`metadata` has exactly 11 integers:
`width,height,maxWidth,maxHeight,sourcePixelFormat,clockwiseRotation,rgbaBytes,
pcmShorts,duplicate,shutdown,hardware`.
`timing` has exactly three doubles: `rawAspect,fps,sourceSampleRate`.

Output RGBA is row-major/unrotated with opaque alpha; little-endian integer view
is `0xAABBGGRR`. Libretro counter-clockwise rotation is converted to clockwise
`(4 - raw) & 3`. Do not rotate again in the adapter. Duplicate frames retain their
cached pixels. Software 0RGB1555, XRGB8888 and RGB565/padded pitch are supported.
Video dimensions are 1..2048, aspect 0.1..10, fps 1..240. The direct video buffer
must fit the declared maximum geometry and be at most 16 MiB.

PCM is native-rate signed 16-bit little-endian interleaved stereo, not yet 48kHz.
The direct PCM buffer is exactly 65,536 bytes (32,768 shorts), source rate
8,000..192,000Hz. Java must use the shared streaming resampler before publishing
a `RetroFrame` and clear its history after reset/state restoration.

Whole-core state is at most 16 MiB and must match the core-reported state size.
`saveCapabilities` checks actual loaded core sizes/pointers within these budgets,
and attempts one bounded read-only serialization (never restoration) for STATE.
Some cores report a placeholder size yet reject serialization; those must not
advertise STATE. Adapters must not infer save support solely from a profile or size.
Readable regions 0/1/2/3 are SAVE_RAM/RTC/system/video memory; limits respectively
4 MiB / 64 KiB / 16 MiB / 16 MiB. Restore only writes SAVE_RAM/RTC, validating BOTH
regions before changing either. This does not persist files or imply NVRAM,
memory-card, save-format or Netplay compatibility. Public save envelopes must
include core/content/options identity, ownership and checksums.

## Hardware rendering limitations

Only explicitly enabled legacy WGL OpenGL compatibility contexts are supported,
requiring OpenGL 2.1 and FBO functions. GLES, OpenGL core-profile, Vulkan and D3D
are rejected. The owner creates a hidden private window/context, bounded FBO and
optional depth/stencil, then calls core reset. Video readback is CPU RGBA (not
zero-copy); GL pack state is explicitly set and pixel pack buffers unbound.
The core's `bottom_left_origin` is applied once. Maximum geometry changes after
hardware initialization require a restart; software dynamic geometry is bounded.
Context/device loss is reported as failure; seamless GPU recovery is not claimed.
No VFS, async audio, disk swapping, analog axes, rumble or netpacket support is
advertised. These require versioned additions and tests, not bigger unchecked arrays.

## Build

Use Java 21 headers and either Visual Studio 2026 with C++ x64/Windows SDK, or a
fixed LLVM-MinGW toolchain. Paths are arguments; no global environment is edited.

```powershell
# Visual Studio CMake; JAVA_HOME is a CMake argument, not a global environment edit.
cmake -S . -B build-vs -A x64 -DJAVA_HOME="C:/path/to/jdk-21"
cmake --build build-vs --config Release

# Deterministic LLVM-MinGW build into a NEW evidence directory.
python build_native.py --compiler "C:/toolchain/bin/x86_64-w64-mingw32-clang++.exe" `
  --jdk "C:/path/to/jdk-21" --output "C:/scratch/new-native-build"
```

The build does not install into resources/mods. Review the source and DLL receipt,
then the release owner pins the selected binary SHA and packages corresponding
sources/license. The script refuses to overwrite earlier evidence. The current
toolchain and exact executed arguments belong in the build receipt.

## Tests (isolated JVM only)

```powershell
python tests/run_mock.py --compiler "C:/toolchain/bin/x86_64-w64-mingw32-clang++.exe" `
  --jdk "C:/path/to/jdk-21" --bridge "C:/scratch/new-native-build/piq-libretro-jni.dll" `
  --output "C:/scratch/new-native-test"
```

The mock core is original redistributable code: three pixel formats, padded rows,
duplicate frames, rotation, four ports, pointer/mouse/keyboard, state, RAM/RTC,
invalid input/callback limits, stale/foreign handles, repeated start/stop, no-game
and real WGL readback/origin. It is not a test of copyrighted games. The test-only
Java bridge declarations must never be packaged in the mod.

`tests/RealCoreProbe.java` is an additional independent native integration caller.
Its arguments are bridge/core/content/private-work/name/fullPath/features/frames,
then optional core option key/value pairs. Caller stages licensed content/BIOS,
records fixed hashes and preserves originals. It outputs metrics and a final
frame; this is not Minecraft or public-network testing.

## Header provenance and licensing

`piq_libretro_jni.cpp`, scripts, tests: GPL-3.0-or-later. Existing project COPYING /
LICENSE terms apply. `libretro.h`: Copyright (C) 2010–2024 The RetroArch team,
MIT license retained in full at the beginning of the file.

Header source is the existing PvZ-Portable vendored libretro v1 API header from
`piq-pvz-addon/vendor/PvZ-Portable/src/SexyAppFramework/platform/libretro/libretro.h`.
Original SHA256: `BF272D81CE94E604751203FC70BFA9C7564BB3CF42776049669A55A284171DF4`.
This copy normalizes CRLF to LF via a reviewed patch; SHA256:
`5875414C47D8AF4FACF118C184B40B0E311285333A46BBFE8221A853EFE5BA7A`.
Upstream project: <https://github.com/libretro/libretro-common> (`include/libretro.h`).
The original vendored snapshot has no supplied upstream commit identifier; do
not invent one or imply all original core binaries were reproducibly built.
