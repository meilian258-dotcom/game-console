param()
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$env:CARGO_HOME=Join-Path $project '.toolchains/cargo'
$env:RUSTUP_HOME=Join-Path $project '.toolchains/rustup'
$cargo=Join-Path $env:CARGO_HOME 'bin/cargo.exe'
$legacy=Join-Path $project 'src/main/resources/core/nes_rust_wasm_bg.wasm'
$before=(Get-FileHash -LiteralPath $legacy -Algorithm SHA256).Hash
if($before -ne '110711E30B64444414A8BE2D0A3B1AB45A442CC9B9452AC7D74DAAB508C933FF'){throw 'Unexpected legacy module; refusing this build'}
& $cargo build --offline --manifest-path (Join-Path $PSScriptRoot 'nes-zapper-ffi/Cargo.toml') --release --target wasm32-unknown-unknown
if($LASTEXITCODE -ne 0){throw "Independent Zapper build failed: $LASTEXITCODE"}
$built=Join-Path $PSScriptRoot 'nes-zapper-ffi/target/wasm32-unknown-unknown/release/piq_nes_zapper_ffi.wasm'
$destination=Join-Path $project 'src/main/resources/core/nes_zapper_v1.wasm'
Copy-Item -LiteralPath $built -Destination $destination -Force
if((Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash){throw 'Independent module copy mismatch'}
if((Get-FileHash -LiteralPath $legacy -Algorithm SHA256).Hash -ne $before){throw 'Legacy module unexpectedly changed'}
Get-FileHash -LiteralPath $destination,$legacy -Algorithm SHA256
