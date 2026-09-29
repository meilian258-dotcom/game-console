"""Run the existing real FML discovery and SFC outer-codec probes on the three final JARs."""
import argparse,json
from pathlib import Path
import verify_retro_alpha19 as compat
from freeze_fc_core_alpha19 import safe_path,require,digest
import prepare_interaction24 as stage

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--stage',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    report=safe_path(a.report);require(report.is_relative_to(stage.ROOT) and not report.exists(),'Refuse existing/outside report')
    jars={k:safe_path(a.stage/name,True) for k,name in stage.NAMES.items()}
    identities={k:{'path':str(path),'sha256':digest(path.read_bytes())}for k,path in jars.items()}
    probes=compat.java_probes(jars)
    require(all(digest(jars[k].read_bytes())==v['sha256']for k,v in identities.items()),'Final JAR changed during probe')
    value={'ok':True,'mode':'final-jar-only','jars':identities,'real_fml_and_outer_codec':probes,
           'production_compiled':False,'minecraft_started':False,'installed':False,
           'limits':['Real FML discovery and codec checks are not a full Minecraft launch or live multiplayer test.']}
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8')as f:json.dump(value,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(report),'jars':identities},ensure_ascii=True))
if __name__=='__main__':main()
