# Cabinet addon API 1 (FC alpha15)

Register a stable ResourceLocation with CabinetBackends.register(id, displayName, localOnly) on common setup. Do not embed client types in common registration. NES is reserved; duplicate IDs and catalogs over16 are rejected. A backend is a capability declaration, not a promise that every ROM works.

On Dist.CLIENT setup, call CabinetClientBackends.register(id, provider). CabinetBackend.open(Path) runs off the game thread and returns an asynchronous CabinetEmulator. A provider owns bounded file reads, platform checks, core initialization, input consumption, frame scheduling and core teardown. The host owns the physical machine, screen, input UI, audio sink and lease.

CabinetFrame carries width/height <=2048, a complete ABGR buffer, finite raw unrotated display aspect, 0..3 libretro content rotation, and at most32768 shorts of stereo48k PCM. Arrays transfer ownership to the host: never mutate them after publishing. Polling must not block. Aggregate bounded audio across frames so a lower rendering rate does not discard every other audio packet. Host applies odd-rotation aspect inversion exactly once and fits without cropping.

Input bits are libretro B,Y,SELECT,START,UP,DOWN,LEFT,RIGHT,A,X,L,R. offerInput represents ordered edges, not just20Hz latest state. clearInput must discard pending pressed edges and release both ports. close must be idempotent and nonblocking; no new native worker may start until an old non-thread-safe core has actually closed. Providers must clean up their own pending worker on cancellation, including startup failure.

Physical authorization uses an identity/dimension/anchor-bound server lease, not client coordinates. Menu TTL600ticks, lease TTL80ticks, heartbeat10ticks, one owner per cabinet and one cabinet per player, bounded global sessions. Server rechecks actual loaded block entities, structure, permissions, distance and activeNES conflicts. Unload, replacement, teardown, logout and expiry close the lease. Persisting a different backend requires OP2. Missing addons fail closed without rewriting the selected ID or deleting ROM data.

Current external providers are localOnly and deliberately refuse publishedLAN/remote servers. A future network provider needs its own authenticated synchronization and invitation/port authority; it must not share the owner's lease with arbitrary players. The current host makes no network lockstep or savestate promise for future addons.

Examples: piq-sfc-home/client/cabinet/SfcCabinetProvider uses the existing public SFC WASM core; piq-native-arcade/client/NativeCabinetBackend uses a separately owned MAME process. Both reuse the main mod's existing single/dual cabinet models. Do not register extra duplicate creative tabs or silently replace another addon backend.
