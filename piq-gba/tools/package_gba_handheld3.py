"""Package only the new GBA addon after final-JAR checks; no install or server publication."""
import argparse,hashlib,json,zipfile
from pathlib import Path
from build_gba_handheld3 import ROOT,FC_SHA,RUNTIME,VERSION,sources
from build_gba_preview import jar
from check_gba_core import digest

def read(path):
    value=json.loads(path.read_text(encoding='utf-8'))
    assert value.get('ok') is True,path
    return value

def main():
    parser=argparse.ArgumentParser()
    for key in ('bundle','native','client','visual','output'):parser.add_argument('--'+key,type=Path,required=True)
    args=parser.parse_args();bundle=args.bundle.resolve(strict=True);output=args.output.resolve()
    allowed=(ROOT.parent/'制作Mod/03-街机模拟').resolve()
    assert output.parent==allowed and output.suffix=='.zip' and not output.exists(),'Unique local delivery required'
    witness=read(bundle/'build-witness.json');native=read(args.native);client=read(args.client);visual=read(args.visual)
    mod=bundle/f'piq_gba-{VERSION}.jar';gba_sha=digest(mod)
    assert witness['jars']['gba']['sha256']==gba_sha
    assert witness['jars']['fc']['sha256']==FC_SHA and digest(Path(witness['jars']['fc']['path']))==FC_SHA
    assert witness['source_sha256']==sources(),'Production/tool/source documentation drift after build'
    assert witness['old_bridge_byte_identical'] and not witness['helper_compiled']
    assert native['jars']['gba']['sha256']==gba_sha and native['jars']['fc']['sha256']==FC_SHA
    assert client['jars']['gba']['sha256']==gba_sha and client['jars']['fc']['sha256']==FC_SHA
    assert visual['jar']['sha256']==gba_sha
    for result in (native,client,visual):
        assert result['mode']=='final-jar-only' and result['production_compiled'] is False
    for name,pin in RUNTIME.items():
        assert digest(bundle/'piq-gba/runtime'/name)==native['runtime'][name]['sha256']==pin
    with zipfile.ZipFile(bundle/'licenses-and-source/piq-gba-source.zip')as source:
        assert set(source.namelist())==set(witness['source_sha256'])
        for name,pin in witness['source_sha256'].items():
            assert hashlib.sha256(source.read(name)).hexdigest().upper()==pin
    entries={'mods/'+mod.name:mod.read_bytes(),'使用说明.md':(bundle/'使用说明.md').read_bytes()}
    for name in RUNTIME:entries['piq-gba/runtime/'+name]=(bundle/'piq-gba/runtime'/name).read_bytes()
    for path in (bundle/'licenses-and-source').iterdir():
        assert path.is_file();entries['licenses-and-source/'+path.name]=path.read_bytes()
    for name,path in [('build-witness',bundle/'build-witness.json'),('native-common-final',args.native),('client-final',args.client),('model-final',args.visual)]:
        entries['checks/'+name+'.json']=path.read_bytes()
    entries['预览-离线布局非游戏截图.png']=Path(visual['preview']).read_bytes()
    assert not any(name.lower().endswith(('.gba','.sav','.srm'))for name in entries)
    hashes={name:{'sha256':hashlib.sha256(data).hexdigest().upper(),'bytes':len(data)}for name,data in entries.items()}
    entries['SHA256.json']=(json.dumps(hashes,ensure_ascii=False,indent=2)+'\n').encode()
    output.parent.mkdir(parents=True,exist_ok=True);jar(output,entries)
    with zipfile.ZipFile(output)as final:
        assert len(final.namelist())==len(set(final.namelist()))==len(entries) and final.testzip()is None
        for name,data in entries.items():assert final.read(name)==data
    assert sources()==witness['source_sha256'] and digest(mod)==gba_sha
    receipt={'ok':True,'package':str(output),'sha256':digest(output),'bytes':output.stat().st_size,'files':len(entries),
             'gba_sha256':gba_sha,'required_fc_sha256':FC_SHA,'fc_included':False,'runtime_unchanged':True,
             'installed':False,'published':False,'minecraft_started':False,'entry_hashes':hashes}
    with output.with_suffix('.verification.json').open('x',encoding='utf-8')as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in receipt.items()if k!='entry_hashes'},ensure_ascii=False))
if __name__=='__main__':main()
