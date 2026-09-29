# FC 23 / SFC 11 automatic spectator contract

Implementation contract, not a multiplayer input API. Common types are in `cn.piq.fcarcade.cabinet`; client adapters are owned by root under `client.watch`.

## Common provider API (frozen)

* `WatchAnchor(BlockPos pos, UUID identity)` (immutable position).
* `WatchDescriptor(ResourceLocation provider, UUID source, UUID hostLease, ResourceLocation dimension, WatchAnchor origin, UUID link, List<WatchAnchor> screens)`; link nullable, 1–2 screens, all identifiers non-null otherwise. Cabinet provider ID `piq_fc_arcade:cabinet`; SFC provider ID `piq_sfc_home:sfc`.
* `WatchSource(WatchDescriptor descriptor, UUID hostPlayer)` is server-only identity metadata; no ROM, filesystem paths, controller assignment, or emulator.
* `WatchProvider.sources(MinecraftServer): List<WatchSource>`; `isCurrent(MinecraftServer, WatchSource): boolean`; `isParticipant(MinecraftServer, UUID player): boolean` (any active controller of this provider, not only this source); `canObserve(ServerPlayer, WatchSource): boolean` defaults true; `acceptsUpload(): boolean` defaults true. Cabinet provider overrides acceptsUpload false because existing Room media is relayed after player delivery.
* `WatchProviders.register(ResourceLocation id, WatchProvider provider)` rejects duplicates and more than 8 providers. Provider callbacks run on the server thread and must not force-load chunks. Sources are capped globally at 8, at most 8 spectators each.

## Wire API (frozen)

`WatchNetwork.ClientSink`: `acceptsConnection(Connection)`, `start(Start)`, `stop(Stop)`, `heartbeat(Heartbeat)`, `hostDemand(HostDemand)`, `stream(Stream)`; `WatchNetwork.setClientSink(ClientSink)`, `WatchNetwork.send(CustomPacketPayload)`.

* S2C `Start(long revision, UUID lease, WatchDescriptor descriptor)`.
* S2C `Stop(long revision, UUID lease, String reason)`; same revision as Start is its tombstone. A subsequent Start always has a larger revision, across sources. Repeated Start/Heartbeat cannot revive a stopped lease.
* Bidirectional `Heartbeat(long revision, UUID lease)`; server refresh is accepted only from the actual player connection owning exactly this current lease. Server sends every 40 ticks; client sends every 40 ticks. Timeout 100 ticks.
* C2S `Release(long revision, UUID lease)`; short 40-tick admission backoff.
* C2S `Available(boolean enabled)`; client announces on connection/busy change/every 40 ticks. Default enabled, false clears spectator lease and prevents re-admission; only affects sender, no control authority.
* S2C `HostDemand(long revision, WatchDescriptor descriptor, int watchers)`; `needed()` is watchers > 0. Revision is server-wide monotonically increasing, changes on membership count/source identity; same revision heartbeat every 40 ticks refreshes the client deadline. Zero disables publisher. SFC validates source + P1 hostLease before publishing.
* C2S `Media(CabinetRoomNetwork.Media media)`; same bounded video/audio codec, source UUID in room and original P1 lease in hostMember. Only registered upload providers/current P1 sender/current source with watchers accepted. Old room media remains unchanged.
* S2C `Stream(UUID lease, CabinetRoomNetwork.Media media)`; viewer lease distinguishes re-entry. Client verifies current connection, lease, source and hostLease before decoding. No observer Ready/Input/Reset/ROM packets exist.

`CabinetMediaSender.watchServerbound(Connection, List<CabinetMediaPacket>): boolean` and `watchClientbound(Connection, UUID viewerLease, List<CabinetMediaPacket>): boolean` reserve complete batches using the SAME original Connection window (196608 bytes), with actual write-completion release. No new watch window map.

## Service / client integration

`WatchService.relay(MinecraftServer, UUID source, UUID hostLease, List<CabinetMediaPacket>)` is called only with already validated complete host media, after existing room player forwarding. The service revalidates source/provider/current host and each recipient. No watch recipients means no spectator work.

Root client API: `WatchClient.registerDisplay(provider, DisplayAdapter)` (valid/render/volume/isParticipant), `registerHost(provider, Consumer<WatchNetwork.HostDemand>)`; public `WatchMediaStream(UUID source, UUID hostLease, boolean sender)` facade. No watcher factory/core/input ownership. Exactly one receiver/audio source per connection. Linked cabinet screens reuse one texture; no duplicate audio.

Admission scans every 10 ticks: nearest available valid source in same dimension within 16 blocks; retain current within 20 blocks (hysteresis), one source per player globally. Active controllers in any provider plus original FC/local cabinet sessions excluded. Never force-load a screen/origin. Source disappearance/host connection loss/identity or topology change closes watchers. Spectator mode itself is allowed; observation is read-only, not an interaction bypass.

Spectator egress whole-frame rolling budgets: per source 2 MiB per 20 server ticks, global 8 MiB per 20 ticks; charge every recipient copy including conservative 256-byte per-packet header. Round-robin recipients; skipped/slow viewers cannot queue or hold other viewers. Player media delivery always comes first and never consumes spectator budget. Global maximum 64 spectators (8 × 8). Existing media ingress limits, dimensions and sample clocks remain unchanged.

SFC PCM length is stereo frames (copy frames × 2 shorts, 48 kHz); no borrowed worker arrays cross async boundaries. During join pause, publisher may resend the latest static video every second with new video sequence, never replay PCM. No model/resource/old P1/P2 input changes.

The same `source + hostLease` keeps one publisher stream across zero-viewer intervals: use `sending(false)` and retain cumulative video/audio sequence clocks, not close/recreate at zero. The server intentionally retains anti-replay media sequence state for the whole source generation. A real connection/session/descriptor change closes the stream and supplies a new source generation. Both service and pure `WatchLedger` enforce the 8-source / 64-viewer maximum; `WatchBudget` uses fixed 20-slot memory.
