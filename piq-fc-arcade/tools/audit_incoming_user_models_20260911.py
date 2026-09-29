"""Independent static geometry/UV/texture/archive audit; never executes files from the archive."""
import argparse,base64,hashlib,io,itertools,json,math,re,sys,zipfile
from pathlib import Path,PurePosixPath
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]/'design/user-models-20260911'
def digest(data):return hashlib.sha256(data).hexdigest().upper()
def read(path):return json.loads(path.read_text(encoding='utf-8'))
def close(a,b):
    if isinstance(a,(list,tuple)):return len(a)==len(b)and all(close(x,y)for x,y in zip(a,b))
    return abs(float(a)-float(b))<1e-7
def rotation(element):
    value=element.get('rotation',[0,0,0])
    if isinstance(value,dict):
        angles=[0,0,0];angles['xyz'.index(value['axis'])]=value['angle'];return angles,value['origin'],bool(value.get('rescale',False))
    return value,element.get('origin',[8,8,8]),bool(element.get('rescale',False))
def transformed(element,point):
    angles,origin,rescale=rotation(element);p=[point[i]-origin[i]for i in range(3)]
    if rescale:raise ValueError('Rescale requires explicit independent support')
    for axis,degrees in enumerate(angles):
        if not degrees:continue
        r=math.radians(degrees);c,s=math.cos(r),math.sin(r);x,y,z=p
        p=([x,c*y-s*z,s*y+c*z]if axis==0 else [c*x+s*z,y,-s*x+c*z]if axis==1 else [c*x-s*y,s*x+c*y,z])
    return [p[i]+origin[i]for i in range(3)]
def vertices(element):return [transformed(element,p)for p in itertools.product(*zip(element['from'],element['to']))]
def bounds(points):
    lo=[min(p[i]for p in points)for i in range(3)];hi=[max(p[i]for p in points)for i in range(3)]
    return {'min':lo,'max':hi,'size':[hi[i]-lo[i]for i in range(3)],'blocks':[(hi[i]-lo[i])/16 for i in range(3)]}
def face_vertices(element,face):
    x,y,z=element['from'];X,Y,Z=element['to']
    table={'north':[(x,y,z),(x,Y,z),(X,Y,z),(X,y,z)],'south':[(X,y,Z),(X,Y,Z),(x,Y,Z),(x,y,Z)],'west':[(x,y,Z),(x,Y,Z),(x,Y,z),(x,y,z)],'east':[(X,y,z),(X,Y,z),(X,Y,Z),(X,y,Z)],'up':[(x,Y,z),(x,Y,Z),(X,Y,Z),(X,Y,z)],'down':[(x,y,Z),(x,y,z),(X,y,z),(X,y,Z)]}
    return [transformed(element,p)for p in table[face]]
def outward(element,face):
    a,b,c,d=face_vertices(element,face);u=[b[i]-a[i]for i in range(3)];v=[c[i]-a[i]for i in range(3)]
    n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
    center=transformed(element,[(element['from'][i]+element['to'][i])/2 for i in range(3)])
    return sum(n[i]*((a[i]+b[i]+c[i]+d[i])/4-center[i])for i in range(3))>1e-16
