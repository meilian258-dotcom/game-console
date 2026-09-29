"""Independent visual-only SFC12 audit against frozen spectator SFC11.

Only explicit QA probes are compiled. No production build, game, core, socket,
ROM, installation or alteration of frozen artifacts. New report only.
"""
from __future__ import annotations
import argparse,copy,json,os,sys,tempfile,tomllib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as compat
from verify_device_ui_alpha18 import disassemble,methods
from verify_user_models22 import comparable

BASE=ROOT/'piq-fc-arcade/build/review-watch23-v1'
BASELINE=BASE/'piq_sfc-0.1.0-alpha.11.jar'
BASE_SHA='ADF7172C0997983A61C0566AAEE4AE6F19C7D574D6D5B1971490EB69F4B92393'
FINAL=ROOT/'piq-fc-arcade/build/review-watch23-scale12-v1'
FC=FINAL/'piq_fc_arcade-0.31.0-alpha.23.jar'
FC_SHA='B7BF04BEEA5F58E8E9C0DFA23E1BD8F2CAF6B11455085D843F568B42D9021686'
NATIVE=FINAL/'piq_native_arcade-0.1.0-alpha.7.jar'
NATIVE_SHA='8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A'
PREFIX='cn/piq/sfchome/'
META=compat.META;MANIFEST=compat.MANIFEST
ASSETS={f'assets/piq_sfc_home/models/item/{name}.json'for name in('cartridge','console')}
ADDED={PREFIX+'layout/SfcConsoleScale'+suffix+'.class'for suffix in('','$Point','$Bounds')}
METHODS={
 'client/SfcHardwareMesh':(' reload(', ' lambda$reload$', ' binding('),
 'client/SfcHardwareRenderer':(' render(cn.piq.sfchome.world.SfcHomeConsoleBlockEntity,',),
 'client/SfcAvCableGeometry':(' console(', ' fanout(', ' multiPlug('),
 'client/SfcAvCableRenderer':(' bounds(',),
 'world/SfcHomeConsoleBlock':(' getShape(', ' box('),
}
CHANGED={PREFIX+n+'.class'for n in METHODS}
# These classes may only carry javac line/nest changes; every old instruction,
# field and method signature must still match. No watch/net/server exceptions.
NESTED={PREFIX+n+'.class'for n in('client/SfcHardwareMesh$Part','world/SfcHomeConsoleBlock$1','client/SfcAvCableRenderer$Key','client/SfcAvCableRenderer$Cached')}
NESTED|={PREFIX+'client/SfcAvCableGeometry$'+n+'.class'for n in('Vec','Box','Endpoint','Quad','Mesh','Rect')}

def require(ok,why):
    if not ok:raise AssertionError(why)

def metadata(old,new):
    before=tomllib.loads(old[META].decode('utf-8'));after=tomllib.loads(new[META].decode('utf-8'))
    mods=[m for m in before['mods']if m['modId']=='piq_sfc_home'];require(len(mods)==1 and mods[0]['version']=='0.1.0-alpha.11','Wrong baseline metadata');mods[0]['version']='0.1.0-alpha.12'
    require(before==after,'Only SFC home version may change; FC/core dependencies and mod IDs remain frozen')
    a=old[MANIFEST].decode('utf-8').replace('\r','');b=new[MANIFEST].decode('utf-8').replace('\r','');token='Implementation-Version: 0.1.0-alpha.11\n'
    require(a.count(token)==1 and a.replace(token,'Implementation-Version: 0.1.0-alpha.12\n')==b,'Manifest changed beyond version')

