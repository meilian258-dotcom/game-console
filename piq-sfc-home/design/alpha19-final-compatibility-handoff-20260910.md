# Alpha19 final compatibility technical notes

- Date: 2026-09-10. Scope: final SFC compatibility audit.
- Scope: tools/probes only in this audit; no production edits, Gradle, installation or Minecraft startup.
- Final reports (exclusive new files, unchanged afterward):
  - 最终独立成品审计。
  - SFC 双核心兼容审计。

## Actual final artifacts

| Artifact | SHA-256 |
| --- | --- |
| FC `0.31.0-alpha.19` | `C36E5878C9957F95C7C95DFD28963FF88972EE41F5CF208603BF2612F79000C4` |
| Combined SFC `0.1.0-alpha.6` | `EE32DC98CEC55F2B7361DD2588C647D63DA6AD556CE0E6E20F1B333C8B47627D` |
| Native `0.1.0-alpha.6` | `B503F5BE9F0C1DAA3640CE1926CCAA268577A76FE709CEFBFA05D9FFEEF5E422` |

## Validation

1. `piq-fc-arcade/tools/verify_retro_alpha19.py --fc ... --sfc ... --native ... --report <new path>` passed directly against the above artifacts. It checks immutable baseline hashes, class/runtime ownership, declared dependency graphs, exact frozen core metadata/resources and selected protocol/server/lease/runtime class bytes.
2. Actual NeoForge `JarModsDotTomlModFileReader` → `ModFile.identifyMods` → ASM `compileContent` discovery passed. The combined SFC JAR declares and exposes both old `piq_sfc_arcade` and `piq_sfc_home` entry points. Actual FML ModInfo/Maven VersionRange checks accept FC alone and reject addons without FC. No mod entry point was executed; this is not full FML bootstrap or ModSorter execution.
3. Protected byte-identical entries: FC 931, home/SFC 58, Native 6; the frozen SFC core additionally preserves all 60 non-manifest/non-TOML entries exactly. FC `ServerCabinets.class` permits only the single approved ending-message constant, with all execution bytes otherwise protected. No debug-table exceptions were needed on the final artifacts.
4. Real NeoForge outer serverbound packet codec: 37 assertions, maximum upload packet 30,856 bytes, below 32,767. No old separate SFC core was added to the probe classpath.
5. Checker positive/negative suite: 17 tests passed after the final launcher adjustment. It rejects optional/missing/incorrect FC dependencies, standalone platform dependency, duplicate/foreign runtimes, changed core classes/assets/AT, changed network families and any Native localOnly registration mutation.
6. `piq-sfc-home/tools/run_alpha19_sfc_join_core_probe.py --fc ... --sfc ... --report <new path>` passed 792 assertions using two real WASM cores and the existing original diagnostic ROM. P1 first ran 127 frames; its 1,294,513-byte state was imported into P2; 240 following frames had matching timing/pixels/audio/state. An aborted transaction did not reset P1, which continued afterward. CodeSource checks prove the production Gate/core belong to the final combined SFC, and Wasmtime belongs to final FC. Only test/probe sources were compiled.

## Isolation and limitations

- Windows Java @argfile parsing did not preserve Chinese classpath paths. The final runner uses only SHA-checked identical copies in its own ASCII temporary directory; input and copy hashes are checked before and after probes. The final compatibility report records the mapping. Copies are temporary; no production or frozen artifact was changed.
- The earlier `design/alpha19-fml-discovery-fixture-20260910.json` is explicitly a temporary metadata fixture report, not the final delivery verdict. The two final reports above supersede it for delivery evidence without overwriting it.
- FC and SFC existing network/session safety were preserved; Native remains `localOnly` and must not be presented as Minecraft multiplayer emulation.
- These probes do not test a real Minecraft client/server, protection plugins, physical controllers, actual `SfcPlayback` worker dispatch or commercial games. Diagnostic ROM state continuity is not a claim all games support simultaneous midgame P2 joins.

## Added tools

- FC: `verify_retro_alpha19.py`, `test_verify_retro_alpha19.py`, `check_alpha19_discovery_fixture.py`, `probes/Alpha19ModDiscoveryProbe.java`.
- SFC: `run_alpha19_sfc_join_core_probe.py`, `qa/Alpha19SfcCoreOriginProbe.java` (reuses existing `SfcJoinCoreProbe.java` and original `SfcLegalTestRom.java`).
- Prior frozen validators and reports were not weakened or overwritten.
