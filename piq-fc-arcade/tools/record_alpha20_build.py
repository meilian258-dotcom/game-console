"""Summarize actual FC/SFC Gradle XML after both observed check/jar commands succeed."""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
DELIVERY=ROOT/'制作Mod/03-街机模拟'
FC=DELIVERY/'PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar'
SFC=DELIVERY/'PIQ-SFC家用/0.1.0-alpha.7/piq_sfc-0.1.0-alpha.7.jar'
NATIVE=DELIVERY/'PIQ原生街机/0.1.0-alpha.6/piq_native_arcade-0.1.0-alpha.6.jar'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def counts(project):
    reports=sorted((ROOT/project/'build/test-results/test').glob('TEST-*.xml'))
    if not reports:raise ValueError('No real test XML: '+project)
    result={key:0 for key in ('tests','failures','errors','skipped')}
    for path in reports:
        suite=ET.parse(path).getroot()
        for key in result:result[key]+=int(suite.attrib.get(key,0))
    if result['tests']<=result['skipped'] or result['failures'] or result['errors']:
        raise ValueError('Missing or failed tests: '+project)
    return result

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--fc-and-sfc-check-jar-exited-zero',action='store_true',required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    native_sha=sha(NATIVE)
    if native_sha!='B503F5BE9F0C1DAA3640CE1926CCAA268577A76FE709CEFBFA05D9FFEEF5E422':raise ValueError('Native6 changed')
    report={'schema':'piq-compact-alpha20-build-1','ok':True,
        'projects':{name:counts(name) for name in ('piq-fc-arcade','piq-sfc-home')},
        'unchanged':{'piq-native-arcade':{'path':str(NATIVE),'sha256':native_sha,'rebuilt':False}},
        'jars':{
            'fc':{'build_sha256':sha(ROOT/'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.20.jar'),'final_sha256':sha(FC)},
            'sfc':{'thin_home_sha256':sha(ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.7.jar'),'final_sha256':sha(SFC)},
            'native':{'final_sha256':native_sha}},
        'minecraft_started':False,'installed':False,
        'boundary':'Actual offline Gradle check/jar for FC and SFC; Native6 unchanged, not rebuilt. Not Minecraft GUI or live multiplayer validation.'}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    with args.out.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
