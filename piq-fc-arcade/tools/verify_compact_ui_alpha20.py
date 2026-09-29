"""Strict FC20 / combined SFC7 / unchanged Native6 final-JAR UI audit. No game/install."""
from __future__ import annotations
import argparse,json,re,sys,tempfile,tomllib,struct
from pathlib import Path
from copy import deepcopy
import verify_retro_alpha19 as previous
from freeze_fc_core_alpha19 import safe_path,checked_zip,digest,require
from verify_device_ui_alpha18 import assert_methods_unchanged,disassemble,methods
from check_fc_cartridge_workbench import normalized,instruction_positions

ROOT=previous.ROOT;DELIVERY=previous.DELIVERY;JAVA=previous.JAVA;META=previous.META;MANIFEST=previous.MANIFEST
BASELINES={
 'fc':(DELIVERY/'PIQ-FC街机/alpha19-fc-core-addons/piq_fc_arcade-0.31.0-alpha.19.jar','C36E5878C9957F95C7C95DFD28963FF88972EE41F5CF208603BF2612F79000C4'),
 'sfc':(DELIVERY/'PIQ-SFC家用/0.1.0-alpha.6/piq_sfc-0.1.0-alpha.6.jar','EE32DC98CEC55F2B7361DD2588C647D63DA6AD556CE0E6E20F1B333C8B47627D'),
 'native':(DELIVERY/'PIQ原生街机/0.1.0-alpha.6/piq_native_arcade-0.1.0-alpha.6.jar','B503F5BE9F0C1DAA3640CE1926CCAA268577A76FE709CEFBFA05D9FFEEF5E422')}
VERSIONS={'fc':('0.31.0-alpha.19','0.31.0-alpha.20'),'sfc':('0.1.0-alpha.6','0.1.0-alpha.7')}
MESH='assets/piq_sfc_home/meshes/sfc_hardware.json'
MESH_SHA='65A70EB4C77F90B49DEAF76608BF3AF65D4694D50847BF553305484668D9C454'
def cls(prefix,names):return {prefix+n+'.class'for n in names}
FC='cn/piq/fcarcade/client/';SFC='cn/piq/sfchome/client/'
CHANGED={
 'fc':cls(FC,['ui/DeviceUi','ui/DeviceUi$DeviceButton','ui/DeviceLayout','ui/DeviceFormLayout','ClientCartridgeEditor','cabinet/CabinetMenuScreen','ArcadeSaveSlotsScreen']),
 'sfc':cls(SFC,['SfcCardEditorScreen','SfcCardLibrary']),'native':set()}
ADDED={'fc':cls(FC,['ui/CartridgeWorkbenchLayout','ClientCartridgeEditor$Search']),
       'sfc':cls(SFC,['SfcWorkbenchDisplay']),'native':set()}
# These records may acquire changed Nest/InnerClasses metadata from their UI
# owner; every field/method signature and generated method instruction is checked.
UI_NESTED={'fc':cls(FC,['ui/DeviceLayout$Browser','ui/DeviceLayout$Rect','ClientCartridgeEditor$Entry','ClientCartridgeEditor$LocalRom','ClientCartridgeEditor$LocalCover','ClientCartridgeEditor$Upload']),
           'sfc':cls(SFC,['SfcCardEditorScreen$Phase','SfcCardEditorScreen$Imported','SfcCardLibrary$Row']),'native':set()}

# javac inlines these three DeviceUi palette fields into exactly seven other
# screens. NO method is exempted: after replacing CP Integer payloads, every
# remaining byte (including Code, fields, attributes and debug tables) must match.
PALETTE_ONLY=cls(FC,['ArcadeSaveCatalogScreen','ArcadeSettingsScreen','LeaderboardPanelScreen','RomLibraryScreen','RomRenameScreen','SkinLibraryScreen','rom/LocalRomPickerScreen'])
PALETTE={0xB30C1012:0x88000000,0xFF98A4A3:0xFF505050,0xFFE7E3D8:0xFF303030}