def asset_delta(name,old,new):
    a=json.loads(old);b=json.loads(new);old_gui=a['display'].pop('gui');gui=b['display'].pop('gui')
    require(a==b,'Non-GUI item model/transform altered '+name)
    if name.endswith('/cartridge.json'):
        wanted=copy.deepcopy(old_gui);require(wanted['scale']==[1.22]*3,'Unexpected old cartridge GUI');wanted['scale']=[2.4]*3
        require(gui==wanted,'Cartridge GUI differs beyond exact 1.22 -> 2.4 scale')
    else:
        require(set(gui)==set(old_gui)=={'rotation','translation','scale'} and gui['rotation']==old_gui['rotation']==[30,225,0],'Console GUI angle changed')
        require(gui['scale']==[.669155]*3 and gui['translation']==[.17252,3.57112,0],'Console GUI differs from approved exact fitted transform')
    return {'before':old_gui,'after':gui}

def classify(old,new):
    metadata(old,new)
    require(set(new)-set(old)==ADDED,'Unexpected added entry: '+repr((set(new)-set(old))^ADDED))
    require(not(set(old)-set(new)),'Deleted frozen entry')
    changed=[];nested=[];assets={};same=0
    for name,raw in old.items():
        if name in(META,MANIFEST):continue
        if raw==new[name]:same+=1;continue
        if name in ASSETS:assets[name]=asset_delta(name,raw,new[name])
        elif name in CHANGED:changed.append(name)
        elif name in NESTED:nested.append(name)
        else:raise AssertionError('Protected network/server/watch/playback/core/handheld/model difference: '+name)
    require(set(assets)==ASSETS,'Both approved GUI fixes must be present')
    for name in ADDED:require(new[name][:4]==b'\xca\xfe\xba\xbe','Invalid added scale class')
    return {'identical_entries':same,'changed_classes':sorted(changed),'metadata_only_pending':sorted(nested),'added_classes':sorted(ADDED),'changed_assets':assets,
            'old_network_server_watch_playback_preserved':True,'standalone_controller_preserved':True,'mesh_png_resources_preserved':True,'removed_entries':0}

def compare_methods(old,new,allowed=()):
    for signature,body in old.items():
        if any(token in signature for token in allowed):continue
        require(signature in new and comparable(body)==comparable(new[signature]),'Unauthorized method instruction change: '+signature)
    require(all(any(token in signature for token in allowed)for signature in set(new)-set(old)),'Unauthorized added method')
    return len([s for s in old if not any(t in s for t in allowed)])

def bytecode(oldjar,newjar,scope):
    result={}
    for entry in scope['changed_classes']+scope['metadata_only_pending']:
        name=entry[:-6].replace('/','.');relative=entry[len(PREFIX):-6];allowed=METHODS.get(relative,())
        old=methods(disassemble(oldjar,compat.JAVA/'javap.exe',name));new=methods(disassemble(newjar,compat.JAVA/'javap.exe',name))
        protected=compare_methods(old,new,allowed)
        def fields(jar):
            out=compat.run([compat.JAVA/'javap.exe','-p','-s','-classpath',jar,name],ROOT)
            return [line.strip()for line in out.splitlines()if line.strip().endswith(';')and '('not in line]
        require(fields(oldjar)==fields(newjar),'Existing fields changed '+name)
        result[name]={'protected_old_methods':protected,'visual_method_exceptions':list(allowed)}
    return result

def negative_controls(old,new):
    result=[]
    for label,name,operation in [('watch',PREFIX+'client/SfcWatchPublisher.class','change'),('wire',PREFIX+'net/SfcHomeNetwork.class','change'),('server',PREFIX+'server/SfcHomeServer.class','change'),('playback',PREFIX+'client/SfcPlayback.class','change'),('handheld',PREFIX+'client/SfcControllerPose.class','change'),('mesh','assets/piq_sfc_home/meshes/sfc_hardware.json','change'),('rom','unexpected.sfc','add'),('deleted_id',PREFIX+'registry/SfcHomeRegistries.class','delete')]:
        altered=dict(new)
        if operation=='delete':altered.pop(name)
        else:altered[name]=b'Negative fixture only'
        try:classify(old,altered)
        except(AssertionError,ValueError):result.append(label)
        else:raise AssertionError('Negative accepted '+label)
    return result

