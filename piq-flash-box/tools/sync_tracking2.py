"""Append prototype.2 evidence through existing local APIs; never install or publish.

Run only after package_prototype2.py. An attempt marker intentionally survives
failure: inspect partial API results before retrying, rather than rerunning blind.
Tokens stay in memory and are never included in receipts or error output.
"""
import hashlib
import json
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/flash-box-prototype2'
MANAGER = 'http://127.0.0.1:18742'
DESK = 'http://127.0.0.1:18743'
VERSION = '0.1.0-prototype.2'
MOD = 'piq_flash_box'
BOX = 'GC-058'
PERFORMANCE_TITLE = 'Flash 画面与输入响应优化'
FIELDS = ('title', 'description', 'version', 'evidence', 'feedback', 'screenshot',
          'blocker', 'module', 'stage', 'priority', 'kind', 'acceptance')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def stamp():
    return datetime.now().astimezone().isoformat()


def sha(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest().upper()


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write_receipt(path, value):
    # Exclusive creation also prevents silently replacing an earlier receipt.
    with Path(path).open('x', encoding='utf-8') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=120) as response:
        return json.load(response)


def mutate(base, path, method, body, token, header):
    request = urllib.request.Request(base + path,
        data=json.dumps(body, ensure_ascii=False).encode('utf-8'), method=method,
        headers={'Content-Type': 'application/json', header: token, 'Origin': base})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def only(items, key, value):
    matches = [item for item in items if item[key] == value]
    require(len(matches) == 1, f'Expected one {key}={value}; inspect before retry')
    return matches[0]


def tasks(state):
    result = {task['id']: task for task in state['tasks']}
    require(len(result) == len(state['tasks']), 'Duplicate task IDs')
    return result


def guard_desk(expected, releases):
    current = get(DESK, '/api/state')
    # Check revisions and the full original records, not just writable fields.
    require(tasks(current) == expected, 'Workbench changed since snapshot; refuse concurrent overwrite')
    require(current['releases'] == releases, 'Release snapshot changed; refuse to continue')
    return current


def append(original, addition):
    require(addition not in original, 'This evidence is already present; inspect previous attempt')
    return (original + '\n\n' + addition).strip()


def validate_fields(body):
    limits = {'title': 160, 'description': 10000, 'version': 120, 'evidence': 10000,
              'feedback': 10000, 'screenshot': 2000, 'blocker': 3000}
    for key, limit in limits.items():
        require(isinstance(body[key], str) and len(body[key]) <= limit, f'{key} exceeds API limit')


def validate_delivery():
    delivery = read_json(OUT / 'delivery.json')
    require(delivery.get('ok') is True and delivery.get('installed') is False
            and delivery.get('published') is False and delivery.get('gameFilesIncluded') is False,
            'Delivery receipt is not an uninstalled/unpublished game-free package')
    archive, jar = Path(delivery['archive']), Path(delivery['jar'])
    for path in (archive, jar):
        require(path.is_file() and path.resolve().is_relative_to(ROOT.resolve()), 'Delivery file missing/outside workspace')
    require(VERSION in jar.name, 'Wrong JAR version')
    require(sha(archive) == delivery['archiveSha256'].upper(), 'Archive SHA mismatch')
    require(sha(jar) == delivery['jarSha256'].upper(), 'JAR SHA mismatch')
    performance_path = OUT / 'runtime-performance.json'
    performance = read_json(performance_path)
    readme = ROOT / 'piq-flash-box/README.md'
    require(VERSION in readme.read_text(encoding='utf-8'), 'README does not cover prototype.2')
    with zipfile.ZipFile(archive) as packed:
        require(packed.testzip() is None, 'Archive CRC failure')
        require(hashlib.sha256(packed.read('mods/' + jar.name)).hexdigest().upper() == sha(jar), 'Archived JAR differs')
        require(packed.read('先读我-安装与边界.md') == readme.read_bytes(), 'Packaged installation README differs')
        verification = json.loads(packed.read('verification.json'))
        require(verification['unitTests'] == {'tests': 12, 'failed': 0, 'skipped': 0}, 'Expected 12 passing tests')
        require(verification['helperPerformance'] == performance, 'Packaged performance report differs')
        require(verification.get('installed') is False and verification.get('published') is False
                and verification.get('stable') is False, 'Verification overstates release state')
    return delivery, performance_path