def rewrite_palette_constants(raw):
    require(len(raw)>=10 and raw[:4]==b'\xca\xfe\xba\xbe','Invalid palette-only class')
    output=bytearray(raw);count=struct.unpack_from('>H',raw,8)[0];position=10;index=1;changes=[]
    while index<count:
        require(position<len(raw),'Truncated class constant pool');tag=raw[position];position+=1
        if tag==1:
            require(position+2<=len(raw),'Truncated UTF8 constant');size=struct.unpack_from('>H',raw,position)[0];position+=2+size
        elif tag==3:
            require(position+4<=len(raw),'Truncated Integer constant');value=struct.unpack_from('>I',raw,position)[0]
            if value in PALETTE:
                struct.pack_into('>I',output,position,PALETTE[value]);changes.append({'constant_pool_index':index,'old':f'{value:08X}','new':f'{PALETTE[value]:08X}'})
            position+=4
        elif tag==4:position+=4
        elif tag in (5,6):position+=8;index+=1
        elif tag in (7,8,16,19,20):position+=2
        elif tag in (9,10,11,12,17,18):position+=4
        elif tag==15:position+=3
        else:raise ValueError('Unsupported constant pool tag: '+str(tag))
        require(position<=len(raw),'Truncated class constant payload');index+=1
    return bytes(output),changes

def palette_only(name,before,after):
    require(name in PALETTE_ONLY,'Class is not approved for palette-only change')
    transformed,changes=rewrite_palette_constants(before)
    require(changes and transformed==after,'Palette-only class contains other byte changes: '+name)
    return {'changes':changes,'all_other_class_bytes_identical':True}

def mapped_palette_body(body):
    # Only javap's literal Integer operand on ldc, never arbitrary text/numbers.
    signed=lambda value:value-(1<<32)if value&(1<<31)else value
    for old,new in PALETTE.items():
        body=re.sub(r'(\bldc(?:_w)?\s+#CP // int )'+str(signed(old))+r'(?=\n|$)',lambda m:m[1]+str(signed(new)),body)
    return body

def read(path):
    path=safe_path(path,True);require(path.stat().st_size<=128*1024*1024,'JAR too large');raw=path.read_bytes()
    _,entries=checked_zip(raw);return digest(raw),{n:v for n,v in entries.items()if not n.endswith('/')}

def originals():
    result={}
    for key,(path,expected)in BASELINES.items():
        actual,result[key]=read(path);require(actual==expected,'Frozen alpha19 baseline changed: '+key)
    return result

def metadata(key,before,after):
    expected=tomllib.loads(before[META].decode('utf-8'));actual=tomllib.loads(after[META].decode('utf-8'))
    if key=='native':require(actual==expected and after[MANIFEST]==before[MANIFEST],'Native6 metadata changed');return actual
    old,new=VERSIONS[key];mod='piq_fc_arcade'if key=='fc'else'piq_sfc_home'
    selected=[m for m in expected['mods']if m['modId']==mod];require(len(selected)==1 and selected[0]['version']==old,'Wrong baseline mod version');selected[0]['version']=new
    if key=='sfc':
        dep=[d for d in expected['dependencies'][mod]if d['modId']=='piq_fc_arcade'];require(len(dep)==1 and dep[0]['versionRange']=='[0.31.0-alpha.19,0.32.0)','Baseline FC requirement')
        dep[0]['versionRange']='[0.31.0-alpha.20,0.32.0)'
    require(actual==expected,'Unapproved mod metadata/dependency difference: '+key)
    old_manifest=before[MANIFEST].decode('utf-8').replace('\r','');new_manifest=after[MANIFEST].decode('utf-8').replace('\r','')
    text='Implementation-Version: '+old+'\n';require(old_manifest.count(text)==1,'Ambiguous old manifest version')
    require(old_manifest.replace(text,'Implementation-Version: '+new+'\n')==new_manifest,'Manifest changed beyond version: '+key)
    return actual

def classify(key,before,after):
    require(set(after)==set(before)|ADDED[key],'Unapproved added/deleted entry inventory: '+key)
    metadata(key,before,after);same=[];changed=[];nested=[];assets=[];palette={}
    for name,raw in before.items():
        candidate=after[name]
        if name in (META,MANIFEST):continue
        if key=='sfc'and name==MESH:
            require(digest(candidate)==MESH_SHA,'SFC mesh does not match reviewed geometry');assets.append(name)
        elif name in CHANGED[key]:changed.append(name)
        elif raw==candidate:same.append(name)
        elif key=='fc'and name in PALETTE_ONLY:palette[name]=palette_only(name,raw,candidate)
        elif name in UI_NESTED[key]:nested.append(name)
        else:raise ValueError('Unapproved class/resource/network/core difference: '+key+'/'+name)
    require(all(name.endswith('.class')and after[name][:4]==b'\xca\xfe\xba\xbe'for name in ADDED[key]),'New UI class is invalid')
    return {'byte_identical_entries':same,'approved_ui_classes':changed,'new_ui_classes':sorted(ADDED[key]),'nested_metadata_requires_method_check':nested,'approved_assets':assets,'palette_constant_only':palette}

