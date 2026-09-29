"""Actual JSON/UV + vanilla hand-volume perspective QA; not a Minecraft screenshot.

No production assets are generated. Source key bounds/travel and active pose
constants are read rather than duplicating them. Optional images are deterministic
perspective-correct UV/depth renders; solid hand colors are diagnostic stand-ins
for player skins. Historical -16/-38/24 values explicitly represent alpha9.
"""
from __future__ import annotations
import argparse, hashlib, itertools, json, math, re
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
import check_controller_pose_pipeline as pose
from render_rocket_arcade_preview import texture_path, font

FACES = ((0,1,3,2),(4,6,7,5),(0,4,5,1),(2,3,7,6),(0,2,6,4),(1,5,7,3))
UNIT = pose.scale([.85/16]*3) @ pose.translation(-8,-8,-8)


def source_caps(subor):
    source=(pose.JAVA/'ControllerButtonGeometry.java').read_text(encoding='utf-8')
    body=re.search(r'private static final Box\[\] '+('SB' if subor else 'FC')+r'=\{(.*?)\};',source,re.S)[1]
    boxes=[np.array([float(x) for x in values.split(',')]).reshape(2,3)
           for values in re.findall(r'new Box\(([^)]+)\)',body)]
    travel=re.search(r'subor\?([.\d]+):([.\d]+);',source)[1 if subor else 2]
    return boxes,float(travel)


def rig(pitch, swing=0, right=True, two=True):
    source=(pose.JAVA/'ControllerPoseLayout.java').read_text(encoding='utf-8')
    return pose.first_transform(right,two,swing,*[pose.java_number(source,k) for k in
        ('FIRST_IDLE_Y','FIRST_TWO_HAND_Z','FIRST_MIXED_Z')],pitch=pitch)


def meshes(subor,port):
    if subor:
        path=pose.ASSETS/'meshes/home_subor_sb926.json';data=json.loads(path.read_bytes())
        tex=np.array(Image.open(pose.ASSETS/ (data['texture'].split(':',1)[1])).convert('RGBA'))
        faces=[(np.array(t['p']),np.array(t['uv']),tex) for t in data['groups'][f'p{port+1}_held']['triangles']]
    else:
        path=pose.ASSETS/f'models/block/home_controller_p{port+1}_held.json';data=json.loads(path.read_bytes());faces=[]
        for q in pose.collect_quads(data):
            v=q.vertices.copy()
            v=np.c_[16-v[:,2],v[:,1],v[:,0]] if port==0 else np.c_[v[:,2],v[:,1],16-v[:,0]]
            faces.append((v,q.uv/16,np.array(Image.open(texture_path(pose.ASSETS.parent,q.texture)).convert('RGBA'))))
    return faces,hashlib.sha256(path.read_bytes()).hexdigest().upper()


def project(v, fov=70, aspect=16/9):
    assert np.all(v[:,2]<-.05)
    d=-v[:,2]*math.tan(math.radians(fov/2))
    return np.c_[.5+v[:,0]/d/aspect/2,.5-v[:,1]/d/2]


def hit(target, triangle):
    a,b,c=triangle;e1,e2=b-a,c-a;q=np.cross(target,e2);det=float(e1@q)
    if abs(det)<1e-11:return False
    s=-a;u=float(s@q/det)
    if u<0 or u>1:return False
    q=np.cross(s,e1);v=float(target@q/det);t=float(e2@q/det)
    return v>=0 and u+v<=1 and 0<t<.99999


def arm_triangles(vertices):
    return [vertices[list(tri)] for a,b,c,d in FACES for tri in [(a,b,c),(a,c,d)]]


