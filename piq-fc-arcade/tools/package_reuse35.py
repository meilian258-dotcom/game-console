"""Package one FC35 update plus corresponding FC/platform source, never touch installed mods."""
import argparse
import json
from pathlib import Path
import sys

from build_reuse35 import ROOT, NAME, inputs
from freeze_fc_core_alpha19 import read_jar, safe_path, digest, require
from freeze_fc_compact_alpha20 import RESTORE
from package_audit34 import write_zip


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stage', required=True, type=Path); p.add_argument('--evidence', required=True, action='append', type=Path)
    p.add_argument('--name', required=True); a = p.parse_args()
    import re
    require(re.fullmatch(r'[\w\u4e00-\u9fff-]{1,100}', a.name), 'Simple output name')
    stage = safe_path(a.stage); raw_witness = (stage / 'build-witness.json').read_bytes(); witness = json.loads(raw_witness)
    require(witness['ok'] and witness['inputs'] == inputs(), 'Stale source fence')
    sha, _, jar = read_jar(stage / NAME); require(sha == witness['fc']['sha256'], 'Final FC35 changed')
    evidence = {}; schemas = set()
    for path in a.evidence:
        data = json.loads(path.read_bytes())
        require(data.get('ok') and data.get('mode') == 'final-jar-only' and data.get('production_compiled') is False, 'Final evidence required')
        actual = data.get('jars', {}).get('fc', {}).get('sha256', data.get('sha256'))
        require(actual == sha, 'Evidence from another candidate ' + str(path))
        require(path.name not in evidence, 'Duplicate evidence name'); evidence[path.name] = path.read_bytes(); schemas.add(data.get('schema'))
    require({'piq-reuse35-addons-1', 'piq-game-reuse35-final-1'} <= schemas, 'Need addon and dedicated reuse checks')
    readme = ROOT / 'piq-fc-arcade/design/上传复用-alpha35-使用说明.md'; readme_bytes = readme.read_bytes()
    entries = {'mods/' + NAME: (stage / NAME).read_bytes(), '使用说明.md': readme_bytes, 'checks/build-witness.json': raw_witness}
    for name, raw in evidence.items(): entries['checks/' + name] = raw
    for name, raw in jar.items():
        if name.startswith('META-INF/') and ('license' in name.lower() or 'notice' in name.lower()) and not name.endswith('/'):
            entries['licenses/' + name.removeprefix('META-INF/')] = raw
    sources = {}; source_fence = {}
    def add(path, name):
        safe_path(path, True); require(name not in sources, 'Duplicate source ' + name)
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
    probe = ROOT / 'piq-gba/tools/qa/GbaHandheldCommon3Probe.java'
    add(probe, 'verification-source/piq-gba/tools/qa/' + probe.name)
    for name in RESTORE: sources['release-assets/piq-fc-arcade/' + name] = jar[name]
    sources.update({n: b for n, b in entries.items() if not n.startswith('mods/')})
    sources['源码说明.md'] = '''# FC35 对应源码

本次只更新 FC 公共核心。包含 FC 与内部 retro-platform 的完整生产代码/资源/测试/构建定义、FC 的本地 NES Rust/光枪 FFI 源和许可证。SFC20、Native12、GBA4 及外置运行库未改变，源码仍在先前成套交付中。本包不包含用户 ROM、BIOS、存档或商业游戏。

Java 21 / NeoForge 21.1.236。普通构建入口 piq-fc-arcade/gradlew.bat check jar --offline；离线需要已缓存的依赖。两张历史 FC 手柄源稿和发行资源不同，release-assets 内为本次保持的发行原字节，冻结时恢复，不覆盖原稿。

tools/build_reuse35.py、专项 QA 及旧历史工具含本机路径、其它同级项目和固定旧成品依赖，保留用于审计，不是新电脑开箱重建承诺。请在独立源码副本调整环境，不在游戏实例里运行。checks/build-witness.json 记录生产输入与最终 class 差异；未改变的模型/模拟核心和附属受 SHA 围栏保护。

自动化只验证注明的范围，不代表完整 Minecraft 启动、真人双机或第三方保护模组组合验收。
'''.encode()
    require(witness['inputs'] == inputs() and all(digest(Path(n).read_bytes()) == pin for n, pin in source_fence.items()), 'Sources changed while packaging')
    parent = safe_path(ROOT / '制作Mod/03-街机模拟')
    output = parent / (a.name + '.zip'); source_output = parent / (a.name + '-源码.zip'); receipt = parent / (a.name + '.verification.json')
    require(not any(x.exists() for x in (output, source_output, receipt)), 'New delivery required')
    result = {'ok': True, 'installed': False, 'published': False, 'shutdown_scheduled': False,
        'fc': witness['fc'], 'unchanged_addons': witness['unchanged_addons'],
        'test_package': write_zip(output, entries), 'source_package': write_zip(source_output, sources),
        'evidence': {n: digest(b) for n, b in evidence.items()}, 'requires_matched_fc35_on_clients_and_server': True}
    require(witness['inputs'] == inputs() and digest(readme.read_bytes()) == digest(readme_bytes), 'Post-package source/readme fence')
    with receipt.open('x', encoding='utf-8') as f: json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