FC_EDITOR_ALLOWED=['ClientCartridgeEditor(cn.piq.fcarcade.home.CartridgeNetwork$Reply)',' init(', ' render(', ' rebuildWidgets(', ' selectedRom(', ' selectedCover(', ' pageKeys(', 'lambda$init$']
SFC_EDITOR_ALLOWED=['SfcCardEditorScreen(', ' init()', 'rebuildRows(', 'controls(', 'scanLocal(', 'render(', 'action(', 'tab(', 'shownDirectory(', 'displayName(', 'visibleSelection(', 'fit(', 'lambda$init$', 'lambda$rebuildRows$', 'lambda$scanLocal$']

def api(path,name):
    return previous.run([JAVA/'javap.exe','-J-Dfile.encoding=UTF-8','-p','-s','-classpath',path,name],ROOT).split('\n',1)[-1].strip()

def bytecode(paths,oldpaths,classification):
    result={}
    for key in ('fc','sfc'):
        entries={}
        for entry in classification[key]['nested_metadata_requires_method_check']:
            name=entry[:-6].replace('/','.')
            require(api(paths[key],name)==api(oldpaths[key],name),'UI nested field/method API changed: '+name)
            entries[name]=assert_methods_unchanged(oldpaths[key],paths[key],JAVA/'javap.exe',name,[])
        result[key]={'nested_identity_methods':entries}
    result['fc']['unchanged_editor_methods']=assert_methods_unchanged(oldpaths['fc'],paths['fc'],JAVA/'javap.exe','cn.piq.fcarcade.client.ClientCartridgeEditor',FC_EDITOR_ALLOWED)
    result['sfc']['unchanged_editor_methods']=assert_methods_unchanged(oldpaths['sfc'],paths['sfc'],JAVA/'javap.exe','cn.piq.sfchome.client.SfcCardEditorScreen',SFC_EDITOR_ALLOWED)
    name='cn.piq.fcarcade.client.ArcadeSaveSlotsScreen'
    require(api(paths['fc'],name)==api(oldpaths['fc'],name),'Save slots field/method API changed')
    oldslots=normalized(disassemble(oldpaths['fc'],JAVA/'javap.exe',name));newslots=normalized(disassemble(paths['fc'],JAVA/'javap.exe',name))
    require(set(oldslots)==set(newslots),'Save slots method inventory changed');slot_methods=[]
    for signature,bodies in oldslots.items():
        if ' init('in signature:continue
        expected=map(mapped_palette_body,bodies)if ' render('in signature else bodies
        require(sorted(map(instruction_positions,expected))==sorted(map(instruction_positions,newslots[signature])),'Unreviewed save slot behavior change: '+signature);slot_methods.append(signature)
    result['fc']['unchanged_save_slot_methods']=slot_methods
    result['fc']['save_slot_render_exception']='Only the same three exact palette integers; all remaining instructions and branch targets preserved.'
    # Only painting/initial widget construction changes in the common cabinet menu.
    name='cn.piq.fcarcade.client.cabinet.CabinetMenuScreen'
    before=normalized(disassemble(oldpaths['fc'],JAVA/'javap.exe',name));after=normalized(disassemble(paths['fc'],JAVA/'javap.exe',name))
    menu_ui=['CabinetMenuScreen(cn.piq.fcarcade.cabinet.CabinetNetwork$Menu)', ' init(', ' render(', ' openAppearance(', 'lambda$init$']
    menu_methods=[]
    for signature,bodies in before.items():
        if any(token in signature for token in menu_ui):continue
        require(signature in after and sorted(map(instruction_positions,after[signature]))==sorted(map(instruction_positions,bodies)),'Original cabinet selection/lifecycle changed: '+signature);menu_methods.append(signature)
    require(all(signature in before or any(token in signature for token in menu_ui)for signature in after),'Unexpected new cabinet method')
    result['fc']['unchanged_menu_methods']=menu_methods
    appearance=[body for signature,bodies in after.items()if ' openAppearance('in signature for body in bodies]
    require(len(appearance)==1,'Missing unique appearance action');appearance=appearance[0]
    for token in ['getConnection:', 'CabinetTarget.matches:', 'isAlive:', 'isSpectator:', 'hasPermissions:', 'distanceToSqr:', 'FcNetwork.requestSkinLibrary:', 'Method onClose:']:
        require(token in appearance,'Appearance permission/old API contract missing: '+token)
    require(appearance.index('Method onClose:')<appearance.index('FcNetwork.requestSkinLibrary:'),'Appearance must close old menu before asking for library')
    paint=methods(disassemble(paths['fc'],JAVA/'javap.exe','cn.piq.fcarcade.client.ui.DeviceUi$DeviceButton'))
    require(not any(' renderWidget('in s for s in paint)and any(' renderString('in s for s in paint),'Shared button must use inherited vanilla widget rendering')
    for key,name in [('fc','cn.piq.fcarcade.client.ClientCartridgeEditor'),('sfc','cn.piq.sfchome.client.SfcCardEditorScreen')]:
        code=disassemble(paths[key],JAVA/'javap.exe',name)
        require('CartridgeWorkbenchLayout.of:'in code,'Workbench must use shared layout')
    return result

