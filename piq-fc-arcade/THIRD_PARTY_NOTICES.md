# Third-party notices

## FC76.44 distribution

The complete test release includes a separate corresponding-source asset on the
same release page: fixed upstream sources, complete modified Mesen/RetroArch/JNI
sources, patches, build instructions and original license texts. See
`docs/整套测试版-20261007.md`. No component is relicensed by this notice.

FC76.44's legacy Zapper WASM has equal-width diagnostic-prefix redactions in its
data section. Its code and all non-data sections are unchanged; its new SHA-256
is `b8b2a72543fa49f286e645bf4e5b13840485c2ceddb66a4734f01b08c25ff64c`.
The release supplies the derivative and transform source. Old raw-memory saves
are not migrated or overwritten. No Mesen or JNI binary changed for this revision.

## Generic Libretro JNI ABI2

PIQ's bridge implementation is GPL-3.0-or-later, with source/build scripts under
`native/libretro-jni/`. The bundled libretro API header retains its upstream MIT
notice. The bridge is main-mod-owned; core binaries remain addon-owned with their
original licenses. It does not relicense FBNeo, Mesen, Mesen-S or PvZ. The release
provides matching bridge source and API header, without claiming a complete
byte-reproducible third-party SDK. No ROM, BIOS or game data is included.

FC76.22's RetroArch 1.22.2 PNP7 frontend additionally gates input polling and save
capture to outer core runs (not recursive rollback replay). The matching modified
frontend source and minimal patch accompany this candidate; CRC/protocol checks
are not weakened. This is separate from the optional JNI bridge.

This file describes third-party code bundled in the FC main JAR. These components
retain their own licenses and ownership; PIQ's own licensing does not replace them.
The named license files below are included in the JAR under `META-INF/licenses/`.

## RetroArch 1.22.2 (FC68 Netplay local experiment)

- Project: https://github.com/libretro/RetroArch/tree/v1.22.2
- License: GPL-3.0-or-later; verbatim text in `META-INF/licenses/GPL-3.0.txt`.
- Locally compiled minimal Windows x64 frontend: `core/netplay/piq-retroarch.exe`.
- Changes: bounded loopback audio/video/input bridge, display pacing, Minecraft-authorized
  P2/spectator socket capabilities and loopback-only listeners. Original Netplay algorithm retained.
- Bridge source and build transform: `piq-fc-arcade/netplay-native/` in this workspace.
- Full modified source/build receipt: `retroarch-piq-pnp7-corresponding-source.zip`
  in the release source asset, including upstream dependency sources and notices.
- No ROM or BIOS included. Runtime support and validation remain separate from
  source availability; this does not certify universal Netplay compatibility.

## Mesen libretro (FC62 candidate)

- Project: https://github.com/libretro/Mesen
- Upstream source under review: `0102910c39ad1a62bc3f784466f3f67ca9eae335`
- License: GPL-3.0; complete upstream text: `META-INF/licenses/GPL-3.0.txt`.
- Official binary source: https://buildbot.libretro.com/nightly/
- Windows x86-64 SHA-256: `2b3fbe286995c80ebbc85239fd28c8fa07b1011cc69c7f9021816429e3473885`
- Linux x86-64 SHA-256: `552f8ab6ac1fd08bd555f589eb999be73a469c79ccfa929f884adb2cf3366b43`

These fixed candidate binaries are bundled under `core/libretro/`. No runtime
download or arbitrary user-selected core is enabled. The fixed upstream source,
CI/build files and matching official download-cache hashes accompany the release.
The original per-artifact CI receipt was unavailable; this does not claim a
byte-for-byte rebuild with historical toolchains.
Mesen's upstream copyright notices must be retained with corresponding source.

## Java Native Access (JNA)

- Project: https://github.com/java-native-access/jna
- Version: 5.14.0; unmodified worker-only JAR.
- License choice for this distribution: Apache-2.0 (upstream also offers LGPL).
- Original notice: `META-INF/licenses/JNA-LICENSE.txt`.
- Full Apache-2.0 text: `META-INF/licenses/wasmtime4j-LICENSE.txt`.

JNA is not merged into Minecraft's classpath. It is loaded only by the isolated
libretro worker process. Third-party dependency licenses remain unchanged.

