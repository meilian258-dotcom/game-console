"""Freeze the SHA-verified SFC8-only update; never touches a Minecraft instance."""
import hashlib,json,sys,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
import merge_sfc_addon as safe

ROOT=Path(__file__).resolve().parents[2]
PROJECT=ROOT/'piq-sfc-home'
DELIVERY=ROOT/'制作Mod/03-街机模拟'
FINAL=DELIVERY/'PIQ-SFC家用/0.1.0-alpha.8/piq_sfc-0.1.0-alpha.8.jar'
EXPECTED='3C678DC03EF9479564BC3F7005210E9DE868792A8D29DD0CDD62CAB3C79BC1E5'

def digest(raw):return hashlib.sha256(raw).hexdigest().upper()

def main():
    candidate=PROJECT/'build/review-multiplayer8-v1/piq_sfc-0.1.0-alpha.8.jar'
    paths={
        'README-zh.md':DELIVERY/'SFC双人联机-alpha8-使用说明.md',
        'playback-multiplayer.json':PROJECT/'design/playback-multiplayer8-v1.json',
        'final-independent-audit.json':PROJECT/'build/review-multiplayer8-v1/final-independent-audit.json',
        'client-identity.json':PROJECT/'design/sfc-client-identity-20260910-v1.json',
        'server-authority.json':PROJECT/'design/sfc8-server-authority-20260910.json',
    }
    for path in [candidate,FINAL,*paths.values()]:safe.safe_path(path,existing=True)
    safe.require(digest(candidate.read_bytes())==EXPECTED and digest(FINAL.read_bytes())==EXPECTED,'Final/candidate SHA mismatch')
    entries={name:path.read_bytes() for name,path in paths.items()}
    playback=json.loads(entries['playback-multiplayer.json']);audit=json.loads(entries['final-independent-audit.json'])
    safe.require(playback['passed'] and playback['mode']=='final-jar-only' and not playback['production_compiled'],'Worker proof must use final production')
    safe.require(playback['jars']['sfc']['sha256']==EXPECTED and audit['jars']['sfc']['sha256']==EXPECTED and audit['ok'],'Reports refer to another JAR')
    safe.require(all(json.loads(entries[n])['ok'] for n in ('client-identity.json','server-authority.json')),'Failed component tests')
    totals={name:0 for name in ('tests','failures','errors','skipped')}
    xml=list((PROJECT/'build/test-results/test').glob('TEST-*.xml'))
    safe.require(bool(xml),'No Gradle reports')
    for path in xml:
        suite=ET.parse(path).getroot()
        for name in totals:totals[name]+=int(suite.attrib[name])
    safe.require(totals=={'tests':210,'failures':0,'errors':0,'skipped':0},'Unexpected final full test totals')
    proof={'ok':True,'sfc_version':'0.1.0-alpha.8','final_jar':str(FINAL),'sha256':EXPECTED,'bytes':FINAL.stat().st_size,
           'verified_candidate_copied_byte_identically':True,'gradle_tests':totals,
           'gradle_xml_sha256':{p.name:digest(p.read_bytes()) for p in xml},
           'evidence_sha256':{name:digest(raw) for name,raw in entries.items() if name.endswith('.json')},
           'protocol':4,'minimum_fc':'0.31.0-alpha.20','same_sfc_version_required_on_clients_and_server':True,
           'models_changed':False,'installed':False,'live_minecraft_multiplayer_tested':False}
    entries[FINAL.name]=FINAL.read_bytes()
    entries['verification.json']=(json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    entries['SHA256.txt']=(''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(entries.items()))).encode('utf-8')
    output=safe.safe_path(DELIVERY/'SFC双人联机-alpha8-更新包-20260910.zip',existing=False)
    sidecar=safe.safe_path(FINAL.parent/'delivery-verification.json',existing=False)
    safe.require(not output.exists() and not sidecar.exists(),'Refusing to overwrite a release')
    with zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as bundle:
        for name,raw in sorted(entries.items()):bundle.writestr(name,raw)
    with zipfile.ZipFile(output) as bundle:
        safe.require(bundle.testzip() is None and len(bundle.namelist())==len(entries) and set(bundle.namelist())==set(entries),'ZIP CRC/member mismatch')
        for name,raw in entries.items():safe.require(bundle.read(name)==raw,'ZIP member mismatch: '+name)
    proof.update(zip=str(output),zip_sha256=digest(output.read_bytes()),zip_bytes=output.stat().st_size,zip_members=sorted(entries))
    with sidecar.open('x',encoding='utf-8') as stream:json.dump(proof,stream,ensure_ascii=False,indent=2)
    print(json.dumps({k:proof[k] for k in ('ok','sha256','zip','zip_sha256','zip_bytes','gradle_tests')},ensure_ascii=True))

if __name__=='__main__':main()
