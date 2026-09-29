"""Read-only final JAR audit for the exact FC38 reviewed resource plan.

No Gradle, javac, game launch, installation, or resource rewrites. The only write is
the caller-requested new JSON report. Uses production final archives, checks every
entry/CRC and all original .class bytes, and verifies the Native13 whole archive.
"""
import argparse
import json
from pathlib import Path
import sys

import build_release38 as b


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stage', type=Path, required=True)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--plan-sha256', required=True)
    p.add_argument('--report', type=Path, required=True)
    a = p.parse_args()
    stage = b.safe_path(a.stage)
    b.require(stage.is_relative_to(b.ROOT / 'piq-fc-arcade/build'), 'Read only a workspace stage')
    b.require(not b.safe_path(a.report).exists(), 'Never overwrite a previous audit')
    plan, raw, expected, planned = b.make_plan(a.plan, a.plan_sha256)
    witness_path = b.safe_path(stage / 'build-witness.json', True)
    witness_raw = witness_path.read_bytes()
    witness = json.loads(witness_raw)
    b.require(witness['schema'] == b.BUILD_SCHEMA and witness['ok'] and witness['mode'] == 'resource-only-freeze',
              'Expected a complete FC38 freeze witness')
    b.require(witness['plan_sha256'] == b.digest(raw) and (stage / 'approved-plan.json').read_bytes() == raw,
              'Frozen approval manifest differs')
    b.require(witness['source_fence'] == planned['source_fence'], 'Source no longer matches delivered code/resources')
    b.require(witness['production_compiled'] is False and witness['compiled_outputs_used'] is False,
              'Resource-only archive provenance required')
    b.require(witness['regression_checks'], 'Missing check regression evidence')
    expected_files = set(b.NAMES.values()) | {'build-witness.json', 'approved-plan.json'} | {
        p + '-check.log' for p in witness['regression_checks']}
    b.require({p.name for p in stage.iterdir()} == expected_files, 'Unexpected files in frozen stage')
    results = {}
    for kind, name in b.NAMES.items():
        target = stage / name
        sha, _, entries = b.read_jar(target)
        entries = b.clean(entries)
        b.require(entries == expected[kind], 'Final JAR differs from approved plan: ' + kind)
        b.require(sha == witness['mods'][kind]['sha256'] and target.stat().st_size == witness['mods'][kind]['bytes'],
                  'Final JAR identity mismatch: ' + kind)
        _, _, old = b.read_jar(b.BASE_DIR / b.BASE[kind][0])
        old = b.clean(old)
        classes = [n for n in old if n.endswith('.class')]
        b.require(set(classes) == {n for n in entries if n.endswith('.class')}, 'Class set changed')
        b.require(all(entries[n] == old[n] for n in classes), 'Class bytes changed')
        if kind == 'native':
            b.require(sha == b.BASE[kind][1], 'Native13 whole archive changed')
        expected_raw = (b.BASE_DIR / b.BASE[kind][0]).read_bytes() if kind == 'native' else b.jar_bytes(expected[kind])
        b.require(b.digest(expected_raw) == sha, 'Final archive serialization differs from planned freeze')
        planned_changes = set(planned['mods'][kind]['changed'] + planned['mods'][kind]['added'] + planned['mods'][kind]['removed'])
        actual_changes = {n for n in set(old) | set(entries) if old.get(n) != entries.get(n)}
        b.require(planned_changes == actual_changes, 'Delta inventory differs')
        results[kind] = dict(path=str(target), sha256=sha, bytes=target.stat().st_size, class_files_unchanged=len(classes),
                             changed=sorted(actual_changes), protected_unchanged=planned['mods'][kind]['protected_unchanged'])
    b.require(b.source_snapshot(plan) == planned['source_fence'] and witness_path.read_bytes() == witness_raw,
              'Inputs changed during audit')
    result = dict(schema='piq-release38-final-1', ok=True, mode='final-jar-only', production_compiled=False,
                  jars=results, sha256=results['fc']['sha256'], approved_plan_sha256=b.digest(raw),
                  build_witness_sha256=b.digest(witness_raw), mod_ids=planned['mod_ids'],
                  unchanged_production_java=planned['source_fence']['unchanged_production_java'],
                  installed=False, published=False, minecraft_started=False, limits=planned['limits'])
    b.exclusive_json(a.report, result)
    print(json.dumps(dict(ok=True, report=str(b.safe_path(a.report)), jars=results), ensure_ascii=False))


if __name__ == '__main__':
    main()
