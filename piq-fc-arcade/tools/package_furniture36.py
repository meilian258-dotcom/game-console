"""Package a frozen furniture candidate and matching sources; no installation or publishing."""
import argparse
import json
from pathlib import Path
import re
import sys

from build_furniture36 import ROOT, NAME, inputs
from freeze_fc_core_alpha19 import read_jar, safe_path, digest, require
from freeze_fc_compact_alpha20 import RESTORE
from package_audit34 import write_zip


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True, action='append')
    parser.add_argument('--preview', type=Path, action='append', default=[])
    parser.add_argument('--name', required=True)
    args = parser.parse_args()
    require(re.fullmatch(r'[\w\u4e00-\u9fff-]{1,100}', args.name), 'Simple delivery name required')
    stage = safe_path(args.stage); raw_witness = (stage / 'build-witness.json').read_bytes(); witness = json.loads(raw_witness)
    require(witness['ok'] and witness['schema'] == 'piq-furniture36-build-1' and witness['inputs'] == inputs(), 'Stale source fence')
    sha, _, archive = read_jar(stage / NAME); require(sha == witness['fc']['sha256'], 'Final JAR changed')
    evidence = {}; schemas = set()
    for path in args.evidence:
        raw = path.read_bytes(); report = json.loads(raw)
        require(report.get('ok') and report.get('mode') == 'final-jar-only' and report.get('production_compiled') is False,
                'Final archive evidence required: ' + str(path))
        actual = report.get('jars', {}).get('fc', {}).get('sha256', report.get('sha256'))
        require(actual == sha, 'Evidence belongs to another JAR: ' + str(path))
        require(path.name not in evidence, 'Duplicate evidence name')
        evidence[path.name] = raw; schemas.add(report.get('schema'))
    require({'piq-furniture36-common-1', 'piq-furniture36-render-1', 'piq-reuse35-addons-1',
             'piq-game-reuse35-final-1'} <= schemas, 'Need furniture, rendering, addon and previous transfer regression checks')
    readme = ROOT / 'piq-fc-arcade/design/家具-alpha36-使用说明.md'
    entries = {'mods/' + NAME: (stage / NAME).read_bytes(), '使用说明.md': readme.read_bytes(), 'checks/build-witness.json': raw_witness}
    entries.update({'checks/' + n: b for n, b in evidence.items()})
    for path in args.preview:
        require(path.suffix.lower() == '.png', 'Only reviewed preview PNGs')
        name = '离线预览/' + path.name; require(name not in entries, 'Duplicate preview'); entries[name] = path.read_bytes()
    for name, raw in archive.items():
        if name.startswith('META-INF/') and ('license' in name.lower() or 'notice' in name.lower()) and not name.endswith('/'):
            entries['licenses/' + name.removeprefix('META-INF/')] = raw
    sources = {}; source_fence = {}
    def add(path, name):
        safe_path(path, True); require(name not in sources, 'Duplicate source: ' + name)
        raw = path.read_bytes(); sources[name] = raw; source_fence[str(path)] = digest(raw)
    excludes = {'.git', '.gradle', '.toolchains', 'target', '__pycache__', 'node_modules', 'build'}
    for project in ('piq-fc-arcade', 'piq-retro-platform'):
        base = ROOT / project
        for sub in ('src', 'gradle', 'native', 'tools'):
            for path in sorted((base / sub).rglob('*')):
                rel = path.relative_to(base)
                if not path.is_file() or any(p in excludes for p in rel.parts): continue
                if sub == 'tools' and path.suffix not in {'.py', '.java', '.md', '.ps1', '.bat', '.json'}: continue
                add(path, 'source/' + project + '/' + rel.as_posix())
        for name in ('gradlew', 'gradlew.bat', 'settings.gradle', 'build.gradle', 'gradle.properties', 'LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'ARCADEMOD_ASSET_NOTICE.md'):
            if (base / name).is_file(): add(base / name, 'source/' + project + '/' + name)
    original = ROOT / 'model-handoffs/bench-stool-20260913-1710/original'
    for path in sorted(original.rglob('*')):
        if path.is_file(): add(path, 'model-inputs/' + path.relative_to(original).as_posix())
    notes = ROOT / 'piq-fc-arcade/design/工作笔记-20260913-家具木材适配.md'
    add(notes, 'design/' + notes.name)
    probe = ROOT / 'piq-gba/tools/qa/GbaHandheldCommon3Probe.java'
    add(probe, 'verification-source/piq-gba/tools/qa/' + probe.name)
    for name in RESTORE: sources['release-assets/piq-fc-arcade/' + name] = archive[name]
    sources.update({n: b for n, b in entries.items() if not n.startswith('mods/')})
    sources['源码说明.md'] = '''# FC36 家具候选对应源码

包括 FC/内部 retro-platform、NES Rust/光枪FFI、家具原模型输入和对应生成/验证工具。木材在运行时引用 Minecraft 资源，不附原版木材 PNG。马扎布带/金属来自服主提供模型的纹理；本轮不重新设计或覆盖原件。源代码许可见各项目原许可，模型原件按服主授权用于本模组。

Java21 / Minecraft1.21.1 / NeoForge21.1.236。工具含本机路径/同级项目/历史冻结包依赖，不是新电脑开箱离线重建承诺。两张历史手柄源码草稿与发行件不同，release-assets 为本次保护的原发行件；不覆盖源码草稿。普通 Gradle 依赖须已缓存。

只更新FC36，附属SFC20/Native12/GBA4及外置运行库不变。自动化/离线预览不等于真人Minecraft联机、骑乘或资源包实机验收。本包不包含用户ROM、BIOS或游戏存档。
'''.encode()
    require(witness['inputs'] == inputs() and all(digest(Path(n).read_bytes()) == pin for n, pin in source_fence.items()), 'Source changed during package')
    parent = safe_path(ROOT / '制作Mod/03-街机模拟')
    output = parent / (args.name + '.zip'); source_output = parent / (args.name + '-源码.zip'); receipt = parent / (args.name + '.verification.json')
    require(not any(p.exists() for p in (output, source_output, receipt)), 'New delivery required')
    result = {'ok': True, 'installed': False, 'published': False, 'shutdown_scheduled': False,
        'fc': witness['fc'], 'unchanged_addons': witness['unchanged_addons'],
        'test_package': write_zip(output, entries), 'source_package': write_zip(source_output, sources),
        'evidence': {n: digest(b) for n, b in evidence.items()}, 'requires_matched_fc36_on_clients_and_server': True}
    require(witness['inputs'] == inputs() and entries['使用说明.md'] == readme.read_bytes(), 'Final fence changed')
    with receipt.open('x', encoding='utf-8') as stream: json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
