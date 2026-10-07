# SFC compact cartridge workbench technical notes

- Date: 2026-09-10. Scope: compact SFC workbench UI.
- User scope: compact centered vanilla-style device UI, matching FC/SFC cartridge workbenches. No version, build configuration, server, network, emulator, model, packaging or installation change by this patch.

## Production delta

- `client/SfcCardEditorScreen.java`: shared `CartridgeWorkbenchLayout` uses all 14 controls; title `SFC / 卡带工作台`, subtitle current cartridge; first toolbar row name/save/players, second games/cover/search/refresh; five fixed footer buttons ROM directory/cover directory/previous/next/close. Clear/restore are immediately above the explicit primary cover action. Shared `DeviceUi` supplies vanilla button rendering and gray panel colors.
- The two fixed folder buttons call the existing bounded async scanner for the explicit ROM/cover directory without switching the selected tab. Parent/real-file validation, tickets, same-connection/screen authorization, upload transactions and cancellation are preserved.
- One search value is kept across both tabs. It filters in-memory rows only, preserves selection/drafts, resets page anchors on query change, and blocks primary actions for a selected row hidden by the current filter. Typing rebuilds only row widgets, not the full screen, so the active text field keeps focus. Cursor restoration on widget initialization uses `moveCursorTo(..., false)`.
- Detail section consistently shows pending game/cover, name and source. Limited-height layouts compact this to one/two lines; tooltips retain complete details. Text stops above cover controls/primary action. Both tabs include page count in the status strip.
- `client/SfcCardLibrary.java`: adds a UI-only display-name function, defaulting to the original filename. Search accepts original names and display aliases; a non-dirty automatic title draft uses the alias. Manually edited drafts keep precedence. Row key/path/hash/original filename remain unchanged.
- New `client/SfcWorkbenchDisplay.java`: current server ROM uses the actual card title. Other exact 64-hex `.sfc`/`.smc` (or extensionless) server filenames display `未命名游戏 · <first 8>` without guessing a game name. Normal names/local filenames remain intact; original filename, full hash or path remain in tooltips.
- Existing `SfcCardEditorScreen$Phase` and `$Imported` source definitions are unchanged. No asset or registry ID changes.

## Tests and evidence

- New `SfcWorkbenchDisplayTest`: 11 tests, including alias/raw-name search, stable raw identity, exact hash detection, local paths, default vs manually edited drafts, filter-hidden selections and bounded detail lines.
- Existing `SfcCardEditorSourceTest` keeps its five security/worker/late-message/draft contracts while strengthening checks for all shared controls and shared search. Other existing editor state tests are unchanged.
- New QA files: `tools/check_sfc_workbench_ui.py`, `tools/qa/SfcWorkbenchTestRunner.java`.
- Actual MC 1.21.1/NeoForge API local javac compilation succeeded without Gradle. The pure suite passed 36 tests (25 existing plus 11 new).
- Compared the compiled screen with the exact frozen merged SFC6 JAR: 21 non-UI methods preserve instructions, including authorization, send, import/write transactions, update/tick, cleanup and close. UI methods and the explicitly directory-parameterized scan entry were excluded, not silently declared unchanged.
- Exclusive report: `design/sfc-compact-workbench-validation-20260910.json`; includes source hashes and method count.
- Production code is ready for integration; shared DeviceUi/layout changes and final Gradle/package validation remain separate.

## Limitations

No Minecraft scene/screenshot, actual directory window, live upload, client/server, physical controller or native core was started. Pure geometry/state and source/bytecode checks do not replace in-game visual validation. Frozen alpha19 artifacts, existing reports, user instance/config/save/ROM files remain untouched.
