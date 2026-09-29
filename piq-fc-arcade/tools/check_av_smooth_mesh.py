"""Alpha8 actual Java pipe-surface smoothness audit, with immutable alpha7 comparison.

Uses generated quads and reconstructs ring centers/cap normals for measurements.
Does not implement another route/curve generator and does not edit raster assets.
"""
from __future__ import annotations
import argparse
import io
import json
import subprocess
import tempfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from check_av_table_mesh import SOURCES as LEGACY_PROBE_SOURCES,PROBE,JDK,run_probe,metrics,model_scene,cable_quads
from import_subor_hardware import CATEGORY,encoded,sha,write_new
from render_rocket_arcade_preview import Quad,render_view

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'src/main/java/cn/piq/fcarcade'
# Current-source mode needs the new TV layout dependencies. Historical JAR mode
# still compiles only the unchanged probe against its explicitly verified JAR.
SOURCES=LEGACY_PROBE_SOURCES+[JAVA/'layout/RocketArcadeGeometry.java',
                            JAVA/'home/LargeLcdTvLayout.java',JAVA/'home/VintageTvLayout.java']
BASELINE=CATEGORY/'piq_fc_arcade-0.31.0-alpha.7.jar'
BASELINE_SHA='B2DAF27EA28F33A7DFA5138E6D324BE15BECDE140305C6FD280B3542F3FA52A4'
OUT=CATEGORY/'AV圆滑分线-alpha8'


def smooth_metrics(data):
    result=metrics(data)
    if result['empty']:return result
    route=np.asarray(data['route']);faces=np.asarray([face[1:] for face in data['mesh']]);segments=len(route)-3
    rings=[faces[i*8:(i+1)*8,0].mean(0) for i in range(segments)]
    rings.append(faces[(segments-1)*8:segments*8,3].mean(0));rings=np.asarray(rings)
    if not np.allclose(rings,route[1:-1],atol=1e-8,rtol=0):raise ValueError('Actual tube rings do not follow reported route')
    differences=np.diff(rings,axis=0);directions=differences/np.linalg.norm(differences,axis=1)[:,None]
    angles=np.degrees(np.arccos(np.clip(np.sum(directions[:-1]*directions[1:],axis=1),-1,1)))
    joint=[]
    for endpoint,index,neighbor in (('console',0,1),('tv',-1,-2)):
        center=rings[index];target=rings[neighbor]-center;target/=np.linalg.norm(target)
        for q in faces:
            if np.linalg.norm(q[0]-center)>1e-8 or np.linalg.norm(q[3]-center)>1e-8:continue
            radius=np.linalg.norm(q[1]-center)
            if min(abs(radius-.011),abs(radius-.011*.65))>1e-8:continue
            normal=np.cross(q[1]-q[0],q[2]-q[0]);normal/=np.linalg.norm(normal)
            joint.append({'endpoint':endpoint,'degrees':float(np.degrees(np.arccos(np.clip(normal@target,-1,1))))})
    if len(joint)!=48:raise ValueError('Missing one of six real branch terminal rings')
    result.update(actual_trunk_ring_count=len(rings),maximum_trunk_ring_turn_degrees=float(angles.max(initial=0)),
                  branch_terminal_rings_checked=6,maximum_branch_to_trunk_angle_degrees=max(v['degrees'] for v in joint))
    return result


def analyze(jar=None,expected=None):
    if sha(BASELINE.read_bytes())!=BASELINE_SHA:raise ValueError('Released alpha7 JAR changed')
    if jar and (not expected or sha(jar.read_bytes())!=expected.upper()):raise ValueError('Final JAR requires exact expected hash')
    snapshots={str(path):sha(path.read_bytes()) for path in SOURCES+[PROBE]}
    rows=[];samples={};findings=[]
    with tempfile.TemporaryDirectory(prefix='piq-av-smooth-qa-') as directory:
        current=Path(directory)/'current';old=Path(directory)/'old';current.mkdir();old.mkdir()
        args=[str(JDK/'javac.exe'),'-encoding','UTF-8','-d',str(current)]
        args+=['-cp',str(jar)] if jar else [str(p) for p in SOURCES]
        subprocess.run(args+[str(PROBE)],check=True,capture_output=True,timeout=30)
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-cp',str(BASELINE),'-d',str(old),str(PROBE)],check=True,capture_output=True,timeout=30)
        cp=str(current)+(';' + str(jar) if jar else '')
        setups=[(console,tv,turn,turn,-4,0,0) for console in range(3) for tv in range(3) for turn in range(4)]
        for setup in setups:
            data=run_probe(cp,setup);stats=smooth_metrics(data);rows.append({'setup':setup,**stats})
            if stats['empty'] or stats['actual_surface_min_y']< -1e-9 or not stats['six_plugs_color_contract']:
                findings.append({'setup':setup,'problem':'invisible / below tabletop / missing plugs'})
            elif stats['maximum_branch_to_trunk_angle_degrees']>.001:
                findings.append({'setup':setup,'problem':'branch terminal is not tangent-continuous','angle':stats['maximum_branch_to_trunk_angle_degrees']})
            if setup==(0,0,0,0,-4,0,0):samples['after']=data
        samples['before']=run_probe(str(old)+';'+str(BASELINE),(0,0,0,0,-4,0,0))
    comparison={name:smooth_metrics(data) for name,data in samples.items()}
    if comparison['after']['maximum_branch_to_trunk_angle_degrees']>=comparison['before']['maximum_branch_to_trunk_angle_degrees']:
        findings.append('Actual branch-to-trunk joint angle did not improve')
    for path,digest in snapshots.items():
        if sha(Path(path).read_bytes())!=digest:raise RuntimeError('Source changed during audit: '+path)
    if jar and sha(jar.read_bytes())!=expected.upper():raise ValueError('JAR changed during actual geometry audit')
    report={'ok':not findings,'source_sha256':snapshots,'alpha7_jar_sha256':BASELINE_SHA,
            'mode':'final_jar' if jar else 'source','jar_sha256':expected.upper() if jar else None,
            'actual_java_mesh_metrics':rows,'current_placements_checked':len(rows),'comparison':comparison,'findings':findings,
            'limits':['Actual compiled Java quads and cap normals, not an independent reimplementation of their curves.',
                      'Pipe-vs-housing triangle SAT and full 864 separated / close-spacing matrices are covered by production Java regression tests.',
                      'Same-base tabletop inference only; different-height geometry remains a suspended bridge, not stepped-terrain following.',
                      'Offline generated geometry preview, not a Minecraft screenshot.']}
    return report,samples


