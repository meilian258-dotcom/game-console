"""Targeted FC permission/save repairs: source preflight or final-JAR-only. No game/network."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
PRODUCTION=['server/ServerArcadeSessions','server/PlayerSaveSlots','server/InteractionTransaction','home/HomeHardware']
TESTS=['server/InteractionTransactionTest','server/PlayerSaveMigrationSafetyTest','server/PlayerSaveSlotsTest','home/HomeLinkLedgerTest','home/HomeAvPermissionTransactionTest']

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--fc',required=True,type=Path);parser.add_argument('--report',required=True,type=Path);parser.add_argument('--source',action='store_true');args=parser.parse_args()
    assert not args.report.exists(),'Use a new report path'
    sources=[ROOT/'src/main/java/cn/piq/fcarcade'/f'{name}.java' for name in PRODUCTION] if args.source else []
    tests=[ROOT/'src/test/java/cn/piq/fcarcade'/f'{name}.java' for name in TESTS]
    probes=[ROOT/'tools/qa/FcPermissionRepairRunner.java',ROOT/'tools/qa/FcPermissionRepairWiringProbe.java']
    fence={str(path.relative_to(ROOT)):q.digest(path.read_bytes()) for path in sources+tests+probes}
    final_hash=q.digest(args.fc.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-permission-repair-') as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jar=tmp/'fc.jar';shutil.copyfile(args.fc,jar)
        preferred=[]
        for group,version in [('org.junit.jupiter','5.13.4'),('org.junit.platform','1.13.4'),('org.ow2.asm','9.8')]:
            preferred.extend(p for p in q.CACHE.glob(f'{group}/*/{version}/*/*.jar') if '-sources' not in p.name and '-javadoc' not in p.name)
        cp=os.pathsep.join(map(str,[out,*preferred,jar,q.MC,*q.dependencies()]))
        argfile=tmp/'cp.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,*probes],ROOT)
        behavior_log=q.run([q.JAVA/'java.exe','@'+str(argfile),'FcPermissionRepairRunner',*['cn.piq.fcarcade.'+name.replace('/','.') for name in TESTS]],ROOT)
        wiring_log=q.run([q.JAVA/'java.exe','@'+str(argfile),'FcPermissionRepairWiringProbe',out if args.source else jar],ROOT)
        behavior=q.parse_last_json(behavior_log);wiring=q.parse_last_json(wiring_log)
        assert fence=={str(path.relative_to(ROOT)):q.digest(path.read_bytes()) for path in sources+tests+probes}
        if not args.source:assert all(not(out/'cn/piq/fcarcade'/f'{name}.class').exists() for name in PRODUCTION)
        report={'ok':True,'mode':'source-preflight-real-api' if args.source else 'final-jar-only','production_compiled':args.source,
                'jars':{'fc':{'path':str(args.fc.resolve()),'sha256':final_hash}},'behavior':behavior,'wiring':wiring,'sources':fence,
                'logs':{'compile':compile_log,'behavior':behavior_log,'wiring':wiring_log},
                'limits':['Pure permission transaction callbacks and actual synthetic save files are exercised; no real Minecraft world or claim plugin is started.',
                          'ASM verifies real compiled MC entry wiring, not event dispatch in an actual world.',
                          'No user ROM/save, server, installation or network operations.']}
    assert q.digest(args.fc.read_bytes())==final_hash
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as handle:json.dump(report,handle,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(args.report),'behavior':behavior,'wiring':wiring}))

if __name__=='__main__':main()
