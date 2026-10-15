# SFC WASM runtime notices and historical build lock

This supplement accompanies the jgenesis-based SFC WASM retained in complete
SFC54. It does not contain an emulator binary, ROM, BIOS, save or user data,
and does not announce a rebuilt core or a new release.

## Artifact and source identity

- Packaged WASM: `assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm`, 1,913,067
  bytes, SHA-256
  `8bff9655f9c41ceb418ca21c32d9707e84b3ccb5f4151141f32c06240c898e5a`.
- Original pre-privacy-processing build: SHA-256
  `5e310012b259039ae5a0a45d3681bbeb44c2963ad6a6f325ffacde5c80f63648`.
  The packaged derivative changes 59 equal-length diagnostic source-path
  prefixes in the data section. It is not a new emulator implementation.
- Upstream: [jgenesis at the fixed revision
  25fc9e55ff065284c7b5f5855e2fc6d0f26e8270](https://github.com/jsgroth/jgenesis/tree/25fc9e55ff065284c7b5f5855e2fc6d0f26e8270).
- Required accompanying source includes the complete SFC-scoped jgenesis
  source archive, its source-exclusion manifest, and the PIQ source in
  `piq-sfc-arcade/native/sfc-wasm/`, `piq-sfc-arcade/native/build-wasm.ps1`
  and `piq-sfc-arcade/third_party/jgenesis/`. The modified jgenesis
  `common/jgenesis-common/Cargo.toml` and `src/timeutils.rs` are part of
  that source; an untouched upstream archive alone is not its replacement.
- Privacy-processing source is provided by
  `piq-sfc-arcade/tools/package_sfc_privacy51.py` and
  `source-control/wasm_diagnostic_paths.py`. The package retains the
  before/after relationship in `META-INF/piq-sfc-wasm-privacy.json`.

## Original crate notices

`crates/` preserves 52 original license/copyright files for 25 registry
crate versions identified by the retained historical target-side Cargo
dependency fingerprints. Every copied file was also compared with its
member in the corresponding original `.crate` archive; all 25 archive
SHA-256 values match the historical Cargo.lock checksums.

`inventory.json` records each exact crate version, its original Cargo
license expression, fixed crate-download URL, archive checksum and copied
file hashes. These are original notices, not a new combined license.
Alternative licenses retain their original choices and conditions.
`crc-catalog` keeps its `LICENSES/` subdirectory; `rand` and `rand_core`
also retain their `COPYRIGHT` files.

This is a concrete target-runtime notice inventory, not a claim of a
complete binary SBOM. Host-only procedural macros and build scripts are
not asserted to be linked into the WASM merely because Cargo.lock lists
them. The historical lock preserves their dependency identities separately.

## Rust standard library

The historical compiler was Rust **1.97.1**, revision
`8bab26f4f68e0e26f0bb7960be334d5b520ea452`, targeting
`wasm32-unknown-unknown`. The original
`rust-1.97.1/COPYRIGHT-library.html` is retained without edits, including
the `dlmalloc 0.2.13` notices relevant to this WASM.

This upstream standard-library notice collection also describes other
targets and configurations. Its inclusion does not assert that every
component named in that document is linked into this artifact. It is not
the Rust 1.96.1 material associated with the separate Wasmtime inventory.
The fixed Rust distribution-manifest identity and the copied document's
actual digest are recorded separately in `inventory.json`; no fresh
download or byte-identical compiler rebuild is claimed.

## Historical Cargo.lock, not a project lock replacement

`historical-build/Cargo.lock` is an unedited copy of the actual retained
WASM build lock: 10,829 bytes, SHA-256
`f376ed49de856c11f2321b1495c06f6b294eec1c3fc0363733fadff0d5491219`.

It is evidence and a historical build input. It does **not** replace the
active `native/sfc-wasm/Cargo.lock`, change dependency resolution in the
current project, or claim that the previously published project lock was
the exact one used for this binary. That earlier lock still listed a
`jgenesis-common -> js-sys` edge and 13 browser-related dependencies after
the associated source dependency had been removed.

A developer rebuilding the historical source can select this lock in an
isolated development copy together with the matching source changes and
the supplied build script. Preserve the original source and modification
notices. No build is performed by this supplement, and a newly built WASM
must not be presented as the existing artifact solely because it shares a
version name.

## Distribution boundary

Supply this directory together with the complete applicable PIQ/jgenesis
source, build and privacy-processing scripts, original GPL and other
component notices. These notice files are not a substitute for the
corresponding source. Their addition to the repository does not mean that
an existing frozen JAR already contains them; the actual distribution
must include the applicable companion materials.
