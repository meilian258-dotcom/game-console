# Game Console: Retro Platform — internal shared library

See the [branding and packaging policy](../source-control/BRANDING.md). Internal package names,
API identities and build outputs remain compatible. This library is not a player-installable mod.

## Client defaults (FC76.26, 2026-09-29)

New confirmed migration target (2026-09-29): FC, SFC, GBA, native arcade, PvZ and MD must migrate their existing runtime modes to the shared JNI route, including existing light-gun support. Future emulator addons use JNI as their default integration target; Flash and Java ME are excluded from this migration. This is a development requirement, not a claim of completed adapters, multi-instance support or gun Netplay. See the [scope and acceptance plan](../piq-fc-arcade/design/JNI全面迁移-范围与验收.md). The following FC76.26 description remains the shipped baseline.

User-requested JNI defaults now use `LibretroRuntimes.defaultBackend(adapted)` for existing Windows x64 client adapters, plus connection-scoped `JniClientPreference`. Explicit runtime factories and server/process constructors remain unchanged. This is not automatic save migration, silent failure fallback or support for every emulator/mode. Addons calling these new methods require FC76.26+. See [scope and install matrix](../piq-fc-arcade/design/FC76.26-JNI默认与自动旁观.md).

## Historical Runtime API v1 / opt-in JNI (FC76.22, 2026-09-29)

FC76.24 extends this trial with a bounded generic two-digital-lane `RollbackTimeline`; the concrete FC Mesen network/session/permissions remain in the main mod, not in the shared library. GBA11 and MD1 reuse ABI1 without another bridge DLL. Only FC has the new JNI Netplay route; other systems must explicitly implement and validate their own capabilities. Main private provider adds default `acceptsFile/fileHint` methods so new cartridge formats need not be hardcoded in the main UI. See the [current trial guide](../piq-fc-arcade/design/FC76.24-GBA11-MD1-JNI试用说明.md).

`LibretroRuntime` is the backend-neutral owner-thread boundary; `LibretroProcess` retains its original constructors and record types. `LibretroRuntimes.create(profile, ResourceOwner.class, PROCESS or JNI_TRIAL)` selects explicitly; defaults do not change. `LibretroMemoryStore` preserves its old process overloads and adds runtime overloads with identical format/identity validation. Main-mod-owned JNI ABI1 supports pinned Windows x64 software cores and opt-in WGL compatibility; one active native session per JVM. Unsupported capabilities, including real PvZ state rejection, are not advertised. This is a trial API, not an automatic Netplay migration or a stable SDK. See [scope, saves, capability limits and addon example](../piq-fc-arcade/design/通用JNI一期-FC76.22-使用与附属接入.md).

Developer entry (2026-09-28): start with the [behavior and configuration contract, Chinese](../piq-fc-arcade/design/方块电玩功能行为与配置规范-v1.md) for distances, exit/power rules, menu buttons and admin-terminal scope. Follow the [SFC-based addon implementation guide](../piq-sfc-home/design/以SFC为蓝本-附属制作说明.md) for actual registration, core, device, input, watch, save and packaging paths. Existing differences and missing extension APIs are explicitly marked; these docs do not introduce a stable public SDK or change runtime behavior.

Supplementary workflow: [production process](../piq-fc-arcade/design/方块电玩模组制作规范-v1.md), [feature brief](../piq-fc-arcade/design/功能制作说明模板.md), [acceptance checklist](../piq-fc-arcade/design/功能制作验收清单.md), [backend capability declaration](../piq-fc-arcade/design/附属能力声明模板.md).

Shared Java API and input domain code for the FC main mod and its addons.
This directory is a source/build boundary, NOT a separately installed Minecraft mod.
The FC main JAR will contain this code once; addons compile against the FC main JAR.
This library must never depend on the FC, SFC or arcade implementation modules.

User decision (2026-09-10): the main mod includes FC, televisions, cabinets, writing UI and common
extension APIs. SFC, native arcade and future systems are optional addons. There is no separate
platform-mod installation step and no plan to require an FC addon just to play FC.

The first stage preserves legacy block/item/backend IDs, frame protocols, save formats and emulator
cores. Pure interfaces and input tests are groundwork, not proof that every old session has migrated
or that real Xbox / PlayStation / Switch controllers have been tested.

Build from this directory using `../piq-fc-arcade/gradlew.bat --offline check jar`.
The resulting `piq_retro_internal` JAR is for development only: do not put it in `mods` or release bundles.
No ROMs, BIOS, emulator native binaries or user data belong in this project.

## Addon-owned libretro cores (FC67 / SFC37, 2026-09-23)

An addon may call `new LibretroProcess(profile, AddonClass.class)`. The explicit class owns
the native-core resource lookup across NeoForge named-module boundaries. The worker,
manifest and JNA still come from the FC module. SHA256 validation, private extraction,
timeouts and process cleanup are unchanged. The original constructor retains FC ownership
for backward compatibility. Do not duplicate bridge classes or the worker inside addons.

SFC37 is the first consumer (Windows x64 local candidate). Each addon supplies its own
profile, identity, fixed native artifact and license; new hardware/API features may still
require a bridge upgrade. This does not enable RetroArch Netplay or migrate save formats.
