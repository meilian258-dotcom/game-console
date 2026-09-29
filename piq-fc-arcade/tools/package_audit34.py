"""Local four-mod update and corresponding project sources. No runtime duplication or installation."""
import argparse
import json
from pathlib import Path
import re
import sys
import zipfile

from build_audit34 import ROOT, NAMES, inputs
from freeze_fc_core_alpha19 import digest, read_jar, require, safe_path
from freeze_fc_compact_alpha20 import RESTORE


def sha(path): return digest(path.read_bytes())


def write_zip(path, entries):
    require(not path.exists(), 'Refuse existing archive ' + str(path))
    records = {n: {'sha256': digest(b), 'bytes': len(b)} for n, b in sorted(entries.items())}
    entries = dict(entries, **{'SHA256.json': (json.dumps(records, ensure_ascii=False, indent=2) + '\n').encode()})
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, raw in sorted(entries.items()):
            require(not name.startswith('/') and ':' not in name and '\\' not in name and not any(p in ('.', '..', '') for p in name.split('/')), 'Unsafe archive name')
            info = zipfile.ZipInfo(name, (2026, 9, 13, 0, 0, 0)); info.external_attr = 0o100644 << 16
            z.writestr(info, raw, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    with zipfile.ZipFile(path) as z:
        require(len(z.namelist()) == len(set(z.namelist())) == len(entries) and z.testzip() is None, 'ZIP integrity')
        for name, raw in entries.items(): require(z.read(name) == raw, 'ZIP byte mismatch ' + name)
    return {'path': str(path), 'sha256': sha(path), 'bytes': path.stat().st_size, 'files': len(entries)}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stage', required=True, type=Path); p.add_argument('--name', required=True)
    p.add_argument('--evidence', required=True, action='append', type=Path); a = p.parse_args()
    require(re.fullmatch(r'[\w\u4e00-\u9fff-]{1,100}', a.name), 'Simple output name')
    stage = safe_path(a.stage); witness = json.loads((stage / 'build-witness.json').read_text(encoding='utf-8'))
    require(witness.get('ok') and witness['inputs'] == inputs(), 'Stale production source fence')
    expected = {k: row['sha256'] for k, row in witness['mods'].items()}
    evidence = []; schemas = set()
    for path in a.evidence:
        data = json.loads(path.read_text(encoding='utf-8'))
        require(data.get('ok') and data.get('mode') == 'final-jar-only' and data.get('production_compiled') is False,
                'Evidence must be successful final-JAR-only ' + str(path))
        jars = data.get('jars', {})
        if not jars and 'head_transform' in data and 'mapping_transform_and_queue' in data:
            jars = {'fc': data['input']}
        require(set(jars) & set(expected), 'Evidence must identify a final JAR ' + str(path))
        for k, value in jars.items():
            if k in expected: require(value['sha256'] == expected[k], 'Stale JAR evidence ' + str(path))
        evidence_name = path.parent.name + '-' + path.name if path.name == 'report.json' else path.name
        require(evidence_name not in {(x.parent.name + '-' + x.name if x.name == 'report.json' else x.name) for x, _ in evidence}, 'Duplicate evidence filename')
        schemas.add(data.get('schema')); evidence.append((path, data))
    require('piq-audit34-bundle-final-1' in schemas, 'Missing real FML/native/common checks')
    # Require distinct final checks for every audit component, not just a generic build success.
    predicates = {
        'permission': lambda d: 'wiring' in d and 'behavior' in d,
        'audio': lambda d: d.get('schema') == 'piq-cabinet-audio-fixes-1',
        'shutdown': lambda d: d.get('schema') == 'piq-gba-shutdown-1',
        'sfc-audit': lambda d: 'downloads' in d and 'repair' in d and 'download_sending' in d,
        'settings': lambda d: 'probe' in d and set(d.get('jars', {})) == {'fc', 'gba'},
        'keyboard': lambda d: 'head_transform' in d and 'mapping_transform_and_queue' in d}
    for keyword, check in predicates.items():
        require(any(check(d) for _, d in evidence), 'Missing final evidence: ' + keyword)
    require(any(d.get('actual', {}).get('generic_actual_factory_openSync') for _, d in evidence), 'Missing final SFC dual-worker regression')
    parent = safe_path(ROOT / '制作Mod/03-街机模拟')
    output = parent / (a.name + '.zip'); source_output = parent / (a.name + '-源码.zip')
    receipt = parent / (a.name + '.verification.json')
    for path in [output, source_output, receipt]: require(not path.exists(), 'New delivery required ' + str(path))
    entries = {'使用说明.md': (ROOT / 'piq-fc-arcade/design/审查修复-alpha34-使用说明.md').read_bytes(),
               'checks/build-witness.json': (stage / 'build-witness.json').read_bytes()}
    for kind, name in NAMES.items():
        mod = stage / name; require(sha(mod) == expected[kind], 'Frozen mod changed')
        entries['mods/' + name] = mod.read_bytes()
        _, _, jar = read_jar(mod)
        for n, b in jar.items():
            if n.startswith('META-INF/') and ('license' in n.lower() or 'notice' in n.lower()) and not n.endswith('/'):
                entries['licenses/' + kind + '/' + n.removeprefix('META-INF/')] = b
    for path, _ in evidence:
        name = path.parent.name + '-' + path.name if path.name == 'report.json' else path.name
        entries['checks/' + name] = path.read_bytes()
    source_entries = {}; source_fence = {}
    def add(path, name):
        safe_path(path, True); raw = path.read_bytes(); require(name not in source_entries, 'Duplicate source path')
        source_entries[name] = raw; source_fence[str(path)] = digest(raw)
    excludes = {'.git', '.gradle', '.toolchains', 'target', '__pycache__', 'node_modules'}
    for project in ['piq-fc-arcade', 'piq-retro-platform', 'piq-native-arcade', 'piq-sfc-arcade', 'piq-sfc-home', 'piq-gba']:
        base = ROOT / project
        for sub in ['src', 'gradle', 'helper/src', 'native', 'tools']:
            for path in sorted((base / sub).rglob('*')):
                relative = path.relative_to(base)
                if not path.is_file() or any(part in excludes for part in relative.parts): continue
                if sub == 'tools' and path.suffix not in {'.py', '.java', '.md', '.ps1', '.bat', '.json'}: continue
                add(path, 'source/' + project + '/' + relative.as_posix())
        for name in ['gradlew', 'gradlew.bat', 'settings.gradle', 'build.gradle', 'gradle.properties', 'LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'ARCADEMOD_ASSET_NOTICE.md']:
            if (base / name).is_file(): add(base / name, 'source/' + project + '/' + name)
    # The merged SFC JAR embeds GPL jgenesis WASM: ship the complete local source tree and its build wrapper.
    third_party = ROOT / 'piq-sfc-arcade/third_party'
    for path in sorted(third_party.rglob('*')):
        relative = path.relative_to(third_party)
        if path.is_file() and not any(part in excludes for part in relative.parts) and path.suffix != '.zip':
            add(path, 'source/piq-sfc-arcade/third_party/' + relative.as_posix())
    _, _, final_fc = read_jar(stage / NAMES['fc'])
    for name in RESTORE: source_entries['release-assets/piq-fc-arcade/' + name] = final_fc[name]
    source_entries['使用说明.md'] = entries['使用说明.md']
    source_entries['checks/build-witness.json'] = entries['checks/build-witness.json']
    source_entries['源码说明.md'] = ('''# 对应源码与构建边界

本包包含六个同级项目的生产代码、资源、测试、构建定义、辅助进程源码及现有 QA 工具；FC 的 Rust 源、独立光枪 ABI 和 SFC 的完整本地 jgenesis 源树亦保留。没有重新编译或修改原生模拟核心。

Java 21 / NeoForge 21.1.236 下依次构建 FC、旧 SFC 核心、SFC home、Native；GBA 使用 tools/build_audit34.py 中的直接 javac 步骤。工具里的 --offline 需要已缓存依赖；系统 JDK、Gradle/Cargo 缓存和 C++ 工具链未附带。历史冻结/审计工具保留原机器路径和旧基线输入要求，不是新电脑开箱重建脚本；只在新的源码副本中配置环境。

SFC 发布件由 home20 与 core7 合并，不能将 thin home 单独作为最终包。两项 FC 手柄历史源稿与发行资源不同，release-assets/ 保存最终发行字节；普通构建后依照 build-witness 的记录恢复这两项，不改用户原稿。旧注册 ID、模型和核心二进制均受围栏保护。

此次只分发更新 MOD，不重复分发外置 GBA/Native DLL 和 helper。外置运行库仍使用先前测试包的完全相同版本；其完整上游源码、许可证、补丁和工具链记录仍随原运行库包/源码伴随包提供。当前新增 Java 改动的对应源均在本包。第三方构建脚本只是历史重建记录，请先审阅路径和环境，勿在游戏实例中直接执行。

checks/build-witness.json 记录真实源 SHA 与最终类差异；原始审查失败记录没有被覆盖。自动测试不是完整 Minecraft 或真人双机验收，也不是跨机器逐字节可重复构建保证。上游源码可能包含原创开发测试夹具，不是玩家商业游戏。
''').encode()
    source_entries.update({n: b for n, b in entries.items() if n.startswith('licenses/')})
    require(witness['inputs'] == inputs(), 'Production source changed while packaging')
    require(all(sha(Path(n)) == pin for n, pin in source_fence.items()), 'Source/tool changed while packaging')
    result = {'ok': True, 'installed': False, 'published': False, 'runtime_unchanged': True, 'runtime_included': False,
              'commercial_or_user_rom_bios_saves_included': False,
              'test_package': write_zip(output, entries), 'corresponding_source': write_zip(source_output, source_entries),
              'mods': expected, 'evidence': {str(path): sha(path) for path, _ in evidence}}
    require(witness['inputs'] == inputs() and all(sha(Path(n)) == pin for n, pin in source_fence.items()), 'Post-package source fence')
    with receipt.open('x', encoding='utf-8') as f: json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
