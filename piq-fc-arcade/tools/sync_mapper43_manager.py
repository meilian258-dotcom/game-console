"""Update only the FC local ledger through the manager's documented API."""
import json
import urllib.request
from pathlib import Path
from datetime import datetime

ROOT=Path(__file__).resolve().parents[2]
URL='http://127.0.0.1:18742'
REPORT=ROOT/'outputs/mapper43/manager-sync-v1.json'
JAR=ROOT/'制作Mod/03-街机模拟/方块电玩-FC43-赤色要塞测试/game_console-0.31.0-alpha.43.jar'
SHA='108dedb98b2be0a1d8682868d32da5698357900b9bbb85fe2ccf4198c55aa372'

def get(path):
    with urllib.request.urlopen(URL+path,timeout=60) as r:return json.load(r)
def post(path,body,token):
    req=urllib.request.Request(URL+path,data=json.dumps(body,ensure_ascii=False).encode(),headers={
        'Content-Type':'application/json','X-PIQ-Token':token,'Origin':URL},method='POST')
    with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)

def main():
    if REPORT.exists():raise ValueError('Refuse to overwrite sync evidence')
    import hashlib
    if hashlib.sha256(JAR.read_bytes()).hexdigest()!=SHA:raise ValueError('Unexpected FC43 artifact')
    before=get('/api/catalog');token=before['token']
    old=next(m for m in before['mods'] if m['id']=='piq_fc_arcade')['tracking']
    post('/api/scan',{},token)
    catalog=get('/api/catalog');fc=next(m for m in catalog['mods'] if m['id']=='piq_fc_arcade')
    matching=[a for a in fc['artifacts'] if a['sha256'].lower()==SHA and Path(a['path'])==JAR]
    if len(matching)!=1:raise ValueError('Frozen FC43 artifact not uniquely scanned')
    note=('2026-09-15 FC 0.31.0-alpha.43（协议35）Mapper19赤色要塞测试版：'
        '交付 制作Mod/03-街机模拟/方块电玩-FC43-赤色要塞测试/game_console-0.31.0-alpha.43.jar，'
        '与 build/review-mapper43-v1 冻结成品逐字节相同，SHA256 '+SHA+'。'
        'Gradle 1706项、0失败、8跳过；Rust79/79及zapper87/87（各1既有忽略文档测试）；'
        '最终JAR-only合成+用户私有ROM验证、370比较帧/275471音频样本/552289断言通过，'
        '标题/关卡/移动已离线目视；24新改class与实际编译结果及27项归档变更独立核对通过。'
        '独立Mapper19 WASM/namespace/存档槽，旧FC和光枪WASM/资产保持字节；N163扩展音频未实现。'
        '客户端与服务器必须同时FC43；街机17/SFC25(core9)/GBA8沿用既有成品，非本次重编。'
        '未安装、发布、重启或关机；真实MC联机/旁观/长时间通关待验证，不指定稳定版。'
        '安装指南：piq-fc-arcade/design/方块电玩-FC43-赤色要塞测试与安装.md；'
        '证据：outputs/mapper43/final-jar-audit.json、probe-final-jar-private-v1.json。'
        'build/libs同版本为未恢复历史贴图的编译中间件，不用于交付；历史成品保留。')
    merged=(old.get('notes','')+'\n\n'+note).strip()
    if len(merged)>12000:raise ValueError('Would truncate existing notes; manual coordination required')
    value={'id':'piq_fc_arcade','owner':old.get('owner') if old.get('owner') not in (None,'','待登记') else '像素匠 / Codex',
        'status':'编译通过','notes':merged,'stableHash':old.get('stableHash','')}
    post('/api/notes',value,token)
    after=get('/api/catalog');final=next(m for m in after['mods'] if m['id']=='piq_fc_arcade')
    for k in ('owner','status','notes','stableHash'):
        if final['tracking'][k]!=value[k]:raise ValueError('Ledger readback mismatch: '+k)
    projects=get('/api/projects');project=next(p for p in projects['projects'] if p['id']=='block_arcade')
    if project['name']!='方块电玩' or 'FC43' not in str(project['guide']):raise ValueError('Project guide/group mismatch')
    if not any(Path(f['path'])==JAR for f in project['files']):raise ValueError('Frozen artifact missing in project files')
    if not any(Path(f['path'])==JAR and f['sha256'].lower()==SHA for f in final['latest']):raise ValueError('Latest points to an intermediate instead of the delivery')
    report={'ok':True,'at':datetime.now().astimezone().isoformat(),'artifact':str(JAR),'sha256':SHA,
        'project':project['name'],'guide':project['guide'],'guideScope':project['guideScope'],
        'tracking':final['tracking'],'latest':final['latest'],'installed':False,'published':False,
        'scan_errors':after.get('errors',[]),'previous_manual_tracking_preserved':True}
    with REPORT.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(REPORT),'latest':final['latest'],'scan_errors':after.get('errors',[])},ensure_ascii=False))

if __name__=='__main__':main()
