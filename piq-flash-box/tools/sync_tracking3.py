"""Record only Flash prototype3 through existing local APIs; preserve manual history."""
import hashlib
import json
import zipfile
from pathlib import Path
import sync_tracking2 as api

OUT = api.ROOT / 'outputs/flash-box-prototype3'
VERSION = '0.1.0-prototype.3'
WORLD_TITLE = 'Flash 看电视操作与世界按键隔离'
api.OUT = OUT
api.VERSION = VERSION


def main():
    for name in ('tracking.json', 'manager-sync.json', 'sync_tracking3.started.json'):
        api.require(not (OUT / name).exists(), 'Previous/partial run exists: inspect before any retry')
    delivery = api.read_json(OUT / 'delivery.json')
    api.require(delivery['ok'] and not delivery['installed'] and not delivery['published']
                and not delivery['gameFilesIncluded'], 'Invalid delivery state')
    for key, digest in (('archive', 'archiveSha256'), ('jar', 'jarSha256')):
        path = Path(delivery[key])
        api.require(path.resolve().is_relative_to(api.ROOT.resolve()) and api.sha(path) == delivery[digest], 'Artifact mismatch')
    performance = api.read_json(OUT / 'runtime-performance.json')
    api.require(performance['ok'] is True, 'Runtime evidence failed')
    with zipfile.ZipFile(delivery['archive']) as archive:
        api.require(archive.testzip() is None, 'Archive CRC failed')
        api.require(hashlib.sha256(archive.read('mods/' + Path(delivery['jar']).name)).hexdigest().upper()
                    == delivery['jarSha256'], 'Packed JAR differs')
        verification = json.loads(archive.read('verification.json'))
        tests = verification['unitTests']
        api.require(tests['tests'] >= 32 and tests['failed'] == 0 and tests['skipped'] == 0, 'Unit tests failed')
        api.require(verification['helperPerformance'] == performance and verification['javaHelperSmoke']['ok'], 'Smoke differs')
        api.require(archive.read('先读我-安装与边界.md') == (api.ROOT / 'piq-flash-box/README.md').read_bytes(), 'Guide differs')
        api.require(verification['stable'] is False, 'Must not mark stable')
    before = api.get(api.MANAGER, '/api/catalog')
    old_notes = {row['id']: row['tracking'] for row in before['mods']}
    original = api.get(api.DESK, '/api/state')
    expected = api.tasks(original)
    api.require(not any(t['title'] == WORLD_TITLE for t in original['tasks']), 'World control task already exists')
    api.require(VERSION not in old_notes[api.MOD].get('notes', ''), 'Version already recorded')
    for ident in ('GC-058', 'GC-059'):
        api.require(ident in expected and 'Flash' in expected[ident]['title'], 'Wrong task identity')
    summary = ('2026-09-18 原型3（' + VERSION + '，严格配FC56）：新增明确“看电视操作”，关闭大预览后在世界中控制本机Flash；'
        '鼠标转视角，键盘接管两组游戏键并阻止MC移动/跳跃/快捷键，右键回预览，Esc退出接管但保留电视播放。'
        '进入需就绪首帧/有效电视，松键后重武装；失焦、菜单、断线、超距、失权/设备失效与关电视退出接管。'
        '运行器0.1.2图像协议支持JPEG/PNG，Java有界尺寸先验与内存解码；性能只引用实际helper测量，不能当MC/网络FPS。'
        f'离线构建与{tests["tests"]}项自动测试通过，冻结JAR/helper联动通过；新增世界键鼠和最终流畅度待Minecraft人工验收。'
        '实测报告=' + str(OUT / 'runtime-performance.json') + '；指南piq-flash-box/README.md。'
        '成品=' + delivery['jar'] + '；SHA256=' + delivery['jarSha256'] + '；ZIP=' + delivery['archive'] + '。'
        '双方附属JAR和执行客户端完整runtime成套更新。无游戏文件，不安装/发布/重启，不标稳定。'
        '仍仅本机，无远端双人、确定性省流或存档保障。')
    feedback = ('2026-09-18 用户反馈原型2：截图显示界面已清晰、电视接收画面，游戏内显示25FPS，'
        'MC接收约8FPS；用户明确仍卡并要求在外面看电视也能控制。截图不代表Esc与全生命周期已通过验收。')
    updates = {}
    for ident in ('GC-058', 'GC-059'):
        old = expected[ident]
        update = {key: old.get(key, '') for key in api.FIELDS}
        update.update(version='Flash Box ' + VERSION + ' + FC56', stage='待验收', acceptance='未验收',
            evidence=api.append(old.get('evidence', ''), summary), feedback=api.append(old.get('feedback', ''), feedback), revision=old['revision'])
        api.validate_fields(update)
        updates[ident] = update
    new_task = {key: '' for key in api.FIELDS}
    new_task.update(title=WORLD_TITLE, description='用户希望不打开大预览菜单、直接看实体电视操作Flash。'
        '首版明确按钮进入；不同时移动MC人物，鼠标可转视角，Esc归还操作。仍是同机两组按键，不是服务器远端双人。',
        module='通用', kind='功能', stage='待确认', priority='P2', acceptance='未验收',
        version='Flash Box ' + VERSION + ' + FC56', evidence=summary, feedback=feedback)
    api.validate_fields(new_task)
    notes = {key: old_notes[api.MOD].get(key, '') for key in ('owner', 'status', 'notes', 'stableHash')}
    notes.update(id=api.MOD, notes=api.append(notes['notes'], summary))
    api.require(len(notes['notes']) <= 12000, 'Notes would be truncated')
    api.guard_desk(expected, original['releases'])
    api.write_receipt(OUT / 'sync_tracking3.started.json', {'at': api.stamp(), 'version': VERSION,
        'archiveSha256': delivery['archiveSha256'], 'note': 'Inspect partial API state before retry; tokens never stored.'})
    api.mutate(api.MANAGER, '/api/scan', 'POST', {}, before['token'], 'X-PIQ-Token')
    scanned = api.get(api.MANAGER, '/api/catalog')
    api.require({row['id']: row['tracking'] for row in scanned['mods']} == old_notes, 'Concurrent notes change')
    row, project, component = api.validate_project(scanned, delivery)
    api.mutate(api.MANAGER, '/api/notes', 'POST', notes, before['token'], 'X-PIQ-Token')
    after = api.get(api.MANAGER, '/api/catalog')
    row, project, component = api.validate_project(after, delivery)
    api.require(all(row['tracking'][key] == notes[key] for key in ('owner', 'status', 'notes', 'stableHash')), 'Notes readback differs')
    api.require(all(row['tracking'] == old_notes[row['id']] for row in after['mods'] if row['id'] != api.MOD), 'Other notes changed')
    api.write_receipt(OUT / 'manager-sync.json', {'ok': True, 'at': api.stamp(), 'version': VERSION, 'project': project['name'],
        'component': {key: component[key] for key in ('id', 'name', 'purpose', 'side', 'evidence')},
        'jar': delivery['jar'], 'jarSha256': delivery['jarSha256'], 'archive': delivery['archive'],
        'otherNotesUnchanged': True, 'stableHashUnchanged': True, 'scanErrors': after.get('errors', [])})
    for ident, update in updates.items():
        api.guard_desk(expected, original['releases'])
        old = expected[ident]
        task = api.mutate(api.DESK, '/api/tasks/' + ident, 'PUT', update, original['token'], 'X-Desk-Token')['task']
        api.require(task['description'] == old['description'] and task['originalDescription'] == old['originalDescription']
            and task['history'][:len(old['history'])] == old['history'], 'Original content/history changed')
        expected[ident] = task
    api.guard_desk(expected, original['releases'])
    task = api.mutate(api.DESK, '/api/tasks', 'POST', new_task, original['token'], 'X-Desk-Token')['task']
    api.require(task['id'] not in expected and task['stage'] == '待确认', 'Unexpected new task')
    expected[task['id']] = task
    api.guard_desk(expected, original['releases'])
    new_task.update(stage='待验收', revision=task['revision'])
    task = api.mutate(api.DESK, '/api/tasks/' + task['id'], 'PUT', new_task, original['token'], 'X-Desk-Token')['task']
    expected[task['id']] = task
    final = api.guard_desk(expected, original['releases'])
    selected = [t for t in final['tasks'] if t['id'] in {'GC-058', 'GC-059', task['id']}]
    api.require(all(t['stage'] == '待验收' and t['acceptance'] == '未验收' for t in selected), 'Wrong stage')
    receipt = {'ok': True, 'at': api.stamp(), 'version': VERSION, 'manager': {'ok': True, 'mod': api.MOD},
        'tasks': [{key: t[key] for key in ('id', 'title', 'version', 'stage', 'acceptance', 'revision')} for t in selected],
        'otherTasksUnchanged': True, 'releasesUnchanged': True, 'originalHistoryPreserved': True,
        'stableHashUnchanged': True, 'installed': False, 'published': False}
    api.write_receipt(OUT / 'tracking.json', receipt)
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
