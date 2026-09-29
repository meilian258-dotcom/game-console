"""Independent FC22/SFC10 model-only delta audit against frozen FC21/SFC9.

No production compilation, mod entry points, emulator, sockets or installation.
"""
from __future__ import annotations
import argparse,importlib.util,json,os,re,sys,tempfile,tomllib
from pathlib import Path
import verify_retro_alpha19 as compat
from verify_device_ui_alpha18 import disassemble,methods
from check_fc_cartridge_workbench import instruction_positions
from import_user_dual_model import derive

ROOT=compat.ROOT;TOOLS=Path(__file__).resolve().parent;JAVA=compat.JAVA
META=compat.META;MANIFEST=compat.MANIFEST
BASE=ROOT/'piq-fc-arcade/build/review-linked21-v1'
BASELINES={
 'fc':(BASE/'piq_fc_arcade-0.31.0-alpha.21.jar','0147D49C542E82DDF2DD37CDAFFBDB135CDA20E11907B4060B471A2FBC0D3F93'),
 'sfc':(BASE/'piq_sfc-0.1.0-alpha.9.jar','F951146515D7F35257585A9DA7536453F1DFFA70285F51EDD612D65C771DF951'),
 'native':(BASE/'piq_native_arcade-0.1.0-alpha.7.jar','8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A')}
def require(ok,why):
    if not ok:raise AssertionError(why)
def classes(prefix,names):return {prefix+n+'.class'for n in names.split()}
FC='cn/piq/fcarcade/';SFC='cn/piq/sfchome/'
CHANGED={
 'fc':classes(FC,'''client/DualCabinetRenderer client/DualCabinetRenderer$1 client/DualCabinetRenderer$ItemRenderer client/DualCabinetRenderer$SkinUv client/DualCabinetRenderer$Cached client/DualCabinetRenderer$Face layout/DualCabinetGeometry layout/CabinetVideoGeometry world/DualCabinetFootprint client/ClientArcadeEvents client/cabinet/CabinetClientBackends client/cabinet/CabinetPeerInputs client/cabinet/CabinetMenuScreen client/RomLibraryScreen client/SkinLibraryScreen'''),
 'sfc':classes(SFC,'''client/SfcAvCableGeometry client/SfcHardwareMeshData client/SfcHardwareMeshData$Part client/SfcHardwareMesh client/SfcHardwareMesh$Part client/SfcControllerPose client/SfcHardwareItems$HardwareItemRenderer client/SfcCoverGeometry client/SfcHomeClient'''),
 'native':set()}
ADDED={'fc':classes(FC,'layout/DualCabinetControls layout/DualCabinetControls$Part layout/DualCabinetControls$Motion'),'sfc':classes(SFC,'client/SfcButtonAnimation client/SfcButtonAnimation$Binding client/SfcButtonAnimation$Transform'),'native':set()}
# Nested pure records may only change debug/nest metadata, never signatures or instructions.
NESTED={'fc':classes(FC,'layout/CabinetVideoGeometry$Frame layout/CabinetVideoGeometry$Uv layout/CabinetVideoGeometry$Vertex world/DualCabinetFootprint$Cell world/DualCabinetFootprint$Bounds world/DualCabinetFootprint$Facing'),'sfc':classes(SFC,'client/SfcCoverGeometry$Face client/SfcAvCableGeometry$Vec client/SfcAvCableGeometry$Box client/SfcAvCableGeometry$Endpoint client/SfcAvCableGeometry$Quad client/SfcAvCableGeometry$Mesh client/SfcAvCableGeometry$Rect client/SfcHardwareItems client/SfcHardwareItems$ItemModel'),'native':set()}
NESTED['fc']|=classes(FC,'client/RomLibraryScreen$Entry client/RomLibraryScreen$EntryBuilder client/SkinLibraryScreen$Entry')
NESTED['sfc']|=classes(SFC,'client/SfcHomeClient$SessionOrder client/SfcHomeClient$SessionOrder$Assignment')
INLINE={
 'cn.piq.fcarcade.layout.ArcadeOccupancyLabelLayout':(' labelY(', 'ldc #CP // float 2.35f','fconst_2 '),
 'cn.piq.fcarcade.server.ArcadeOccupancyDisplay':(' updateLeaderboard(', 'ldc #CP // float 0.225f','ldc #CP // float 0.16f')}
