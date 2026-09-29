# FC32 generic cabinet deterministic synchronization

Owner: `/root/fix_sfc_av`. Local development only; no installation, ROM modification, publishing, or game-world launch.

## Implemented behavior

- Server-owned 1/2/3/4-seat rooms retain existing consent, physical topology, protection, exact connection/member, distance, heartbeat, and removal checks. An immutable per-room choice selects media or deterministic local execution. Only an administrator may change an idle cabinet's saved choice; an active run is never reset or switched automatically.
- `cabinet-room-4` adds the assignment mode; mandatory `cabinet-sync-1` carries the separate synchronization lane. Old media Ready/Input/Reset cannot bypass local-sync readiness.
- Every participating client has one dedicated worker owning factory, step, state, and close calls. The server runs no emulator. It produces authoritative per-frame four-port masks using independent 32-edge FIFOs and a bounded 7,200-frame history. No prediction is used.
- Hello compares server-authorized shared ROM hash/content manifest, core compatibility ID, frame rate, and complete initial-state SHA-256. ROM resolution runs off the Minecraft thread through the root-owned shared-game service.
- The host uploads a verified initial state before input/frame advance begins. Later host checkpoints do not pause or reload the host. Approved guests receive a bounded snapshot and authoritative history; their input is disabled until the matching complete restore transaction is acknowledged.
- Full state hashes every 300 frames compare guests against the host. Only a divergent guest is restored. Repeated failed corrections release that guest. Host failure safely ends the room rather than silently resetting a running game. Guests leaving clear only their own current and queued inputs.
- Media remains available to observers even in deterministic mode. Observers do not receive controller authority through this lane. MAME is not registered for local synchronization: real restore tests failed audio/state equivalence. Existing MAME media mode is retained.

## Resource/lifecycle bounds

- Snapshot maximum 16 MiB, ordered chunks 24 KiB, one incoming snapshot per room. Four room caches plus four incoming assemblies are bounded to 128 MiB; restore readers share the cache array. SHA verification uses a bounded one-thread worker/queue, never the server tick.
- All outgoing chunks/history use the same actual-Connection completion window as media/shared ROM/watch traffic.
- Guest restore/catch-up deadline 45 seconds. Normal startup deadline 180 seconds; only exact authorized active shared-game progress extends it, with 20-second completion grace and a 300-second overall ceiling. Removal clears deadlines and transaction state.
- Worker audio has an independent 28,800-short stereo FIFO (300 ms). Slow picture consumption returns accumulated PCM, not only the final frame's audio. Overflow drops oldest samples at stereo boundaries; restore/catch-up clears stale audio. Latest video remains droppable.
- Restore, revision changes, queued-step selection, and output publication share one monitor, while expensive core operations remain outside it on the sole worker. Cancelled/superseded results cannot publish into a new restore.
- A stale Restore packet is validated before any local key/pad pause. Resync requests retry at a bounded cadence if a server repair cooldown initially deferred them.

## Evidence and final entry point

- `design/cabinet-sync32-source-v1.json`: 29 passing real pure-policy/worker tests, including four-port order, partial/stale transactions, slow-consumer PCM continuity, fixed audio budget, cancellation during factory, and two latch-controlled restore races. This is explicitly source QA, not final-JAR evidence.
- SFC owner report `piq-sfc-home/design/repair19-workers-source-v1.json`: separate actual Wasm processes, host checkpoint at 300 and guest replay to 600; complete state agrees with both host and home execution. Must be rerun against frozen final artifacts.
- Final command: `python tools/check_cabinet_sync32.py --fc <final FC32 JAR> --report <new JSON path>`.
- Default final command compiles only test/probe classes, checks production CodeSource, executes the worker behavior suite, and uses real cached Minecraft/NeoForge outer packet codecs. `--source-pure` is an explicit non-final development mode only.
- No remote Minecraft server/client session, public-network latency, or physical hardware performance is claimed by these tests.

## Production ownership

Modified existing outer classes: `cabinet/CabinetBackends`, `CabinetRoomLedger`, `CabinetRoomNetwork`, `CabinetRooms`; `client/cabinet/CabinetBackend`, `CabinetClientBackends`. Existing nested classes follow actual compiled delta inventory.

New common classes: `CabinetSyncCore`, `CabinetSyncMode`, `CabinetSyncState`, `CabinetSyncGate`, `CabinetSyncSender`, `CabinetSyncSettings`, `CabinetSyncTimeline` + `Step`, `CabinetSynchronizer` + `Sync/Peer/Transfer`, `CabinetSyncNetwork` + `ClientSink/Hello/Input/Frames/StatePart/Upload/Restore/Ack/Active/Digest/UploadGrant/Resync/Mode/Setting`.

New client classes: `CabinetSyncClient` + `Upload`, `CabinetSyncWorker` + `Factory/Opened/Event/Restore`.

No assets, old NES/home-session algorithms, ROM files, existing frozen artifacts, or native cores were changed by this component. Root-owned shared ROM service, mode GUI/media tuning, and SFC-owned adapter/repair changes have independent handoffs.