def ui_probe(paths):
    with tempfile.TemporaryDirectory(prefix='alpha20-ui-probe-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();staged={}
        for key,path in paths.items():
            target=tmp/(key+'.jar');raw=path.read_bytes()
            with target.open('xb')as stream:stream.write(raw)
            require(target.read_bytes()==raw,'Probe copy mismatch');staged[key]=target
        cp=previous.os.pathsep.join(map(str,[out,staged['fc'],staged['sfc']]))
        probe=ROOT/'piq-fc-arcade/tools/probes/Alpha20WorkbenchProbe.java'
        previous.run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',out,probe],tmp)
        data=previous.parse_last_json(previous.run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.Alpha20WorkbenchProbe',staged['fc'],staged['sfc']],tmp))
        require(data.get('ok')and data.get('production_origin')=='final-jar-only','Final pure UI probe failed');return data

def audit(paths,with_probes=True):
    old=originals();hashes={};jars={}
    for key,path in paths.items():hashes[key],jars[key]=read(path)
    require(hashes['native']==BASELINES['native'][1],'Native6 must be the unchanged original delivery')
    categories={key:classify(key,old[key],jars[key])for key in paths}
    owners=previous.unique_ownership(jars)
    # Stronger namespace/content rejection supplements raw baseline protection.
    from package_fc_core_alpha19 import check_ownership
    check_ownership(jars)
    with tempfile.TemporaryDirectory(prefix='alpha20-bytecode-')as folder:
        tmp=Path(folder);staged={};bases={}
        for key,path in paths.items():
            target=tmp/(key+'.jar');target.write_bytes(path.read_bytes());staged[key]=target
            target=tmp/(key+'-old.jar');target.write_bytes(BASELINES[key][0].read_bytes());bases[key]=target
        code=bytecode(staged,bases,categories)
    probes=previous.java_probes(paths)if with_probes else {}
    if with_probes:probes['compact_workbench']=ui_probe(paths)
    require(all(read(path)[0]==hashes[key]for key,path in paths.items()),'Candidate changed during audit')
    return {'ok':True,'schema':'piq-compact-alpha20-final-1','jars':{key:{'path':str(path),'sha256':hashes[key]}for key,path in paths.items()},
            'classification':categories,'ownership':owners,'bytecode':code,'probes':probes,'sfc_mesh_sha256':MESH_SHA,
            'native_unchanged':True,'installed':False,'minecraft_or_native_core_started':False,
            'limits':['Actual FML discovery/ASM, not full FML bootstrap.','Pure UI and codec tests do not replace Minecraft scene, live upload, multiplayer or physical-controller testing.','Native6 remains localOnly.']}

def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser(description=__doc__)
    for key in ('fc','sfc','native','report'):parser.add_argument('--'+key,type=Path,required=True)
    args=parser.parse_args();report=safe_path(args.report);require(not report.exists(),'Refusing to overwrite audit report')
    paths={key:safe_path(getattr(args,key),True)for key in ('fc','sfc','native')};require(len(set(paths.values()))==3,'Distinct final JARs required')
    require(report not in paths.values(),'Report cannot overwrite an input');result=audit(paths)
    report.parent.mkdir(parents=True,exist_ok=True);safe_path(report.parent)
    with report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(report),'jars':result['jars'],'bytecode':result['bytecode']},ensure_ascii=False))
if __name__=='__main__':main()