CHANGED['fc']|={name.replace('.','/')+'.class'for name in INLINE}
GETTERS={
 'fc':{'cn.piq.fcarcade.client.ClientArcadeEvents':{'public static int[] cabinetVisualInputs(net.minecraft.core.BlockPos);'},
       'cn.piq.fcarcade.client.cabinet.CabinetClientBackends':{'public static int[] visualInputs(cn.piq.fcarcade.cabinet.CabinetTarget);'},
       'cn.piq.fcarcade.client.cabinet.CabinetPeerInputs':{'public int[] visualPair(int, int, int, boolean);'}},
 'sfc':{'cn.piq.sfchome.client.SfcHomeClient':{'static int visualInputMask();'}}}
UI={
 'cn.piq.fcarcade.client.cabinet.CabinetMenuScreen':(' init(', ' openAppearance(', ' render('),
 'cn.piq.fcarcade.client.RomLibraryScreen':(' init(',),
 'cn.piq.fcarcade.client.SkinLibraryScreen':(' init(', ' render(', ' choose(', ' dedicatedAppearance(')}
INCOMING_REPORT=ROOT/'piq-fc-arcade/design/user-models-20260911/independent-incoming-audit.json'
INCOMING_REPORT_SHA='48AFDE6840F19B0A23399318AB6E8C7EF4E188E7FAC1DC3233557B38B2E04D71'
SFC_ASSET_SHA={'assets/piq_sfc_home/meshes/sfc_hardware.json':'4B944A5A98CF926A38092BCDF3B8FA5E8D1B07631B116E554E7DD551B4D80E64',
 'assets/piq_sfc_home/textures/block/user_sfc_20260911.png':'4BBBA0F53697D69A919F5FC12750E608AA50A4D71D8281F23D6428A5D02BD920',
 'assets/piq_sfc_home/textures/block/user_sfc_cartridge_20260911.png':'7608F10AD4205635C321AA423F348CA68515B16FDDC7BDF9F111811121D4892A',
 'assets/piq_sfc_home/models/item/console.json':'466510B9D2073126A07593BB9DB5A4083A0EBDDBF6CFBDC2D73579D37E00F33D'}