def validate_project(catalog, delivery):
    row = only(catalog['mods'], 'id', MOD)
    require(any(a['version'] == VERSION and a['sha256'].upper() == delivery['jarSha256'].upper()
                and Path(a['path']).is_file() and sha(a['path']) == delivery['jarSha256'].upper()
                for a in row['latest']), 'Latest manager artifact is not prototype.2 with the verified SHA')
    require(any(Path(a['path']) == Path(delivery['jar']) and a['sha256'].upper() == delivery['jarSha256'].upper()
                for a in row['artifacts']), 'Frozen delivery JAR missing from manager inventory')
    project = only(get(MANAGER, '/api/projects')['projects'], 'id', 'block_arcade')
    component = only(project['components'], 'id', MOD)
    require(component['side'] == 'both' and VERSION in component['evidence']
            and '播放盒' in component['name'] and '本机' in component['purpose'], 'Project identity/install-side evidence incorrect')
    require(any(Path(doc['path']) == ROOT / 'piq-flash-box/README.md'
                for doc in project['documents'] if doc.get('path')), 'Project README missing')
    require(any(Path(extra['path']) == Path(delivery['archive']) for extra in project['extras']), 'Project archive missing')
    require(not catalog.get('errors'), 'Manager scan has errors; inspect before writing notes')
    return row, project, component