def raster(canvas,depth,v,uv,texture):
    # Perspective-correct 1/z interpolation, nearest source texel + cutout.
    h,w=depth.shape;s=project(v)*[w,h];inv=-1/v[:,2]
    left=max(0,int(np.floor(s[:,0].min())));right=min(w,int(np.ceil(s[:,0].max())))
    top=max(0,int(np.floor(s[:,1].min())));bottom=min(h,int(np.ceil(s[:,1].max())))
    if left>=right or top>=bottom:return
    (x0,y0),(x1,y1),(x2,y2)=s;den=(y1-y2)*(x0-x2)+(x2-x1)*(y0-y2)
    if abs(den)<1e-10:return
    xx,yy=np.meshgrid(np.arange(left,right)+.5,np.arange(top,bottom)+.5)
    a=((y1-y2)*(xx-x2)+(x2-x1)*(yy-y2))/den;b=((y2-y0)*(xx-x2)+(x0-x2)*(yy-y2))/den;c=1-a-b
    z=a*inv[0]+b*inv[1]+c*inv[2];dv=depth[top:bottom,left:right]
    good=(a>=-1e-8)&(b>=-1e-8)&(c>=-1e-8)&(z>dv+1e-10)
    if not good.any():return
    rows,cols=np.nonzero(good);weights=np.c_[a[good],b[good],c[good]]*inv
    texuv=weights@uv/z[good,None];tx=np.clip((texuv[:,0]*texture.shape[1]).astype(int),0,texture.shape[1]-1);ty=np.clip((texuv[:,1]*texture.shape[0]).astype(int),0,texture.shape[0]-1)
    color=texture[ty,tx].copy();keep=color[:,3]>=26;rows,cols=rows[keep],cols[keep];color=color[keep];color[:,3]=255
    # Directional fill light, only for readable side depth; no source PNG change.
    normal=np.cross(v[1]-v[0],v[2]-v[0]);normal/=max(np.linalg.norm(normal),1e-12)
    color[:,:3]=(color[:,:3]*(.72+.28*abs(normal@np.array([.25,.7,.669])))).clip(0,255).astype('uint8')
    canvas[top+rows,left+cols]=color;dv[rows,cols]=z[good][keep]


def render(faces, subor, port, pitch, old=False, pressed=False):
    w,h=1920,1080;canvas=np.full((h,w,4),[223,230,236,255],dtype=np.uint8);depth=np.full((h,w),-np.inf)
    matrix=rig(pitch);boxes,travel=source_caps(subor)
    for raw,uv,tex in faces:
        v=raw.copy()
        # Production Subor upper/lower buttons in each column share A/B masks.
        moving=boxes[1:3]+(boxes[5:7] if subor else [])
        if pressed and any(np.all(v>=box[0]-.0001) and np.all(v<=box[1]+.0001) for box in moving):v[:,2]-=travel
        v=pose.points(v,matrix@UNIT)
        for tri in [(0,1,2)]+([(0,2,3)] if len(v)==4 else []):raster(canvas,depth,v[list(tri)],uv[list(tri)],tex)
    for right in (True,False):
        v=pose.points(pose.first_arm_vertices(right,False,True,1.18,historical=old),matrix)
        for i,(a,b,c,d) in enumerate(FACES):
            # Skin-neutral diagnostic colors; actual default/slim arm dimensions.
            tex=np.full((2,2,4),[181,134,95,255] if i==5 else [86,111,144,255],dtype=np.uint8)
            uv=np.array([[0,0],[0,1],[1,1],[1,0]])
            for tri in [(0,1,2),(0,2,3)]:raster(canvas,depth,v[[a,b,c,d]][list(tri)],uv[list(tri)],tex)
    return Image.fromarray(canvas).convert('RGB')