def geometry(jar,fc):
    tool=ROOT/'piq-sfc-home/tools/qa/SfcScale12FinalProbe.java'
    cache=compat.CACHE;deps=[]
    for group,version in [('com.google.code.gson','2.10.1'),('org.joml','1.10.5')]:
        deps.extend(p for p in(cache/group).rglob('*.jar')if version in p.parts and '-sources'not in p.name and '-javadoc'not in p.name)
    with tempfile.TemporaryDirectory(prefix='piq-sfc-scale12-geometry-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();staged=tmp/'sfc.jar';staged.write_bytes(jar.read_bytes())
        cp=os.pathsep.join(map(str,[classes,staged,*deps]));compat.run([compat.JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,tool],tmp)
        require(all(p.name.startswith('SfcScale12FinalProbe')for p in classes.rglob('*.class')),'Production compiled by geometry probe')
        report=compat.parse_last_json(compat.run([compat.JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.SfcScale12FinalProbe',staged],tmp))
        require(report.get('ok')and report.get('production_origin')=='final-jar-only','Geometry did not use final classes')
        require(compat.digest(staged.read_bytes())==compat.digest(jar.read_bytes()),'Staged SFC changed')
        report['final_jar_only']=True;return report

def audit(sfc):
    oldhash,old=compat.archive(BASELINE);require(oldhash==BASE_SHA,'Frozen SFC11 changed');newhash,new=compat.archive(sfc)
    require(compat.digest(FC.read_bytes())==FC_SHA and compat.digest(NATIVE.read_bytes())==NATIVE_SHA,'Retained FC23/Native7 changed')
    scope=classify(old,new);negative=negative_controls(old,new)
    corepath,corehash=compat.BASELINES['core'];actual,core=compat.archive(corepath);require(actual==corehash,'Frozen old SFC6 changed');protected=[n for n in core if n not in(META,MANIFEST)]
    require(all(new.get(n)==core[n]for n in protected),'Old SFC core/runtime/AT altered')
    with tempfile.TemporaryDirectory(prefix='piq-sfc-scale12-bytecode-')as folder:
        tmp=Path(folder);oldjar=tmp/'old.jar';newjar=tmp/'new.jar';oldjar.write_bytes(BASELINE.read_bytes());newjar.write_bytes(sfc.read_bytes());method_report=bytecode(oldjar,newjar,scope)
    actual_geometry=geometry(sfc,FC);fml=compat.java_probes({'fc':FC,'sfc':sfc,'native':NATIVE})
    require(compat.digest(sfc.read_bytes())==newhash and compat.digest(BASELINE.read_bytes())==BASE_SHA,'Candidate or baseline changed during QA')
    return {'ok':True,'schema':'piq-sfc-scale12-independent-1','visual_only':True,'jars':{'sfc':{'path':str(sfc),'sha256':newhash},'fc':{'path':str(FC),'sha256':FC_SHA}},
            'baseline_sfc11':{'path':str(BASELINE),'sha256':BASE_SHA},'strict_scope':scope,'old_methods':method_report,'frozen_sfc6_entries_preserved':len(protected),
            'negative_controls':negative,'geometry':actual_geometry,'real_fml_and_outer_codec':fml,'production_compiled':False,'minecraft_or_native_core_started':False,'network_socket_opened':False,'installed':False,
            'limits':['No live Minecraft render, input, networking or audio-device test.','Pure final classes verify geometry and item projection; real FML/outer codecs do not constitute full game startup.']}

def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--sfc',type=Path,required=True);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    require(not args.report.exists(),'Refusing to replace a report');jar=args.sfc.resolve(strict=True);require(jar!=BASELINE.resolve(),'Candidate must not overwrite frozen SFC11')
    result=audit(jar);args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as output:json.dump(result,output,ensure_ascii=False,indent=2);output.write('\n')
    print(json.dumps({'ok':True,'report':str(args.report),'sha256':compat.digest(args.report.read_bytes()),'sfc_sha256':result['jars']['sfc']['sha256']},ensure_ascii=False))
if __name__=='__main__':main()
