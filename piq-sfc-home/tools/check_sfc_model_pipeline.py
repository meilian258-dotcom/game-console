"""Historical vanilla-resource/transform compatibility and actual Java layer selection.

Alpha5 live geometry is validated separately by check_sfc_hardware_mesh.py. The eight
old JSON resources remain byte-frozen, so resource-pack transforms are not silently changed.
"""
from __future__ import annotations
import argparse,copy,importlib.util,json,subprocess,sys,tempfile
from pathlib import Path
from import_sfc_models import PROJECT,WORKSPACE,ASSETS,FROZEN,MODELS,sha,encoded,blockstate,console_item
sys.path.insert(0,str(WORKSPACE/'piq-fc-arcade/tools'))
import numpy as np
from render_rocket_arcade_preview import collect_quads
from check_controller_pose_pipeline import rotation,translation,points

OUT=WORKSPACE/'制作Mod/03-街机模拟/PIQ-FC街机/SFC附属首版模型草案/附属工程接入QA-v2'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
SOURCE=PROJECT/'src/main/java/cn/piq/sfchome/client/SfcModelPresentation.java'

def java_probe():
    with tempfile.TemporaryDirectory(prefix='piq-sfc-models-') as tmp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',tmp,str(SOURCE),str(PROJECT/'tools/qa/SfcModelPresentationProbe.java')],check=True,capture_output=True,timeout=30)
        text=subprocess.run([str(JDK/'java.exe'),'-cp',tmp,'SfcModelPresentationProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    data={'models':{},'visible':{},'yaw':{}}
    for row in text.splitlines():
        kind,i,value=row.split();i=int(i)
        data[{'MODEL':'models','VISIBLE':'visible','YAW':'yaw'}[kind]][i]=float(value) if kind=='YAW' else value
    return data,text

def inspect(resources=None,probe=None):
    if resources is None:resources={rel:(ASSETS/rel).read_bytes() for rel in MODELS}
    if probe is None:probe,stdout=java_probe()
    else:stdout='Provided production-probe fixture for negative tests.'
    checks=[];models={};hashes={}
    def check(name,ok):checks.append({'name':name,'ok':bool(ok)})
    for rel,(original,expected) in MODELS.items():
        raw=resources[rel];check('byte-frozen v2 geometry '+rel,sha(raw)==expected and raw==(FROZEN/original).read_bytes())
        models[original]=json.loads(raw);hashes[rel]=sha(raw)
    for rel,expected in (('blockstates/console.json',blockstate()),('models/item/console.json',console_item())):
        raw=(ASSETS/rel).read_bytes();check('exact new-namespace wrapper '+rel,json.loads(raw)==expected);hashes[rel]=sha(raw)
    selector_paths=['block/sfc_console_body','block/sfc_console_controller_1','block/sfc_console_controller_2','block/sfc_console_slot_cover','block/sfc_console_cartridge_inserted']
    check('actual Java selector uses exactly five standalone native layers',probe['models']==dict(enumerate(selector_paths)))
    model_by_layer=[json.loads(resources['models/'+p+'.json']) for p in selector_paths]
    state_summaries=[]
    for mask in range(8):
        expected='0'+('1' if mask&1 else '')+('2' if mask&2 else '')+('4' if mask&4 else '3')
        actual=probe['visible'][mask];check('Java synced visibility mask '+str(mask),actual==expected)
        es=[e for i in map(int,actual) for e in model_by_layer[i]['elements']]
        combined={'textures':model_by_layer[0]['textures'],'elements':es};v=np.concatenate([q.vertices for q in collect_quads(combined)])
        for t in range(4):
            matrix=translation(8,0,8)@rotation('y',probe['yaw'][t])@translation(-8,0,-8)
            rotated=points(v,matrix);reference=v.copy()
            for _ in range(t):reference=np.column_stack((16-reference[:,2],reference[:,1],reference[:,0]))
            check('mask '+str(mask)+' facing '+str(t)+' correct single rotation and one-cell bounds',np.allclose(rotated,reference,atol=1e-8) and np.all((rotated>=-1e-8)&(rotated<=16+1e-8)))
        state_summaries.append({'mask':mask,'layers':actual,'elements':len(es),'bounds':[v.min(0).tolist(),v.max(0).tolist()]})
    # Composite comes from actual installed layers, not by invoking the draft generator.
    inserted=copy.deepcopy(model_by_layer[0]);inserted['elements']+=copy.deepcopy(model_by_layer[1]['elements']+model_by_layer[2]['elements']+model_by_layer[4]['elements'])
    models['sfc_console_inserted_preview.json']=inserted
    spec=importlib.util.spec_from_file_location('sfc_frozen_v2_model',FROZEN/'build_sfc_addon_models-frozen.py');draft=importlib.util.module_from_spec(spec);spec.loader.exec_module(draft)
    native,tex=draft.audit(models);check('all actual native model bounds GUI slot-clearance and coplanar QA',native['ok'])
    check('addon does not add PNG bitmap assets',not list(ASSETS.rglob('*.png')))
    renderer=(PROJECT/'src/main/java/cn/piq/sfchome/client/SfcHardwareRenderer.java').read_text(encoding='utf-8')
    check('client subscriber is physically client-only','value = Dist.CLIENT' in renderer and 'bus = EventBusSubscriber.Bus.MOD' in renderer)
    mesh_renderer=(PROJECT/'src/main/java/cn/piq/sfchome/client/SfcHardwareMesh.java').read_text(encoding='utf-8')
    check('live renderer atomically replaces bounded mesh data at resource reload','SfcHardwareMeshData.read(reader)' in mesh_renderer and 'meshes=Map.copyOf(next)' in mesh_renderer and 'meshes=Map.of()' in mesh_renderer and 'RegisterClientReloadListenersEvent' in mesh_renderer)
    check('BER reads synchronized hand and cartridge state','console.controllerDocked(0)' in renderer and 'console.controllerDocked(1)' in renderer and 'console.hasCartridge()' in renderer)
    check('BER does not double-scale placed native geometry','.scale(' not in renderer and 'SfcModelPresentation.visible(i, p1, p2, card)' in renderer)
    check('BER draws exact free-mesh UV with no bitmap replacement','SfcHardwareMesh.draw(MESH_GROUPS[i], poses, buffers, light, overlay)' in renderer and '.setUv(data[at+3],data[at+4])' in mesh_renderer and 'Math.min(vertex,2)*8' in mesh_renderer)
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'resource_sha256':hashes,'java_probe':probe,'java_stdout':stdout,
       'selector_sha256':sha(SOURCE.read_bytes()),'renderer_sha256':sha(renderer.encode()),'states':state_summaries,
       'native_v2_checks':native['checks'],'native_v2_texture_sha256':native['texture_sha256'],
       'note':'Historical vanilla JSON/transform compatibility and actual Java layer selection. Alpha5 live free geometry is checked separately in check_sfc_hardware_mesh.py. Does not claim Minecraft gameplay, dedicated-server classloading or resource-reload integration testing.'},models,tex,draft

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');a=p.parse_args();report,models,tex,draft=inspect()
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
    if a.write:
        from import_subor_hardware import write_new
        draft.OUT=OUT;out=draft.previews(models,tex);report['preview_sha256']={str(k):sha(v) for k,v in out.items()};out[OUT/'sfc-addon-model-pipeline.json']=encoded(report);write_new(out)
if __name__=='__main__':main()
