"""Strict FC20 / home7 or explicitly selected home8 merge. Historical defaults stay frozen."""
import argparse,json,tomllib
from pathlib import Path
import merge_sfc_addon as old

VERSION='0.1.0-alpha.7'
FC_RANGE='[0.31.0-alpha.20,0.32.0)'

def combined_metadata(core,home,version=VERSION):
    old.require(version in ('0.1.0-alpha.7','0.1.0-alpha.8'),'Unsupported home release')
    c=old.parsed_metadata(core[old.META],'piq_sfc_arcade');h=old.parsed_metadata(home[old.META],'piq_sfc_home')
    old.require(c['mods'][0]['version']=='0.2.0-alpha.6' and h['mods'][0]['version']==version,'Expected frozen core6 plus selected home release')
    deps={d['modId']:d for d in h['dependencies']['piq_sfc_home']}
    old.require(len(deps)==len(h['dependencies']['piq_sfc_home']) and set(deps)=={'neoforge','minecraft','piq_fc_arcade','piq_sfc_arcade'},'Unexpected home7 dependency inventory')
    old.require(deps['piq_fc_arcade']['versionRange']==FC_RANGE and deps['piq_fc_arcade']['ordering']=='AFTER','Home7 must require FC20 AFTER')
    old.require(deps['piq_sfc_arcade']['versionRange']=='[0.2.0-alpha.6,0.3.0)','Bundled core dependency changed')
    old.require(all(d['type']=='required' and d['side']=='BOTH' for d in deps.values()),'Dependency authority weakened')
    def body(raw):
        text=raw.decode('utf-8').replace('\r\n','\n');old.require(text.count('[[mods]]')==1,'Ambiguous mod table')
        return text[text.index('[[mods]]'):].rstrip()+'\n'
    result=('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n\n'+body(core[old.META])+'\n'+body(home[old.META])).encode('utf-8')
    expected=dict(c);expected['mods']=c['mods']+h['mods'];expected['dependencies']=c['dependencies']|h['dependencies']
    old.require(tomllib.loads(result.decode('utf-8'))==expected,'Merge changed metadata semantics')
    return result

def plan(core_path,home_path,expected_home_sha,version=VERSION):
    core_sha,core=old.read_archive(core_path);home_sha,home=old.read_archive(home_path)
    old.require(core_sha==old.FROZEN_SHA,'Frozen SFC6 archive SHA mismatch')
    old.require(old.re.fullmatch('[0-9a-fA-F]{64}',expected_home_sha) is not None and home_sha==expected_home_sha.upper(),'Home archive SHA mismatch')
    old.ownership(core,'sfcarcade');old.ownership(home,'sfchome')
    old.require(set(core)&set(home)==old.SYNTHESIZED,'Unapproved shared archive entry')
    old.require(old.AT in core and old.AT not in home and old.WASM in core,'Missing/duplicate AT or WASM owner')
    old.require(core[old.AT]==b'public com.mojang.blaze3d.platform.NativeImage pixels\n','Frozen AT changed')
    entries={k:v for k,v in core.items()if k not in old.SYNTHESIZED}
    entries.update({k:v for k,v in home.items()if k not in old.SYNTHESIZED})
    entries[old.META]=combined_metadata(core,home,version)
    entries[old.MANIFEST]=('Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: '+version+'\r\nImplementation-Vendor: PIQ\r\n\r\n').encode('ascii')
    return old.Plan(entries,core,home,core_sha,home_sha,version)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--core',type=Path,default=old.FROZEN_CORE)
    parser.add_argument('--home',type=Path,required=True);parser.add_argument('--home-sha256',required=True)
    parser.add_argument('--home-version',choices=['0.1.0-alpha.7','0.1.0-alpha.8'],default=VERSION)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--check-only',action='store_true');parser.add_argument('--audit-only',action='store_true');args=parser.parse_args()
    inputs={old.safe_path(args.core,existing=True),old.safe_path(args.home,existing=True)}
    output=old.safe_path(args.output,existing=args.audit_only);report=old.safe_path(args.report,existing=False)
    old.require(output not in inputs and report not in inputs and report!=output,'Input/output/report must differ')
    old.require(not report.exists(),'Refusing to overwrite report')
    if not args.audit_only:old.require(not output.exists(),'Refusing to overwrite delivery')
    p=plan(args.core,args.home,args.home_sha256,args.home_version)
    if args.check_only:
        print(json.dumps({'ok':True,'check_only':True,'version':args.home_version,'fc_range':FC_RANGE,'home_sha256':p.home_sha,'core_sha256':p.core_sha,'entries':len(p.entries),'installed':False}));return
    result=old.verify(output,p)if args.audit_only else old.build(output,p)
    old.require(old.read_archive(args.core)[0]==p.core_sha and old.read_archive(args.home)[0]==p.home_sha,'Input changed during merge')
    result.update(schema='piq-sfc-alpha20-merged-1',fc_minimum='0.31.0-alpha.20')
    report.parent.mkdir(parents=True,exist_ok=True);old.safe_path(report.parent,existing=False)
    with report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in result.items()if k!='protected'},ensure_ascii=True))
if __name__=='__main__':main()
