"""Isolated real-WASM checks; optional user-ROM screenshots remain in a new private output directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
OLD_MODULES = {
    'nes_rust_wasm_bg.wasm': '110711e30b64444414a8be2d0a3b1ab45a442cc9b9452ac7d74daab508c933ff',
    'nes_zapper_v1.wasm': 'c8d8824e5caf727678c642e6b0539deaa7c0084f33524d96779d90c7b5da79ef',
}
NEW_MODULE = 'nes_mapper19_v1.wasm'
NEW_MODULE_SHA256 = '900467682864994b1e80d8d288ef0864da60927ee6213b19310576379b330985'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def preserved_modules():
    actual = {name: digest(ROOT / 'src/main/resources/core' / name) for name in OLD_MODULES}
    if actual != OLD_MODULES:
        raise AssertionError('A frozen legacy or Zapper WASM resource changed')
    return actual


def jar_modules(jar_path):
    """Read exact bounded, unique module entries from the pinned artifact, with ZIP CRC checks."""
    expected = {**OLD_MODULES, NEW_MODULE: NEW_MODULE_SHA256}
    actual = {}
    new_bytes = None
    with zipfile.ZipFile(jar_path) as archive:
        for name, identity in expected.items():
            matches = [info for info in archive.infolist() if info.filename == 'core/' + name]
            if len(matches) != 1 or not 8 <= matches[0].file_size <= 4 * 1024 * 1024:
                raise ValueError('Missing, duplicate or oversized final-JAR module: ' + name)
            data = archive.read(matches[0])
            actual[name] = hashlib.sha256(data).hexdigest()
            if actual[name] != identity:
                raise AssertionError('Final-JAR module identity mismatch: ' + name)
            if name == NEW_MODULE:
                new_bytes = data
    return actual, new_bytes


def execute(arguments, timeout=240):
    result = subprocess.run(list(map(str, arguments)), cwd=ROOT, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + '\n' + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fc', type=Path, required=True, help='Pinned runtime JAR, or the exact final FC artifact when --jar-only is selected')
    parser.add_argument('--fc-sha256', default='349cf80d69b1ea9bc20b2eb99870efd9a72adce339bd34eaddfa3c5eeb69cea0')
    parser.add_argument('--jar-only', action='store_true', help='Compile only the QA class; all production classes/resources must come from the supplied final JAR')
    parser.add_argument('--report', type=Path, required=True, help='New result JSON; never overwrites')
    parser.add_argument('--rom', type=Path, help='Optional private existing ROM; not copied or packaged')
    parser.add_argument('--output', type=Path, help='New private directory for optional screenshots/summary')
    args = parser.parse_args()
    if args.report.exists():
        raise ValueError('Refusing to overwrite evidence')
    if bool(args.rom) != bool(args.output):
        raise ValueError('--rom and --output must be supplied together')
    if args.output and args.output.exists():
        raise ValueError('Refusing to reuse private output directory')
    if args.output:
        private_root = (ROOT.parent / 'outputs/mapper43/private').resolve()
        if not args.output.resolve().is_relative_to(private_root) or args.output.resolve() == private_root:
            raise ValueError('Private screenshots must stay in a new child of outputs/mapper43/private, outside source/resources/build')
    if digest(args.fc) != args.fc_sha256.lower():
        raise ValueError('FC runtime JAR SHA-256 mismatch')
    sources = [ROOT / 'tools/qa/Mapper19CoreProbe.java']
    if args.jar_only:
        preserved, module_bytes = jar_modules(args.fc)
        resource_paths = []
        resource_inputs = []
    else:
        sources = [ROOT / ('src/main/java/cn/piq/fcarcade/' + name) for name in [
            'core/NesCore.java', 'rom/INesHeader.java', 'rom/NesCompatibility.java',
            'core/wasm/NamcoWasmNesCore.java']] + sources
        module = ROOT / 'src/main/resources/core' / NEW_MODULE
        module_bytes = module.read_bytes()
        if hashlib.sha256(module_bytes).hexdigest() != NEW_MODULE_SHA256:
            raise AssertionError('Source Mapper19 module identity mismatch')
        resource_paths = [ROOT / 'src/main/resources']
        resource_inputs = [module]
        preserved = preserved_modules()
    inputs = sources + resource_inputs + [args.fc.resolve(), Path(__file__).resolve()]
    before = {str(path): digest(path) for path in inputs}
    result = {'ok': False, 'minecraft_started': False, 'private_rom_used': bool(args.rom),
              'jar_only': args.jar_only, 'production_sources_compiled': not args.jar_only,
              'source_resources_on_classpath': not args.jar_only,
              'frozen_old_modules': {name: preserved[name] for name in OLD_MODULES},
              'verified_module_sha256': {**preserved, NEW_MODULE: NEW_MODULE_SHA256},
              'inputs_sha256': before}
    failed = None
    try:
        with tempfile.TemporaryDirectory(prefix='mapper19-core-probe-') as directory:
            compiled = Path(directory)
            cp = os.pathsep.join(map(str, [compiled, *resource_paths, args.fc.resolve()]))
            execute([JAVA / 'javac.exe', '-encoding', 'UTF-8', '-proc:none', '-cp', cp, '-d', compiled, *sources])
            if args.jar_only:
                compiled_classes = sorted(str(path.relative_to(compiled)).replace('\\', '/') for path in compiled.rglob('*.class'))
                if compiled_classes != ['cn/piq/fcarcade/core/wasm/Mapper19CoreProbe.class']:
                    raise AssertionError('Jar-only mode unexpectedly compiled production classes: ' + repr(compiled_classes))
                result['compiled_qa_classes'] = compiled_classes
            command = [JAVA / 'java.exe', '-Xmx768m', '-Djava.awt.headless=true', '-cp', cp,
                       'cn.piq.fcarcade.core.wasm.Mapper19CoreProbe']
            if args.rom:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                command += [args.rom.resolve(), args.output.resolve()]
            stdout = execute(command, timeout=600)
            result['result'] = json.loads(stdout.strip().splitlines()[-1])
            result['stdout'] = stdout
            tampered_root = compiled / 'tampered-resource'
            tampered_file = tampered_root / 'core/nes_mapper19_v1.wasm'
            tampered_file.parent.mkdir(parents=True)
            tampered = bytearray(module_bytes)
            tampered[-1] ^= 1
            with tampered_file.open('xb') as stream:
                stream.write(tampered)
            tampered_cp = os.pathsep.join(map(str, [compiled, tampered_root, *resource_paths, args.fc.resolve()]))
            result['tampered_module'] = json.loads(execute([
                JAVA / 'java.exe', '-Xmx256m', '-cp', tampered_cp,
                'cn.piq.fcarcade.core.wasm.Mapper19CoreProbe', '--expect-module-reject']).strip().splitlines()[-1])
        after = {str(path): digest(path) for path in inputs}
        current_modules = jar_modules(args.fc)[0] if args.jar_only else preserved_modules()
        if before != after or current_modules != preserved:
            raise AssertionError('Sources, runtime or protected WASM changed during check')
        result['ok'] = True
        result['input_fences_unchanged'] = True
    except Exception as error:
        failed = error
        result['error'] = str(error)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': result['ok'], 'report': str(args.report.resolve()),
                      'private_output': str(args.output.resolve()) if args.output else None}, ensure_ascii=False))
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
