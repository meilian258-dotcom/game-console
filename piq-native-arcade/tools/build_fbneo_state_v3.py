"""Build the reviewed FBNeo state-v3 patch offline in a NEW directory.

No downloads, installation, ROMs, or in-place upstream tree modifications.
FBNeo has its own non-commercial license; see the accompanying source bundle.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMMIT = 'a251c76229f1637e433b93e29845039752771b6d'
SOURCE_SHA = '55cc0f5bf305d8953fa0a20c3598164d39efc03ef3740c7b01e7ebf143cd4d7a'


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--toolchain', type=Path, required=True, help='LLVM-MinGW bin directory')
    parser.add_argument('--shell', type=Path, required=True, help='MSYS sh.exe')
    parser.add_argument('--git', type=Path, required=True)
    args = parser.parse_args()
    args.git = args.git.resolve(strict=True)
    args.shell = args.shell.resolve(strict=True)
    args.toolchain = args.toolchain.resolve(strict=True)
    archive = args.source_archive.resolve(strict=True)
    if sha(archive) != SOURCE_SHA:
        raise ValueError('Upstream source identity mismatch')
    patch = ROOT / 'design/fbneo-study/jni-state-v3/state-v3.patch'
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = out / ('FBNeo-' + COMMIT)
    with zipfile.ZipFile(archive) as z:
        if len(z.infolist()) > 20000 or sum(i.file_size for i in z.infolist()) > 400 * 1024 * 1024:
            raise ValueError('Source archive budget')
        for item in z.infolist():
            path = out / item.filename
            if not path.resolve().is_relative_to(source):
                raise ValueError('Unsafe source entry: ' + item.filename)
            # The pinned archive includes macOS framework symlinks. ZipFile
            # extracts their contents as regular files, never follows links;
            # retain their metadata in the source bundle for other platforms.
        z.extractall(out)
    # Use an isolated tree (not the parent project's Git index).
    subprocess.run([str(args.git), 'apply', '--check', str(patch)], cwd=source, check=True)
    subprocess.run([str(args.git), 'apply', str(patch)], cwd=source, check=True)
    bundle = out / 'FBNeo-a251c76-jni-state-v3-source.zip'
    with zipfile.ZipFile(archive) as z, zipfile.ZipFile(bundle, 'x', zipfile.ZIP_DEFLATED) as dest:
        for item in z.infolist():
            if not item.is_dir():
                dest.writestr(item, (out / item.filename).read_bytes())
        dest.writestr('GAME-CONSOLE-MODIFICATIONS.patch', patch.read_bytes())
    tc = args.toolchain.resolve(strict=True)
    env = os.environ.copy()
    env['PATH'] = str(tc) + os.pathsep + str(args.shell.resolve().parent) + os.pathsep + env['PATH']
    command = [str(tc / 'mingw32-make.exe'), '-j4', 'platform=win',
               'CC=x86_64-w64-mingw32-clang', 'CXX=x86_64-w64-mingw32-clang++',
               'EXE_EXT=.exe', 'SHELL=' + str(args.shell.resolve()), 'GIT_VERSION=unknown',
               'SHARED=-shared -Wl,--no-undefined,--no-insert-timestamp',
               'LDFLAGS=-static-libgcc -static-libstdc++ -static']
    started = time.monotonic()
    flags = subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS if os.name == 'nt' else 0
    with (out / 'build.log').open('xb') as log:
        proc = subprocess.run(command, cwd=source / 'src/burner/libretro', env=env,
                              stdout=log, stderr=subprocess.STDOUT, timeout=1200, creationflags=flags)
    result = dict(exitCode=proc.returncode, seconds=round(time.monotonic() - started, 2),
                  sourceCommit=COMMIT, sourceArchiveSha256=SOURCE_SHA, patchSha256=sha(patch),
                  sourceBundleSha256=sha(bundle), sourceBundleBytes=bundle.stat().st_size,
                  cleanBuild=True, binaryReproducibilityVerified=False,
                  compiler=subprocess.check_output([str(tc / 'clang.exe'), '--version'], env=env).decode().splitlines()[0],
                  flags=command[1:5] + command[-3:], noROMs=True)
    if proc.returncode == 0:
        import shutil
        core = out / 'fbneo_libretro.dll'
        shutil.copyfile(source / 'src/burner/libretro/fbneo_libretro.dll', core)
        result.update(coreSha256=sha(core), coreBytes=core.stat().st_size)
    (out / 'build.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result), flush=True)
    raise SystemExit(proc.returncode)


if __name__ == '__main__':
    main()
