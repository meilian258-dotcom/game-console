"""Maintain FC44/Arcade0.1.0 local project notes using the documented manager APIs only."""
import hashlib
import json
from pathlib import Path
import urllib.request
from datetime import datetime

ROOT = Path(__file__).resolve().parents[2]
URL = 'http://127.0.0.1:18742'
DELIVERY = ROOT / 'outputs/arcade44/delivery-v1.json'
REPORT = ROOT / 'outputs/arcade44/manager-sync-v1.json'
NAMES = {'piq_fc_arcade': 'game_console-0.31.0-alpha.44.jar',
         'piq_native_arcade': 'game_console_arcade-0.1.0.jar'}

def get(path):
    with urllib.request.urlopen(URL + path, timeout=60) as response:
        return json.load(response)

def post(path, body, token):
    request = urllib.request.Request(URL + path, data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json', 'X-PIQ-Token': token, 'Origin': URL}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

def main():
    if REPORT.exists():
        raise ValueError('Refuse to repeat already recorded synchronization')
    delivery = json.loads(DELIVERY.read_text(encoding='utf-8'))
    if delivery.get('ok') is not True:
        raise ValueError('Delivery not verified')
    directory = ROOT / '制作Mod/03-街机模拟/方块电玩-FC44-街机正式同步'
    if Path(delivery['directory']) != directory:
        raise ValueError('Unexpected delivery directory')
    for name in NAMES.values():
        with (directory / name).open('rb') as source:
            if hashlib.file_digest(source, 'sha256').hexdigest().upper() != delivery['files'][name]:
                raise ValueError('Artifact changed since delivery')
    before = get('/api/catalog')
    token = before['token']
    notes = {}
    for mod, name in NAMES.items():
        old = next(m for m in before['mods'] if m['id'] == mod)['tracking']
        addition = ('2026-09-15 FC44（协议35）/街机0.1.0配套：'
            '本地输入同步正式功能仅已核验kof97/mslug2精确版本和两席，原固定profile、运行库、ROM/BIOS校验不变；'
            '用户反馈上一版本地同步可用，不等同本次全游戏验收。通用街机已接回服务端真实占用名，'
            '退出/断线/双柜/重载清理、旧排行榜不误删；新显示实机待验证。'
            '本次全量Gradle/CRC及源编译围栏结果见review-arcade44-v1/build-witness.json，'
            '安装指南piq-fc-arcade/design/方块电玩-FC44-街机正式同步与安装.md。'
            '当前成品' + str(directory / name) + '，SHA256=' + delivery['files'][name] + '。'
            '仅本地交付，未安装/发布/重启；不指定稳定哈希。FC旧三WASM/贴图、街机六库均保持原字节。'
            'SFC25/core9、GBA8沿用，build/libs FC44为含两张旧草稿贴图的编译中间件，不用于交付。'
            '客户端和服务端统一FC44+街机0.1.0；内置库启动补缺，不覆盖冲突文件。')
        merged = (old.get('notes', '') + '\n\n' + addition).strip()
        if len(merged) > 12000:
            raise ValueError('Would truncate previous manual notes: ' + mod)
        notes[mod] = {'id': mod,
            'owner': old.get('owner') if old.get('owner') not in (None, '', '待登记') else '像素匠 / Codex',
            'status': '编译通过', 'notes': merged, 'stableHash': old.get('stableHash', '')}
    post('/api/scan', {}, token)
    catalog = get('/api/catalog')
    for mod, name in NAMES.items():
        row = next(m for m in catalog['mods'] if m['id'] == mod)
        if not any(Path(a['path']) == directory / name and a['sha256'].upper() == delivery['files'][name]
                   for a in row['latest']):
            raise ValueError('Latest does not select the exact delivery: ' + mod)
        post('/api/notes', notes[mod], token)
    after = get('/api/catalog')
    project = next(p for p in get('/api/projects')['projects'] if p['id'] == 'block_arcade')
    if project['name'] != '方块电玩' or 'FC44' not in str(project['guide']):
        raise ValueError('Wrong project guide/group')
    verified = {}
    for mod, name in NAMES.items():
        row = next(m for m in after['mods'] if m['id'] == mod)
        for key in ['owner', 'status', 'notes', 'stableHash']:
            if row['tracking'][key] != notes[mod][key]:
                raise ValueError('Note readback mismatch')
        if not any(Path(f['path']) == directory / name for f in project['files']):
            raise ValueError('Project does not show new artifact')
        verified[mod] = {'tracking': row['tracking'], 'latest': row['latest']}
    receipt = {'ok': True, 'at': datetime.now().astimezone().isoformat(),
        'project': project['name'], 'guide': project['guide'], 'guideScope': project['guideScope'],
        'mods': verified, 'scan_errors': after.get('errors', []), 'installed': False, 'published': False}
    with REPORT.open('x', encoding='utf-8') as output:
        json.dump(receipt, output, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(REPORT), 'scan_errors': receipt['scan_errors']}, ensure_ascii=True))

if __name__ == '__main__':
    main()
