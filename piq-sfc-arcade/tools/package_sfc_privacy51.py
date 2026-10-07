"""Create the reviewed SFC51 complete candidate without rebuilding gameplay code.

Only fixed, equal-length Rust source-path prefixes in WASM data section 11,
the two component versions and release metadata may change. Input SHA pins
and match counts make this a specific derivative, not a general binary scrubber.
No original path values are emitted in reports.
"""
# SPDX-License-Identifier: GPL-3.0-or-later
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'source-control'))
from wasm_diagnostic_paths import redact, sections

INPUT_JAR_SHA = 'c51ffb4be95add3b4f6d2330a37ebeb20f3118804cea53139273ff2ed0e08d56'
INPUT_WASM_SHA = '5e310012b259039ae5a0a45d3681bbeb44c2963ad6a6f325ffacde5c80f63648'
DERIVED_WASM_SHA = '8bff9655f9c41ceb418ca21c32d9707e84b3ccb5f4151141f32c06240c898e5a'
MODULE = 'assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm'
TOML = 'META-INF/neoforge.mods.toml'
MANIFEST = 'META-INF/MANIFEST.MF'
PROVENANCE = 'META-INF/piq-sfc-wasm-privacy.json'

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def sanitize_module(original):
    assert digest(original) == INPUT_WASM_SHA, 'Unexpected original WASM'
    patterns = [
        (rb'[A-Za-z]:[\\/]Users[\\/][^\\/\x00-\x20]+[\\/]\.piq-sfc-toolchains[\\/]workspace', 57),
        (rb'[A-Za-z]:[\\/][^:\x00-\x20]{1,200}?[\\/]piq-fc-arcade[\\/]\.toolchains[\\/]cargo', 2),
    ]
    prefixes = []
    for pattern, expected in patterns:
        matches = list(re.finditer(pattern, original))
        assert len(matches) == expected, 'Unexpected source-prefix count'
        prefixes.extend(dict.fromkeys(match.group() for match in matches))
    candidate, receipt = redact(original, tuple(prefixes))
    assert digest(candidate) == DERIVED_WASM_SHA, 'Derived module does not match the reviewed transform'
    replacements = receipt['replacements']
    assert len(replacements) == 59
    assert not re.search(rb'[A-Za-z]:[\\/]Users[\\/]', candidate)
    assert not any(re.search(pattern, candidate) for pattern, _ in patterns)
    section_checks = []
    for kind, begin, finish in sections(original):
        same = original[begin:finish] == candidate[begin:finish]
        assert same or kind == 11, 'Non-data WASM section changed'
        section_checks.append({'id': kind, 'offset': begin, 'length': finish-begin,
                               'unchanged': same, 'sha256': digest(candidate[begin:finish])})
    provenance = {'schema': 'sfc-wasm-public-path-derivative-1', 'date': '2026-10-07',
                  'original_sha256': INPUT_WASM_SHA, 'derived_sha256': digest(candidate),
                  'bytes': len(candidate), 'data_section_only': True, 'equal_length': True,
                  'replaced_source_prefixes': len(replacements), 'replacements': replacements,
                  'sections': section_checks, 'abi_version': 1,
                  'state_format_changed': False, 'runtime_algorithm_changed': False,
                  'description': 'Only Rust diagnostic source-location prefixes changed. Native Mesen-S/JNI, WASM executable sections and serialization code are unchanged.'}
    return candidate, provenance

def build(source, output):
    original_jar = source.read_bytes()
    assert digest(original_jar) == INPUT_JAR_SHA, 'Unexpected SFC50 complete input'
    assert not output.exists(), 'Refusing to overwrite candidate'
    output.mkdir(parents=True)
    with zipfile.ZipFile(source) as jar:
        assert jar.testzip() is None
        assert len(jar.namelist()) == len(set(jar.namelist()))
        original = {item.filename: jar.read(item) for item in jar.infolist()}
    candidate = dict(original)
    wasm, provenance = sanitize_module(original[MODULE])
    candidate[MODULE] = wasm
    assert original[TOML].count(b'0.1.0-alpha.50') == 1
    assert original[TOML].count(b'0.2.0-alpha.10') == 2
    candidate[TOML] = original[TOML].replace(b'0.1.0-alpha.50', b'0.1.0-alpha.51').replace(b'0.2.0-alpha.10', b'0.2.0-alpha.11')
    assert original[MANIFEST].count(b'0.1.0-alpha.50') == 1
    candidate[MANIFEST] = original[MANIFEST].replace(b'0.1.0-alpha.50', b'0.1.0-alpha.51')
    candidate[PROVENANCE] = (json.dumps(provenance, ensure_ascii=False, indent=2)+'\n').encode()
    target = output/'game-console-sfc-0.1.0-alpha.51.jar'
    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, data in sorted(candidate.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 7, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, data)
    with zipfile.ZipFile(target) as jar:
        assert jar.testzip() is None
        assert all(jar.read(name) == data for name, data in candidate.items())
    changed = sorted(name for name in original if original[name] != candidate[name])
    assert changed == sorted([MODULE, TOML, MANIFEST])
    added = sorted(set(candidate)-set(original))
    assert added == [PROVENANCE]
    assert source.read_bytes() == original_jar
    report = {'schema':'sfc51-candidate-1','input_sha256':INPUT_JAR_SHA,'file':target.name,
              'bytes':target.stat().st_size,'sha256':digest(target.read_bytes()),
              'changed_entries':changed,'added_entries':added,'deleted_entries':[],
              'class_files_unchanged':all(original[n]==candidate[n] for n in original if n.endswith('.class')),
              'native_mesen_s_unchanged':all(original[n]==candidate[n] for n in original if n.endswith('.dll')),
              'wasm':provenance, 'minecraft_client_tested':False}
    (output/'package-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'original.wasm').write_bytes(original[MODULE])
    (output/'candidate.wasm').write_bytes(wasm)
    print(json.dumps({key:report[key] for key in ('file','bytes','sha256','changed_entries','added_entries','class_files_unchanged','native_mesen_s_unchanged')},indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.input, args.output)
