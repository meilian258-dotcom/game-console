param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
$fcToolchains = Join-Path (Split-Path -Parent $workspace) 'piq-fc-arcade\.toolchains'
$asciiToolchains = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.piq-sfc-toolchains'
$asciiWorkspace = Join-Path $asciiToolchains 'workspace'
$env:CARGO_HOME = Join-Path $fcToolchains 'cargo'
$env:RUSTUP_HOME = Join-Path $asciiToolchains 'rustup'
$env:RUSTUP_TOOLCHAIN = 'stable-x86_64-pc-windows-gnu'
$env:CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS = '--cfg getrandom_backend="custom"'
$rustBin = Join-Path $env:RUSTUP_HOME 'toolchains\stable-x86_64-pc-windows-gnu\bin'
$env:PATH = "$rustBin;$env:PATH"
$cargo = Join-Path $rustBin 'cargo.exe'

if (-not (Test-Path -LiteralPath $cargo)) {
    throw "Rust toolchain not found: $cargo"
}

New-Item -ItemType Directory -Path $asciiWorkspace -Force | Out-Null
& robocopy.exe $workspace $asciiWorkspace /MIR /XD '.gradle' '.toolchains' 'build' 'target' /XF '*.zip' /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) {
    throw "Failed to stage SFC sources in ASCII-only path (robocopy exit code $LASTEXITCODE)"
}

$manifest = Join-Path $asciiWorkspace 'native\sfc-wasm\Cargo.toml'
if ($Clean) {
    & $cargo clean --manifest-path $manifest
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

& $cargo build --manifest-path $manifest --target wasm32-unknown-unknown --release
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$source = Join-Path $asciiWorkspace 'native\sfc-wasm\target\wasm32-unknown-unknown\release\piq_sfc_wasm.wasm'
$destination = Join-Path $workspace 'src\main\resources\assets\piq_sfc_arcade\core\piq_sfc_wasm.wasm'
New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Force
Get-Item -LiteralPath $destination | Select-Object FullName, Length, LastWriteTime