## nes-rust (retained legacy cores)

- Project: https://github.com/takahirox/nes-rust
- Components: `core/nes_rust_wasm_bg.wasm`, `core/nes_zapper_v1.wasm`
- License: MIT
- Copyright: Copyright (c) 2018 Takahiro

The full license text is stored in `src/main/resources/META-INF/licenses/nes-rust-LICENSE.txt`.

The WebAssembly cores are rebuilt from the ROM-free MIT source copy under
`native/nes-rust`, using `native/nes-rust/ffi` and `native/nes-zapper-ffi`.
PIQ modifications provide raw ABI adapters and the additional light-gun/input and
state functionality documented with those sources. The upstream MIT copyright and
permission notice remain unchanged in `nes-rust-LICENSE.txt`.

No ROM file is distributed with PIQ FC Arcade.

## Wasmtime4j API, JNI adapter and native loader

- Project: https://github.com/tegmentum/wasmtime4j
- Components: `wasmtime4j`, `wasmtime4j-jni`, `wasmtime4j-native-loader`
- Version: 46.0.1-1.2.0
- Maven group: `ai.tegmentum`
- Upstream tag: `v46.0.1-1.2.0`
- Source commit: `a377267cc70f891bb7c9618259251380ba26e57b`
- License: Apache-2.0
- Copyright in the upstream LICENSE: Copyright 2025 Tegmentum AI
- Full, unchanged text: `META-INF/licenses/wasmtime4j-LICENSE.txt`
- Exact source: https://github.com/tegmentum/wasmtime4j/blob/a377267cc70f891bb7c9618259251380ba26e57b/LICENSE

The build expands these Maven dependency archives into the FC main JAR. It is a
flat merge, not NeoForge Jar-in-Jar; upstream Java package names, Maven POM
attribution and versions remain intact. Platform selection retains the upstream
Windows x86-64 and Linux x86-64 native libraries; it does not change their code or
ownership. Original archive signatures/manifests and module descriptors are not
reused as signatures/descriptors for the merged PIQ archive.

## Wasmtime native runtime

- Project: https://github.com/bytecodealliance/wasmtime
- Version declared by the above Wasmtime4j source: 46.0.1
- Upstream tag: `v46.0.1`
- Source commit: `823d1b8f251494a06288194d0df746191f535ff7`
- Upstream author group: The Wasmtime Project Developers
- License: Apache-2.0 WITH LLVM-exception
- Full, unchanged text, including the LLVM exceptions:
  `META-INF/licenses/wasmtime-LICENSE.txt`
- Exact source: https://github.com/bytecodealliance/wasmtime/blob/823d1b8f251494a06288194d0df746191f535ff7/LICENSE
- Version evidence: https://github.com/tegmentum/wasmtime4j/blob/a377267cc70f891bb7c9618259251380ba26e57b/Cargo.toml

The native implementation is carried by the upstream Wasmtime4j JNI distribution
at `natives/windows-x86_64/wasmtime4j.dll` and
`natives/linux-x86_64/libwasmtime4j.so`. Wasmtime compiles the bundled NES
WebAssembly cores to native code at runtime. These libraries are not commercial
game ROMs or console BIOS files.

## Attribution verification scope

At the exact commits above, neither upstream repository has a separate `NOTICE`
file in its checked Git tree. The three selected Maven artifacts likewise do not
carry a separate NOTICE. No upstream NOTICE is invented or replaced by this file;
this file is PIQ's additional distribution attribution. The upstream LICENSE texts
and the existing NES MIT notice are preserved verbatim. Source URLs, Git blob
identities and file SHA-256 values are recorded in
`META-INF/licenses/wasmtime-provenance.json`.

This is not an exhaustive software bill of materials or a legal-compliance
certification. The Wasmtime4j release tag does not include a Cargo.lock; a fully
reproducible attribution inventory for every third-party Rust/native dependency
inside its prebuilt libraries has not been established here. Do not substitute a
newly resolved dependency tree or current upstream branch for that build's actual
dependency provenance. Other emulator add-ons and separately supplied runtime
packs have their own license and source materials outside this FC notice.
