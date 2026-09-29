"""Supplied-JAR-only hand model QA. Compiles the explicit probe, never production."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'build/review-appliance29-v1'
BASELINES={'fc':(BASE/'piq_fc_arcade-0.31.0-alpha.29.jar','80FD1512F69CCEB192EF78C02D5A3893924C835B322FC8CF9AF441FEB4B833C2'),
           'sfc':(BASE/'piq_sfc-0.1.0-alpha.17.jar','9D033CDF969E53F7642C7AC52C69065231D497FF51A134C3DF952095B2971D2E')}

def method(code,token):
    found=[body for signature,body in q.methods(code).items() if token in signature]
    q.require(len(found)==1,'Expected exact method '+token)
    return found[0]

def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--sfc',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--candidate',action='store_true');a=p.parse_args()
    q.require(not a.report.exists(),'Refuse report overwrite')
    jars={};entries={};baselines={};assets={}
    for name,path in {'fc':a.fc,'sfc':a.sfc}.items():
        sha,entries[name]=q.archive(path);jars[name]={'path':str(path.resolve()),'sha256':sha}
        old_path,expected=BASELINES[name];old_sha,baselines[name]=q.archive(old_path);q.require(old_sha==expected,'Pinned baseline changed '+name)
    for name in ('zapper_stand','zapper_stand_cable'):
        path='assets/piq_fc_arcade/models/item/'+name+'.json';old=json.loads(baselines['fc'][path]);new=json.loads(entries['fc'][path]);old_display=old.pop('display');new_display=new.pop('display')
        q.require(old==new,'Original geometry/UV/parent changed '+path)
        q.require(all(k in new_display and new_display[k]==v for k,v in old_display.items()),'Existing GUI/display changed '+path)
        q.require(set(new_display)-set(old_display)=={'firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand','ground','fixed'},'Unexpected display extension '+path)
        assets[path]={'sha256':q.digest(entries['fc'][path]),'old_geometry_uv_gui_preserved':True,'display':new_display}
    textures=set()
    for path,raw in baselines['fc'].items():
        if path.startswith('assets/piq_fc_arcade/models/block/zapper_stand/') or path.startswith('assets/piq_fc_arcade/textures/block/zapper_stand'):
            q.require(entries['fc'].get(path)==raw,'Original stand block/texture changed '+path)
            if path.endswith('.json'):
                for value in json.loads(raw).get('textures',{}).values():
                    if not value.startswith('#'):
                        namespace,texture=value.split(':',1) if ':' in value else ('minecraft',value)
                        texture_path='assets/'+namespace+'/textures/'+texture+'.png'
                        if namespace=='piq_fc_arcade':textures.add(texture_path)
    q.require(bool(textures),'Expected existing stand texture references')
    for path in textures:q.require(entries['fc'].get(path)==baselines['fc'][path],'Referenced original PNG changed '+path)
    for path,raw in baselines['sfc'].items():
        if path.startswith('assets/piq_sfc_home/'):
            q.require(entries['sfc'].get(path)==raw,'SFC resource changed '+path)
    q.require(entries['sfc']['cn/piq/sfchome/client/SfcControllerPoseLayout.class']==baselines['sfc']['cn/piq/sfchome/client/SfcControllerPoseLayout.class'],'Existing first-person rig changed')
    enum_path='META-INF/piq-fc-controller-enumextensions.json'
    q.require(entries['fc'][enum_path]==baselines['fc'][enum_path],'Existing registered FC enums changed')
    bytecode={}
    with tempfile.TemporaryDirectory(prefix='piq-held30-') as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();copies={}
        for name,path in {'fc':a.fc,'sfc':a.sfc}.items():
            copies[name]=tmp/(name+'.jar');shutil.copyfile(path,copies[name]);q.require(q.digest(copies[name].read_bytes())==jars[name]['sha256'],'Copy differs')
        for cls in ('SfcControllerPose','SfcHardwareItems$1','SfcHardwareItems$ItemModel'):
            raw=q.disassemble(copies['sfc'],q.JAVA/'javap.exe','cn.piq.sfchome.client.'+cls);bytecode[cls]=q.digest(raw.encode())
            if cls=='SfcControllerPose':
                arm=method(raw,' armPose(');render=method(raw,' renderHands(')
                for token in ('FIRST_ARMS','getItemInHand:','ItemStack.isEmpty:','ControllerArmPoseParameters.TWO_HANDS:','ControllerArmPoseParameters.SINGLE_HAND:','EnumProxy.getValue:'):q.require(token in arm,'Missing arm API guard '+token)
                q.require(arm.index('FIRST_ARMS')<arm.index('ControllerArmPoseParameters.TWO_HANDS:'),'First-person guard must precede enum')
                q.require('ThreadLocal.set:' in render and render.count('ThreadLocal.set:')>=3 and 'Exception table:' in render and 'any' in render,'Missing finally ThreadLocal restore')
                q.require(render.index('ItemStack.isEmpty:')<render.index('setCanceled:'),'Never cancel occupied support hand')
            elif cls=='SfcHardwareItems$1':q.require('SfcControllerPose.armPose:' in method(raw,' getArmPose('),'Item extension not delegated')
            else:
                transform=method(raw,' applyTransform(')
                for token in ('THIRD_PERSON_LEFT_HAND','THIRD_PERSON_RIGHT_HAND','SfcControllerPose.thirdTransform:','BakedModel.applyTransform:'):q.require(token in transform,'Third-only wrapper '+token)
        enums=q.disassemble(copies['fc'],q.JAVA/'javap.exe','cn.piq.fcarcade.client.ControllerArmPoseParameters')
        q.require(enums.count('ControllerPose.applyArms:')==2,'Both existing enum proxies must call original FC arm rig')
        bytecode['ControllerArmPoseParameters']=q.digest(enums.encode())
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,*copies.values(),q.MC,resources,*q.dependencies()]));af=tmp/'cp.args';af.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        probe=ROOT/'tools/qa/HeldModels30Probe.java'
        q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,probe],ROOT)
        visual=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'cn.piq.sfchome.client.HeldModels30Probe',copies['fc'],copies['sfc'],q.MC],ROOT))
        q.require(not visual['production_compiled'],'Probe must never compile production')
        for path in out.rglob('*.class'):q.require(all(path.relative_to(out).as_posix() not in content for content in entries.values()),'Accidentally compiled production')
        for name,path in {'fc':a.fc,'sfc':a.sfc}.items():q.require(q.digest(path.read_bytes())==jars[name]['sha256'],'Input JAR changed while checking')
    report={'ok':visual['ok'],'mode':'final-jar-only','artifact_stage':'candidate' if a.candidate else 'final','production_compiled':False,'compiled_only_probe':True,'jars':jars,'baseline_jars':{k:{'path':str(v[0]),'sha256':v[1]} for k,v in BASELINES.items()},'assets':assets,'bytecode_sha256':bytecode,'actual':visual,'tools_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes()) for p in (Path(__file__),probe)},'limits':['No Minecraft instance, live player, GPU screenshot or enum-extension transformer boot. Actual SFC transform, actual MC ItemTransform/PoseStack, existing FC applyArms and final-bytecode call/gate checks only.','Mirroring/visible-size bounds are model-space diagnostics, not a claim of physical device or in-game visual testing.']}
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'ok':visual['ok'],'assertions':visual['assertions'],'report':str(a.report),'failures':visual['failures']}));q.require(visual['ok'],'Actual geometry/pose assertions failed; failure evidence preserved')

if __name__=='__main__':main()