def check(output, images=True):
    source=(pose.JAVA/'ControllerPoseLayout.java').read_text(encoding='utf-8');pitch=pose.java_number(source,'FIRST_PITCH')
    arm_pitch=pose.java_number(source,'FIRST_ARM_PITCH');arm_roll=pose.java_number(source,'FIRST_ARM_ROLL')
    assert pitch==-60 and arm_pitch==30 and arm_roll==60
    reports=[];occlusion=[];comparisons=[];near=-100;handtop=2
    for subor,port in itertools.product((False,True),(0,1)):
        faces,sha=meshes(subor,port);raw=np.concatenate([v for v,_,_ in faces]);boxes,travel=source_caps(subor)
        scenarios=[]
        for right,two,swing,fov,aspect in itertools.product((True,False),(True,False),(0,.05,.125,.25,.5,.75,1),(60,70),(4/3,16/9)):
            v=pose.points(raw,rig(pitch,swing,right,two)@UNIT);ndc,bounds=pose.projected_bounds(v,fov,aspect)
            # Taller Subor case: keep >=1% bottom clearance in water; FC retains
            # its separately reviewed >=2% conservative-box margin.
            assert np.max(abs(ndc))<1 and bounds[1]>.69 and bounds[3]<(.99 if subor else .98)
            scenarios.append(bounds)
        reports.append({'subor':subor,'port':port,'model_sha256':sha,'perspective_scenarios':len(scenarios),'min_top':min(b[1] for b in scenarios),'max_bottom':max(b[3] for b in scenarios)})
        for right,slim,size,swing in itertools.product((True,False),(True,False),(1,1.18,1.3),(0,.05,.125,.25,.5,.75,1)):
            matrix=rig(pitch,swing,right);arms=[pose.points(pose.first_arm_vertices(r,slim,True,size),matrix) for r in (True,False)]
            triangles=[t for arm in arms for t in arm_triangles(arm)]
            near=max(near,max(a[:,2].max() for a in arms));handtop=min(handtop,min(pose.projected_bounds(a,70,16/9)[1][1] for a in arms))
            for part,box in enumerate(boxes,1):
                if not subor and port==1 and part>=4:continue
                key=(box[0]+box[1])/2;key[2]=box[1,2]
                for down in (0,1):
                    test=key.copy();test[2]-=down*travel if part!=1 else 0
                    target=pose.points([test],matrix@UNIT)[0];blocked=any(hit(target,t) for t in triangles)
                    assert not blocked,(subor,port,right,slim,size,swing,part,down,'key center occluded')
                    occlusion.append(1)
        for candidate in (-16,-55,-60,-65):
            matrix=rig(candidate);v=pose.points(raw,matrix@UNIT);_,bounds=pose.projected_bounds(v,70,16/9)
            key=(boxes[1][0]+boxes[1][1])/2;key[2]=boxes[1][1,2]
            keys=pose.points([key,key+[0,0,-travel]],matrix@UNIT);delta=(project(keys)[1]-project(keys)[0])*[1920,1080]
            comparisons.append({'subor':subor,'port':port,'pitch':candidate,'screen_ltrb_70_16_9':bounds,'A_down_pixels_at_1080':float(np.linalg.norm(delta))})
        if images:
            sheet=Image.new('RGB',(1920,920),(243,245,247));draw=ImageDraw.Draw(sheet)
            draw.text((28,16),f"{'小霸王' if subor else 'FC'} P{port+1} · 真实模型 / UV + 原版方手体积",font=font(27,True),fill=(25,35,48))
            specs=[(-16,True,False,'alpha9 · 原直立握持'),(pitch,False,False,'alpha10 · 30° 斜托'),(pitch,False,True,'alpha10 · A/B 完全下压')]
            for i,(angle,old,pressed,label) in enumerate(specs):
                image=render(faces,subor,port,angle,old,pressed)
                # Crop exact lower viewport, never an invented camera orientation.
                crop=image.crop((540,720,1380,1080)).resize((620,266),Image.Resampling.LANCZOS)
                x=20+i*630;sheet.paste(crop,(x,100));draw.text((x+12,64),label,font=font(20,True),fill=(35,45,60))
                full=image.resize((620,349),Image.Resampling.LANCZOS);sheet.paste(full,(x,410))
            draw.text((28,796),'上：同一相机底部裁切放大；下：完整 16:9 / 70° 手部相机。',font=font(22),fill=(45,55,68))
            draw.text((28,837),'离线透视预览，不是游戏截图；手臂颜色仅作几何参考，无玩家皮肤、游戏光照和输入时序。',font=font(20),fill=(45,55,68))
            sheet.save(output/f"{'subor' if subor else 'fc'}-p{port+1}-slanted-comparison.png")
    assert near<-.05 and handtop>.695
    result={'ok':True,'production_pitch':pitch,'local_wrist_pitch':arm_pitch,'local_wrist_roll':arm_roll,'models':reports,'cap_center_sleeve_triangle_rays':len(occlusion),'arm_closest_z':float(near),'arm_min_top_all_sizes':float(handtop),'candidate_comparisons':comparisons,'limits':['Offline numerical/render QA, not an actual Minecraft screenshot or input playtest','Key centers checked against actual vanilla hand/sleeve closed volumes; does not claim every edge texel of every cap is unobstructed','70 degree hand FOV plus approximate 60 degree water case, 4:3 and 16:9; other mods may override the projection','Third person unchanged and checked separately by check_controller_pose_pipeline.py','Fully equipped object; intentional equip-in/out lowers the item outside view']}
    (output/'slanted-pose-validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--no-images',action='store_true');args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    result=check(args.output,not args.no_images);print(json.dumps(result,ensure_ascii=True))
