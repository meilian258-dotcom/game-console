"""Development-only FML/protocol probe using frozen-byte metadata fixtures.

This is not a release check: all JARs are temporary copies of old frozen classes
with upcoming metadata. It proves probe viability before final builds exist.
"""
import argparse
import json
from pathlib import Path
import tempfile
import zipfile
import verify_retro_alpha19 as verifier
from test_verify_retro_alpha19 import Alpha19CompatibilityTests


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--report",type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError("Do not overwrite old reports")
    Alpha19CompatibilityTests.setUpClass()
    fixture={key:dict(entries) for key,entries in Alpha19CompatibilityTests.fixture.items()}
    del fixture["fc"]["cn/piq/retro/client/GamepadInput.class"] # dummy ownership entry is never class-scanned
    with tempfile.TemporaryDirectory(prefix="alpha19-fml-fixture-") as folder:
        paths={}
        for key,entries in fixture.items():
            path=Path(folder)/(key+"-metadata-fixture.jar");paths[key]=path
            with zipfile.ZipFile(path,"w",compression=zipfile.ZIP_DEFLATED) as jar:
                for name,raw in entries.items():jar.writestr(name,raw)
        result=verifier.java_probes(paths)
    report={"ok":True,"fixture_only":True,"not_a_final_release_validation":True,"probes":result,"installed":False,"minecraft_or_native_core_started":False}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open("x",encoding="utf-8") as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=="__main__":main()
