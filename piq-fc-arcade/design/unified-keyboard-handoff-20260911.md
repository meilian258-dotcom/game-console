# Unified keyboard handoff

## Shared API

`cn.piq.retro.client.KeyboardConfig`: immutable NES/SFC/ARCADE configurations; LEGACY/NUMPAD/WASD/CUSTOM presets, per-system preserved custom arrays and F8/F7 defaults. Native NES bit order is A/B/Select/Start/Up/Down/Left/Right; SFC and arcade retain the existing 12-bit protocol. SFC B/A/Y/X/L/R is keypad 1/2/3/4/5/6 or J/K/L/I/O/P. ARCADE new presets instead map buttons 1/2/3/4/5/6 (bits 0/1/8/9/10/11) sequentially to those same physical keys. ARCADE LEGACY is unchanged.

`KeyboardInput.settingsSnapshot()` returns config/revision/warning; `save(draft, revision)` explicitly writes only `config/piq-keyboard.properties`. Reads are bounded to 64 KiB; parent links/special files are refused, stale revisions fail, own temporary file is atomically replaced. No new option rebinding or `options.txt` edits. Existing FC/SFC interrupted-suppression journal recovery is deliberately retained.

`registerLegacy(Profile, Supplier<int[][]>)` supplies real existing primary and alternate keys for the settings display. `displayKeys(Profile)` gives primary native-order keyboard codes; a mouse primary becomes unbound in the keyboard-only editor. `keys(KeyMapping...)` and `legacyKey(KeyMapping)` preserve mouse bindings internally, including physical-neutral checks. They never consume mouse events.

`attach(owner, profile, keys, authorized, forceRelease, edge)` uses the host's exact identity and authorization rather than acquiring another global owner. `poll` returns mask/enabled/armed/locked. `pause` clears this owner's state before invoking the guarded force callback; `release` removes ownership. Callbacks use the original host send/capture/force-release path and preserve sequence numbers, leases and edge FIFO behavior. SFC's force callback recursion terminates because the state is already paused and callback reentry is guarded.

LEGACY/NUMPAD start in parallel-world mode. WASD starts free, explicitly requiring the toggle to play. Toggle enters locked mode or returns to free mode. Free/GUI/unfocused input is zero and the host gamepad is paused, without a core reset or session stop. Resume requires all bound physical game keys to be neutral and a fresh press. Settings opens globally in an active world without a screen; toggle requires a current authorized owner. Escape and mouse/right-click are never captured by the keyboard hook.

Actual current primary/alternate/Reset/Mute LEGACY bindings are checked before saving hotkeys. A preexisting F7/F8 conflict disables that hotkey, preserving the original game key; the user is directed to `/fc-controls` to rebind. No hardcoded LEGACY fallback is treated as an authoritative current mapping. `settingsSnapshot.warning` includes these live conflicts.

## Implementation scope

New shared classes: `KeyboardConfig` + Profile/Preset/Bindings; `KeyboardConfigStore` + Loaded; `KeyboardControlState` + Mode; `KeyboardRouting` + Route; `KeyboardInput` + Sample. New client Mixin `cn.piq.fcarcade.mixin.KeyboardHandlerMixin`, `piq_fc_keyboard.mixins.json`, one template mixin registration.

Existing FC classes modified: `ArcadeKeyMappings` (register real keys; stop new temporary rebinding), `ClientArcadeSession` (poll/mix/force/identity only), `ClientArcadeEvents` (install/settings command), `CabinetClientBackends` (same input callbacks; SFC backend uses SFC configuration, otherwise ARCADE; startup message). Native: only `NativeArcadeClient` keyboard polling/lifecycle/startup message. No Native helper/core/model/network or SFC sources modified by this agent.

Generic SFC cabinets preserve the old generic cabinet LEGACY keys, including O/P, but use the SFC custom/preset profile; they do not overwrite the globally registered SFC home KeyMappings for the settings display. Non-keyboard rendering/server methods were not intentionally changed.

## Verification

`tools/check_unified_keyboard.py --report <new-report>` compiles four pure production classes plus 47 JUnit tests. It checks quick press/release, no repeat resurrection, GUI/focus/toggle neutral rearming, alternate keyboard and mouse bindings, per-owner separation, native bit order, immutable per-profile settings, explicit save/roundtrip/CAS refusal/invalid and oversized files, and actual permission-first routing for settings/toggle/GUI/Escape. Additional existing host source contracts and actual ownership tests follow the new shared callback, without weakening current connection/owner/focus/Escape/GUI restrictions.

It also compiles the input facade/Mixin/FC mappings/shared cabinet/Native client against cached real MC/NeoForge APIs. The actual KeyboardHandler descriptor is checked against `keyPress(JIIII)V`, and compiled injection descriptor, HEAD/cancellable annotation, client-only JSON and template registration are verified. ClientArcadeSession's old `NativeImage.pixels` access requires the normal project access transformer and is covered by the normal Gradle build, not this raw-MC API fixture.

Evidence is source-based, not a final-JAR or live game result. Live Mixin transformation, an actual GLFW window/physical controller and dedicated-server multiplayer have not been exercised by this test. Root must perform final full builds and artifact validation; do not present these checks as live multiplayer gameplay.
