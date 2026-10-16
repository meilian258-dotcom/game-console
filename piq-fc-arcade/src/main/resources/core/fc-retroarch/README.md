# Explicit FC RetroArch pad core

This additional route uses the unmodified official Libretro Windows x64 FCEUmm binary fetched on 2026-10-17. It does not replace the historical Mesen, JNI core, device defaults, or saved games. No emulator core or RetroArch executable was changed or rebuilt for this route.

- Runtime identity: `FCEUmm (SVN) ac0d00a`.
- Binary: 4,162,048 bytes, SHA-256 `a04f7fa28bdd46f602bb62750ab9b5e360d8e8d8ece97de8ad3ef1b669257ffd`.
- [Official binary ZIP](https://buildbot.libretro.com/nightly/windows/x86_64/latest/fceumm_libretro.dll.zip): SHA-256 `224d180b58061b55c5b778e11800d989821c9aeb22d0c32dae946cc7ef5b4398`. The URL changes over time; a different hash must be refused, never silently imported.
- [Upstream source at ac0d00a7fc01166f84c7bac01d1084ebfd2b865e](https://github.com/libretro/libretro-fceumm/tree/ac0d00a7fc01166f84c7bac01d1084ebfd2b865e); [source ZIP](https://codeload.github.com/libretro/libretro-fceumm/zip/ac0d00a7fc01166f84c7bac01d1084ebfd2b865e), SHA-256 `62ee00f82a0d94c9d36a3dc0d07e5dff24a395df66060d37cc6d443a29620efe`.
- The runtime commit suffix matches this source. This is not a claim of a reproducible upstream binary rebuild.

FCEUmm is by the FCEU Team, CaH4e3, and the contributors credited in its source. Original GNU GPL v2 text is in `licenses/FCEUmm-COPYING.txt`; the included nes_ntsc component's original license is in `licenses/nes_ntsc-license.txt`. Source headers and other bundled component notices remain in the full source archive. Mod source licensing does not replace third-party licensing. Preserve the binary/source correspondence and all notices when preparing any distribution.

The mod's supported declaration is Windows x64, `.nes`, NTSC, two gamepads (257/257), 48 kHz, no external BIOS or HD packs. Exact options are in `FcRetroarchPad`; omitted options use the pinned binary defaults, including deterministic RAM fill `$ff`. Upstream capabilities such as FDS, HD packs, additional mappers or peripherals are not automatically enabled or certified by this declaration. Original-ROM core/protocol probes and Minecraft acceptance have separate scopes.
