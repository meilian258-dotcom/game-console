"""Build the local user package only after the exact frozen JAR audit passes."""
from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,shutil,zipfile,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
DELIVERY=ROOT/'制作Mod/03-街机模拟'
PACKAGE=DELIVERY/'统一界面与SFC双人-alpha18-安装包-20260910'
AUDIT=DELIVERY/'PIQ-FC街机/alpha18-device-ui-v2/final-independent-audit.json'
EXPECTED={
 'fc':'E4A9FC00A492A1E8C7FC4E89D9534A1FBE6A389B64438C393E52D6163AACEC47',
 'sfc':'82578C8DEF9567B1408D8B7F384E8DCC92D388498091ADC0EC308D56AF5D811C',
 'native':'11B8420C09BD44E942F555C7F01BCE14CF8666F8D455046B532875A9240FFA87',
}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def copy(source,name):
    target=PACKAGE/name
    if target.exists():raise ValueError('Refusing to overwrite '+str(target))
    shutil.copyfile(source,target)
    if sha(source)!=sha(target):raise ValueError('Copy hash mismatch')
    return target
def main():
    report=json.loads(AUDIT.read_text(encoding='utf-8'))
    if report.get('ok') is not True:raise ValueError('Final audit did not pass')
    if PACKAGE.with_suffix('.zip').exists():raise ValueError('Final package already exists')
    for key,expected in EXPECTED.items():
        entry=report['archives'][key];p=Path(entry['path'])
        if entry['sha256']!=expected or sha(p)!=expected:raise ValueError('Audited JAR changed '+key)
    summary={'validated_at_utc':datetime.now(timezone.utc).isoformat(),'projects':{},'minecraft_launched':False,'instance_modified':False}
    for project in ('piq-fc-arcade','piq-sfc-home','piq-native-arcade'):
        counts={k:0 for k in ('tests','failures','errors','skipped')}
        suites=list((ROOT/project/'build/test-results/test').glob('TEST-*.xml'))
        if not suites:raise ValueError('No test results '+project)
        for result in suites:
            suite=ET.parse(result).getroot()
            for key in counts:counts[key]+=int(suite.get(key,'0'))
        if counts['failures'] or counts['errors']:raise ValueError('Failed tests '+project)
        summary['projects'][project]=counts
    for entry in report['archives'].values():
        source=Path(entry['path']);copy(source,source.name)
    copy(AUDIT,'成品独立检查.json')
    copy(ROOT/'piq-sfc-home/design/sfc-live-join-final-two-core-20260910.json','双模拟器当前进度同步检查.json')
    copy(ROOT/'piq-sfc-home/design/sfc-hardware-alpha5-v1/pose/mesh-pose-audit.json','SFC模型与持握检查.json')
    copy(ROOT/'piq-fc-arcade/design/device-ui-preview/cartridge-512x278.png','界面离线预览.png')
    copy(ROOT/'piq-sfc-home/design/sfc-hardware-alpha5-v1/hardware-overview.png','SFC模型离线预览.png')
    with (PACKAGE/'构建测试汇总.json').open('x',encoding='utf-8')as f:json.dump(summary,f,ensure_ascii=False,indent=2)
    files=sorted(p for p in PACKAGE.iterdir()if p.is_file())
    with (PACKAGE/'SHA256.txt').open('x',encoding='utf-8')as f:
        f.write('\n'.join(sha(p)+'  '+p.name for p in files)+'\n')
    files=sorted(p for p in PACKAGE.iterdir()if p.is_file())
    zipped=PACKAGE.with_suffix('.zip')
    with zipfile.ZipFile(zipped,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as z:
        for p in files:z.write(p,p.name)
    with zipfile.ZipFile(zipped)as z:
        if z.testzip()is not None or set(z.namelist())!={p.name for p in files}:raise ValueError('Bad package archive')
        for p in files:
            if hashlib.sha256(z.read(p.name)).hexdigest().upper()!=sha(p):raise ValueError('Archive hash mismatch '+p.name)
    result={'ok':True,'path':str(zipped),'sha256':sha(zipped),'bytes':zipped.stat().st_size,'files':len(files),'installed':False}
    with zipped.with_suffix('.verification.json').open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=True))
if __name__=='__main__':main()
