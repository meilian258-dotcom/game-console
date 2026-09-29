"""Use existing local workbench/manager APIs, preserve unrelated records and stable flags."""
import hashlib
import json
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/flash-box-prototype1'
MANAGER='http://127.0.0.1:18742'
DESK='http://127.0.0.1:18743'
FIELDS=('title','description','version','evidence','feedback','screenshot','blocker','module','stage','priority','kind','acceptance')

def get(base,path):
    with urllib.request.urlopen(base+path,timeout=120) as r:return json.load(r)

def mutate(base,path,method,body,token,header):
    request=urllib.request.Request(base+path,data=json.dumps(body,ensure_ascii=False).encode('utf-8'),method=method,
        headers={'Content-Type':'application/json',header:token,'Origin':base})
    with urllib.request.urlopen(request,timeout=120) as r:return json.load(r)

def main():
    report=OUT/'tracking.json'
    if report.exists():raise RuntimeError('Already synchronized; inspect before any retry')
    delivery=json.loads((OUT/'delivery.json').read_text(encoding='utf-8'))
    assert delivery['ok']
    with Path(delivery['archive']).open('rb') as f:
        assert hashlib.file_digest(f,'sha256').hexdigest().upper()==delivery['archiveSha256']
    before=get(MANAGER,'/api/catalog');mt=before['token']
    before_notes={m['id']:m['tracking'] for m in before['mods']}
    mutate(MANAGER,'/api/scan','POST',{},mt,'X-PIQ-Token')
    scanned=get(MANAGER,'/api/catalog')
    row=next(m for m in scanned['mods'] if m['id']=='piq_flash_box')
    old=row['tracking']
    addition=('2026-09-18 原型1（0.1.0-prototype.1）：独立播放盒连接现有电视；'
        '创造OP2仅本机SWF预览，两组键鼠端口不等于远端双人。'
        '固定Ruffle nightly-2026-09-16，Windows x64/.NET Desktop6/WebView2；'
        '双方JAR严格配FC56，运行器只在实际执行的客户端。'
        'Gradle check/jar成功，6项测试0失败；真实森林第一部到第一关、两角色分别移动、暂停恢复/退出通过；'
        'Java冻结JAR到helper联动首帧6950ms，307200像素，暂停哈希稳定、正常退出无自身helper遗留，缺文件与取消启动拒绝通过。'
        '未做Minecraft真正启动/Mixin/电视显示、远端双人、确定性省流、完整通关和音频听感验收；无存档保障。'
        '旧TV及贴墙正下方线材有限制，声音直接系统输出。安装说明piq-flash-box/README.md。'
        '成品='+delivery['jar']+'；SHA256='+delivery['jarSha256']+'；ZIP='+delivery['archive']+'。'
        '未安装、发布或重启，不指定稳定哈希；不包含SWF/ROM/BIOS。')
    notes={ 'id':'piq_flash_box','owner':old.get('owner') if old.get('owner') not in ('',None,'待登记') else '像素匠 / Codex',
        'status':'编译通过','notes':old.get('notes','') if addition in old.get('notes','') else (old.get('notes','')+'\n\n'+addition).strip(),'stableHash':old.get('stableHash','')}
    assert len(notes['notes'])<=12000
    mutate(MANAGER,'/api/notes','POST',notes,mt,'X-PIQ-Token')
    after=get(MANAGER,'/api/catalog')
    for mod in after['mods']:
        if mod['id'] in before_notes and mod['id']!='piq_flash_box':assert mod['tracking']==before_notes[mod['id']]
    row=next(m for m in after['mods'] if m['id']=='piq_flash_box')
    assert any(a['version']=='0.1.0-prototype.1' and a['sha256'].upper()==delivery['jarSha256'] for a in row['latest'])
    assert any(Path(a['path'])==Path(delivery['jar']) and a['sha256'].upper()==delivery['jarSha256'] for a in row['artifacts'])
    assert all(row['tracking'][k]==notes[k] for k in ('owner','status','notes','stableHash'))
    project=next(p for p in get(MANAGER,'/api/projects')['projects'] if p['id']=='block_arcade')
    component=next(c for c in project['components'] if c['id']=='piq_flash_box')
    assert component['side']=='both' and '播放盒' in component['name'] and '本机' in component['purpose']
    assert any(Path(d['path'])==ROOT/'piq-flash-box/README.md' for d in project['documents'] if d.get('path'))
    assert any(Path(e['path'])==Path(delivery['archive']) for e in project['extras'])
    manager_receipt={'ok':True,'at':datetime.now().astimezone().isoformat(),'project':project['name'],
        'component':component,'scanErrors':after.get('errors',[]),'otherNotesUnchanged':True}
    (OUT/'manager-sync.json').write_text(json.dumps(manager_receipt,ensure_ascii=False,indent=2),encoding='utf-8')

    previous=get(DESK,'/api/state');dt=previous['token'];ids={'GC-020','GC-056','GC-057'}
    box_title='Flash 播放盒与电视适配（本机原型）'
    assert not any(t['title']==box_title for t in previous['tasks']), 'Existing box task: inspect instead of duplicating'
    base={k:'' for k in FIELDS}
    base.update(title=box_title,module='通用',stage='待确认',priority='P2',kind='功能',acceptance='未验收',
        description=('用户明确选择独立播放盒连接现有电视，非复古电脑。首版做可扩展其他SWF的本机功能原型，'
            '森林冰火人只是首测，不打包游戏。此项只验收盒子/接线/本机界面/画面和生命周期；'
            '远端双人留在GC-056、省流确定性留在GC-057，不能合并宣称完成。'),
        version='Flash Box 0.1.0-prototype.1 + FC56',
        evidence=('已有附属JAR、固定运行器及源码测试包。6项自动测试，真实森林第一关/两角色独立按键，'
            'Java到helper联动及退出通过。Minecraft实际启动/Mixin/电视显示与按键冲突待人工验收。'
            '外观临时，Windows x64/.NET6/WebView2；无存档/远端双人/省流；桌面电视优先，旧TV/垂直贴墙线材有限制。'
            '包：'+delivery['archive']+'；指南：piq-flash-box/README.md'))
    box=mutate(DESK,'/api/tasks','POST',base,dt,'X-Desk-Token')['task']
    base['stage']='待验收';base['revision']=box['revision']
    box=mutate(DESK,'/api/tasks/'+box['id'],'PUT',base,dt,'X-Desk-Token')['task']
    new_id=box['id']
    stages={'GC-020':'开发中','GC-056':'开发中','GC-057':'待开发'}
    additions={
        'GC-020':f'2026-09-18 后续实际开发：独立播放盒本机原型已出测试包（{new_id}待验收）；通用远端双人GC-056开发中，省流GC-057待开发。H5未实现，整个总需求未完成。',
        'GC-056':f'2026-09-18 原型1：采用固定Ruffle/WebView2独立helper，森林第一部真实进入第一关，两组本机按键分别移动，鼠标/暂停/退出通过。Java到helper联动首帧6950ms，6项单测通过。新增播放盒硬件独立验收见{new_id}。还没有服务器第二位玩家席位/远端输入/共享音画/游戏分发，不把本机两组按键记成联机完成。',
        'GC-057':'2026-09-18 用户已授权尝试开发，当前先完成本机运行器底座，本项仍待开发。Ruffle页面内按实时tick运行，PNG捕获15fps不是逻辑锁步。尚未实现统一虚拟时钟/随机/资源顺序或双实例确定性实验，没有带宽节省比例和中途加入保障。'
    }
    for ident in sorted(ids):
        current=get(DESK,'/api/state');task=next(t for t in current['tasks'] if t['id']==ident)
        # Refuse to overwrite concurrent edits compared with the initial API snapshot.
        original=next(t for t in previous['tasks'] if t['id']==ident)
        assert task['revision']==original['revision']
        update={k:task.get(k,'') for k in FIELDS}
        update.update(stage=stages[ident],acceptance='未验收',revision=task['revision'],
            evidence=(task.get('evidence','')+'\n\n'+additions[ident]).strip())
        if ident!='GC-057':update['version']='Flash Box 0.1.0-prototype.1（本机原型，联机未完成）'
        if ident=='GC-020':update['description']+='\n\n'+additions[ident]
        mutate(DESK,'/api/tasks/'+ident,'PUT',update,dt,'X-Desk-Token')
    final=get(DESK,'/api/state')
    for task in previous['tasks']:
        if task['id'] not in ids:assert next(t for t in final['tasks'] if t['id']==task['id'])==task
    assert previous['releases']==final['releases']
    selected=[{k:t[k] for k in ('id','revision','title','stage','acceptance','version')} for t in final['tasks'] if t['id'] in ids|{new_id}]
    for task in selected:
        assert task['stage']==('待验收' if task['id']==new_id else stages[task['id']]) and task['acceptance']=='未验收'
    receipt={'ok':True,'at':datetime.now().astimezone().isoformat(),'manager':{'ok':True,'project':project['name'],'newMod':'piq_flash_box'},
        'tasks':selected,'otherTasksUnchanged':True,'releasesUnchanged':True,'installed':False,'published':False}
    report.write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(receipt,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