def audit_group(folder):
    bbpath=next(folder.glob('*.bbmodel'));bb=read(bbpath)
    # Manifests may duplicate an embedded complete model; only the direct static export is authoritative here.
    exports=[p for p in folder.glob('*.json')if p.name in ('arcade_universal_deep.json','super_famicom.json','sfc_cartridge.json')]
    if len(exports)!=1:raise ValueError('Expected one static Java export')
    path=exports[0];model=read(path);elements=bb['elements'];problems=[];checks=0
    def check(good,message):
        nonlocal checks;checks+=1
        if not good:problems.append(message)
    check(len(elements)==len(model['elements']),'Java/BB element count')
    ids={e['uuid']:e for e in elements};check(len(ids)==len(elements),'Unique element UUIDs')
    groups={g['uuid']:g for g in bb['groups']};check(len(groups)==len(bb['groups']),'Unique group UUIDs')
    group_members={uuid:[]for uuid in groups};seen=[]
    def visit(nodes,parents):
        for node in nodes:
            if isinstance(node,str):
                check(node in ids,'Outliner unknown element '+node);seen.append(node)
                for parent in parents:group_members[parent].append(node)
            else:
                uuid=node['uuid'];check(uuid in groups,'Unknown outliner group '+uuid)
                visit(node.get('children',[]),parents+[uuid])
    visit(bb['outliner'],[]);check(sorted(seen)==sorted(ids),'Outliner complete exactly-once element ownership')
    for g in groups.values():check(close(g.get('rotation',[0,0,0]),[0,0,0]),'Nonzero group transform '+g['name'])
    mirrored=0;triangles=0;rotated=0
    for n,(b,j)in enumerate(zip(elements,model['elements'])):
        label=f'element {n} {b.get("name","")}'
        check(b.get('type','cube')=='cube',label+' is cube');check(b.get('export',True),label+' exported')
        check(all(math.isfinite(v)for v in b['from']+b['to']),label+' finite bounds')
        check(all(a<t for a,t in zip(b['from'],b['to'])),label+' no inverted/zero thickness')
        check(close(b['from'],j['from'])and close(b['to'],j['to']),label+' geometry export')
        a,o,r=rotation(b);ja,jo,jr=rotation(j);rotated+=any(a)
        check(sum(abs(v)>1e-9 for v in a)<=1 and all(v in(-45,-22.5,0,22.5,45)for v in a),label+' legal Java single-axis rotation')
        check(close(a,ja)and r==jr and(not any(a)or close(o,jo)),label+' rotation/origin export')
        check(not r,label+' no rescale')
        check(b.get('shade',True)==j.get('shade',True),label+' shade')
        visible={face:value for face,value in b['faces'].items()if value.get('texture')is not None}
        check(set(visible)==set(j['faces']),label+' visible face set (null texture faces omitted)')
        for face,bf in visible.items():
            jf=j['faces'][face];uv=bf['uv'];scale=[bb['resolution']['width'],bb['resolution']['height']]*2
            check(len(uv)==4 and all(math.isfinite(v)and 0<=v<=scale[i]for i,v in enumerate(uv)),label+'/'+face+' UV bounded')
            check(close([v/scale[i]*16 for i,v in enumerate(uv)],jf['uv']),label+'/'+face+' UV export')
            check(str(bf['texture'])==str(jf['texture']).lstrip('#'),label+'/'+face+' texture reference')
            check(bf.get('rotation',0)==jf.get('rotation',0),label+'/'+face+' UV rotation')
            check(outward(b,face),label+'/'+face+' outward winding')
            mirrored+=uv[2]<uv[0]or uv[3]<uv[1];triangles+=2
    texture_records=[]
    for texture in bb['textures']:
        raw=base64.b64decode(texture['source'].split(',',1)[1],validate=True)
        external=folder/texture['relative_path'];check(raw==external.read_bytes(),'Embedded PNG matches external '+external.name)
        with Image.open(io.BytesIO(raw))as image:
            dims=list(image.size);image.verify()
        check(dims==[bb['resolution']['width'],bb['resolution']['height']],'Embedded atlas dimensions')
        resource=model['textures'][str(texture['id'])];namespace,asset=resource.split(':',1)
        resource_file=folder/'minecraft_assets/assets'/namespace/'textures'/f'{asset}.png'
        check(resource_file.read_bytes()==raw,'Resource texture byte identity')
        texture_records.append({'file':external.name,'dimensions':dims,'sha256':digest(raw),'embedded_external_resource_identical':raw==external.read_bytes()==resource_file.read_bytes()})
    resource_models=list((folder/'minecraft_assets').rglob('*.json'));check(len(resource_models)==1,'One static resource model')
    check(model==read(resource_models[0]),'Top-level Java/resource Java full equality')
    mapping=folder/'按键动画映射.json';animation=[]
    if mapping.exists():
        for mapped in read(mapping)['groups']:
            uuid=mapped['groupUuid'];check(uuid in groups,'Mapped group UUID')
            check(mapped['groupName']==groups[uuid]['name']and close(mapped['pivot'],groups[uuid]['origin']),'Mapped group name/pivot '+mapped['groupName'])
            check(sorted(mapped['elementUuids'])==sorted(group_members[uuid]),'Mapped exact group ownership '+mapped['groupName'])
            animation.append({'name':mapped['groupName'],'pivot':mapped['pivot'],'elements':len(mapped['elementUuids']),'action':mapped.get('action',mapped.get('kind'))})
    important=[]
    for e in elements:
        if re.search('屏幕|标签|AV|音频|视频|RCA|插槽|卡槽|出线|插头|肩键',e.get('name',''),re.I):
            important.append({'name':e['name'],'uuid':e['uuid'],'bounds':bounds(vertices(e)),'rotation':rotation(e)[0],'faces':{f:{'corners':face_vertices(e,f),'uv':v['uv']}for f,v in e['faces'].items()if f in ('north','up')}})
    return {'group':folder.name,'bbmodel':bbpath.name,'static_json':path.name,'ok':not problems,'checks':checks,'problems':problems,'elements':len(elements),'rotated_elements':rotated,'groups':len(groups),'triangles':triangles,'mirrored_uv_faces':mirrored,'bounds':bounds([p for e in elements for p in vertices(e)]),'textures':texture_records,'display':model.get('display',bb.get('display',{})),'group_geometry':[{'name':g['name'],'uuid':uuid,'pivot':g['origin'],'elements':len(group_members[uuid]),'bounds':bounds([p for eid in group_members[uuid]for p in vertices(ids[eid])])}for uuid,g in groups.items()if group_members[uuid]],'animation':animation,'integration_surfaces':important}
