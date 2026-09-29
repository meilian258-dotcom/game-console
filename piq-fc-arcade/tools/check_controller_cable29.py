"""Pure production and real MC pose probes; never starts a game or modifies jars/assets."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
SFC=ROOT.parent/'piq-sfc-home'
FC_TYPES=['cn.piq.fcarcade.layout.RocketArcadeGeometry','cn.piq.fcarcade.layout.ControllerCableGeometry','cn.piq.fcarcade.client.ControllerPoseLayout']
FC_TESTS=['cn.piq.fcarcade.layout.ControllerCableGeometryTest','cn.piq.fcarcade.client.ControllerCablePoseTest']
SFC_TYPES=['cn.piq.sfchome.layout.SfcConsoleScale','cn.piq.sfchome.client.SfcControllerPoseLayout']
SFC_TESTS=['cn.piq.sfchome.client.SfcControllerCableGeometryTest']
def source(root,kind,name):return root/('src/'+kind+'/java')/Path(name.replace('.','/')+'.java')
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path);p.add_argument('--sfc',type=Path);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    assert not a.report.exists(),'Refuse report overwrite'
    assert bool(a.fc)==bool(a.sfc),'Supply both final jars, or neither for explicit source-only QA'
    final=bool(a.fc);jars={}
    if final:
        for name,path in {'fc':a.fc,'sfc':a.sfc}.items():
            digest,entries=q.archive(path);jars[name]={'path':str(path.resolve()),'sha256':digest}
    with tempfile.TemporaryDirectory(prefix='piq-cable29-')as temp:
        temp=Path(temp);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir()
        bases=[]
        if final:
            for name,path in {'fc':a.fc,'sfc':a.sfc}.items():
                target=temp/(name+'.jar');shutil.copyfile(path,target);assert q.digest(target.read_bytes())==jars[name]['sha256'];bases.append(target)
        cp=os.pathsep.join(map(str,[out,*bases,q.MC,*q.dependencies()]));af=temp/'cp.args';af.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[]if final else [source(ROOT,'main',n)for n in FC_TYPES]+[source(SFC,'main',n)for n in SFC_TYPES]
        tests=[source(ROOT,'test',n)for n in FC_TESTS]+[source(SFC,'test',n)for n in SFC_TESTS]
        probes=[ROOT/'tools/qa/ControllerCableVisualProbe.java',ROOT/'tools/qa/CabinetRoomTestRunner.java']
        q.run([q.JAVA/'javac.exe','@'+str(af),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,*probes],ROOT)
        prop=['-Dpiq.cable.finalJar='+str(bases[0])]if final else[]
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),*prop,'CabinetRoomTestRunner',*FC_TESTS,*SFC_TESTS],ROOT))
        visual=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(af),'cn.piq.fcarcade.client.ControllerCableVisualProbe',bases[0]if final else out,q.MC],ROOT))
        assert visual['ok'] and visual['actual_camera_and_pose_stack'] and not visual['minecraft_instance_or_world_created']
        if final:
            for jar in bases:
                with zipfile.ZipFile(jar)as zip:
                    assert not any(path.relative_to(out).as_posix()in zip.namelist()for path in out.rglob('*.class')),'Accidentally compiled production'
            for name,path in {'fc':a.fc,'sfc':a.sfc}.items():assert q.digest(path.read_bytes())==jars[name]['sha256']
    report={'ok':True,'mode':'final-jar-only'if final else'source-pure-and-real-MC-pose','jars':jars,
        'production_compiled':not final,'compiled_only_probes_and_tests':final,'pure':result,'visual':visual,
        'source_sha256':{str(path.relative_to(ROOT.parent)):q.digest(path.read_bytes())for path in sources+tests+probes},
        'assets_modified':False,'minecraft_started':False,'limits':['No running world, actual network/player or GPU renderer test. Real Camera/PoseStack plus production geometry/identity gate tested.',
        'First-person idle equip with existing controller rig; third-person hand-region approximation, not exact animated arm bones. Non-default hand FOV/modded poses may offset cord attachment.']}
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'ok':True,'tests':result['passed_tests'],'visual_assertions':visual['assertions'],'report':str(a.report)}))
if __name__=='__main__':main()
