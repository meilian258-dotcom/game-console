# Game Console: Retro Platform — internal shared library

See the [branding and packaging policy](../source-control/BRANDING.md). Internal package names,
API identities and build outputs remain compatible. This library is not a player-installable mod.

## Read-only native budget queries (FC76.36 candidate, 2026-10-03)

`NativeLibretroBridge.freeSlotsIfLoaded()` reports the existing four-owner gate without loading a DLL or taking an owner lock. `LibretroJniRuntime.nativeSlotHeld()` checks the exact token/generation; it remains held until native teardown actually returns. These are budget snapshots, not reservations or permission to call a core off its owner thread. No ABI, core binary, save identity or four-slot limit changes. Public multi-source observer lifetimes live in the main mod, not this library; see the [first-batch contract and verification](../piq-fc-arcade/design/公共JNI多屏旁观第一批-20261003.md).

## Shared launch lifecycle (FC76.34 candidate, 2026-10-03)

`cn.piq.retro.flow.DeviceSessionFlow` is a pure, versioned state machine for preparation,
optional save selection, independent second-player permission, loading, READY and bounded shutdown.
Content capacity, save player labels and session join permission are separate fields.
It contains no world, network or storage implementation. The Minecraft adapter/UI belongs to the
main mod's `HomeLaunchServer/Network/Screen`; MD10 uses it and FC retains its existing save editor
before the shared confirmation. This does not migrate all legacy systems or establish a stable SDK.
See the [adapter contract, compatibility and verification record](../piq-fc-arcade/design/公共流程与MD接入-20261003.md).
Flash and PvZ are development-validation devices deferred by the user for this business migration;
their existing code and prior JNI evidence remain intact.

## Client defaults (FC76.26, 2026-09-29)

New confirmed migration target (2026-09-29): FC, SFC, GBA, native arcade, PvZ and MD must migrate their existing runtime modes to the shared JNI route, including existing light-gun support. Future emulator addons use JNI as their default integration target; Flash and Java ME are excluded from this migration. This is a development requirement, not a claim of completed adapters, multi-instance support or gun Netplay. See the [scope and acceptance plan](../piq-fc-arcade/design/JNI全面迁移-范围与验收.md). The following FC76.26 description remains the shipped baseline.

User-requested JNI defaults now use `LibretroRuntimes.defaultBackend(adapted)` for existing Windows x64 client adapters, plus connection-scoped `JniClientPreference`. Explicit runtime factories and server/process constructors remain unchanged. This is not automatic save migration, silent failure fallback or support for every emulator/mode. Addons calling these new methods require FC76.26+. See [scope and install matrix](../piq-fc-arcade/design/FC76.26-JNI默认与自动旁观.md).

## Historical Runtime API v1 / opt-in JNI (FC76.22, 2026-09-29)

FC76.24 extends this trial with a bounded generic two-digital-lane `RollbackTimeline`; the concrete FC Mesen network/session/permissions remain in the main mod, not in the shared library. GBA11 and MD1 reuse ABI1 without another bridge DLL. Only FC has the new JNI Netplay route; other systems must explicitly implement and validate their own capabilities. Main private provider adds default `acceptsFile/fileHint` methods so new cartridge formats need not be hardcoded in the main UI. See the [current trial guide](../piq-fc-arcade/design/FC76.24-GBA11-MD1-JNI试用说明.md).

`LibretroRuntime` is the backend-neutral owner-thread boundary; `LibretroProcess` retains its original constructors and record types. `LibretroRuntimes.create(profile, ResourceOwner.class, PROCESS or JNI_TRIAL)` selects explicitly; defaults do not change. `LibretroMemoryStore` preserves its old process overloads and adds runtime overloads with identical format/identity validation. Main-mod-owned JNI ABI1 supports pinned Windows x64 software cores and opt-in WGL compatibility; one active native session per JVM. Unsupported capabilities, including real PvZ state rejection, are not advertised. This is a trial API, not an automatic Netplay migration or a stable SDK. See [scope, saves, capability limits and addon example](../piq-fc-arcade/design/通用JNI一期-FC76.22-使用与附属接入.md).

Developer entry: start with section 1 of the [shared production and interaction standard, Chinese](../piq-fc-arcade/design/机器制作与交互标准.md) for the complete target workflow and device-type branches. Consult the [component workflow and reuse map](../piq-fc-arcade/design/全组件运行流程与复用接口总览.md) for actual UI, source/API, runtime, save and cleanup paths and their gaps. Reusing a layout or registering an interface does not complete the business workflow.

The [behavior and configuration details](../piq-fc-arcade/design/方块电玩功能行为与配置规范-v1.md) retain distance, permission and admin-terminal contracts. The [SFC-based guide](../piq-sfc-home/design/以SFC为蓝本-附属制作说明.md) is a historical SFC41 wiring and compatibility reference; its early process default and separate session/save paths are not the target for new addons. These documents do not establish a stable SDK or claim that the target workflow is implemented.

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

## JNI-first adapters (GC-110 development, 2026-09-29)

New emulator adapters target `LibretroRuntimes` with a trusted `LibretroProfile` and
the addon's resource-owner class; do not copy native bridge classes into an addon.
Windows x64 is the currently implemented JNI platform, not a Linux support claim.
ABI2 reserves up to four independent owner-thread sessions, each with a private core
library and generation handle. A hung owner retains its reservation and files; do not
force-unload it. `isJniBusy()` now means capacity unavailable, not "one core exists".
Per-system limits and server authorization still apply independently.

Named ZIP/BIOS content uses bounded `loadBundle` or the local-only `loadFiles` adapter:
safe relative names, immutable copies and content identity checks remain mandatory.
The optional trusted core artifact byte budget (maximum 512 MiB) only accommodates
large pinned DLLs; it does not increase ROM/upload/state limits. No network-supplied
DLL path, SHA override or core options may become a trusted profile.

JNI is not a Netplay capability flag. Declare and test complete state restoration,
all input ports, save ownership, clock, AV timing, cancellation and teardown first.
Known FBNeo NeoGeo restore failures and the special process-isolated NeoGeo snapshot
adapter remain unresolved. This working tree is not an installable full migration;
see [scope and evidence](../piq-fc-arcade/design/JNI全面迁移-范围与验收.md).

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
