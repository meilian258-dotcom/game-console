FC JNI Netplay Mesen r2, isolated from the existing Mesen/RetroArch core.
Upstream: https://github.com/libretro/Mesen, commit 0102910c39ad1a62bc3f784466f3f67ca9eae335
License: GPL-3.0-or-later; see bundled LICENSE.txt and delivered corresponding-source archive.
Changes: ControlManager serialization includes _isLagging; VRC7 initialization applies its PRG RAM enable register before the first snapshot; BaseMapper restores mapped-but-disabled PRG/CHR banks without discarding their descriptors. Exact-state/CRC checks are retained.
New v2 trial save namespace: previous r1 trial saves are preserved, not loaded or overwritten.
Windows x64 DLL SHA256: 591976547fa49a29ad3c20acecd96ef46eed0a7376398295a9468cfaae55c05c
Build instructions and patching script: piq-fc-arcade/native-mesen-netplay/README.md and build.py in the source supplement.
This is an experimental PIQ build, not an upstream Mesen release.
