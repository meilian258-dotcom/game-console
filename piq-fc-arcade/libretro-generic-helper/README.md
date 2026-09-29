# Generic software libretro worker prototype (v3)

This independently compiled Java 21/JNA worker does not replace or change the FC63
worker or its state profile. Entry point: `cn.piq.retro.worker.GenericLibretroWorker`.
This is a local software-core host, **not RetroArch Netplay**. Native code runs
in a separate process; this is crash containment, not an OS security sandbox.

The parent must choose an approved SHA-verified core and descriptor, create a
private empty working directory, generate a random authentication token, enforce
operation/process deadlines and reap only the process it owns. There is no core
download, Minecraft world access, ROM fetching or save-slot policy.

## Wire format

All numbers are Java DataInput/DataOutput big endian. Strings use `writeUTF` /
`readUTF` (modified UTF-8 with its 65535-byte wire limit, further bounded by field).
Child arguments: `port token absoluteCorePath absoluteWorkDirectory`.
Connect only to IPv4 `127.0.0.1`. Child sends int `0x504c5232`, int `3`, UTF token;
parent replies int `0x4f4b4159`. Idle sessions have no socket idle timeout.

Each command starts with an int. Each response starts with boolean success. On
failure it writes a UTF explanation (at most 512 characters) and exits.
Metadata: int width, height, maxWidth, maxHeight; float aspect; double fps,
sampleRate; int sourcePixelFormat (0=0RGB1555, 1=XRGB8888, 2=RGB565).

| Command | Request after command | Success payload |
| --- | --- | --- |
| LOAD=1 | UTF expectedCoreName, UTF lowercase extension; boolean needFullpath; int portCount; portCount int devices; int gunMode; int optionCount; optionCount UTF key/value pairs; int ROM length + bytes | UTF actualName, UTF version, metadata |
| RUN=2 | int frames; int outputMask; for each frame: portCount int 16-bit RetroPad masks then int packedGun | metadata; boolean lastFrameDuplicate; int RGBA length + bytes; int audioShortCount + shorts |
| SAVE=3 | none | int state length + native bytes |
| RESTORE=4 | int state length + native bytes | none |
| RESET=5 | none | metadata |
| CLOSE=6 | none | none, process exits |
| MEMORY=7 | int libretro memory ID 0..3 | int length + bytes (zero means not provided) |
| RESTORE_SAVE_MEMORY=8 | int SAVE_RAM length + bytes; int RTC length + bytes | none |

v3 adds opt-in persistent memory restore. The combined SAVE_RAM/RTC limit is
16 MiB. Both region sizes and pointers are validated before either write; other
memory IDs cannot be written. Save RAM must be restored after LOAD and before
the first RUN. This is distinct from SAVE/RESTORE whole-core snapshots and does
not automatically persist on CLOSE. v2 parents/workers cannot be mixed with v3.

The public `LibretroMemoryStore` is an independent, opt-in disk experiment. Supply
an existing private world/owner/slot directory and `process.persistenceIdentity()`;
call `restore(process)` before running and `save(process)` before closing. It does
not decide player/cartridge ownership, integrate Minecraft power-off, or migrate
existing PLR1 saves. Identity conservatively includes ROM, native core hash/version,
options and devices, so cross-platform/core/version import is not promised. Writes
use same-directory atomic replacement and retain the last different valid file;
corrupt files are reported, never silently overwritten or automatically recovered.
The SHA-256 checksum detects corruption, not malicious modification. Only memory
exposed as SAVE_RAM/RTC is handled, not core-private disk NVRAM files.

RUN frames are 1..120; outputMask VIDEO=1 and AUDIO=2 may be combined or zero.
All frames are emulated with their own input; no simulation frame is skipped.
Video retains the most recent frame even when output is omitted, so a later core
duplicate-frame callback is correct. The prototype still converts video callbacks
in the child; it saves IPC bytes, not all rendering CPU. Audio is native signed
16-bit interleaved left/right PCM at metadata sampleRate; **not mono float**.
A batch returns only the final cached image and requested audio for all frames.

The descriptor supports 1..4 digital RetroPad ports (standard 16-bit libretro
layout, not FC's old eight-bit mask). Digital joypad subtypes and NONE are accepted.
Explicit gunMode=1 permits Mesen subtype 262 on port 1 and its pointer/mouse bridge;
packedGun is FC x8/y8/offscreen-bit16/trigger-bit17. gunMode=0 requires packedGun=0.
Mesen's unused expansion port is explicitly NONE.

Bounds: ROM 64 MiB; native state and memory each 16 MiB; each video dimension
1..4096 and RGBA at most 32 MiB; total audio per RUN 262144 stereo frames;
options at most 256; fps 1..240; sample rate 8000..192000 Hz. Native-state
compatibility and snapshot display/input restoration are parent responsibilities.

Supported: in-memory/full-path ROM content, persistent extended content info, v0
core options, dynamic software geometry, all three software pixel formats,
duplicate frames, stereo audio, digital input, native states and optional memory.
Unsupported callbacks return false: hardware rendering, VFS, analog devices,
disk/subsystem loading, firmware provisioning, netpacket, options-v1/v2-only cores
and asynchronous frontend interfaces are not advertised. Mesen is the real-core
integration target this iteration; generic capability does not imply that every
other core has passed validation.
