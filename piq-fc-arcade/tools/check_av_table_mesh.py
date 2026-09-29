"""Read-only historical alpha6/alpha7 actual Java cable mesh audit.

No second route implementation. Compile only the probe and execute immutable
released alpha6/alpha7 JARs. Alpha8 development uses check_av_smooth_mesh.py.
"""
from __future__ import annotations
import argparse
import collections
import io
import json
import subprocess
import tempfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from import_subor_hardware import ASSETS,CATEGORY,encoded,sha,write_new
from render_rocket_arcade_preview import Quad,collect_quads,render_view

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'src/main/java/cn/piq/fcarcade'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
OUT=CATEGORY/'AV贴桌修正-alpha7'
BASELINE=CATEGORY/'piq_fc_arcade-0.31.0-alpha.6.jar'
BASELINE_SHA='BF4DF8ECC2F7D4F542ADB3843CAE35AB446B11809A8DD3AF35FAB50906D0FE78'
REVIEWED=CATEGORY/'piq_fc_arcade-0.31.0-alpha.7.jar'
REVIEWED_SHA='B2DAF27EA28F33A7DFA5138E6D324BE15BECDE140305C6FD280B3542F3FA52A4'
SOURCES=[JAVA/'home/HomeConsoleLayout.java',JAVA/'home/HomeHardwareScale.java',
         JAVA/'client/HomeHardwareRenderLayout.java',JAVA/'client/HomeAvCableLayout.java',JAVA/'client/HomeAvCableMesh.java']
PROBE=ROOT/'tools/qa/AvTableMeshProbe.java'
COLORS=(0xE6B52C,0xDEDFD7,0xAC2828)


def run_probe(classpath,setup):
    raw=subprocess.run([str(JDK/'java.exe'),'-cp',classpath,'AvTableMeshProbe',*[str(v) for v in setup]],
                       check=True,capture_output=True,timeout=30).stdout
    return json.loads(raw)


def metrics(data):
    if not data['mesh']:return {'empty':True}
    all_points=np.array([face[1:] for face in data['mesh']]);route=np.array(data['route'])
    path=route[1:-1];trunk_count=(len(route)-3)*8+16
    lengths=np.linalg.norm(np.diff(path[:,[0,2]],axis=0),axis=1)
    flat=np.maximum(path[:-1,1],path[1:,1])<=.027000001
    counts=collections.Counter(face[0] for face in data['mesh'])
    return {'empty':False,'quads':len(data['mesh']),'trunk_quads':trunk_count,'route_points':len(route),
            'actual_surface_min_y':float(all_points[:,:,1].min()),'actual_surface_max_y':float(all_points[:,:,1].max()),
            'trunk_min_y':float(all_points[:trunk_count,:,1].min()),'horizontal_trunk_length':float(sum(lengths)),
            'table_contact_length_fraction':float(sum(lengths[flat])/sum(lengths)),
            'colored_quad_counts':{f'{color:06X}':counts[color] for color in COLORS},
            'six_plugs_color_contract':all(counts[color]==96 for color in COLORS),
            'start_fanout_junction':path[0].tolist(),'end_fanout_junction':path[-1].tolist()}


