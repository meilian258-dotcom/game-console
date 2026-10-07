# FC29 cartridge save-mode restoration (2026-09-11)

## Real behavior

The game-library tab restores a `当前卡存档：关闭/个人/机器` button in the existing detail action row. Panel dimensions, shared layout and cover-tab controls are unchanged. It is enabled only for the currently written server ROM when that exact ROM is the visible selection, not during scan/upload/another edit. Selecting a different game or filtering out the current selection cannot silently change its save mode. A new game must first be written to the card.

The button cycles the existing `RomSaveMode` enum. Modes are shared per ROM hash across all FC cards and cabinets; changes affect the next powered session, never switch an active core or delete old saves. Machine saves use machine identity; personal mode opens the existing owner-slot workflow for the player who powers on, independently of later controller borrowers. The gun core uses a separate namespace. Runtime behavior is implemented by the FC runtime agent, not by this UI change.

## Exact production scope

All relative to `piq-fc-arcade/src/main/java/cn/piq/fcarcade/`:

- `client/ClientCartridgeEditor.java`: current-card save button/tooltip, selection+busy gating and send method; information area stops above the reused action row. The enum switch may add `ClientCartridgeEditor$1.class`; allow the entire existing `ClientCartridgeEditor` stem, including nested classes. Upload/cancel/selection-write paths untouched.
- `home/CartridgeNetwork.java`: `SET_SAVE_MODE = 9`, `Request.total` carries exactly 0/1/2; extra fields/data/offset/nonempty filename are rejected. Dedicated cartridge registrar **30 -> 31**, so matching client/server are required. Request/Reply layout otherwise unchanged; existing catalog already carries `saveMode`. Allow `CartridgeNetwork` stem and nested classes.
- `home/CartridgeComputerBinding.java`: pure `permitsSaveModeSetting(mode,requestedRom,writtenRom,romExists,busy)` used by UI and service. Hash must be full lowercase SHA256 and match the written card.
- `server/ServerCartridgeService.java`: token/card/slot/OP/computer/protection-event guards remain mandatory; no upload can be changed. Exact current ROM rechecked after permission callbacks immediately before `library.setSaveMode`. No cartridge name, ROM, cover or assembly mutation. Allow outer and existing nested stem.
- `server/ServerRomLibrary.java`: only `setSaveMode` adds the same previous-value rollback already used for player metadata if atomic property-file persistence fails. No broader store/index/save algorithm change.

No new production source class, no edits to `ServerArcadeSessions`, no resource/UI texture changes, and no `CartridgeWorkbenchLayout` production changes.

## Tests and tools

- New `home/CartridgeSaveModeSettingTest`: 5 pure/source-contract tests (all modes, wrong/unwritten/local ROM, upload/busy, canonical request, real authority wiring, selection/refresh/resize no unintended send).
- New `server/ServerRomSaveModePolicyTest`: 4 **actual ServerRomLibrary disk tests** in JUnit-created temp directories: all modes persist/reload, reupload keeps metadata, missing ROM/null rejected, failed first/change write restores memory and leaves previous bytes intact.
- `client/ui/CartridgeWorkbenchLayoutTest`: 1 added existing-row geometry test across supported sizes.
- Updated ordinary `tools/test_cartridge_computer_access.py` version/operation bounds assertion to 31/9; old held-card/protection checks retained.
- New `tools/check_cartridge_save_mode.py`, `tools/qa/CartridgeSaveModeTestRunner.java`, `tools/qa/CartridgeSaveModeProbe.java`.

Development command: `python tools/check_cartridge_save_mode.py --fc build/libs/piq_fc_arcade-0.31.0-alpha.29.jar --source --report design/cartridge-save29-source-v1.json`. Result: **13 JUnit tests + 23 actual Request constructor/wire-codec assertions passed**, no world/core. It compiles selected development production sources against the actual MC API; it is not final-JAR evidence.

Final command: omit `--source`, supply frozen final `--fc`, choose a new report path. It compiles only tests/probes, verifies five production class origins from the input JAR and records exact input path/SHA. Expected codec assertion count is 28 (23 behavior + 5 origins). Full FC build is a separate check.

## SFC saving boundary (important)

The SFC card editor never had a real None/Player/Machine save mode. `SfcPlayback` cold-starts ROM/reset; its Host periodically writes local recovery files and on close, explicitly **not automatically loaded and not a server save** (`SfcClientFiles`). This turn adds no misleading SFC save-mode button and does not invent persistent multiplayer saves. A proper SFC save policy would require separate verified snapshot identity/restore/authority/network work.
