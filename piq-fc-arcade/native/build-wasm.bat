@echo off
setlocal

set "PROJECT_ROOT=%~dp0.."
set "CARGO_HOME=%PROJECT_ROOT%\.toolchains\cargo"
set "RUSTUP_HOME=%PROJECT_ROOT%\.toolchains\rustup"
set "CARGO=%CARGO_HOME%\bin\cargo.exe"
set "MANIFEST=%~dp0nes-rust\Cargo.toml"
set "WASM=%~dp0nes-rust\target\wasm32-unknown-unknown\release\piq_nes_ffi.wasm"
set "DESTINATION=%PROJECT_ROOT%\src\main\resources\core\nes_rust_wasm_bg.wasm"

if not exist "%CARGO%" (
    echo Missing project Rust toolchain: .toolchains\cargo\bin\cargo.exe
    exit /b 1
)

"%CARGO%" build --manifest-path "%MANIFEST%" --release --target wasm32-unknown-unknown -p piq_nes_ffi
if errorlevel 1 exit /b %errorlevel%

copy /y "%WASM%" "%DESTINATION%" >nul
if errorlevel 1 exit /b %errorlevel%

echo Updated %DESTINATION%
