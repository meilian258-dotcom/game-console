# Unified cabinet alpha15

User scope: fix SFC-to-FC-TV AV use, merge the three creative pages, and make existing single/dual cabinets select installed emulator add-ons. Keep original model resources, IDs, ROM stores, and legacy NES/network flow.

Common SPI: CabinetBackends plus server-authorized menu, persistent cabinet identity/backend, single-owner external leases and bounded heartbeat/release payloads. Only installed providers appear; absent providers fail closed. NES retains original gameplay; current SFC/MAME external providers expose local-only capability, not a promise of arbitrary ROM or online compatibility.

Client SPI: CabinetBackend creates an asynchronous CabinetEmulator with bounded input/frame/audio. The host handles non-pausing ROM/input UI, full source UV/aspect/rotation, audio, focus, lease and teardown. Add-ons only implement cores. SFC uses the existing published core API; MAME retains exact-child process isolation and pinned runtime.

Targets KOF97/Dino require actual user ROM sets and supporting BIOS/device files. No commercial game is bundled or claimed tested without evidence. Generic single-ZIP diagnostic success is not these games' compatibility acceptance.

No active-game hot-swap; installation requires game exit and recoverable backups.
