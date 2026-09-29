"""Final JAR audit for SFC8; FC20, Native6, SFC6 core and all art stay frozen."""
import argparse,copy,json,os,sys,tempfile,tomllib
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as audit

DELIVERY=ROOT/'制作Mod/03-街机模拟'
BASE=DELIVERY/'PIQ-SFC家用/0.1.0-alpha.7/piq_sfc-0.1.0-alpha.7.jar'
FC=DELIVERY/'PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar'
NATIVE=DELIVERY/'PIQ原生街机/0.1.0-alpha.6/piq_native_arcade-0.1.0-alpha.6.jar'
HASHES={'baseline':'D3EFB376B3AE7C2AE2CE4F2D3A08298EC5447F3F79400216F1AE9A1181BB54D0',
        'fc':'CAC47CAE12E7183A76CF8874C6E8B8C4242435C01759C797EE78A5BD8A80C864',
        'native':'B503F5BE9F0C1DAA3640CE1926CCAA268577A76FE709CEFBFA05D9FFEEF5E422'}
from check_confirmation_screens import methods
ALLOWED={
    'client/SfcHomeClient','client/SfcHomeClient$Setup','client/SfcHomeClient$SessionOrder','client/SfcHomeClient$SessionOrder$Assignment',
    'client/SfcJoinClient','client/SfcJoinClient$TransferGuard','client/SfcJoinScreen','client/SfcJoinScreen$Consent',
    'client/SfcPlayback','client/SfcPlayback$Picture','client/SfcPlayback$Restore','client/SfcPlayback$Host','client/SfcPlayback$Audio',
    'client/SfcPlayback$MinecraftHost','client/SfcPlayback$MinecraftHost$1',
    'server/SfcHomeServer','server/SfcHomeServer$1','server/SfcHomeServer$State','server/SfcHomeServer$Lease',
    'server/SfcHomeServer$Session','server/SfcHomeServer$Joining','server/SfcControllerAuthority',
    'net/SfcHomeNetwork','net/SfcHomeNetwork$ClientHandler','net/SfcHomeNetwork$CoverChunk','net/SfcHomeNetwork$CoverRequest',
    'net/SfcHomeNetwork$Editor','net/SfcHomeNetwork$EditorAction','net/SfcHomeNetwork$Frames','net/SfcHomeNetwork$Input',
    'net/SfcHomeNetwork$Leave','net/SfcHomeNetwork$Ready','net/SfcHomeNetwork$RomChunk','net/SfcHomeNetwork$RomEntry',
    'net/SfcHomeNetwork$RomRequest','net/SfcHomeNetwork$Session','net/SfcHomeNetwork$Stopped',
    'net/SfcJoinNetwork','net/SfcJoinNetwork$Client','net/SfcJoinNetwork$Allow','net/SfcJoinNetwork$Applied',
    'net/SfcJoinNetwork$Approval','net/SfcJoinNetwork$Capture','net/SfcJoinNetwork$ControllerInput',
    'net/SfcJoinNetwork$ControllerReady','net/SfcJoinNetwork$ControllerLeave','net/SfcJoinNetwork$Decision',
    'net/SfcJoinNetwork$Offer','net/SfcJoinNetwork$Result','net/SfcJoinNetwork$State','net/SfcJoinNetwork$Upload'}

def permitted(name):
    if name in (audit.META,audit.MANIFEST):return True
    return name.startswith('cn/piq/sfchome/') and name.endswith('.class') and name.removeprefix('cn/piq/sfchome/').removesuffix('.class') in ALLOWED

def verify_delta(before,after):
    audit.require(not set(before)-set(after),'Old JAR entries removed')
    changes=[n for n in after if n not in before or before[n]!=after[n]]
    audit.require(all(permitted(n) for n in changes),'Out-of-scope changes: '+str([n for n in changes if not permitted(n)]))
    assets={n:audit.digest(v) for n,v in before.items() if n.startswith('assets/')}
    audit.require({n:audit.digest(v) for n,v in after.items() if n.startswith('assets/')}==assets,'Artwork/core resources changed')
    return {'changed_entries':sorted(changes),'unchanged_entries':len(after)-len(changes),
            'all_asset_entries_unchanged':len(assets),'asset_sha256':assets}


