"""Build the prototype host; print its pin for deliberate review, never auto-update Java pins."""
import hashlib, json, pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
compiler = ROOT.parent / 'outputs/libretro64/retroarch-poc/toolchain/llvm-mingw-20250910-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe'
target = ROOT / 'src/main/resources/core/pvz/piq-pvz-host.exe'
if not compiler.is_file():
    raise SystemExit(f'Configured compiler is missing: {compiler}')
subprocess.run([str(compiler), '-O2', '-std=c++20', '-static', '-municode',
    '-I' + str(ROOT/'vendor/PvZ-Portable/src/SexyAppFramework/platform/libretro'),
    str(ROOT/'native/pvz_host.cpp'), '-o', str(target), '-lopengl32', '-lgdi32', '-luser32'], check=True)
receipt = {'file': str(target), 'sha256': hashlib.sha256(target.read_bytes()).hexdigest().upper(),
    'compiler': str(compiler), 'note': 'Review PvzRuntime.HOST_SHA and rerun check/probes before delivery.'}
ROOT.joinpath('build').mkdir(exist_ok=True)
ROOT.joinpath('build/native-receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps(receipt, indent=2))
