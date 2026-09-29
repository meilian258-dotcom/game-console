# ArcadeMod historical experimental asset notice

An earlier private experimental `legacy_fc_arcade` cabinet used these assets
adapted from KenLPham/SuperHB's ArcadeMod:

- `generic_machine.obj`
- `generic_machine.mtl`
- `generic_machine.png`

Original source:
https://github.com/KenLPham/ArcadeMod/tree/1.12.2

The original repository is licensed under GNU GPL version 3. This experimental
integration is intended for private evaluation only and must not be included in
a public or third-party distribution unless the licensing question is resolved.

## FC38 source-resource cleanup (2026-09-14)

The three unused production files `legacy_generic_machine.obj`,
`legacy_generic_machine.mtl`, and `legacy_generic_machine.png` have been moved
out of `src/main/resources` into the local, non-release quarantine at
`outputs/release38/quarantine/arcademod-legacy/` in the parent workspace. Their
original bytes and SHA-256 values are retained there for traceability and
recovery. The quarantine is not an input to the FC release artifacts and must
not be included in public binary or source packages.

The current `legacy_fc_arcade` model resolves to `rocket_arcade_body.json` and
`rocket_arcade_skin.png`; it does not reference these three historical files.
Existing historical test JARs and source archives have not been rewritten and
may still contain the removed assets. Those older artifacts are not cleared for
public redistribution by this cleanup.

This notice preserves the original attribution, license information, and
unresolved historical distribution restriction. Removal of these unused files
does not grant rights to any other model, texture, branding, or third-party
component, and is not a claim that all release licensing has been resolved.
