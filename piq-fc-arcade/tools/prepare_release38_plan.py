"""Create the exact, reviewable resource operation artifact for FC38. No JAR writes."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_release38 as build


def sha(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    report = json.loads((ROOT / 'outputs/release38/texture-final-v1/texture-report.json').read_bytes())
    assert report['ok'] and report['applied'] and report['asset_count'] == 5
    operations = report['operations']
    baseline = build.BASE_DIR / build.BASE['fc'][0]
    assert sha(baseline.read_bytes()) == build.BASE['fc'][1]
    with zipfile.ZipFile(baseline) as jar:
        old = {name: jar.read(name) for name in jar.namelist()}
    spec = importlib.util.spec_from_file_location('removed', ROOT / 'tools/check_release38_removed_assets.py')
    removed = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(removed)
    for entry, (size, original_hash) in removed.FILES.items():
        assert len(old[entry]) == size and sha(old[entry]) == original_hash.upper()
        assert not (removed.RESOURCE_ROOT / entry).exists()
        assert sha((removed.QUARANTINE / entry).read_bytes()) == original_hash.upper()
        operations.append(dict(kind='fc', entry=entry, action='remove', category='unused-resource',
                               source=None, before_sha256=original_hash.upper(), after_sha256=None,
                               reason='Unreferenced historical third-party assets: remove from new release; keep exact private recovery copy.'))
    for entry, source in [
        ('THIRD_PARTY_NOTICES.md', 'piq-fc-arcade/THIRD_PARTY_NOTICES.md'),
        *[(f'META-INF/licenses/{name}', f'piq-fc-arcade/src/main/resources/META-INF/licenses/{name}')
          for name in ['wasmtime4j-LICENSE.txt', 'wasmtime-LICENSE.txt', 'wasmtime-provenance.json']],
    ]:
        payload = (ROOT / source).read_bytes()
        operations.append(dict(kind='fc', entry=entry, action='replace' if entry in old else 'add',
                               category='license', source=source,
                               before_sha256=sha(old[entry]) if entry in old else None,
                               after_sha256=sha(payload), reason='Bundle pinned full upstream license and accurate component attribution, preserving third-party ownership.'))
    result = dict(schema='piq-release38-plan-1', operations=operations, metadata_changes=[])
    target = ROOT / 'outputs/release38/operations-v1.json'
    with target.open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(dict(path=str(target), sha256=sha(target.read_bytes()), operations=len(operations))))


if __name__ == '__main__':
    main()
