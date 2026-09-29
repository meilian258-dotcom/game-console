"""Actual installed computer JSON vs production Java shape, six-axis seams and GUI."""
import argparse,json,subprocess,tempfile
import numpy as np
from build_cartridge_computer_model import MODEL,ITEM,STATE,OUT,SCREEN,audit,build,item_model,blockstate
from import_subor_hardware import encoded,sha,write_new
from check_dual_render_pipeline import JDK,JAVA,ROOT
from render_rocket_arcade_preview import collect_quads

def java_parts():
    source=JAVA/'home/CartridgeComputerLayout.java';probe=ROOT/'tools/qa/CartridgeComputerLayoutProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-computer-layout-') as tmp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',tmp,str(source),str(probe)],check=True,capture_output=True,timeout=30)
        out=subprocess.run([str(JDK/'java.exe'),'-cp',tmp,'CartridgeComputerLayoutProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    data={}
    for row in out.splitlines():
        a=row.split();d=data.setdefault(int(a[1]),{'parts':[]});b=np.array(list(map(float,a[3:]))).reshape(2,3)
        if a[0]=='BOUND':d['bounds']=b
        else:d['parts'].append(b)
    return data,out

def intersection(a,b):
    r=(max(a[0],b[0]),max(a[1],b[1]),min(a[2],b[2]),min(a[3],b[3]))
    return r if r[2]-r[0]>1e-8 and r[3]-r[1]>1e-8 else None

def subtract(a,b):
    r=intersection(a,b)
    if r is None:return [a]
    result=[]
    for q in ((a[0],a[1],r[0],a[3]),(r[2],a[1],a[2],a[3]),(r[0],a[1],r[2],r[1]),(r[0],r[3],r[2],a[3])):
        if q[2]-q[0]>1e-8 and q[3]-q[1]>1e-8:result.append(q)
    return result

def face_rect(q):
    axis={'north':2,'south':2,'east':0,'west':0,'up':1,'down':1}[q.direction];dims=[i for i in range(3) if i!=axis]
    p=q.vertices[:,dims];return axis,float(q.vertices[0,axis]),(*p.min(0),*p.max(0))

def exposed_overlaps(quads):
    """Exact axis-aligned rectangle subtraction of closer opaque projected faces."""
    result=[]
    for direction in ('north','south','east','west','up','down'):
        sign=-1 if direction in ('north','west','down') else 1
        faces=[(q,*face_rect(q)[1:]) for q in quads if q.direction==direction]
        for i,(a,plane,rect) in enumerate(faces):
            for b,p,r in faces[i+1:]:
                if abs(plane-p)>1e-8:continue
                overlap=intersection(rect,r)
                if overlap is None:continue
                visible=[overlap]
                for blocker,pb,rb in faces:
                    if (pb-plane)*sign<=1e-8:continue
                    visible=[v for old in visible for v in subtract(old,rb)]
                    if not visible:break
                if visible:result.append({'face':direction,'elements':[a.element_index,b.element_index],'plane':plane,'rectangles':visible})
    return result

def front_intrusions(model,quads):
    screen=SCREEN[:4];found=[]
    for q in quads:
        if q.direction!='north':continue
        e=model['elements'][q.element_index]
        if e['name'].startswith('静态终端'):continue
        _,plane,r=face_rect(q)
        hit=intersection(r,screen)
        if hit is not None and plane<SCREEN[4]-1e-6:found.append((q.element_index,r))
    return found

def analyze():
    raw=MODEL.read_bytes();model=json.loads(raw);report,tex,matrix=audit(model);q=collect_quads(model);v=np.concatenate([a.vertices for a in q]);data,stdout=java_parts();checks=[]
    def check(name,ok):checks.append({'name':name,'ok':bool(ok)})
    check('all three installed resources equal deterministic native export',raw==encoded(build()) and ITEM.read_bytes()==encoded(item_model()) and STATE.read_bytes()==encoded(blockstate()))
    for t in range(4):
        turned=v.copy()
        for _ in range(t):turned=np.column_stack((16-turned[:,2],turned[:,1],turned[:,0]))
        inside=np.zeros(len(v),bool)
        for b in data[t]['parts']:inside|=np.all((turned>=b[0]-1e-8)&(turned<=b[1]+1e-8),axis=1)
        check('turn '+str(t)+' actual vertices contained in real Java shape union',inside.all())
        check('turn '+str(t)+' real Java whole envelope matches actual model bounds',np.allclose([turned.min(0),turned.max(0)],data[t]['bounds'],atol=1e-10))
    overlap=exposed_overlaps(q);intrusions=front_intrusions(model,q)
    check('no six-axis exposed coplanar overlapping face rectangles',not overlap)
    check('complete 4:3 recessed screen has no shell projected across it',not intrusions)
    # Vanilla reference is read directly from the fixed local 1.21.1 client JAR.
    green=tex['minecraft:block/lime_concrete'][7:9,7:9]
    check('vanilla terminal texels are visible green not transparent atlas markers',np.all(green[:,:,3]==255) and np.all(green[:,:,1]>green[:,:,0]) and np.all(green[:,:,1]>green[:,:,2]))
    report.update(ok=all(c['ok'] for c in checks),checks=checks,exposed_coplanar_overlaps=overlap,screen_occluders=intrusions,production_stdout=stdout,
            model_sha256=sha(raw),item_sha256=sha(ITEM.read_bytes()),blockstate_sha256=sha(STATE.read_bytes()),production_layout_sha256=sha((JAVA/'home/CartridgeComputerLayout.java').read_bytes()),
            collision_note='Actual production Java six-part shape union tested against every exported vertex in four blockstate facings.',
            seam_note='Six orthographic axis views, exact native cube rectangles; not a Minecraft AO/shader or arbitrary-angle certification.')
    return report

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');a=p.parse_args();report=analyze()
    if a.write:
        if not report['ok']:raise ValueError('Computer QA failed; not freezing a report')
        write_new({OUT/'cartridge-computer-production-qa.json':encoded(report)})
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
if __name__=='__main__':main()
