# Production Mixin transform QA

Run after a successful project build:

```text
python tools/run-transform.py --java-home <JDK-21-directory>
```

The runner reads this project's `build/javac.args` and `build/build-result.json`.
No machine paths are embedded in the runner. It binds the tested Mixin bytes to
the built JAR and prioritizes that build's exact NeoForge 21.1.250 and Replay
targets. Older duplicate Maven library versions are excluded, and the selected
external ASM / Sponge libraries precede dependencies that could bundle old ASM.
The first declared Mixin 0.8.7 service implementation is kept rather than
silently upgrading its Fabric wrapper: wrappers with the same Mixin version can
have incompatible host APIs. Competing Sponge implementations are excluded.

For an already reviewed, narrower instance dependency list, use:

```text
python tools/run-transform.py --java-home <JDK-21-directory> --dependencies-args <reviewed-javac.args>
```

The optional list supplies dependencies only; the production artifact and target
Replay / NeoForge JARs still come from this project's own build receipt.

`KeyboardMixinTransformProbe.java` supplies the existing read-only Sponge host.
`QuickModeProductionTransformProbe.java` applies all five production Mixins and
checks their precise hooks, unique state/accessor, original constructor bodies,
unchanged methods, stack validity and complete original instructions/exception
regions after undoing only the reviewed redirect plumbing. It also verifies that
the indicator Invoker forwards exactly the original receiver/overlay/boolean to
Replay's private `updateIndicator`, with every original QuickMode method intact.

Outputs stay in a fresh `build/transform-*` directory. `verification.json` is a
shareable aggregate receipt. Generated class/ASM dumps, dependency argument files
and failure logs are local-only diagnostics and must not be bundled in releases.
No recording input, player name, UUID, entity coordinate, private packet capture,
game startup, graphical validation or third-party binary is included in this
tool. The original game JARs are read-only and are never replaced.