def audit():
    source=ROOT/'source';files={p.relative_to(source).as_posix():p.read_bytes()for p in source.rglob('*')if p.is_file()}
    if len(files)!=48:raise ValueError('Expected exact 48 immutable incoming entries')
    with zipfile.ZipFile(ROOT/'original.zip')as archive:
        if len(archive.infolist())!=48 or len(set(archive.namelist()))!=48:raise ValueError('Archive members not unique/exact')
        for item in archive.infolist():
            name=item.filename;path=PurePosixPath(name)
            if path.is_absolute()or '..'in path.parts or '\\'in name or (item.external_attr>>16)&0o170000==0o120000:raise ValueError('Unsafe archive member')
            if files.get(name)!=archive.read(item):raise ValueError('Copied source mismatch '+name)
    png=[]
    for name,data in files.items():
        if name.lower().endswith('.png'):
            with Image.open(io.BytesIO(data))as image:entry={'file':name,'dimensions':list(image.size),'mode':image.mode,'sha256':digest(data)};image.verify()
            png.append(entry)
    models=[audit_group(p)for p in sorted(source.iterdir())if p.is_dir()]
    hashes={n:digest(b)for n,b in files.items()}
    if any(digest((source/n).read_bytes())!=v for n,v in hashes.items()):raise ValueError('Input changed while auditing')
    return {'ok':all(x['ok']for x in models),'archive_sha256':digest((ROOT/'original.zip').read_bytes()),'immutable_entries':48,'archive_vs_copied_source_exact':True,'file_sha256':hashes,'png_files':png,'models':models,'limits':['Only parses copied JSON/BBModel/PNG and original ZIP; no incoming scripts executed, originals changed, Minecraft launched or production files edited.','Positive cube volumes plus right-handed rotations and outward face tests exclude geometric inversion; they do not certify artistic appearance or game renderer culling.','Runtime screen/AV/hand transforms, collision and owner-bound button animation still require integration review.']}
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    data=audit();a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(data,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':data['ok'],'entries':48,'png_files':len(data['png_files']),'models':[{k:m[k]for k in('group','ok','checks','problems','elements','groups','triangles','bounds')}for m in data['models']]},ensure_ascii=False))
    if not data['ok']:raise SystemExit(1)
if __name__=='__main__':main()