def current_client_probes(path,before,after):
    """Compile only the existing probe; every production class is loaded from the candidate."""
    with tempfile.TemporaryDirectory(prefix='sfc8-final-client-') as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        candidate=tmp/'sfc.jar';candidate.write_bytes(path.read_bytes());baseline=tmp/'baseline.jar';baseline.write_bytes(BASE.read_bytes())
        fc=tmp/'fc.jar';fc.write_bytes(FC.read_bytes())
        audit.require(audit.digest(candidate.read_bytes())==audit.digest(path.read_bytes()),'Probe copy mismatch')
        resources=audit.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,audit.MC,resources,fc,candidate,*audit.dependencies()]));args=tmp/'cp.args'
        args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        probe=ROOT/'piq-sfc-home/tools/qa/SfcClientIngressProbe.java'
        audit.run([audit.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,probe],tmp)
        result=audit.parse_last_json(audit.run([audit.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(args),'cn.piq.sfchome.net.SfcClientIngressProbe'],tmp))
        audit.require(result.get('ok') and result.get('assertions')==27,'Real final-JAR ingress/lease codec probe failed')
        def bytecode(jar,name):
            return audit.run([audit.JAVA/'javap.exe','-J-Dfile.encoding=UTF-8','-J-Dstdout.encoding=UTF-8','-J-Dstderr.encoding=UTF-8','-p','-c','-classpath',jar,name],tmp)
        records=[n[:-6].replace('/','.') for n in before if n.startswith(('cn/piq/sfchome/net/SfcHomeNetwork$','cn/piq/sfchome/net/SfcJoinNetwork$')) and n.endswith('.class') and not n.endswith(('$Client.class','$ClientHandler.class'))]
        for name in records:audit.require(methods(bytecode(baseline,name))==methods(bytecode(candidate,name)),'Old network record semantics changed: '+name)
        audit.require(len(records)==23,'Unexpected baseline record inventory')
        networks={name:bytecode(candidate,'cn.piq.sfchome.net.'+name) for name in ('SfcHomeNetwork','SfcJoinNetwork')}
        for name,code in networks.items():
            register=[body for signature,body in methods(code).items() if ' register(' in signature]
            audit.require(len(register)==1 and '// String 4' in register[0] and 'registrar:' in register[0],name+' not protocol 4')
            audit.require('IPayloadContext.connection:' in code and 'IPayloadContext.enqueueWork:' in code and 'acceptsConnection:' in code,name+' lacks source-connection queue fence')
        home_register=next(body for signature,body in methods(networks['SfcHomeNetwork']).items() if ' register(' in signature)
        for old in ('Ready','Leave'):audit.require('SfcHomeNetwork$'+old+'.TYPE:' not in home_register,'Unbound '+old+' still registered')
        for new in ('ControllerReady','ControllerLeave'):audit.require('SfcJoinNetwork$'+new+'.TYPE:' in networks['SfcJoinNetwork'],'Lease wrapper not registered: '+new)
        for call in ('SfcHomeServer.controllerReady:','SfcHomeServer.controllerLeave:'):audit.require(call in networks['SfcJoinNetwork'],'Missing guarded server dispatch: '+call)
        for name,data in after.items():
            if name.startswith(('cn/piq/sfchome/net/','cn/piq/sfchome/server/')) and name.endswith('.class'):
                audit.require(b'net/minecraft/client/' not in data and b'cn/piq/sfchome/client/' not in data,'Common class links client: '+name)
        audit.require(audit.digest(candidate.read_bytes())==audit.digest(path.read_bytes()),'Candidate changed during client probe')
        return {'final_jar_only':True,'production_compiled':False,'ingress_and_lease_codec':result,
                'preserved_record_bytecode':sorted(records),'real_registrar_protocol':4,'old_unbound_ready_leave_registered':False,'common_client_links':0}


def negative_scope_checks(before,after):
    assets=next(n for n in before if n.startswith('assets/'))
    cases={'old_asset_changed':{**after,assets:after[assets]+b'corrupt'},
           'unlisted_nested_class':{**after,'cn/piq/sfchome/client/SfcPlayback$Unexpected.class':b'bad'},
           'rom_injection':{**after,'roms/game.sfc':b'bad'},
           'old_entry_removed':{n:v for n,v in after.items() if n!=assets}}
    for label,mutant in cases.items():
        try:verify_delta(before,mutant)
        except ValueError:continue
        raise ValueError('Whitelist accepted negative case: '+label)
    return list(cases)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sfc',type=Path,required=True);parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--sfc-sha256',default='3C678DC03EF9479564BC3F7005210E9DE868792A8D29DD0CDD62CAB3C79BC1E5')
    args=parser.parse_args();audit.require(not args.report.exists(),'Refusing to replace an old report')
    paths={'fc':FC,'sfc':args.sfc.resolve(strict=True),'native':NATIVE}
    hashes={};jars={}
    for key,path in paths.items():hashes[key],jars[key]=audit.archive(path)
    audit.require(hashes['sfc']==args.sfc_sha256.upper(),'Candidate SHA mismatch')
    baseline_sha,before=audit.archive(BASE)
    audit.require(baseline_sha==HASHES['baseline'],'Baseline modified')
    for key in ('fc','native'):audit.require(hashes[key]==HASHES[key],key+' modified')
    delta=verify_delta(before,jars['sfc'])
    metadata=tomllib.loads(jars['sfc'][audit.META].decode())
    expected=copy.deepcopy(tomllib.loads(before[audit.META].decode()))
    for mod in expected['mods']:
        if mod['modId']=='piq_sfc_home':mod['version']='0.1.0-alpha.8'
    audit.require(metadata==expected,'Metadata changed beyond home version 7 to 8')
    audit.require({m['modId']:m['version'] for m in metadata['mods']}=={'piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.8'},'Wrong SFC versions')
    deps=metadata['dependencies']['piq_sfc_home']
    audit.require(any(d['modId']=='piq_fc_arcade' and d['versionRange']=='[0.31.0-alpha.20,0.32.0)' and d['type']=='required' and d['side']=='BOTH' for d in deps),'FC20 dependency missing')
    owners=audit.unique_ownership(jars)
    negative_checks=negative_scope_checks(before,jars['sfc'])
    probes=audit.java_probes(paths)
    probes['sfc8_client_and_protocol']=current_client_probes(paths['sfc'],before,jars['sfc'])
    audit.require(all(audit.archive(path)[0]==hashes[key] for key,path in paths.items()),'JAR changed during audit')
    report={'ok':True,'schema':'piq-sfc-multiplayer8-audit-1','jars':{key:{'path':str(path),'sha256':hashes[key]} for key,path in paths.items()},
            'delta':delta,'owners':owners,'probes':probes,'negative_scope_checks':negative_checks,'protocol':'SFC home/join 4: both ends must update',
            'scope':'SFC home multiplayer; no cabinet multiplayer or model changes',
            'limits':['FML discovery is not full game startup.','No live Minecraft server/clients or physical controller exercised by this audit.'],
            'installed':False}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'sfc_sha256':hashes['sfc'],'changed_entries':len(delta['changed_entries']),'report':str(args.report)},ensure_ascii=True))

if __name__=='__main__':main()