def model_scene():
    result=[];textures={}
    for name,offset in (('home_famicom_console',[0,0,0]),('home_retro_tv',[-64,0,0])):
        model=json.loads((ASSETS/'models/block'/f'{name}.json').read_bytes())
        for q in collect_quads(model):
            result.append(Quad(q.vertices+offset,q.uv,q.texture,q.element_index,q.direction))
            if q.texture not in textures:
                namespace,path=q.texture.split(':',1)
                textures[q.texture]=np.array(Image.open(ASSETS/'textures'/f'{path}.png').convert('RGBA'))
    board=np.full((128,128,4),(104,106,102,255),dtype=np.uint8)
    for x in range(128):
        for z in range(128):
            if (x//16+z//16)%2:board[z,x]=[118,120,114,255]
            if x%16==0 or z%16==0:board[z,x]=[80,83,79,255]
    textures['table']=board
    table=Quad(np.array([[-84,0,-8],[-84,0,64],[36,0,64],[36,0,-8]],dtype=float),
               np.array([[0,0],[0,16],[16,16],[16,0]],dtype=float),'table',0,'table')
    return result,textures,table


def cable_quads(data,textures):
    result=[]
    for index,face in enumerate(data['mesh']):
        color=face[0];key=f'cable_{color:06x}'
        textures[key]=np.array([[[color>>16&255,color>>8&255,color&255,255]]],dtype=np.uint8)
        result.append(Quad(np.array(face[1:])*16,np.full((4,2),8.),key,index,'actual_java_pipe'))
    return result


def preview(before,after,comparison):
    objects,textures,table=model_scene()
    canvas=Image.new('RGB',(1880,1320),'#e7ecee');draw=ImageDraw.Draw(canvas)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',30)
    draw.text((24,18),'AV 贴桌修正 · 已发布 alpha6 与当前实际 Java 网格',font=title,fill='#253845')
    details=[]
    for column,(label,data,stats) in enumerate((('alpha6：长段悬空',before,comparison['before']),('当前：中段落至桌面',after,comparison['after']))):
        x=column*940
        draw.text((x+24,68),label,font=font,fill='#253845')
        cable=cable_quads(data,textures)
        # Identical table, devices, viewpoint and canvas bounds enforce equal framing.
        for row,direction in enumerate(((.7,.65,1.8),(0,.035,1))):
            quads=[table]+objects+cable if row==0 else [table]+cable
            image,view=render_view(quads,textures,direction,size=(920,510),supersample=2)
            backdrop=Image.new('RGBA',image.size,'#e7ecee');backdrop.alpha_composite(image)
            canvas.paste(backdrop.convert('RGB'),(x+10,105+row*545));details.append(view)
        draw.text((x+24,1175),f'主体贴桌长度：{stats["table_contact_length_fraction"]:.1%}；管底最低 {stats["actual_surface_min_y"]:.4f} 格',font=font,fill='#253845')
    draw.text((24,1232),'两端仍从插口自然伸出再下落；三色各两组。下排省去机壳显示真实线身低侧视，没有改动网格。',font=font,fill='#536774')
    draw.text((24,1272),'离线几何渲染，非 Minecraft 截图；仅同高度支撑面贴桌，异高度仍按桥接方式显示。',font=font,fill='#536774')
    outputs={}
    for extension in ('png','jpg'):
        buffer=io.BytesIO();canvas.save(buffer,format='PNG' if extension=='png' else 'JPEG',**({} if extension=='png' else {'quality':92,'optimize':True}))
        outputs[OUT/f'AV贴桌_实际线身与alpha6对比.{extension}']=buffer.getvalue()
    return outputs,details


def analyze():
    if sha(BASELINE.read_bytes())!=BASELINE_SHA:raise ValueError('Released alpha6 JAR hash changed')
    if sha(REVIEWED.read_bytes())!=REVIEWED_SHA:raise ValueError('Released alpha7 JAR hash changed')
    snapshots={str(path):sha(path.read_bytes()) for path in [BASELINE,REVIEWED,PROBE]}
    rows=[];samples={};findings=[]
    with tempfile.TemporaryDirectory(prefix='piq-av-table-qa-') as directory:
        current=Path(directory)/'current';old=Path(directory)/'old';current.mkdir();old.mkdir()
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-cp',str(REVIEWED),'-d',str(current),str(PROBE)],check=True,capture_output=True,timeout=30)
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-cp',str(BASELINE),'-d',str(old),str(PROBE)],check=True,capture_output=True,timeout=30)
        # Each console family, both CRT layouts and LCD, plus rotated broad placements.
        setups=[(console,tv,turn,turn,-4,0,0) for console in range(3) for tv in range(3) for turn in range(4)]
        for setup in setups:
            data=run_probe(str(current)+';'+str(REVIEWED),setup);result=metrics(data)
            rows.append({'setup':setup,**result})
            if result['empty'] or result['actual_surface_min_y'] < -1e-9 or not result['six_plugs_color_contract']:
                findings.append({'setup':setup,'problem':'empty/below table/missing colored plug','metrics':result})
            if setup==(0,0,0,0,-4,0,0):samples['after']=data
        samples['before']=run_probe(str(old)+';'+str(BASELINE),(0,0,0,0,-4,0,0))
    comparison={key:metrics(data) for key,data in samples.items()}
    if comparison['after']['table_contact_length_fraction']<.7:findings.append('FC/CRT trunk contact below 70%')
    if comparison['after']['table_contact_length_fraction']<=comparison['before']['table_contact_length_fraction']:
        findings.append('Trunk contact not improved over released alpha6')
    for path,value in snapshots.items():
        if sha(Path(path).read_bytes())!=value:raise RuntimeError('Source changed during probe: '+path)
    report={'ok':not findings,'read_only':True,'mode':'frozen_alpha7_jar','input_sha256':snapshots,
            'alpha6_jar_sha256':BASELINE_SHA,'alpha7_jar_sha256':REVIEWED_SHA,
            'current_placements_checked':len(rows),'actual_java_mesh_metrics':rows,'comparison_fc_crt_four_blocks':comparison,
            'findings':findings,'limits':['Uses actual compiled production route and tube quads; not Minecraft playtest',
                'Preview supports a common planar table only; no inference about real world blocks between devices',
                'No production/appearance resources or existing QA reports are modified']}
    return report,samples


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    if args.write:parser.error('Historical alpha7 preview/report is frozen; use alpha8 check_av_smooth_mesh.py for new artifacts')
    report,samples=analyze()
    if args.write:
        outputs,views=preview(samples['before'],samples['after'],report['comparison_fc_crt_four_blocks'])
        outputs[OUT/'java网格_当前.json']=encoded(samples['after']);outputs[OUT/'java网格_alpha6.json']=encoded(samples['before'])
        report['preview_views']=views;report['output_sha256']={str(path):sha(data) for path,data in outputs.items()}
        outputs[OUT/'实际AV网格贴桌校验.json']=encoded(report)
        write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
