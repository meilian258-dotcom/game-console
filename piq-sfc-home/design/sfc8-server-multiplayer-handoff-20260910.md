# SFC8 server multiplayer review and repair

Date: 2026-09-10. Scope: SFC8 server multiplayer verification.

## Actual defect and repair

SFC7 reused a running session ID/epoch when the same player received a new P2 lease. `Ready` and `Leave` contained only that session identity. A delayed message from the retired P2 lease could therefore pass `member()` using the player's new lease, prematurely mark the new candidate ready or leave the newly joined port. Input packets already carried a lease; readiness/leave did not.

SFC8 server adds `controllerReady` and `controllerLeave`. Each finds the currently authorized active/candidate member, checks both the actor UUID and the exact current lease UUID, and only then invokes the existing readiness/leave behavior. The client/network agent adds the two lease-bound wrappers, removes unbound inbound registrations, and advances both SFC registrars to protocol 4. Old record codecs remain for the wrapper payloads; SFC7/SFC8 mixed networking is not supported.

New pure production `SfcControllerAuthority` is also used in `validLease`: actual controller item type, lease ID, port, count one and exactly one reference in the owner's inventory must match. Held operations still require the identical stack in main hand. Tick-level inventory validation may inspect a valid temporarily unheld controller, preserving the established lifecycle behavior.

## Reviewed unchanged behavior

- P1 permits requests; P2 applies; only P1's token-bound acceptance allocates a candidate lease. Candidate is not the active P2 port.
- Candidate readiness starts bounded state capture; commit requires candidate identity, token, exact frame/hash and full state delivery. The P2 port is published only after commit.
- Every stage revalidates current console/TV/link/card, player eligibility and interaction permissions. One server-global transfer budget is released on success, abort, removal or departure.
- P2 return/disconnect/invalid input/watchdog failure removes only P2. P1's input timeline/frame is retained. P1 physical return cascades to P2. A client error `Leave` stops the session but intentionally retains P1's leased hand item for explicit return, as before.
- `SfcJoinGate`, `SfcInputTimeline`, `SfcInputHealth` and their algorithms were not changed. Core/model/asset/version/build files were not edited by this patch.
- A suspected NaN comparison weakness in joinReady was ruled out: the actual Ready record constructor rejects non-finite FPS before the server method. It is not reported as a network vulnerability and no redundant helper was added.

## Changed production classes

- `cn.piq.sfchome.server.SfcHomeServer`
- `cn.piq.sfchome.server.SfcHomeServer$Lease` (new immutable authority field)
- New `cn.piq.sfchome.server.SfcControllerAuthority`

Other HomeServer nested classes may acquire compiler Nest/InnerClasses attribute changes but are not algorithmically changed. Final class-delta auditing remains required.

## Validation

New `SfcControllerAuthorityTest` has 9 tests: actor+lease for both ports, invalid constructors, item type/port/count, exact inventory reference/held policy, delayed retired-P2 Ready/Leave, cross-port claims, 24 successive re-leases, and successful candidate commit preserving the P1 timeline.

`tools/check_sfc_controller_authority.py` compiles actual pure production authority/gate/timeline/watchdog and runs those tests plus existing join/queue/watchdog regressions. Final result: **45 tests passed**, 0 failures/skips/aborts. Report: `design/sfc8-server-authority-20260910.json`. The runner's initial expected total 43 was corrected to the actual 45 after every test had passed; no production failure or test was suppressed.

The production server must be compiled against the actual Minecraft API and the complete build must be verified separately. These pure behavioral checks do not instantiate a Minecraft server, exercise protection plugins, or prove live two-player networking. No game, instance install, world/save/ROM write, server operation or publishing was performed.
