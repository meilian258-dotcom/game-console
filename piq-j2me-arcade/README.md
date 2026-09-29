# 方块电玩：Java ME / Game Console: Java ME

Player-facing names follow the [Game Console branding policy](../source-control/BRANDING.md).
Stage reviewed builds as `game-console-j2me-<version>.jar`; preserve legacy mod/resource IDs,
runtime paths, authors and old artifacts. The naming change does not add runtime capabilities.

Independent Java ME/MIDP arcade prototype for Minecraft 1.21.1 and NeoForge
21.1.236. It intentionally does not depend on `piq-fc-arcade`: the FC mod stays
stable while the Java ME runtime is proven. Genuine shared session/library code
can later move into a separate `piq-arcade-core` module.

## Runtime boundary

Minecraft-facing code must not import MicroEmulator classes directly. Emulator
integration belongs under `cn.piq.j2mearcade.core`, behind a small runtime
interface that owns MIDlet loading, pixels, keypad input, audio and RMS data.

The initial runtime candidate is MicroEmulator 2.0.4, a pure-Java Java ME
implementation published under LGPL-compatible module licenses. Its license and
notices must remain available when its binaries are distributed. User-supplied
game JAR/JAD files are never bundled with this mod.

FreeJ2ME remains a compatibility fallback, but is not embedded in this project
because its official repository is GPLv3-or-later.

## Current prototype status

- Minecraft 1.21.1, NeoForge 21.1.236, Java 21 and ModDevGradle 2.0.141.
- Registers a block entity-backed J2ME arcade. Each machine stores its own
  selected game filename; administrators change it with Shift+right-click and
  ordinary right-click starts the configured game.
- Scans and launches user-provided `.jar` files without adding them to
  Minecraft's application classpath.
- Reads `MIDlet-Name` and `MIDlet-1` from each game manifest with a 64 MiB limit.
- Bundles the four required MicroEmulator 2.0.4 modules as jar-in-jar libraries.
- Keeps the emulator client-side; a dedicated server only owns future session state.
- Starts a real MIDP game in an isolated child-first game loader, captures its
  changing LCD framebuffer and forwards virtual keypad input.
- Provides an offline compatibility layer for common Nokia UI/DirectGraphics,
  vibration/light and Nokia Sound APIs used by Series 40/60 games. Nokia Sound
  lifecycle calls are accepted but audio output is still muted.
- Provides the JSR-120 messaging API shape so games that merely reference SMS
  classes can start. No SMS connection, send, receive, browser or external-app
  action is permitted by the emulator.
- Renders the LCD directly onto the arcade model in the world and opens a
  transparent non-pausing controller screen that maps arrows, confirm, number
  keys and both phone soft keys.
- The supplied compatibility game remains external and is never packaged.
- A single client currently owns one MicroEmulator runtime, so starting another
  Java machine replaces the local session. Audio, RMS persistence, game-file
  distribution and multiplayer synchronization are not implemented.

Run the complete automated verification from this directory with the sibling
workspace wrapper:

```powershell
& '..\piq-fc-arcade\gradlew.bat' -p . clean check
```

An external compatibility game can be exercised without packaging it:

```powershell
& '..\piq-fc-arcade\gradlew.bat' -p . `
  '-Pj2meTestJar=C:\path\to\owned-game.jar' realGameSmokeTest
```

For an in-game test, place owned Java ME game files under the instance's
`j2me-games` folder. Game files must not be committed to this repository or
bundled into the mod artifact.
