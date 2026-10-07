# FC33 fixed-snapshot server admission handoff

## Public API

`CabinetBackends.registerSync(ResourceLocation)` retains strict initial-state equality and the backend's existing network capacity.

New APIs:

```java
registerSnapshotSync(ResourceLocation id, int maxPlayers, String compatibilityId)
int syncMaxPlayers(ResourceLocation id)
boolean hostSnapshotSync(ResourceLocation id)
String expectedSyncCompatibility(ResourceLocation id)
```

Snapshot registration is explicit, immutable, requires an existing network-capable non-NES backend, cannot exceed its media capacity, cannot replace another sync policy, and requires a nonblank bounded fixed compatibility fingerprint. The existing `maxPlayers` remains the media capacity. `expectedSyncCompatibility` returns null for strict/unregistered backends.

`CabinetSyncPolicy` is a pure immutable policy. Its `Identity` validates the original Hello field bounds. Pinned policy checks the first host as well as each guest. Guest ROM SHA, content/BIOS manifest ID, compatibility, and fpsMilli must still exactly match the host. Only a pinned policy omits independent *cold initial hash equality*. It does not skip the host's initial upload hash check, guest restore assembly/hash/load-resave checks, or token ACK input gate. The native adapter must enforce its fixed two-ROM/BIOS profile before opening the DLL; no server DLL loading is introduced.

`cabinet-sync-2` is mandatory. Hello wire fields, state size (16 MiB), chunks (24 KiB), and all existing room fields remain unchanged. Old peers are not silently mixed under the prior semantic version.

## Capacity and recovery

Rooms select the persisted/active mode before calculating capacity. Local mode uses `syncMaxPlayers`; media uses existing network capacity. Linked three/four-seat topologies with snapshot capacity two are rejected before opening a room and in the settings page; they are not truncated. Unlinked two-seat and linked single+single remain valid. `validateLease` retains media capability checks and revalidates topology against the room's actual mode, so a legal sync2 room is not rejected merely because the provider also declares media4.

`CabinetSyncRecoveryWindow.available(head, snapshotFrame)` allows state ages 0 through 3599, rejects 3600 and future/negative frames, and never enlarges the original 7200-frame history. Repair entry, transfer tick, and matching-token ACK all enforce it. Expiry removes only that guest; the host continues. The latter half of history is reserved for the host to refresh its cache. The existing 900-tick transfer limit remains. Entering with an already stale cache closes the candidate with an explicit retry-after-refresh message, rather than repeatedly occupying the cache.

While any restore retains the cached array, a new upload Offer is still deferred. Same accepted token is re-granted without extending its deadline. The client owner is implementing bounded latest-checkpoint retention/retry and the native 1800-frame snapshot interval (digest stays 300). This subtask did not change client code.

## Exact production ownership

Modified source:

- `cabinet/CabinetBackends.java`
- `cabinet/CabinetSynchronizer.java`
- `cabinet/CabinetRooms.java`
- `cabinet/CabinetSyncSettings.java`
- `cabinet/CabinetSyncNetwork.java`

New source/classes:

- `cabinet/CabinetSyncPolicy.java` -> `CabinetSyncPolicy.class`, `CabinetSyncPolicy$Identity.class`
- `cabinet/CabinetSyncRecoveryWindow.java` -> `CabinetSyncRecoveryWindow.class`

Expected changed-behavior class stems under `cn/piq/fcarcade/cabinet/`:

- `CabinetBackends`
- `CabinetSynchronizer`, `CabinetSynchronizer$Sync`
- `CabinetRooms`
- `CabinetSyncSettings`
- `CabinetSyncNetwork`, `CabinetSyncNetwork$Hello`

Other existing nested classes have no authorized behavior changes. They may have only line/debug/nest metadata changes after recompilation; protect their old method instructions rather than broadly allowlisting their bodies. In particular the remaining `CabinetSyncNetwork` payload codecs are unchanged.

## Tests and evidence

New source tests:

- `CabinetSyncPolicyTest`: 14 actual pure behavior tests.
- `CabinetSyncRecoveryWindowTest`: 5 actual boundary/state/timeline/ledger tests, including full 3,334,118-byte assembly while the host timeline keeps advancing and guest-only release preserving host edge FIFO.
- `CabinetSnapshotSyncSourceTest`: 6 supplementary wiring contracts, explicitly not runtime networking evidence.

Updated `CabinetSingleSeatSourceTest` checks mode-aware topology while preserving the existing single-seat/media contract.

New `tools/qa/CabinetSnapshotSync33Probe.java` executes the actual backend registry and actual MC outer packet codecs. It checks fixed-policy registration, no capability after a rejected registration, mandatory sync2, wrong payload direction, different initial states under pinned vs strict policies, and a synthetic 3,334,118-byte state through both wire directions. No commercial state/ROM bytes are used.

Runner:

```text
python tools/check_snapshot_sync33.py --fc <final FC33.jar> --report <new report.json>
```

Default is final-JAR-only: compiles tests/probes only, checks exact production CodeSource, and fences final JAR bytes. `--source` is explicitly non-final source preflight and compiles only the owned production files against a supplied baseline jar and cached real APIs.

Completed source preflight: `design/snapshot-sync33-source-v2.json`, SHA256 `AA2D421DE28F9D136CEC2A1B77D352DBABC4AE9FB5DA4E525DB0C65526BD3EBD`.

- 48 tests passed (37 pure behavior + 11 source contracts).
- 1003 real registry/codec assertions.
- 276 actual outer packet round trips; 136 chunks each direction; maximum outer C2S payload 24741 bytes.
- No Minecraft world, socket, emulator, or two-computer play started. This does not replace the native bootstrap-32 restore test or final MC network acceptance.

Earlier `snapshot-sync33-source-v1.json` remains historical; v2 includes the final ACK history/token guard. Root owns the combined handbook/build/package record.

## Final activation review addendum

Root approved one further server guard after the independent client review: a matching-token ACK must be no later than the current authoritative head and no more than 120 frames behind it (`CabinetSyncRecoveryWindow.MAX_ACTIVATION_LAG`). Token identity is checked first. A far/future ACK explicitly closes only that guest, never activates it or pauses/restarts the host. This complements the client's independently implemented <=6 queued-frame catchup gate; no wire fields changed.

Final source preflight is now `design/snapshot-sync33-source-v3.json`, SHA256 `A14375AFA9F8D58D764788B9E32814A2167DE0592F4F2840F098963E01FD22C9`: **51 tests passed** (39 pure behavior, 12 supplementary source contracts), plus the same **1003 actual registry/codec assertions and 276 real outer round trips**. Recovery-window behavior now has 7 tests and source wiring has 7 tests. The 0/120/121-frame, future/negative frame and large-long boundary cases are executed. Production class ownership is unchanged from the list above. v1/v2 reports are preserved, not overwritten.
