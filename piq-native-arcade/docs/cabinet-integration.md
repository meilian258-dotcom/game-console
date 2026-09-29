# Native cabinet module contract — 0.1.0-alpha.1

Owner: dual_lower. Local module only; does not itself establish working MAME emulation, game compatibility or multiplayer support. Root integrates the client/core and performs the only Gradle build.

## Registration and state

`cn.piq.nativearcade.registry.NativeArcadeRegistries`: `CABINET`, `CABINET_PART`, `CABINET_ITEM`, `CABINET_ENTITY`, `PART_ENTITY`. IDs `piq_native_arcade:cabinet`, `piq_native_arcade:cabinet_part`; no part item. Anchor BE exposes `assemblyId()` and `installed()`; proxy BE exposes `owner()` and `anchor()`.

`NativeCabinetStructure.resolveAnchor(Level, BlockPos)` only resolves already-loaded exact-owner cells. `complete(Level, BlockPos)` verifies the six physical BE identities and server ledger. Unloaded cells mean unavailable, not destroyed. No chunk tickets or scans. The native owner does not inherit `FcArcadeBlock` or register with FC sessions.

Six cells = two wide × three high × one deep. For north, part `(x,y,z)=(part%2,part/2,0)`. Clockwise rotations are north `(x,y,z)`, east `(-z,y,x)`, south `(-x,y,-z)`, west `(z,y,-x)`. Third row collision height is 5.6 model units = .35 block. SavedData key `piq_native_arcade_cabinet_assemblies`, UUID + anchor + facing + per-cell identity; six-bit closed cleanup mask `63`.

Placement snapshots roll back only the just-created matching BE/state. BlockItem declines externally supplied BLOCK_ENTITY_DATA/BLOCK_STATE. Breaking any owned part preflights all six chunks/permissions and each other owned part's BreakEvent. Ledger closes before awarding one item; cleanup never removes unrelated replacements. Unloading invokes the close callback but preserves ownership.

## Common-to-client handoff (no packet in this single-player preview)

NeoForge GAME event `NativeCabinetUseEvent` is posted on the SERVER THREAD after complete/permission checks, including forwarding a proxy click to the anchor's protection event and rechecking all six permissions.

Accessors: `level(): ServerLevel`, `player(): ServerPlayer`, `anchor(): BlockPos`, `assemblyId(): UUID`. Call `markHandled()` synchronously if a client integration accepted the event; otherwise the player receives an honest not-running preview message.

The client-only listener must require the same integrated server as `Minecraft.getSingleplayerServer()` and the local player's UUID. Schedule onto the Minecraft thread, then recheck the same world, physical BE and assembly UUID before `NativeArcadeClient.openAt(anchor)`. Never start a process from the common event listener on a dedicated server. Do not use this event as multiplayer authorization.

`NativeCabinetClosedEvent` carries `level()/anchor()/assemblyId()`. Stop only that identity's pending/running native session; a replacement at the same coordinates must survive a delayed close. Close also occurs during unload/incomplete assembly; this does not delete the world structure.

## Render integration

`NativeCabinetRenderer` is client-only MOD subscriber, registers the anchor BER, native item BEWLR, and standalone model `piq_fc_arcade:block/dual_arcade_body`. Only the anchor draws the physical body. Baked quads cache by actual model object to invalidate on reload. All existing textures/resources remain in the FC dependency.

Root installs `NativeCabinetRenderer.setVideoRenderer(VideoRenderer)` where `render(cabinet,partial,poses,buffers,light,overlay)` receives the ORIGINAL ANCHOR-LOCAL world-axis pose, outside the cabinet model yaw/Y-rebase. Callback is wrapped in push/pop. Default callback draws nothing, leaving the actual black glass. The item renderer never calls video.

`NativeCabinetRenderer.turns(cabinet)` returns 0..3. Public pure `NativeCabinetLayout.bounds(turns)`, `.screen(turns)`, `.frame(turns,coreDar)` and `.occupancy(turns)` delegate to FC frozen geometry. Returned points are already in final block units, including the screen's normal offset; DO NOT apply body Y rebase or yaw to these points again. Use core display-aspect metadata, including vertical games and non-square pixels; never stretch/crop based solely on raster dimensions.

North physical display is 24 × 13.5 model units at 22.5 degrees. Draw quad order with full UV: lowerMax(0,1), lowerMin(1,1), upperMin(1,0), upperMax(0,0). North high X is viewer-left. Body MODEL_SCALE=1, raw JSON MODEL_Y_OFFSET=.35 block; total height2.35. Model BER rotates around anchor(.5,0,.5). Item recenter uses .4 scale, (-1,-1.175,-.5), then raw Y+.35; parent item display is inherited from `piq_fc_arcade:item/dual_cabinet`.

Frozen dependency SHA256:

- `models/block/dual_arcade_body.json`: E7F0150F1E75C2DCA5D19549F8479579D4794A39F1198E718D297CEC74B1BEB8
- `textures/block/rocket_arcade_skin.png`: 789512ED7F867C015C6666D40809845DE430E85834CCF7BA4E48BCA57DE815E8

## Validation

`tools/check_native_cabinet.py --write`: actual javac/JUnit reflection executes 20 tests (four orientations, six-part entitlement and reload cleanup, canceled placement, permission/event recheck, geometric fit including 3:4); source/wrapper/frozen-resource checks and real native model preview. Output uses a new versioned category and refuses overwrites. `python -m unittest discover -s tools -p test_native_cabinet.py -v` adds eight tool tests including negative guard/rotation/reload cases. No Gradle was run by this module owner. No Minecraft runtime or multiplayer claim is derived from these checks.

One-time bootstrap/port scripts deliberately refuse existing destinations; they are provenance, not repeatable build steps. They never modify FC sources. Copied/adapted lifecycle code retains GPL-3.0-or-later attribution.