def expected_assets():
    require(compat.digest(INCOMING_REPORT.read_bytes())==INCOMING_REPORT_SHA,'Independent input audit changed')
    original=json.loads(INCOMING_REPORT.read_text(encoding='utf-8'));source=INCOMING_REPORT.parent/'source'
    require({p.relative_to(source).as_posix()for p in source.rglob('*')if p.is_file()}==set(original['file_sha256']),'Incoming inventory changed')
    require(all(compat.digest((source/n).read_bytes())==h for n,h in original['file_sha256'].items()),'Incoming original content changed')
    files,_=derive();fc={p.relative_to(ROOT/'piq-fc-arcade/src/main/resources').as_posix():b for p,b in files.items()}
    spec=importlib.util.spec_from_file_location('user_sfc_import',ROOT/'piq-sfc-home/tools/import_user_sfc_20260911.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    doc,_=m.build();sfc={'assets/piq_sfc_home/meshes/sfc_hardware.json':json.dumps(doc,ensure_ascii=False,separators=(',',':')).encode('utf-8')}
    for resource,path in m.TEXTURES.values():sfc['assets/piq_sfc_home/textures/'+resource.split(':',1)[1]+'.png']=path.read_bytes()
    sfc['assets/piq_sfc_home/models/item/console.json']=m.console_item_bytes()
    require({n:compat.digest(b)for n,b in sfc.items()}==SFC_ASSET_SHA,'SFC assets not reviewed frozen geometry/PNG')
    return {'fc':fc,'sfc':sfc,'native':{}}

def metadata(kind,old,new):
    before=tomllib.loads(old[META].decode('utf-8'));after=tomllib.loads(new[META].decode('utf-8'))
    if kind=='native':require(before==after and old[MANIFEST]==new[MANIFEST],'Native metadata changed');return
    oldv,newv,mod=('0.31.0-alpha.21','0.31.0-alpha.22','piq_fc_arcade')if kind=='fc'else('0.1.0-alpha.9','0.1.0-alpha.10','piq_sfc_home')
    mods=[m for m in before['mods']if m['modId']==mod];require(len(mods)==1 and mods[0]['version']==oldv,'Baseline metadata mismatch');mods[0]['version']=newv
    if kind=='sfc':
        dep=[d for d in before['dependencies'][mod]if d['modId']=='piq_fc_arcade'];require(len(dep)==1 and dep[0]['versionRange']=='[0.31.0-alpha.21,0.32.0)','Old dependency mismatch');dep[0]['versionRange']='[0.31.0-alpha.22,0.32.0)'
    require(before==after,'Unexpected mod metadata change '+kind)
    a=old[MANIFEST].decode('utf-8').replace('\r','');b=new[MANIFEST].decode('utf-8').replace('\r','');token='Implementation-Version: '+oldv+'\n'
    require(a.count(token)==1 and a.replace(token,'Implementation-Version: '+newv+'\n')==b,'Manifest differs beyond version '+kind)

def classify(kind,old,new,assets):
    metadata(kind,old,new);expected_added=ADDED[kind]|(set(assets)-set(old))
    if kind=='sfc':
        name='assets/piq_sfc_home/models/item/console.json';a=json.loads(old[name]);b=json.loads(new[name]);display=b['display'].pop('gui')
        require('gui'not in a['display']and a==b and display=={'rotation':[30,225,0],'translation':[-.625,5.125,0],'scale':[.81,.81,.81]},'Console item changed beyond approved GUI transform')
    require(set(new)-set(old)==expected_added,'Unexpected additions '+kind+': '+repr((set(new)-set(old))^expected_added))
    require(not(set(old)-set(new)),'Unexpected removals '+kind)
    same=[];changed=[];nested=[]
    for name,raw in old.items():
        if name in (META,MANIFEST):continue
        if raw==new[name]:same.append(name);continue
        if name in assets:require(new[name]==assets[name],'Model asset does not exactly derive from original '+name)
        elif name in CHANGED[kind]:changed.append(name)
        elif name in NESTED[kind]:nested.append(name)
        else:raise AssertionError('Unauthorized network/server/core/old asset difference '+kind+'/'+name)
    for name,raw in assets.items():require(new.get(name)==raw,'Missing or changed original-derived asset '+kind+'/'+name)
    for name in ADDED[kind]:require(new[name][:4]==b'\xca\xfe\xba\xbe','Invalid added class '+name)
    return {'identical_entries':len(same),'identical_classes':sum(n.endswith('.class')for n in same),'changed_classes':changed,'nested_metadata_only_pending_check':nested,'added_classes':sorted(ADDED[kind]),'model_assets':{n:compat.digest(v)for n,v in assets.items()},'removed_entries':0}

def comparable(body):
    # Stable instruction identities permit ldc->ldc_w but no operand/branch changes.
    offsets={int(m[1]):i for i,m in enumerate(re.finditer(r'^\s*(\d+):\s+[a-z][a-z0-9_]*\b',body,re.M))}
    def switch(match):
        target=int(match[3]);require(target in offsets,'Switch target is not an instruction')
        return match[1]+match[2]+': @'+str(offsets[target])
    body=re.sub(r'^(\s*)(-?\d+|default):\s+(\d+)\s*$',switch,body,flags=re.M)
    return instruction_positions(body)
def method_comparison(before,after,added=(),allowed_ui=()):
    protected=[]
    for signature,body in before.items():
        if any(token in signature for token in allowed_ui):continue
        require(signature in after and comparable(body)==comparable(after[signature]),'Protected method changed: '+signature);protected.append(signature)
    new=set(after)-set(before)
    require(all(s in added or any(t in s for t in allowed_ui)for s in new),'Unauthorized new methods '+repr(new-set(added)))
    require(set(added)<=new,'Required additive read-only getter missing')
    for signature in added:
        body=after[signature]
        require(not re.search(r'\bput(?:field|static)\b',body),'Getter writes shared state '+signature)
        require(not re.search(r'// (?:InterfaceMethod|Method) .*?(?:Network\.|\.send|\.mix:|\.offerInput|\.clearInput|\.release|\.pause|\.seat:)',body),'Getter invokes stateful I/O '+signature)
    return {'protected_methods':protected,'new_methods':sorted(new)}

def method_audit(paths,baselines,categories):
    result={}
    def api(path,name,exclude=()):
        raw=compat.run([JAVA/'javap.exe','-J-Dfile.encoding=UTF-8','-p','-s','-classpath',path,name],ROOT)
        lines=raw.splitlines()[1:];kept=[];skip=False
        for line in lines:
            if line.strip()in exclude:skip=True;continue
            if skip and line.strip().startswith('descriptor:'):skip=False;continue
            if line.strip():kept.append(line.strip())
        return kept
    for kind,entries in GETTERS.items():
        for name,added in entries.items():
            require(api(baselines[kind],name)==api(paths[kind],name,added),'Getter class fields/old signatures changed '+name)
            result[name]=method_comparison(methods(disassemble(baselines[kind],JAVA/'javap.exe',name)),methods(disassemble(paths[kind],JAVA/'javap.exe',name)),added)
    for name,allowed in UI.items():result[name]=method_comparison(methods(disassemble(baselines['fc'],JAVA/'javap.exe',name)),methods(disassemble(paths['fc'],JAVA/'javap.exe',name)),allowed_ui=allowed)
    for name,(target,old_operand,new_operand)in INLINE.items():
        require(api(baselines['fc'],name)==api(paths['fc'],name),'Label class API changed '+name)
        before=methods(disassemble(baselines['fc'],JAVA/'javap.exe',name));after=methods(disassemble(paths['fc'],JAVA/'javap.exe',name));require(set(before)==set(after),'Label methods changed '+name)
        count=0
        for signature,body in before.items():
            expected=comparable(body)
            if target in signature:
                require(expected.count(old_operand)==1,'Ambiguous label constant '+name);expected=expected.replace(old_operand,new_operand);count+=1
            require(expected==comparable(after[signature]),'Label class changed beyond one approved constant '+name+' '+signature)
        require(count==1,'Expected exactly one label method')
        result[name]={'all_old_methods_protected':len(before),'exact_single_operand_change':{'method':target,'before':old_operand,'after':new_operand}}
    name='cn.piq.fcarcade.world.DualCabinetFootprint'
    result[name]=method_comparison(methods(disassemble(baselines['fc'],JAVA/'javap.exe',name)),methods(disassemble(paths['fc'],JAVA/'javap.exe',name)),allowed_ui=(' bounds(', ' clipped('))
    for kind,category in categories.items():
        for entry in category['nested_metadata_only_pending_check']:
            name=entry[:-6].replace('/','.')
            require(api(baselines[kind],name)==api(paths[kind],name),'Nested fields/old signatures changed '+name)
            result[name]=method_comparison(methods(disassemble(baselines[kind],JAVA/'javap.exe',name)),methods(disassemble(paths[kind],JAVA/'javap.exe',name)))
    return result

def geometry_probes(paths,entries):
    with tempfile.TemporaryDirectory(prefix='piq-model22-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();fc=tmp/'fc.jar';fc.write_bytes(paths['fc'].read_bytes())
        require(compat.digest(fc.read_bytes())==compat.digest(paths['fc'].read_bytes()),'Probe stage mismatch');cp=os.pathsep.join(map(str,[out,fc]))
        sources=[TOOLS/'probes/UserModel22FootprintProbe.java',TOOLS/'probes/UserModel22VisualProbe.java',TOOLS/'qa/UserDualGeometryProbe.java']
        compat.run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',out,*sources],tmp)
        require(not({p.relative_to(out).as_posix()for p in out.rglob('*.class')}&set(entries)),'Production accidentally compiled')
        footprint=compat.parse_last_json(compat.run([JAVA/'java.exe','-cp',cp,'UserModel22FootprintProbe',fc],tmp))
        require(footprint.get('ok')and footprint.get('assertions',0)>=250,'Footprint incomplete')
        visual=compat.parse_last_json(compat.run([JAVA/'java.exe','-cp',cp,'UserModel22VisualProbe',fc],tmp));require(visual.get('ok')and visual.get('assertions',0)>=70,'Visual projection incomplete')
        geometry=compat.parse_last_json(compat.run([JAVA/'java.exe','-cp',cp,'UserDualGeometryProbe'],tmp))
        refpath=ROOT/'piq-fc-arcade/design/user-models-20260911/dual-integration-qa-v3/verification.json'
        require(compat.digest(refpath.read_bytes())=='F67B72218AD0BA1CC8B02F3DA1494887471A6E82E1D0B99D10814B7D7B3E47D4','Frozen source geometry report changed')
        reference=json.loads(refpath.read_text(encoding='utf-8'))
        # Reference key is explicit; no class is taken from its source build directory.
        require(geometry==reference['production_geometry'],'Final geometry differs from source visual/UV audit')
        return {'footprint':footprint,'visual_projection':visual,'geometry':geometry,'production_compiled':False,'origin_jar_sha256':compat.digest(fc.read_bytes())}

def audit(paths,with_probes=True):
    baselines={};archives={};hashes={};oldpaths={};assets=expected_assets()
    for kind,path in paths.items():
        hashes[kind],archives[kind]=compat.archive(path);oldpath,expected=BASELINES[kind];actual,baselines[kind]=compat.archive(oldpath);require(actual==expected,'Frozen baseline changed '+kind);oldpaths[kind]=oldpath
    require(hashes['native']==BASELINES['native'][1],'Native7 must remain whole-byte identical')
    categories={k:classify(k,baselines[k],archives[k],assets[k])for k in paths};owners=compat.unique_ownership(archives)
    corepath,corehash=compat.BASELINES['core'];actual,core=compat.archive(corepath);require(actual==corehash,'Old core changed')
    protected=[n for n in core if n not in(META,MANIFEST)];require(all(archives['sfc'].get(n)==core[n]for n in protected),'SFC6 core/AT/runtime altered')
    negative=[]
    for label,kind,entry,action in [('server','fc',FC+'cabinet/CabinetRooms.class','change'),('wire','sfc',SFC+'net/SfcJoinNetwork.class','change'),('old_texture','fc','assets/piq_fc_arcade/textures/item/controller_twohand_skin.png','change'),('rom','fc','unexpected.nes','add'),('deleted_id','fc',FC+'cabinet/CabinetTarget.class','delete'),('new_texture','fc',next(iter(assets['fc'])),'change')]:
        altered=dict(archives[kind]);altered.pop(entry,None)if action=='delete'else altered.__setitem__(entry,b'Audit mutation only')
        try:classify(kind,baselines[kind],altered,assets[kind])
        except(AssertionError,ValueError):negative.append(label)
        else:raise AssertionError('Negative mutation accepted '+label)
    with tempfile.TemporaryDirectory(prefix='piq-model22-bytecode-')as folder:
        tmp=Path(folder);staged={};old={}
        for key,path in paths.items():
            staged[key]=tmp/(key+'.jar');staged[key].write_bytes(path.read_bytes());old[key]=tmp/(key+'-old.jar');old[key].write_bytes(oldpaths[key].read_bytes())
            require(compat.digest(staged[key].read_bytes())==hashes[key]and compat.digest(old[key].read_bytes())==BASELINES[key][1],'Bytecode probe stage mismatch')
        method_report=method_audit(staged,old,categories)
    probes={}
    if with_probes:probes={'geometry':geometry_probes(paths,archives['fc']),'fml_and_outer_codec':compat.java_probes(paths)}
    require(all(compat.digest(path.read_bytes())==hashes[k]for k,path in paths.items()),'Final changed during audit')
    return {'ok':True,'schema':'piq-user-models22-independent-1','jars':{k:{'path':str(p),'sha256':hashes[k]}for k,p in paths.items()},'strict_scope':categories,'ownership':owners,'old_method_protection':method_report,'frozen_sfc6_entries_preserved':len(protected),'negative_controls':negative,'probes':probes,'production_compiled':False,'installed':False,'minecraft_or_native_core_started':False,'network_socket_opened':False,'limits':['Actual FML parser/ASM discovery and outer codecs, not full mod bootstrap or live multiplayer.','User model geometry checked offline and against final pure Java; no Minecraft scene or physical controller claim.','New dual cabinet uses dedicated default UV atlas; old skin hash retained but not applied; single cabinet skins unchanged.']}

def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__)
    for name in('fc','sfc','native','report'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();require(not a.report.exists(),'Refusing to overwrite report');paths={k:getattr(a,k).resolve(strict=True)for k in('fc','sfc','native')}
    require(len(set(paths.values()))==3 and a.report.resolve()not in paths.values(),'Input/output collision');result=audit(paths)
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'jars':result['jars'],'old_method_classes':len(result['old_method_protection'])},ensure_ascii=False))
if __name__=='__main__':main()
