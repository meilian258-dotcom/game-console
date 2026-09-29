"""Independent spectator QA against explicit production classes or a final JAR.

Only tests and the probe are compiled. Uses real NeoForge codecs and Connection
write promises, but never creates a game/server/world/core or opens a socket.
Report creation is exclusive; original production artifacts are read-only.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from verify_retro_alpha19 import ROOT,TOOLS,JAVA,MC,dependencies,run,parse_last_json,digest,require

TESTS=[
    "cn.piq.fcarcade.cabinet.WatchAuthorityTest",
    "cn.piq.fcarcade.client.cabinet.WatchMediaIsolationTest",
    "cn.piq.fcarcade.client.cabinet.WatchMediaStreamTest",
    "cn.piq.fcarcade.client.watch.WatchLeaseStateTest",
]
BASE=ROOT/"piq-fc-arcade/build/review-user-models22-v1/piq_fc_arcade-0.31.0-alpha.22.jar"

def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--jar",type=Path);mode.add_argument("--classes",type=Path)
    parser.add_argument("--report",type=Path,required=True);args=parser.parse_args()
    require(not args.report.exists(),"Refusing to overwrite existing report")
    original=(args.jar or args.classes).resolve(strict=True);kind="final-jar-only" if args.jar else "compiled-workspace-production"
    before={original.name:digest(original.read_bytes())} if args.jar else {p.relative_to(original).as_posix():digest(p.read_bytes()) for p in original.rglob("*.class")}
    require(bool(before),"No production classes found")
    with tempfile.TemporaryDirectory(prefix="piq-watch23-independent-") as folder:
        tmp=Path(folder);classes=tmp/"tests";classes.mkdir();empty=tmp/"empty";empty.mkdir()
        if args.jar:
            stage=tmp/"fc.jar";shutil.copyfile(original,stage);require(digest(stage.read_bytes())==before[original.name],"Staged final JAR hash mismatch");production=[stage]
        else:
            stage=tmp/"production";shutil.copytree(original,stage);base=tmp/"fc22.jar";shutil.copyfile(BASE,base)
            require(digest(base.read_bytes())=="ECC559B11E21F389BB95AC8113A2D885DC8DE7B91F01DE5C25156AD51AEB5645","Frozen FC22 dependency identity");production=[stage,base]
        resources=MC.parent/"stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar"
        cp=os.pathsep.join(map(str,[classes,*production,MC,resources,*dependencies()]))
        argfile=tmp/"classpath.args";argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding="utf-8")
        tests=[ROOT/"piq-fc-arcade/src/test/java"/(name.replace(".","/")+".java") for name in TESTS]
        probes=[TOOLS/"qa/CabinetRoomTestRunner.java",TOOLS/"qa/Watch23WireProbe.java"]
        run([JAVA/"javac.exe","@"+str(argfile),"-encoding","UTF-8","-proc:none","-sourcepath",empty,"-d",classes,*tests,*probes],tmp)
        java=[JAVA/"java.exe","-Djava.awt.headless=true","@"+str(argfile)]
        pure=parse_last_json(run([*java,"CabinetRoomTestRunner",*TESTS],tmp))
        wire=parse_last_json(run([*java,"cn.piq.fcarcade.cabinet.Watch23WireProbe",stage,kind],tmp,timeout=120))
        require(pure["passed_tests"]>=34 and wire["ok"] and wire["production_origin"]==kind,"Incomplete independent QA")
        after={original.name:digest(original.read_bytes())} if args.jar else {p.relative_to(original).as_posix():digest(p.read_bytes()) for p in original.rglob("*.class")}
        require(before==after,"Production artifacts changed during QA")
        staged_after={original.name:digest(stage.read_bytes())} if args.jar else {p.relative_to(stage).as_posix():digest(p.read_bytes()) for p in stage.rglob("*.class")}
        require(before==staged_after,"Staged production changed or copied inconsistently")
        report={"ok":True,"schema":"piq-watch23-independent-1","production_origin":kind,"production_path":str(original),"production_sha256":before if args.jar else None,
                "production_class_hashes":before if args.classes else None,"production_compiled":False,"pure_behavior":pure,"real_codec_and_shared_window":wire,
                "staged_copy_verified_before_and_after":True,
                "minecraft_or_native_core_started":False,"network_socket_opened":False,"installed":False,
                "limits":["No live Minecraft world/server/players, render or audio-device acceptance test.","Pure lease/budget tests and actual codec/window tests do not replace in-world provider/permission tests.","Queued ingress uses the production dispatcher with a test ClientSink implementing the live-connection contract.","Late-worker exposure is tested with a deterministic injected completion fixture, not a scheduler-race claim."]}
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open("x",encoding="utf-8") as target:json.dump(report,target,ensure_ascii=False,indent=2);target.write("\n")
    print(json.dumps({"ok":True,"report":str(args.report),"tests":pure["passed_tests"],"wire_assertions":wire["assertions"],"origin":kind},ensure_ascii=False))

if __name__=="__main__":main()
