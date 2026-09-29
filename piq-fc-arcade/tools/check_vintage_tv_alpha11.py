"""Alpha11 inset CRT: actual production Java/model, exact visibility and true GUI transforms."""
import argparse,json,subprocess,tempfile
import numpy as np
from build_vintage_tv_alpha11 import MODEL,OUT,build,audit
from import_subor_hardware import ASSETS,encoded,sha,write_new
from check_controller_pose_pipeline import display_matrix,translation,points
from check_dual_render_pipeline import JDK,JAVA,ROOT
from check_large_lcd_presentation import runtime_frame_branch
from check_vintage_tv_presentation import preview as shared_preview
from render_rocket_arcade_preview import collect_quads

SOURCES=[JAVA/'layout/RocketArcadeGeometry.java',JAVA/'home/VintageTvLayout.java']
PROBE=ROOT/'tools/qa/VintageTvPresentationProbe.java'
SCREEN=(4.35,2.,14.55,9.65,3.35)

def clipped_polygon(polygon,axis,limit,positive):
    result=[]
    for a,b in zip(polygon,polygon[1:]+polygon[:1]):
        va=(a[axis]-limit)*(1 if positive else -1);vb=(b[axis]-limit)*(1 if positive else -1)
        if va>=0:result.append(a)
        if (va>=0)!=(vb>=0):result.append(a+(b-a)*(va/(va-vb)))
    return result

def front_occluders(quads):
    """Clip actual triangles into the dynamic rectangle and nearer half-space."""
    result=[]
    for q in quads:
        for ix in ((0,1,2),(0,2,3)):
            polygon=[np.array(q.vertices[i],dtype=float) for i in ix]
            for axis,limit,positive in ((0,SCREEN[0],True),(0,SCREEN[2],False),(1,SCREEN[1],True),(1,SCREEN[3],False),(2,SCREEN[4]-.024-1e-6,False)):
                polygon=clipped_polygon(polygon,axis,limit,positive)
                if len(polygon)<3:break
            if len(polygon)<3:continue
            a=np.array(polygon);area=abs(np.sum(a[:,0]*np.roll(a[:,1],-1)-a[:,1]*np.roll(a[:,0],-1)))/2
            if area>1e-8:result.append({'element':q.element_index,'face':q.direction,'projected_overlap_area':float(area)});break
    return result

def probe():
    with tempfile.TemporaryDirectory(prefix='piq-vintage-alpha11-') as temp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',temp,*map(str,SOURCES),str(PROBE)],check=True,capture_output=True,timeout=30)
        stdout=subprocess.run([str(JDK/'java.exe'),'-cp',temp,'VintageTvPresentationProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    result={}
    for line in stdout.splitlines():
        p=line.split();d=result.setdefault(int(p[1]),{})
        if p[0]=='FRAME':d['cached']=p[-1]=='true';p=p[:-1]
        d[p[0]]=np.array([float(v) for v in p[2:]]).reshape(-1,3)
    return result,stdout

def analyze():
    raw=MODEL.read_bytes();model=json.loads(raw);model_report,textures=audit(model);data,stdout=probe();checks=[]
    checks.append({'name':'installed alpha11 model matches current generator exactly','ok':raw==encoded(build())})
    for t,d in data.items():
        f=d['FRAME'][:4];expected=np.array([[4.35,2.,3.326],[14.55,2.,3.326],[14.55,9.65,3.326],[4.35,9.65,3.326]])/16
        sockets=np.array([[9.5-c*2,3.1,14.04] for c in range(3)])/16
        for _ in range(t):
            expected=np.column_stack((1-expected[:,2],expected[:,1],expected[:,0]));sockets=np.column_stack((1-sockets[:,2],sockets[:,1],sockets[:,0]))
        checks.append({'name':'turn '+str(t)+' exact inset 4:3 cached production frame','ok':bool(d['cached'] and np.allclose(f,expected,atol=1e-12) and abs(np.linalg.norm(f[1]-f[0])/np.linalg.norm(f[3]-f[0])-4/3)<1e-12)})
        checks.append({'name':'turn '+str(t)+' original alpha10 three RCA socket positions unchanged','ok':bool(np.allclose(d['SOCKETS'],sockets,atol=1e-12))})
    quads=collect_quads(model);restored=data[0]['FRAME'][:4]-data[0]['FRAME'][4]*.0015
    glass=[q for q in quads if all(any(np.allclose(p,v/16,atol=1e-12) for v in q.vertices) for p in restored)]
    checks.append({'name':'production screen matches unique opaque black native glass','ok':len(glass)==1 and bool(np.all(textures[glass[0].texture]==[0,0,0,255]))})
    intrusions=front_occluders(quads)
    checks.append({'name':'no actual shell or corner triangle occludes the complete dynamic rectangle','ok':not intrusions})
    item_raw=(ASSETS/'models/item/vintage_tv.json').read_bytes();item=json.loads(item_raw)
    matrix=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)
    vertices=np.concatenate([q.vertices for q in quads]);pixel=points(vertices/16,matrix)[:,:2]*[16,-16]+8
    checks.append({'name':'unchanged actual vanilla GUI matrix contains every model vertex','ok':bool(pixel.min()>=0 and pixel.max()<=16)})
    checks.append({'name':'actual model remains inside original single-cell collision and AV housing envelope','ok':bool(np.all(vertices.min(0)>=[.2,0,1.8]) and np.all(vertices.max(0)<=[15.8,14.3,14.2]))})
    source=(JAVA/'client/ArcadeBlockScreenRenderer.java').read_text(encoding='utf-8')
    checks.append({'name':'runtime Vintage branch uses actual layout and all uncropped UV corners','ok':runtime_frame_branch(source,'HOME_VINTAGE_TV','VintageTvLayout.screen(')})
    report={'ok':all(c['ok'] for c in checks),'checks':checks,'occluding_faces':intrusions,'production_stdout':stdout,
        'model_sha256':sha(raw),'item_sha256':sha(item_raw),'gui_bounds_pixels':[pixel.min(0).tolist(),pixel.max(0).tolist()],
        'actual_model_bounds':[vertices.min(0).tolist(),vertices.max(0).tolist()],
        'production_sha256':{str(p):sha(p.read_bytes()) for p in SOURCES},
        'limits':['Actual Java production frame and raw native mesh; offline QA, not Minecraft execution.',
                  'Triangle clipping certifies the full rectangle from straight ahead, not arbitrary oblique sightlines.',
                  'No automatic GUI fitting; diagnostic grid is not game content.']}
    return report,model,textures,data,matrix

def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');args=p.parse_args();report,model,textures,data,matrix=analyze()
    if args.write:
        if not report['ok']:raise ValueError('Refusing final previews: current model/presentation verification failed')
        output={OUT/path.name.replace('红棕老电视_','内嵌小彩电_'):content for path,content in shared_preview(model,textures,data,matrix).items()}
        report['preview_sha256']={str(path):sha(content) for path,content in output.items()};output[OUT/'vintage-alpha11-presentation-audit.json']=encoded(report);write_new(output)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
if __name__=='__main__':main()
