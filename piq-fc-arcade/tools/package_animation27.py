"""Small visual-only FC patch: exact alpha26 baseline, final-JAR behavior, no installation."""
import json,os,tempfile,tomllib,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
import verify_retro_alpha19 as q
from freeze_fc_core_alpha19 import read_jar,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes

ROOT=Path(__file__).resolve().parents[2];PROJECT=ROOT/'piq-fc-arcade'
BASE=PROJECT/'build/review-controls26-v2/piq_fc_arcade-0.31.0-alpha.26.jar'
BUILT=PROJECT/'build/libs/piq_fc_arcade-0.31.0-alpha.27.jar'
OUT=ROOT/'制作Mod/03-街机模拟/街机按钮动画修正-alpha27-补丁-20260911'
STEMS={'cn/piq/fcarcade/layout/DualCabinetControls','cn/piq/fcarcade/client/DualCabinetRenderer'}
NAME='piq_fc_arcade-0.31.0-alpha.27.jar'

def candidate():
    sha,_,old=read_jar(BASE);old=clean(old)
    require(sha=='74E16FEF0F69C88191C0A64DA4FCCDEFD2F3B18B231FC56C97839E60B885FBE5','Wrong baseline')
    source_sha,_,new=read_jar(BUILT);new=clean(new)
    for name,(source,frozen)in RESTORE.items():
        require(digest(old[name])==frozen and digest(new[name])in(source,frozen),'Unreviewed texture');new[name]=old[name]
    before=tomllib.loads(old[META].decode());after=tomllib.loads(new[META].decode())
    require(before['mods'][0]['version']=='0.31.0-alpha.26','Wrong baseline version')
    before['mods'][0]['version']='0.31.0-alpha.27';require(before==after,'Unreviewed metadata changes')
    require(new[MANIFEST]==old[MANIFEST].replace(b'0.31.0-alpha.26',b'0.31.0-alpha.27'),'Unreviewed manifest')
    require(not(set(old)-set(new)),'No classes/assets may be removed')
    changes={name:raw for name,raw in new.items()if old.get(name)!=raw}
    for name in changes:
        require(name in (META,MANIFEST)or(name.endswith('.class')and name[:-6].split('$')[0]in STEMS),'Unexpected visual-only delta '+name)
    return jar_bytes(new),{'baseline_sha256':sha,'source_jar_sha256':source_sha,
        'changed_entries':{n:{'before':digest(old[n])if n in old else None,'after':digest(raw)}for n,raw in changes.items()},
        'unchanged_entries':sum(new.get(n)==raw for n,raw in old.items()),'removed_entries':[],
        'input_network_cores_models_textures_unchanged':True}

def verify(path):
    jar_sha,entries=q.archive(path);deps=q.dependencies()
    deps=[d for d in deps if not('org.junit.platform'in d.parts and '1.13.4'not in d.parts)and not('org.junit.jupiter'in d.parts and '5.13.4'not in d.parts)]
    tests=[PROJECT/'src/test/java/cn/piq/fcarcade/layout'/n for n in ('DualCabinetControlsTest.java','CabinetKeyboardAnimationTest.java')]
    probe=PROJECT/'tools/qa/Controls26FinalProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-animation27-final-')as folder:
        tmp=Path(folder);copy=tmp/'fc.jar';copy.write_bytes(path.read_bytes());out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        families={'cn/piq/fcarcade/layout/DualCabinetControls','cn/piq/retro/client/KeyboardConfig','cn/piq/retro/client/KeyboardControlState'}
        origins=tmp/'origins.tsv';origins.write_text('\n'.join(str(copy)+'\t'+n[:-6].replace('/','.')for n in entries if n.endswith('.class')and n[:-6].split('$')[0]in families),encoding='utf-8')
        cp=os.pathsep.join(map(str,[out,copy,q.MC,*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*tests,probe],tmp)
        require(not any(p.relative_to(out).as_posix()in entries for p in out.rglob('*.class')),'Production compiled in QA')
        behavior=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'Controls26FinalProbe',origins,'cn.piq.fcarcade.layout.DualCabinetControlsTest','cn.piq.fcarcade.layout.CabinetKeyboardAnimationTest'],tmp))
        require(behavior['ok']and behavior['tests_found']==18 and behavior['tests_succeeded']==18 and behavior['tests_failed']==behavior['tests_skipped']==behavior['tests_aborted']==0,'Incomplete animation tests')
        disassembly=q.run([q.JAVA/'javap.exe','-p','-c','-classpath',copy,'cn.piq.fcarcade.client.DualCabinetRenderer'],tmp)
        require('DualCabinetControls.layoutForBackend:'in disassembly and 'DualCabinetControls$InputLayout;'in disassembly,'Renderer missing backend-specific animation mapping')
        require('DualCabinetControls.motion:(Lcn/piq/fcarcade/layout/DualCabinetControls$Part;IZ)'not in disassembly,'Renderer still uses ambiguous boolean mapping')
        require(digest(copy.read_bytes())==jar_sha==digest(path.read_bytes()),'Final JAR changed')
    return {'ok':True,'mode':'final-jar-only','production_compiled':False,'jar':str(path),'sha256':jar_sha,
        'behavior':behavior,'renderer_backend_dispatch_checked':True,'renderer_disassembly_sha256':digest(disassembly.encode()),
        'test_source_sha256':{str(p.relative_to(PROJECT)):digest(p.read_bytes())for p in [*tests,probe]},
        'minecraft_started':False,'limits':['Final model-group motion and real preset/key-event classes, not a live Minecraft rendering test.','Input masks, helper, protocols, emulators, model resources and textures are protected byte-for-byte.']}