def main():
    marker = OUT / 'sync_tracking2.started.json'
    for name in ('tracking.json', 'manager-sync.json', marker.name):
        require(not (OUT / name).exists(), 'Earlier/partial synchronization exists; inspect instead of rerunning')
    delivery, performance_path = validate_delivery()
    before = get(MANAGER, '/api/catalog')
    manager_token = before['token']
    before_notes = {mod['id']: mod['tracking'] for mod in before['mods']}
    require(MOD in before_notes, 'Prototype.1 manager entry must already exist')
    require(VERSION not in before_notes[MOD].get('notes', ''), 'Prototype.2 already recorded; inspect instead of duplicating')
    original = get(DESK, '/api/state')
    desk_token = original['token']
    expected = tasks(original)
    original_box = only(original['tasks'], 'id', BOX)
    require(original_box['title'] == 'Flash 播放盒与电视适配（本机原型）', 'GC-058 title does not match intended task')
    require(not any(task['title'] == PERFORMANCE_TITLE for task in original['tasks']), 'Performance title already exists; refuse duplicate/overwrite')

    evidence = ('2026-09-18 原型2（' + VERSION + '，配FC56）：复用DeviceScreen免二次模糊；'
        'Esc仅隐藏已就绪操作界面、归零键鼠并释放输入锁，电视继续本机播放，右键同盒复用会话；'
        '未就绪启动关闭即取消；其他菜单/失焦暂停，断线/超距/拆线/失权停止。'
        '画面从20Hz世界tick改为渲染帧消费最新图像，运行器拆分输入/截图调度，复用纯内存PNG解码器。'
        'Java编译及12项自动测试通过。独立helper性能实测仅引用报告，不等同Minecraft实际流畅度：'
        + str(performance_path) + '；SHA256=' + sha(performance_path) + '。'
        '用户已经证实原型1光明神殿（Light Temple）可运行且电视收到画面；原型2菜单清晰度、Esc保留/重开、'
        '键鼠归还及流畅度仍待Minecraft人工复验。暂无跨电脑双人、确定性省流或存档保障。'
        '成品=' + delivery['jar'] + '；SHA256=' + delivery['jarSha256'] + '；ZIP=' + delivery['archive'] + '。'
        '指南piq-flash-box/README.md；双方安装JAR，Windows运行器仅实际执行SWF的客户端。'
        '未安装、发布、重启或标稳定；不带SWF/ROM/BIOS。')
    feedback = ('2026-09-18 用户反馈（原型1）：光明神殿SWF实际可以运行，电视已收到画面；'
        '自绘菜单文字/预览模糊、按钮清晰，Esc关闭界面导致停机，运行稍卡。'
        '本次提供原型2修复待复验，不把原型1可运行反馈作为原型2人工通过。')
    box_update = {key: original_box.get(key, '') for key in FIELDS}
    box_update.update(version='Flash Box ' + VERSION + ' + FC56', stage='待验收', acceptance='未验收',
        evidence=append(original_box.get('evidence', ''), evidence),
        feedback=append(original_box.get('feedback', ''), feedback), revision=original_box['revision'])
    performance_task = {key: '' for key in FIELDS}
    performance_task.update(title=PERFORMANCE_TITLE, module='通用', stage='待确认', priority='P2',
        kind='缺陷', acceptance='未验收', version='Flash Box ' + VERSION + ' + FC56',
        description='用户反馈Flash本机运行稍卡。此项独立跟踪画面捕获、传递、解码/上传和输入响应；'
                    '森林冰火人只是样本，不绑定单一SWF。此项不等于多人省流协议或游戏模拟帧率优化已完成。',
        evidence=evidence, feedback=feedback)
    validate_fields(box_update)
    validate_fields(performance_task)
    notes = dict(before_notes[MOD])
    notes = {key: notes.get(key, '') for key in ('owner', 'status', 'notes', 'stableHash')}
    notes.update(id=MOD, notes=append(notes['notes'], evidence))
    # Keep manual owner/status/stableHash untouched; the dated note states this build's result.
    require(len(notes['notes']) <= 12000, 'Manager notes would be truncated')

    guard_desk(expected, original['releases'])
    write_receipt(marker, {'startedAt': stamp(), 'version': VERSION, 'taskRevision': original_box['revision'],
                          'archiveSha256': delivery['archiveSha256'], 'note': 'Do not retry without inspecting partial API results.'})
    mutate(MANAGER, '/api/scan', 'POST', {}, manager_token, 'X-PIQ-Token')
    scanned = get(MANAGER, '/api/catalog')
    require({mod['id']: mod['tracking'] for mod in scanned['mods']} == before_notes, 'Manager notes changed since snapshot')
    row, project, component = validate_project(scanned, delivery)
    guard_desk(expected, original['releases'])
    mutate(MANAGER, '/api/notes', 'POST', notes, manager_token, 'X-PIQ-Token')
    after = get(MANAGER, '/api/catalog')
    for mod in after['mods']:
        if mod['id'] != MOD:
            require(mod['tracking'] == before_notes.get(mod['id']), 'Other manager notes changed')
    row, project, component = validate_project(after, delivery)
    require(all(row['tracking'][key] == notes[key] for key in ('owner', 'status', 'notes', 'stableHash')), 'Manager write readback differs')
    write_receipt(OUT / 'manager-sync.json', {'ok': True, 'at': stamp(), 'version': VERSION,
        'project': project['name'], 'component': {key: component[key] for key in ('id', 'name', 'side', 'evidence', 'purpose')},
        'jar': delivery['jar'], 'jarSha256': delivery['jarSha256'], 'archive': delivery['archive'],
        'scanErrors': after.get('errors', []), 'otherNotesUnchanged': True, 'stableHashUnchanged': True})

    guard_desk(expected, original['releases'])
    updated = mutate(DESK, '/api/tasks/' + BOX, 'PUT', box_update, desk_token, 'X-Desk-Token')['task']
    require(updated['description'] == original_box['description']
            and updated['originalDescription'] == original_box['originalDescription']
            and updated['history'][:len(original_box['history'])] == original_box['history']
            and updated['feedback'] == box_update['feedback'], 'GC-058 original content/history was not preserved')
    expected[BOX] = updated
    guard_desk(expected, original['releases'])
    added = mutate(DESK, '/api/tasks', 'POST', performance_task, desk_token, 'X-Desk-Token')['task']
    require(added['id'] not in expected and added['stage'] == '待确认', 'Unexpected created task')
    expected[added['id']] = added
    guard_desk(expected, original['releases'])
    performance_task.update(stage='待验收', revision=added['revision'])
    added = mutate(DESK, '/api/tasks/' + added['id'], 'PUT', performance_task, desk_token, 'X-Desk-Token')['task']
    expected[added['id']] = added
    final = guard_desk(expected, original['releases'])
    selected = [task for task in final['tasks'] if task['id'] in {BOX, added['id']}]
    require(len(selected) == 2 and all(task['stage'] == '待验收' and task['acceptance'] == '未验收' for task in selected), 'Final task states differ')
    receipt = {'ok': True, 'at': stamp(), 'version': VERSION, 'manager': {'ok': True, 'mod': MOD},
        'tasks': [{key: task[key] for key in ('id', 'revision', 'title', 'stage', 'acceptance', 'version')} for task in selected],
        'otherTasksUnchanged': True, 'releasesUnchanged': True, 'originalDescriptionAndHistoryPreserved': True,
        'installed': False, 'published': False, 'stableHashUnchanged': True}
    write_receipt(OUT / 'tracking.json', receipt)
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
