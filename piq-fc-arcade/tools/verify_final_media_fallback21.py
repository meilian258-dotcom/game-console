"""Exact final-class comparison with tested source, then execute only final-JAR codec classes."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home

import argparse,hashlib,json,os,shutil,tempfile,zipfile
from pathlib import Path
from check_cabinet_media_fallback import ROOT,JAVA,run,result
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()
def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    previous=json.loads((ROOT/'design/cabinet-media-fallback-20260911.json').read_text(encoding='utf-8'))
    source=[ROOT.parent/'piq-retro-platform/src/main/java/cn/piq/retro/api/RetroFrame.java',ROOT/'src/main/java/cn/piq/fcarcade/cabinet/CabinetMediaCodec.java']
    for path in source:
        if sha(path)!=previous['source_sha256'][str(path.relative_to(ROOT.parent))]:raise ValueError('Tested source changed')
    names=['cn/piq/fcarcade/cabinet/CabinetMediaCodec.class','cn/piq/fcarcade/cabinet/CabinetMediaCodec$Encoded.class','cn/piq/retro/api/RetroFrame.class']
    with zipfile.ZipFile(a.jar)as jar:actual={name:jar.read(name)for name in names}
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(x for x in(cache/group).rglob('*.jar')if version in x.parts and '-sources'not in x.name and '-javadoc'not in x.name)
    with tempfile.TemporaryDirectory(prefix='final-codec21-')as folder:
        temp=Path(folder);staged=temp/'final-fc21.jar';shutil.copyfile(a.jar,staged)
        if sha(a.jar)!=sha(staged):raise ValueError('Final JAR staging mismatch')
        comparison=temp/'comparison-only';comparison.mkdir();matched=None
        for flags in [['-g'],['-g','-parameters'],[]]:
            run([JAVA/'javac.exe','--release','21','-encoding','UTF-8','-proc:none',*flags,'-d',comparison,*source])
            if all((comparison/name).read_bytes()==contents for name,contents in actual.items()):matched=flags;break
        if matched is None:raise ValueError('Final codec class bytes differ from independently compiled tested source')
        classes=temp/'qa';classes.mkdir();empty=temp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,staged,*deps]))
        probes=[ROOT/'tools/qa'/n for n in ['CabinetFinalCodecOriginProbe.java','CabinetMediaFallbackTestRunner.java','CabinetMediaCpuProbe.java']]
        test=ROOT/'src/test/java/cn/piq/fcarcade/cabinet/CabinetMediaCodecTest.java'
        run([JAVA/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*probes,test])
        # The comparison-only classes directory is deliberately absent from this runtime classpath.
        java=[JAVA/'java.exe','-Xmx256m','-cp',cp]
        origin=result(run([*java,'CabinetFinalCodecOriginProbe',staged]));tests=result(run([*java,'CabinetMediaFallbackTestRunner']));cpu=result(run([*java,'CabinetMediaCpuProbe']))
        if sha(a.jar)!=sha(staged):raise ValueError('Final JAR changed during execution')
    report={'ok':True,'final_jar':str(a.jar.resolve()),'final_jar_sha256':sha(a.jar),'exact_tested_source_class_match':True,'comparison_javac_flags':matched,'executed_production_origin':origin,'tests':tests,'cpu':cpu,'class_sha256':{n:hashlib.sha256(b).hexdigest().upper()for n,b in actual.items()},'source_sha256':{str(x.relative_to(ROOT.parent)):sha(x)for x in source},'limits':['Source compiled only into isolated comparison directory for exact byte equality; no comparison classes executed.','All executed production classes came from final JAR only; no Minecraft, native core or network connection started.','Synthetic CPU and payload estimates are not game/network performance guarantees.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False))
if __name__=='__main__':main()
