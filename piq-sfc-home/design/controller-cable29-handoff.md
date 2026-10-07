# SFC17 physical controller technical notes (2026-09-11)

Scope: SFC controller input and cable integration. Game instances, saves, ROMs and previously delivered packages remain unchanged.

## Behavior

- Physical loan distance is **player feet to the bound console block center**, full 3D. Exactly 6 blocks is allowed (`distanceSquared <= 36`); any greater distance revokes that one physical lease. A nearby TV cannot extend this cable. The client input gate uses the identical predicate. The independent execution Host has no such distance limit.
- Expiry removes exact SFC-controller/type + lease matches from current personal inventory, both menu views, cursor and open destination slots, including replacement ItemStack objects or distinct duplicate copies. It marks affected slots dirty and synchronizes menus; no different lease or unrelated item is changed.
- Offline dock clicks borrow a physical controller without TV, card, Session, ROM download, worker, input authority or starting a game. Console identity/loading/dimension, 6-block range, world border and public permission callbacks are checked before/after the operation. One loan per player and one loan per physical console port.
- Power-off/AV stop stops runtime, clears FIFOs and notifies execution clients, but keeps valid physical loans. Removing/unloading the console, >6 blocks, invalid inventory/connection or explicit body return revokes loans. Disconnection/dimension changes retain existing fail-closed Host/session rules.
- Power-on can attach the Host's existing loan on this same console, provided the card enables that port. A different player's preborrowed controller still requires the Host's normal opt-in and approval, by clicking its original dock; the existing snapshot handoff is reused. No automatic approval or second local player.
- Candidate cancellation/failure closes candidate runtime and releases transfer budget while keeping the physical loan. Explicit return or range expiry also aborts a pending join, then really revokes the loan. Idle loans do not count as active control or suppress automatic spectator viewing.

## Exact production scope

All paths relative to `piq-sfc-home/src/main/java/cn/piq/sfchome/`:

1. `server/SfcControllerAuthority.java`: added pure `MAX_CABLE_DISTANCE=6.0` and `withinCableDistance(double squaredDistance)`.
2. `server/SfcControllerInventory.java`: added identity-deduplicated, exact-token generic `revoke` used by the real server cleanup.
3. `server/SfcHomeServer.java`: range gate/copy revocation, physical offline claims, runtime-only stop, approval reuse for preborrowed lease, visual receipt setter on grant/release. Existing Host/worker/network records and frame/core algorithm unchanged.
4. `client/SfcHomeClient.java`: only `ownsController()` adds the same console range gate; no background Host/watch distance changes.
5. `world/SfcHomeConsoleBlockEntity.java`: visual-only player/lease UUID arrays, `controllerVisualPlayer(int)`/`controllerVisualLease(int)` getters, atomic `setControllerVisual`, existing NBT/updateTag transport; loaded server leases reset as before. The old setter's true semantics retained; false clears receipts.

No new production classes. Allow these five class stems and their existing nested classes; renderer changes require a separate audit. No model, PNG, render geometry, network, core, backup or UI class is changed by this patch.

Visual receipts never authorize server input. The public inherited `visualPowered()` supplies the separate running light; cable rendering uses the shared cable adapter.

Actual controller-plug cable-end anchors in unrotated 1/16-block model units after the existing 1.5 scale:

- P1: `(11.0375, 1.08375, 2.582375)` (front-view left/high X).
- P2: `(4.9625, 1.08375, 2.582375)`.
- Divide by 16 for block coordinates; apply existing facing rotation. Do not scale controllers again.

## Validation

- Full SFC `gradlew.bat --offline check jar`: **309 tests, 0 failed/errors/skipped**, exit 0. Thin17 SHA256 `0706ACAF42F2D496D5364443191826FE6E71213097B19D12395A6084487DF94A` (development build, final merged-package verification is still required).
- `tools/check_sfc_controller_cable.py`: 35 real pure-policy/queue/source-wiring tests, `design/controller-cable29-pure-v1.json`. This was the initial range-only source checkpoint, before the subsequently tested offline-loan change.
- New `SfcOfflineControllerSourceTest` adds 7 wiring checks; updated `SfcJoinSourceTest` preserves candidate-runtime/budget/Host-frame safety while matching the explicitly requested physical-loan retention.
- `tools/check_sfc_controller_receipts.py --fc ../piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.29.jar --sfc build/libs/piq_sfc_home-0.1.0-alpha.17.jar --report design/controller-receipts29-actual-v1.json`: **2007 assertions**, actual NeoForge registration, real SFC item type (no substituted holder), actual ItemStack copy/cursor/Slot aliases and precise revocation, BE UUID NBT/updateTag roundtrip, actual client grant gate across shutdown/new sessions. Probe only was compiled; production loaded from input JAR with CodeSource checks. Full input paths/hashes recorded, inputs unchanged.
- Real API probes do **not** start a Minecraft world, player, permission-plugin environment or network socket. They do not claim to execute full server `claim/stop/release` world transitions; those integration paths also have compiled source wiring guards. Existing dual-worker/Watch final-JAR regression may be rerun after final merge.

## Added/updated QA files

- New `src/test/java/cn/piq/sfchome/server/SfcControllerCableTest.java`.
- New `src/test/java/cn/piq/sfchome/server/SfcOfflineControllerSourceTest.java`.
- Updated `src/test/java/cn/piq/sfchome/server/SfcJoinSourceTest.java`.
- New `tools/check_sfc_controller_cable.py`, `tools/qa/SfcControllerCableTestRunner.java`.
- New `tools/check_sfc_controller_receipts.py`, `tools/qa/SfcControllerReceiptProbe.java`.

For final evidence, rerun the receipts command with final merged SFC and final FC JAR paths and a new report path; never overwrite old evidence.
