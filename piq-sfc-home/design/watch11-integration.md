# SFC 11 nearby spectators

This is the SFC provider of `piq-fc-arcade/design/watch23-contract.md`. It does not
change the existing P1/P2 invitation, ROM authorization, frame lockstep, core,
controller inputs, models, covers or textures.

## Runtime boundaries

* Common `SfcWatchProvider` registers during `SfcHomeMod` construction. The only
  additions to `SfcHomeServer` are server-thread-only read-only source/participant
  queries and an independent random `Session.watchSource` identity. A source
  requires P1 ready, a running clock, the exact P1 lease, current two-way AV/TV
  hardware, current ROM binding and existing endpoint permission facts. Queries
  do not create State, load chunks or grant a controller/ROM.
* Participants include all borrowed SFC controllers and the pending P2 applicant;
  nearby viewing never claims P2. The shared service owns discovery, nearest-source
  selection, independent spectator tokens, actual-connection checks and budgets.
* `SfcWatchClient` renders through the existing `HomeVideoDisplay`, checking the
  exact SFC console, TV UUIDs, AV link and complete TV structure. There is no core
  or ROM request in the display adapter. SFC frames use rotation zero. Audio gain
  is distance only; shared WatchClient applies the Minecraft volume once.
* `SfcPlayback.Host.mediaFrame` defaults to no-op. P1 calls it after the existing
  core frame/audio/render copy; P2 never calls it. Runtime/linkage failures trip
  the optional media callback only, not the main game. `mediaClosed` is likewise
  isolated. Existing test `observedFrame` remains unchanged.
* The new `watchPicture` only retains the existing private render-copy object.
  This enables a viewer arriving during P2's snapshot pause to get the current
  static image without touching the core or reconstructing a ROM session.
* `SfcWatchPublisher` copies borrowed RGBA rows with their actual stride and copies
  PCM **stereo frames × 2 shorts**, before handing ownership to the existing
  bounded media worker. No encoding, socket or Minecraft calls run in this tap.
  With zero spectators, the tap does not copy frames. During a pause, it publishes
  a silent retained image at most once per second; it never replays old PCM.
* A same-source zero-viewer interval suspends the same stream, preserving video
  sequence and PCM sample clock. A real connection/source/owner change closes it.
  Temporary deadline/hardware suspension can recover on an authorized heartbeat;
  actual higher-revision zero demand remains a stop. Permanent media failure cannot
  recreate a sequence-zero stream under the same source identity.
* A queued old-connection zero demand cannot advance the new connection's revision:
  current P1, lease and descriptor are checked before ordering. Network output uses
  `CabinetMediaSender.watchServerbound`, sharing the original Connection window.

## Exact production scope

Modified classes (including affected nested classes):

* `cn/piq/sfchome/SfcHomeMod`
* `cn/piq/sfchome/server/SfcHomeServer`, `$Session`
* `cn/piq/sfchome/client/SfcPlayback`, `$Host`, `$MinecraftHost`

Added:

* `cn/piq/sfchome/server/SfcWatchProvider`
* `cn/piq/sfchome/client/SfcWatchClient`, `$Setup`
* `cn/piq/sfchome/client/SfcWatchPublisher`, `$Publication`
* `cn/piq/sfchome/client/SfcWatchFrames`
* `cn/piq/sfchome/client/SfcWatchDemand`

No resource changes. `SfcHomeClient`, all existing network packets/codecs, other
server/core classes and the embedded SFC6 core remain outside this change.

## Validation

`tools/check_sfc_watch_pure.py` executes 18 tests: borrowed-array ownership,
row padding/ABGR/alpha, PCM frame units/tail bounds, maximum dimensions, malformed
stride/length/aspect, silent static frames, connection identity/revision ordering,
expiry recovery, permanent stop, old-source cleanup against a new connection,
and source architecture boundaries. Latest evidence is `watch11-pure-v3.json`.

`tools/check_sfc_watch_playback.py --fc <final.jar> --sfc <final.jar> --report <new.json>`
compiles only its probes against the final JARs and verifies CodeSource. It runs
real SfcPlayback/WASM with an original diagnostic ROM, intentionally throws from
the media tap, checks continued P1 audio/input, no P2 publication, late silent
picture, media suspend/resume sequence retention and safe close callback failure.
Its results must be recorded only after executing it against the final candidate.

The old two-JVM `run_sfc_playback_multiplayer_probe.py` remains unchanged and should
also be rerun for P1/P2 regression. None of these offline tools are an actual
Minecraft spectator/server/socket, protection-plugin, physical-controller or
commercial-game acceptance test. No instance, save, ROM or deployed artifact was
modified by this implementation.