def main():
    archive=OUT.with_suffix('.zip');verification=OUT.with_suffix('.verification.json')
    require(not OUT.exists()and not archive.exists()and not verification.exists(),'Never overwrite a prior delivery')
    raw,scope=candidate();OUT.mkdir(parents=True);mods=OUT/'mods';mods.mkdir();jar=mods/NAME
    with jar.open('xb')as f:f.write(raw)
    report=verify(jar)
    counts=dict(tests=0,failures=0,errors=0,skipped=0)
    for xml in (PROJECT/'build/test-results/test').glob('TEST-*.xml'):
        suite=ET.fromstring(xml.read_bytes())
        for key in counts:counts[key]+=int(suite.attrib[key])
    require(counts==dict(tests=1118,failures=0,errors=0,skipped=7),'Unexpected full test result')
    report.update(scope=scope,build=counts,installed=False)
    guide=('街机按钮动画修正 alpha27（基于 alpha26）\n\n'
        '仅替换 FC 主模组：先退出游戏并备份旧版，从 mods 移出旧 piq_fc_arcade，再放入本包 alpha27。不要同时保留两版。\n'
        '沿用 alpha26 配套的 piq_sfc-0.1.0-alpha.15.jar、piq_native_arcade-0.1.0-alpha.10.jar 和 helper v3；本补丁不需要重换它们。若尚未安装 alpha26 完整包，请先按该包说明升级配套文件。\n\n'
        '从玩家正面看，机台近身排从左到右是按钮1/2/3，靠屏排是4/5/6。原生街机的小键盘123/456、WASD方案JKL/IOP、经典ZXC/ASD均按这个顺序下压；FC的J/K（或小键盘1/2、经典Z/X）对应B/A两颗。SFC原来的动画顺序保留。\n'
        '只修按钮动画与后端映射；游戏实际操作、个人键位设置、模拟器核心、helper、存档、联机输入及模型贴图均不改变。自由移动/失焦松键逻辑不变。\n'
        '已通过完整构建及最终JAR18项键盘到模型动画验证；尚未进行Minecraft实机视觉验收。没有自动安装。\n').encode('utf-8')
    files={'mods/'+NAME:raw,'先看这里.txt':guide,'checks.json':json.dumps(report,ensure_ascii=False,indent=2).encode()}
    files['SHA256.txt']=''.join(digest(v)+'  '+n+'\n'for n,v in sorted(files.items())).encode()
    for name,data in files.items():
        p=OUT/name
        if name.startswith('mods/'):require(p.read_bytes()==data,'JAR readback differs');continue
        with p.open('xb')as f:f.write(data)
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED)as z:
        for name,data in files.items():z.writestr(name,data)
    with zipfile.ZipFile(archive)as z:
        require(z.testzip()is None and set(z.namelist())==set(files),'Bad archive')
        for name,data in files.items():require(z.read(name)==data==(OUT/name).read_bytes(),'Readback differs '+name)
    reproduced,again=candidate();require(reproduced==raw and again==scope,'Source/baseline changed while packaging')
    result={**report,'package':str(archive),'package_sha256':digest(archive.read_bytes()),'package_bytes':archive.stat().st_size}
    with verification.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:result[k]for k in ('ok','jar','sha256','package','package_sha256','package_bytes','build','installed')},ensure_ascii=True))
if __name__=='__main__':main()
