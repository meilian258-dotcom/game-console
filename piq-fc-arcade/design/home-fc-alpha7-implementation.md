# Alpha.7 field-feedback corrections

2026-09-09，像素匠。
Scope: floating same-level AV trunk, over-thick SB926, over-tall dual cabinet only.

## Geometry contract

- Dual body top 48 -> 38.4 model units (2.4 blocks), two-block width and 1.5 render scale retained. Upper elements rigid Y -6.4 in raw model coordinates (-9.6 final units / -0.6 block); lower panels shortened, coin door translated without squeezing. Screen final Y 18.517475170550..29.451589437822 /16; X 8.11..23.89 /16, Z and normal unchanged. Standing eye Y1.62 intersects the screen. Billboard/leaderboard, world render, item recenter (-2/3,-.8,-2/3), selection and clipped third-tier collision share updated geometry. Twelve-cell ownership/protection/drop lifecycle unchanged.
- Wide SB926 floor reduction 1.20 model units only. Body maxY 4.4580092 -> 3.2580092; upper keys/brands/ports translated without rescaling; card maxY6.20000371, lid hingeY3.0025. WIDE_CARD_Y1.52/16, RCA_Y1.05/16. Width, controls, hand-held models and PNG unchanged; existing narrow model retained.
- AV old floor min(socketY) prevented resting on the table. Equal base height routes now use .027 centerline = .023 trunk radius + .004 clearance. Flexible fan-out junction drops up to .15 after axial solid plugs, cubic smooth endpoint lifts blend into a supported middle. Unequal base heights use the old bridge equation unchanged. Bounded XZ two-housing path, 256-point budget, cached SAT mesh checks and six-plug model retained. No arbitrary world collision search or new per-frame route computation.

## Validation and release boundary

- Full clean check --offline: 91 suites, 445 JUnit, 442 pass, 3 Windows symlink permission skips, zero failures/errors; packaged DLL/WASM smoke also run directly against delivered JAR.
- All Python unittest discovery: 174 tests passed. Initial strict support-height test exposed subtraction roundoff; production lowerJunction now uses direct Math.max clamping, assertion retained. A source-string-only pipeline mismatch (-.8 vs -.8F) was fixed in checker, not geometry.
- Actual old/new geometry views under separate alpha7 directories, including screen eye-height and real shell section. Independent AV probes compare published alpha6 with production source/final JAR, inspect actual triangles and colored plugs, and mutation-test missing color and hidden table penetration. No claim of Minecraft in-game testing.
- Manifest home-fc-alpha7-final-reviewed-assets.json SHA0984715042265467B50251EC043A0E77832C8DA8563A53D79894B5EDCD27AF32: identical 47 paths, only wide Subor and dual body changed; other45 inherited entries unchanged. Prior alpha6 and all earlier manifests/JARs/reports preserved.
- Package-HomeFcCandidate.ps1 -Review alpha7 still pins beta3 baseline253D0F6433F2BA901447E05C9A0FB5183ACAB911BD560FCD8BBBFF2998619503, preserves43 non-home appearance assets and restores only two protected controller draft textures in candidate. Never deliver build/libs directly.
- Delivered candidate29,212,702bytes/1241entries, SHA B2DAF27EA28F33A7DFA5138E6D324BE15BECDE140305C6FD280B3542F3FA52A4. Protocol26, no registry/NBT/payload changes. Matching client/server recommended for geometry; no protocol bump inferred.
- Final independent reports and verification checklist live under the designated FC deliverable directory. This turn authorizes local corrections/delivery only: no install, upload, publish, service restart or repeat shutdown.
