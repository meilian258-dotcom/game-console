"""Verify final SFC generic adapter and byte-level preservation; compile probes only, never production."""
import argparse, hashlib, json, os, subprocess, tempfile, sys, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def entries(path):
    with zipfile.ZipFile(path) as jar:return {n:jar.read(n) for n in jar.namelist() if not n.endswith('/')}
def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser()
    for name in ['fc','sfc','baseline','report']:parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    if args.report.exists():raise ValueError('Use a new immutable report')
    jars={k:getattr(args,k).resolve(strict=True) for k in ['fc','sfc','baseline']};hashes={k:sha(v) for k,v in jars.items()}
    assert hashes['fc']=='0147D49C542E82DDF2DD37CDAFFBDB135CDA20E11907B4060B471A2FBC0D3F93'
    assert hashes['sfc']=='F951146515D7F35257585A9DA7536453F1DFFA70285F51EDD612D65C771DF951'
    assert hashes['baseline']=='3C678DC03EF9479564BC3F7005210E9DE868792A8D29DD0CDD62CAB3C79BC1E5'
    old,new=entries(jars['baseline']),entries(jars['sfc']);assert old.keys()==new.keys(),'Unexpected added/removed SFC entries'
    changed={n for n in old if old[n]!=new[n]}
    allowed={'META-INF/MANIFEST.MF','META-INF/neoforge.mods.toml','cn/piq/sfchome/SfcHomeMod.class',
             'cn/piq/sfchome/client/cabinet/SfcCabinetInputs.class','cn/piq/sfchome/client/cabinet/SfcCabinetProvider.class','cn/piq/sfchome/client/cabinet/SfcCabinetSession.class'}
    assert changed==allowed,changed^allowed
    protected={n for n in old if n not in allowed};assert all(old[n]==new[n] for n in protected)
    probes=[ROOT/'tools/qa/SfcCabinetFinalPortsProbe.java',ROOT/'tools/qa/SfcCabinetActualCoreProbe.java',
            ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    source_hashes={str(p.relative_to(ROOT.parent)):sha(p) for p in probes+[Path(__file__).resolve()]}
    def run(command,cwd):
        result=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
        if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
        return result.stdout
    with tempfile.TemporaryDirectory(prefix='piq-sfc-final-ports-') as name:
        temp=Path(name);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,jars['sfc'],jars['fc']]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*probes],temp)
        output=run([JAVA/'java.exe','-Xmx1G','-Dfile.encoding=UTF-8','-cp',cp,'cn.piq.sfchome.client.cabinet.SfcCabinetFinalPortsProbe',jars['sfc'],jars['fc']],temp)
    assert all(sha(path)==hashes[name] for name,path in jars.items())
    assert all(sha(ROOT.parent/name)==digest for name,digest in source_hashes.items())
    report={'passed':True,'production_compiled':False,'mode':'final-jar-only','jars':{k:{'path':str(p),'sha256':hashes[k]} for k,p in jars.items()},
            'changed_entries':sorted(changed),'protected_unchanged_entry_count':len(protected),'home_network_core_and_all_assets_unchanged':True,
            'probe':json.loads(next(s for s in reversed(output.splitlines()) if s.startswith('{'))),'probe_output':output,'source_sha256':source_hashes,
            'limits':['No Minecraft instance, server, real multiplayer socket, audio device or controller was started.','Only original generated 65816 ROM; no commercial compatibility claim.','Byte comparison includes all entries outside six explicitly allowed generic-adapter/metadata entries.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
