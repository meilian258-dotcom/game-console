"""Bundle publisher-preview JARs, source candidate and portable documentation.

Not a public-release approval. Excludes local audit reports, absolute input
witness paths and private development data. Requires exact frozen source receipt.
"""
import argparse
import json
from pathlib import Path
import sys
from package_public41 import ROOT, DOCS, BUILD, require, sha, zip_bytes, verify


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--freeze', type=Path, required=True)
    ap.add_argument('--build-witness-sha256', required=True)
    ap.add_argument('--source', type=Path, required=True)
    ap.add_argument('--source-sha256', required=True)
    ap.add_argument('--source-receipt-sha256', required=True)
    ap.add_argument('--addendum', type=Path)
    args = ap.parse_args()
    base = args.freeze.resolve(strict=True)
    require(base.is_relative_to(BUILD.resolve()) and base != BUILD.resolve(), 'Expected build child')
    inputs = {}

    def read(path):
        raw = path.read_bytes()
        inputs[path] = raw
        return raw

    witness_raw = read(base / 'build-witness.json')
    require(sha(witness_raw) == args.build_witness_sha256, 'Build witness mismatch')
    witness = json.loads(witness_raw)
    require(witness['ok'] and witness['schema'] == 'game-console-public41-build-1', 'Not successful freeze')
    source_path = args.source.resolve(strict=True)
    require(source_path.parent == base and 'corresponding-source-candidate' in source_path.name, 'Expected source candidate')
    source = read(source_path)
    require(sha(source) == args.source_sha256, 'Source ZIP mismatch')
    receipt_raw = read(source_path.with_suffix('.receipt.json'))
    require(sha(receipt_raw) == args.source_receipt_sha256, 'Source receipt pin mismatch')
    receipt = json.loads(receipt_raw)
    require(receipt['ok'] and receipt['schema'] == 'game-console-public41-source-candidate-receipt-1'
            and receipt['sha256'] == args.source_sha256 and receipt['bytes'] == len(source)
            and receipt['crc_and_all_entry_bytes_verified'], 'Source candidate incomplete')
    entries = {'source/' + source_path.name: source}
    release_index = {}
    for kind in ('fc', 'sfc'):
        item = witness['mods'][kind]
        path = base / item['filename']
        require(path.parent == base and path.suffix == '.jar', 'Bad mod filename')
        raw = read(path)
        require(sha(raw) == item['sha256'] and receipt['releases'][kind]['sha256'] == item['sha256'], 'Source/binary version mismatch')
        entries['jars/' + path.name] = raw
        release_index[kind] = dict(filename=path.name, bytes=len(raw), sha256=sha(raw))
    for name in ('01-安装与更新.md', '02-中英项目描述.md', '03-源码构建说明.md', '04-发布边界与未决项.md', '06-作者发布清单.md', '07-本版变更.md'):
        entries['docs/' + name] = read(DOCS / name)
    entries['README.md'] = ('# Game Console / 方块电玩 — 发布形式预览\n\n'
        '这里是给作者查看的本地 alpha 候选，不是已经获准公开发行的正式包。\n\n'
        '- `jars/`：主模组及合并 SFC 附属。FC 玩家只拿第一个；SFC 玩家拿两个。\n'
        '- `docs/01-安装与更新.md`：玩家安装说明；`02-中英项目描述.md`：页面文案。\n'
        '- `source/`：与本次版本关联的源码候选，普通玩家不用安装。\n'
        '- `docs/06-作者发布清单.md`：上架前仍需完成的事项。\n\n'
        '上传普通模组时提供单个 JAR；不要把整个作者预览包当作 MOD 或平台整合包上传。\n'
        '运行环境：Minecraft 1.21.1 / NeoForge 21.1.236 / Java 21；执行端 Windows/Linux x64。\n'
        'SFC 启动固件使用依据、完整原生依赖归属及资产再分发权利仍待确认；自动化校验不是实机多人验收。\n').encode('utf-8')
    if args.addendum:
        addendum = args.addendum.resolve(strict=True)
        require(addendum == ROOT / 'outputs/public41/REVIEW-ADDENDUM.md', 'Only the explicit reviewed addendum is allowed')
        entries['REVIEW-ADDENDUM.md'] = read(addendum)
        entries['README.md'] += '\n补充待办：旧 WASM 含本机构建路径字符串，尚未清理，见 `REVIEW-ADDENDUM.md`。\n'.encode('utf-8')
    entries['release-index.json'] = json.dumps(dict(name='Game Console', chinese_name='方块电玩', status='publisher-preview-not-cleared-for-public-release',
        jars=release_index, source=dict(filename=source_path.name, sha256=args.source_sha256), installed=False, published=False), ensure_ascii=False, indent=2).encode('utf-8')
    entries['SHA256SUMS.txt'] = ''.join(sha(raw) + '  ' + name + '\n' for name, raw in sorted(entries.items())).encode('utf-8')
    out = base / ('Game-Console-publisher-preview-alpha41-v2.zip' if args.addendum else 'Game-Console-publisher-preview-alpha41.zip')
    result_path = base / ('publisher-preview-verification-v2.json' if args.addendum else 'publisher-preview-verification.json')
    require(not out.exists() and not result_path.exists(), 'Do not overwrite earlier artifacts')
    raw = zip_bytes(entries); verify(raw, entries)
    require(all(path.read_bytes() == original for path, original in inputs.items()), 'Input drift')
    with out.open('xb') as stream:
        stream.write(raw)
    verify(out.read_bytes(), entries)
    require(sha(out.read_bytes()) == sha(raw) and all(path.read_bytes() == original for path, original in inputs.items()), 'Final fence failed')
    result = dict(ok=True, filename=out.name, bytes=len(raw), sha256=sha(raw), entries={n: sha(data) for n, data in entries.items()},
                  build_witness_sha256=args.build_witness_sha256, source_receipt_sha256=args.source_receipt_sha256, candidate_only=True, published=False)
    with result_path.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
