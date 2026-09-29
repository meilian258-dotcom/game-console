# NES WASM core build

`nes-rust/` is a minimal, ROM-free vendor copy of
[`takahirox/nes-rust`](https://github.com/takahirox/nes-rust), licensed under MIT.
PIQ changes expose the cartridge RAM range `$6000-$7FFF` and provide a small raw C ABI in
`nes-rust/ffi/`.

No `wasm-bindgen` CLI is required. Build with Rust stable and the
`wasm32-unknown-unknown` target:

```powershell
.\build-wasm.bat
```

The script replaces `src/main/resources/core/nes_rust_wasm_bg.wasm`. Run the Java test suite
after rebuilding because it exercises the resulting exports through Chicory.

The upstream `roms`, screenshots, CLI and browser examples are intentionally not vendored.