def preview(samples,report):
    objects,textures,table=model_scene()
    canvas=Image.new('RGB',(1840,1590),'#e7ecee');draw=ImageDraw.Draw(canvas)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22);title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',29)
    draw.text((24,16),'AV 圆滑分线 · alpha7 / alpha8 实际 Java 管面同尺度对照',font=title,fill='#253845')
    details=[]
    for column,(label,name) in enumerate((('alpha7：尖点分线、硬折主线','before'),('alpha8：三头同切向归束、主线圆角','after'))):
        x=column*920;data=samples[name];cable=cable_quads(data,textures)
        draw.text((x+20,63),label,font=font,fill='#253845')
        for row,direction in enumerate(((.35,.55,1.8),(0,.065,1))):
            quads=[table]+objects+cable if row==0 else [table]+cable
            image,view=render_view(quads,textures,direction,size=(900,420),supersample=2)
            canvas.paste(image.convert('RGB'),(x+10,105+row*448));details.append(view)
        # Endpoint close-up: retain actual connector/pipe quads within one fixed
        # spatial window; omit the broad housing so framing cannot hide the joint.
        low=np.array([-59,0,27]);high=np.array([-37,12,47])
        close=[q for q in cable+objects if (q.vertices.min(0)>=low).all() and (q.vertices.max(0)<=high).all()]
        textures['close_table']=np.full((1,1,4),(105,117,121,255),dtype=np.uint8)
        close.append(Quad(np.array([[-59,0,27],[-59,0,47],[-37,0,47],[-37,0,27]],float),np.zeros((4,2)),'close_table',-1,'reference'))
        image,view=render_view(close,textures,(0,1,.7),size=(900,425),supersample=2)
        canvas.paste(image.convert('RGB'),(x+10,1001));details.append(view)
        stats=report['comparison'][name]
        draw.text((x+20,1435),f'分线 / 主线夹角最大 {stats["maximum_branch_to_trunk_angle_degrees"]:.2f}°',font=font,fill='#253845')
        draw.text((x+20,1474),f'主线相邻管环折角最大 {stats["maximum_trunk_ring_turn_degrees"]:.2f}°',font=font,fill='#253845')
    draw.text((24,1524),'下排为电视端真实管面近景（省去大机壳）；同层桌面中心净距仍为 0.027 格。',font=font,fill='#52636f')
    draw.text((24,1557),'没有用示意曲线替代导出的实体管面；离线模型渲染，非游戏截图。',font=font,fill='#52636f')
    outputs={}
    for ext in ('png','jpg'):
        buffer=io.BytesIO();canvas.save(buffer,format='PNG' if ext=='png' else 'JPEG',**({} if ext=='png' else {'quality':92,'optimize':True}))
        outputs[OUT/f'AV圆滑分线_实际网格前后近景.{ext}']=buffer.getvalue()
    return outputs,details


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true')
    parser.add_argument('--jar',type=Path);parser.add_argument('--jar-sha256');parser.add_argument('--report',type=Path);args=parser.parse_args()
    report,samples=analyze(args.jar,args.jar_sha256)
    if args.write:
        if args.jar:parser.error('--write previews are source-stage only; final JAR uses --report')
        outputs,views=preview(samples,report)
        outputs[OUT/'java网格_alpha7.json']=encoded(samples['before']);outputs[OUT/'java网格_alpha8.json']=encoded(samples['after'])
        report['preview_views']=views;report['output_sha256']={str(p):sha(data) for p,data in outputs.items()}
        outputs[OUT/'实际圆滑AV管面校验.json']=encoded(report);write_new(outputs)
    if args.report:
        if not args.jar or args.report.exists() or args.report.parent.name!='家用FC-0.31.0-alpha.8-模型预览':parser.error('Final report must be a new file in alpha8 preview folder')
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_bytes(encoded(report))
        print('REPORT_SHA256='+sha(args.report.read_bytes()))
    print(json.dumps({key:value for key,value in report.items() if key not in ('actual_java_mesh_metrics','source_sha256','preview_views','output_sha256')},ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
