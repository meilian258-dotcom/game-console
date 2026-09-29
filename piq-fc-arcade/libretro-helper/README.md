# FC libretro worker

Private Java 21 child process for the pinned Mesen libretro core. Minecraft must not load this helper or JNA into its own class loader. The parent authenticates a loopback socket, enforces operation deadlines, verifies packaged binary hashes, envelopes saves with ROM/core/profile identity and destroys only its owned child on timeout.

Build with Java 21 and JNA 5.14.0:

```
javac --release 21 -encoding UTF-8 -cp <jna-5.14.0.jar> -d <classes> src/main/java/cn/piq/fcarcade/libretro/worker/MesenWorker.java
jar --create --file <fc-libretro-helper.jar> -C <classes> .
```

Arguments: `<loopback port> <random token> <verified core absolute path> <new empty owned work directory>`.

Wire protocol uses Java DataInput/DataOutput (big endian), version 1. Child writes `0x504c5231`, version, UTF token; parent replies `0x4f4b4159`. Each command begins with an int. Every response starts with success boolean, or false plus bounded UTF error then disconnect. LOAD(1): boolean Zapper, length+ROM; returns UTF name, UTF version, double FPS, double rate. STEP(2): NES P1/P2 masks and packed Zapper; returns length+RGBA, sample count+mono floats, length+2 KiB CPU RAM. SAVE(3): length+raw state. RESTORE(4): length+state. RESET(5), CLOSE(6): no payload. State identity belongs to parent, not this raw transport.

Bounds: ROM 64 MiB, state 16 MiB, 256x240 RGBA, 4096 mono samples per frame. Mesen is pinned to NTSC, 44100 Hz, all-zero initial RAM, software XRGB8888, no overscan/filter/HD pack/turbo. The original iNES bytes are staged only in the parent's private work directory. No firmware, BIOS, ROM download, arbitrary frontend configuration, hardware context or VFS is provided. Process isolation is not an operating-system security sandbox.

In-memory extended content info is retained until close, so Windows Unicode paths do not depend on Mesen's narrow-path file loader. After initialization, idle socket reads have no timeout (a paused game is valid); the parent must own an operation deadline and child-process cleanup. `RESTORE` does not transmit a frame or RAM: parent caches should be restored from its save envelope until the next STEP.

`src/test/.../WorkerProbe.java` is a standalone Java probe which synthesizes an original NROM cartridge and exercises two isolated workers, audio/video/RAM determinism, both controllers, snapshots, Zapper, invalid lengths and a 16-second pause. Run it with `<helper.jar> <jna.jar> <core path> <probe output directory>` on its classpath alongside helper classes. This is not Minecraft or multiplayer acceptance testing.

Copyright 2026 方块电玩 contributors. Helper source: GPL-3.0-or-later. Mesen and JNA retain their own upstream licenses; package their corresponding notices separately.
