# FC23 automatic spectator independent QA

Author: `/root/fix_sfc_av`; 2026-09-11. Root integrates maintenance-manual record.

## Scope

No production, model, version, installation, ROM or frozen delivery changes. Added:

- `src/test/java/cn/piq/fcarcade/cabinet/WatchAuthorityTest.java` — 20 behavioral tests of the production ledger/budget.
- `src/test/java/cn/piq/fcarcade/client/cabinet/WatchMediaIsolationTest.java` — 7 media-thread/isolation/lifecycle tests using synthetic pixels and PCM only.
- `tools/qa/Watch23WireProbe.java` — real production registration, real NeoForge outer codecs, actual Connection/EmbeddedChannel write completion, source-connection queued dispatch.
- `tools/check_watch23_independent.py` — compiles tests/probe only against explicit compiled classes or an explicit final JAR; exclusive report creation and artifact hashes checked before/after.

Runner additionally includes root's 2 `WatchMediaStreamTest` and 5 `WatchLeaseStateTest` cases (34 total). Earlier source reports are retained. The final invocation must use `--jar` and a new report path; `--classes` reports are not final-package evidence.

## Concrete review findings

- Client previously treated a still-referenced but disconnected Connection as live; early tick return could preserve media/demand before Minecraft dropped its packet listener. Root fixed `liveConnection()` to return null for disconnected connections. This triggers cleanup and rejects queued old-connection payloads without changing wire format.
- The shared media sender now demonstrably preserves one 196608-byte physical Connection window across Room/Watch route changes and viewer lease changes. Actual completed promises free it; repeated old callbacks cannot free a new ticket. A separate viewer's reservation/completion is independent.
- Ledger source limit is enforced globally even if callers offer differing source sets. Budget uses fixed 20-slot rolling windows, not one retained object per packet. Removing a source does not refund global bytes already sent.
- SFC publisher read-only review confirms copied worker arrays, silent static frames during join pause, preserved sequence clocks across zero viewers, and no observer Ready/Input/ROM path. Other agent owns its 18 pure tests.

## Evidence and limits

`watch23-independent-source-v2.json`: 34 tests and 1356 real-wire assertions on then-current compiled production. The final probe also adds simultaneous independent-Connection backpressure checks; use the final report's count.

The wire probe executes `WatchNetwork.register`, checks actual directional registration, routes both actual outer payload codecs, bounds 24 KiB media chunks including outer headers, denies malicious/truncated descriptors, and tests queued source identity. It does not create Minecraft players/worlds/server instances, start an emulator, open sockets, load a commercial ROM, or access an audio device. It does not prove game render placement, audio playback, protection-plugin interaction, LAN latency or actual multiplayer end-to-end experience.

The late-worker test deterministically injects a completed result after closing the facade; this proves that the facade will not expose it, not that a scheduler race was observed.

Eight viewers is a capped capacity, not a full-quality guarantee: raw 48 kHz stereo PCM alone costs about 1.54 MB/s for eight viewers; the fixed 2 MiB/source/second budget can drop complete audio/video packets on complex scenes. Player delivery precedes and is not charged to the spectator budget.
