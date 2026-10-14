"""Build into an explicit scratch output; does not install or update any resource DLL."""
import argparse, hashlib, json, pathlib, subprocess

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--compiler', required=True, type=pathlib.Path)
    parser.add_argument('--jdk', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent
    output = args.output.resolve()
    if output.exists():
        raise SystemExit('Output directory must be new; preserve earlier native evidence')
    for path in (args.compiler, args.jdk / 'include/jni.h', args.jdk / 'include/win32/jni_md.h'):
        if not path.is_file():
            raise SystemExit(f'Missing required tool/header: {path}')
    output.mkdir(parents=True)
    target = output / 'piq-libretro-jni.dll'
    command = [str(args.compiler.resolve()), '-std=c++20', '-O2', '-Wall', '-Wextra', '-static', '-shared',
               '-Wl,--no-insert-timestamp', '-I' + str(args.jdk.resolve() / 'include'),
               '-I' + str(args.jdk.resolve() / 'include/win32'), str(root / 'piq_libretro_jni.cpp'),
               '-o', str(target), '-lopengl32', '-lgdi32', '-luser32', '-lbcrypt']
    run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (output / 'build.log').write_text(run.stdout, encoding='utf-8')
    def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest().upper()
    receipt = dict(exitCode=run.returncode, command=command, sources={p.name:sha(p) for p in
                   (root / 'piq_libretro_jni.cpp', root / 'libretro.h', root / 'runtime_dependencies.h')})
    if run.returncode == 0:
        receipt.update(dll=str(target), sha256=sha(target), bytes=target.stat().st_size)
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt))
    if run.stdout: print(run.stdout)
    raise SystemExit(run.returncode)

if __name__ == '__main__': main()
