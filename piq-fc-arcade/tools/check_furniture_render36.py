"""Final-JAR-only furniture geometry/material resource reload QA; no game or native core starts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import verify_retro_alpha19 as q

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fc', type=Path, required=True); p.add_argument('--report', type=Path, required=True)
    args = p.parse_args(); fc = args.fc.resolve(strict=True); report = args.report.resolve()
    q.require(not report.exists(), 'New report required')
    before, production = q.archive(fc)
    source = ROOT / 'tools/qa/FurnitureRender36Probe.java'; inputs = [source, Path(__file__).resolve()]
    source_sha = {str(path.relative_to(ROOT)): sha(path) for path in inputs}
    with tempfile.TemporaryDirectory(prefix='piq-furniture-render36-') as name:
        work=Path(name); classes=work/'qa'; classes.mkdir(); empty=work/'empty'; empty.mkdir()
        staged=work/'fc.jar'; shutil.copyfile(fc, staged)
        vanilla=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        # The general cache includes MC 1.20's LWJGL 3.3.1 native DLLs as well. Use this game's
        # exact 3.3.3 Java/native pair for the CPU-only NativeImage/SpriteContents check.
        dependencies=[path for path in q.dependencies() if 'org.lwjgl' not in path.parts or
                      ('3.3.3' in path.parts and ('natives-' not in path.name or path.name.endswith('natives-windows.jar')))]
        cp=os.pathsep.join(map(str,[classes,staged,q.MC,vanilla,*dependencies]))
        cp_args=work/'cp.args'; cp_args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        copied=work/source.name; shutil.copyfile(source,copied)
        q.run([q.JAVA/'javac.exe','@'+str(cp_args),'--release','21','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,copied],work)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','-Xmx512m','-Djava.awt.headless=true','@'+str(cp_args),
            'cn.piq.fcarcade.furniture.client.FurnitureRender36Probe',staged,vanilla,work/'fixtures'],work,90))
        q.require(result.get('ok') and result['actual_multi_pack_resource_manager'] and result['wood_species']==11,'Resource-manager QA')
        compiled=sorted(path.relative_to(classes).as_posix() for path in classes.rglob('*.class'))
        q.require(all(Path(path).name[:-6].split('$')[0]=='FurnitureRender36Probe' for path in compiled),'Only probe compiled')
        q.require(not set(compiled)&set(production),'No production shadow classes')
        q.require(before==sha(fc)==sha(staged),'JAR changed during QA')
        q.require(source_sha=={str(path.relative_to(ROOT)):sha(path) for path in inputs},'QA source changed')
    data={'schema':'piq-furniture36-render-1','ok':True,'mode':'final-jar-only','production_compiled':False,
          'jar':str(fc),'sha256':before,'resource_checks':result,'qa_source_sha256':source_sha,
          'compiled_qa_classes':compiled,'minecraft_started':False,'installed':False,
          'limits':['Actual Minecraft resource-manager precedence, native vanilla texture IDs, replacement/removal, malformed mesh reload and final-JAR geometry are executed.',
                    'Actual BlockModel/ItemTransform and final-JAR item-fit geometry projection are executed. SpriteContents/TextureAtlasSprite normalized UV uses CPU native memory only.',
                    'Renderer atlas/Cull/lighting integration is checked in compiled bytecode. No GPU atlas upload, rendered frame, in-world seating or multiplayer playtest was run.',
                    'Animation metadata is preserved for the vanilla atlas, not a claim of observed animated frames.']}
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8') as out:json.dump(data,out,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(report),'resource_checks':result},ensure_ascii=False))


if __name__ == '__main__':main()
