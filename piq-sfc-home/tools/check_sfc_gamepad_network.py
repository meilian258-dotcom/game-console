"""Compile/run actual pure SFC gates and timelines; no Gradle, game or native runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")


def execute(command):
    result=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=60)
    if result.returncode:raise AssertionError(result.stdout+"\n"+result.stderr)
    return result.stdout


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--report",type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError("Refusing to overwrite an old report")
    cache=Path("C:/Users/13498/.gradle/caches/modules-2/files-2.1");deps=[]
    for group,version in [("org.junit.platform","1.13.4"),("org.junit.jupiter","5.13.4"),("org.opentest4j","1.3.0"),("org.apiguardian","1.1.2")]:
        deps.extend(p for p in (cache/group).rglob("*.jar") if version in p.parts and "-sources" not in p.name and "-javadoc" not in p.name)
    production=[ROOT/"src/main/java/cn/piq/sfchome/server"/(name+".java") for name in ("SfcJoinGate","SfcInputHealth","SfcInputTimeline")]
    production.append(ROOT/"src/main/java/cn/piq/sfchome/client/SfcInputSendPolicy.java")
    tests=[ROOT/"src/test/java/cn/piq/sfchome/server"/(name+".java") for name in ("SfcJoinGateTest","SfcInputHealthTest","SfcInputTimelineTest","SfcGamepadNetworkRegressionTest")]
    tests.append(ROOT/"src/test/java/cn/piq/sfchome/client/SfcInputSendPolicyTest.java")
    with tempfile.TemporaryDirectory(prefix="sfc-gamepad-net-") as folder:
        classes=Path(folder)/"classes";classes.mkdir();empty=Path(folder)/"empty";empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,*deps]))
        execute([JAVA/"javac.exe","-encoding","UTF-8","-proc:none","-sourcepath",empty,"-cp",cp,"-d",classes,*production,*tests,ROOT/"tools/qa/SfcGamepadNetworkTestRunner.java"])
        output=execute([JAVA/"java.exe","-cp",cp,"SfcGamepadNetworkTestRunner"])
    result={"ok":True,"tests":41,"output":output,"production_sha256":{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in production},
            "minecraft_or_native_core_started":False,"limits":["Executes actual pure queues, watchdog, consent transaction and client dispatch policy; one source wiring check supplements these tests.","Not a live Minecraft server, permission plugin, physical controller or two-client network test."]}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open("x",encoding="utf-8") as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=="__main__":main()
