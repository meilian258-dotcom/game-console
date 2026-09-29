"""Offline z-buffer geometry/material QA of the compiled furniture meshes.

Minecraft textures are read from the existing 1.21.1 client resource JAR only
for previews. No Mojang texture is copied into the mod, and previews are not
claims of in-game or resource-pack integration testing.
"""
from __future__ import annotations

from collections import defaultdict
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from prepare_furniture36 import (AREA_EPS, EPS, INPUT, MODELS, OUTPUT, area,
    bounds, compile_mesh, cross, dot, load_model, mul, norm, overlaps, role,
    split, sub, union_faces)

MC_RESOURCES=Path("C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar")


def poly_intersection(a,b,normal):
    result=a
    for i,p in enumerate(b):
        edge=sub(b[(i+1)%len(b)],p)
        n=cross(edge,normal)
        result,_=split(result,n,dot(n,p))
        if not result: return []
    return result


def coplanar(polygons):
    planes=defaultdict(list)
    for face,p in polygons:
        if face.role != "wood": continue
        key=tuple(round(x,5) for x in face.normal)+(round(dot(face.normal,p[0]),5),)
        planes[key].append((face,p,bounds(p)))
    hits=[]
    for group in planes.values():
        for i,(face,p,bbox) in enumerate(group):
            for other,q,qbox in group[i+1:]:
                if not overlaps(bbox,qbox): continue
                overlap=area(poly_intersection(p,q,face.normal))
                if overlap > 1e-7:
                    hits.append({"first":face.name,"second":other.name,
                                 "face":face.direction,"overlapArea":overlap})
    return hits


def hull_area(points):
    pts=sorted(set((round(p[0],8),round(p[1],8)) for p in points))
    def turn(o,a,b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    def half(seq):
        result=[]
        for p in seq:
            while len(result)>=2 and turn(result[-2],result[-1],p)<=EPS:
                result.pop()
            result.append(p)
        return result
    hull=half(pts)[:-1]+half(pts[::-1])[:-1]
    return abs(sum(p[0]*hull[(i+1)%len(hull)][1]-p[1]*hull[(i+1)%len(hull)][0]
                   for i,p in enumerate(hull)))/2


def geometry_checks():
    reports={}
    for key,(relative,_) in MODELS.items():
        _,solids,faces=load_model(INPUT/relative)
        polygons,_=union_faces(solids,faces)
        original=coplanar([(f,f.points) for f in faces])
        remaining=coplanar(polygons)
        assert not remaining,(key,remaining[:5])
        caps=[]
        for prefix in sorted({s.member for s in solids if "横木_" in s.name}):
            for direction in ("east","west"):
                original_faces=[f for f in faces if f.name.startswith(prefix+"_") and f.direction==direction]
                result=[p for f,p in polygons if f.name.startswith(prefix+"_") and f.direction==direction]
                expected=hull_area([(p[1],p[2]) for f in original_faces for p in f.points])
                actual=sum(area(p) for p in result)
                assert abs(expected-actual)<1e-6,(key,prefix,direction,expected,actual)
                caps.append({"member":prefix,"face":direction,"expectedConvexHullArea":expected,
                             "actualNonOverlappingArea":actual})
        reports[key]={"originalCoplanarOverlaps":original,"remainingCoplanarOverlaps":remaining,
                      "crossbarEndCapCoverage":caps}
    return reports


def material_images(species,detail,resources,diagnostic=False):
    side=f"stripped_{species}_log"
    end=side+"_top"
    if species in ("crimson","warped"):
        side=f"stripped_{species}_stem";end=side+"_top"
    if species=="bamboo":
        side="stripped_bamboo_block";end=side+"_top"
    images={}
    for role_,name in (("wood_side",side),("wood_end",end)):
        path=f"assets/minecraft/textures/block/{name}.png"
        images[role_]=np.asarray(Image.open(io.BytesIO(resources.read(path))).convert("RGBA"))
    details=np.asarray(Image.open(detail).convert("RGBA"))
    images["cloth"]=details;images["metal"]=details
    if diagnostic:
        yy,xx=np.mgrid[0:16,0:16]
        tex=np.zeros((16,16,4),dtype=np.uint8)
        tex[:,:,:3]=np.where((((xx//2)+(yy//2))%2)[...,None],np.array([245,32,220]),np.array([12,245,64]))
        tex[:,:,3]=255
        images["wood_end"]=tex;images["wood_side"]=tex
    return images


def render(mesh,textures,direction=(1.0,0.85,1.3),size=(600,380),zoom=1.0):
    w,h=size
    camera=np.array(norm(direction))
    right=np.array(norm(cross((0,1,0),camera)))
    up=np.array(norm(cross(camera,right)))
    verts=np.array([p for g in mesh["groups"].values() for t in g["triangles"] for p in t["p"]])
    center=(verts.min(axis=0)+verts.max(axis=0))/2
    projected=np.column_stack(((verts-center)@right,-(verts-center)@up))
    scale=min((w-55)/(projected[:,0].max()-projected[:,0].min()),
              (h-55)/(projected[:,1].max()-projected[:,1].min()))*zoom
    out=np.full((h,w,3),(232,230,220),dtype=np.uint8)
    depth=np.full((h,w),-np.inf)
    light=np.array(norm((-0.2,1,0.65)))
    for material,group in mesh["groups"].items():
        tex=textures[material]
        th,tw,_=tex.shape
        for tri in group["triangles"]:
            normal=np.array(tri["n"])
            if normal@camera<=1e-8:continue
            pts=np.array(tri["p"])-center
            sx=pts@right*scale+w/2
            sy=-(pts@up)*scale+h/2
            sz=pts@camera
            x0,x1=max(0,math.floor(sx.min())),min(w-1,math.ceil(sx.max()))
            y0,y1=max(0,math.floor(sy.min())),min(h-1,math.ceil(sy.max()))
            if x1<x0 or y1<y0:continue
            yy,xx=np.mgrid[y0:y1+1,x0:x1+1];xx=xx+.5;yy=yy+.5
            denom=(sy[1]-sy[2])*(sx[0]-sx[2])+(sx[2]-sx[1])*(sy[0]-sy[2])
            if abs(denom)<1e-10:continue
            a=((sy[1]-sy[2])*(xx-sx[2])+(sx[2]-sx[1])*(yy-sy[2]))/denom
            b=((sy[2]-sy[0])*(xx-sx[2])+(sx[0]-sx[2])*(yy-sy[2]))/denom
            c=1-a-b
            z=a*sz[0]+b*sz[1]+c*sz[2]
            live=(a>=-1e-8)&(b>=-1e-8)&(c>=-1e-8)&(z>depth[y0:y1+1,x0:x1+1])
            if not live.any():continue
            uv=np.array(tri["uv"])
            u=a*uv[0,0]+b*uv[1,0]+c*uv[2,0]
            v=a*uv[0,1]+b*uv[1,1]+c*uv[2,1]
            tx=np.clip((u*tw).astype(int),0,tw-1);ty=np.clip((v*th).astype(int),0,th-1)
            samples=tex[ty,tx]
            live &= samples[:,:,3]>=128
            factor=.48+.52*max(0,normal@light)
            out[y0:y1+1,x0:x1+1][live]=np.clip(samples[:,:,:3][live]*factor,0,255).astype(np.uint8)
            depth[y0:y1+1,x0:x1+1][live]=z[live]
    return Image.fromarray(out)


def previews(meshes,detail,output):
    geometry=geometry_checks()
    (output/"surface-validation.json").write_text(json.dumps(geometry,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc",24)
    labels={"bench":"长条凳 · 原尺寸 2 格长","stool_open":"马扎 · 展开","stool_folded":"马扎 · 收起"}
    woodnames={"oak":"橡木","spruce":"云杉木","cherry":"樱花木","warped":"诡异木","bamboo":"竹材"}
    previews_=[]
    with zipfile.ZipFile(MC_RESOURCES) as resources:
        for species in ("oak","spruce","cherry","warped","bamboo"):
            textures=material_images(species,detail,resources)
            canvas=Image.new("RGB",(1800,435),(232,230,220));draw=ImageDraw.Draw(canvas)
            for i,(key,mesh) in enumerate(meshes.items()):
                canvas.paste(render(mesh,textures),(i*600,50))
                draw.text((i*600+24,14),woodnames[species]+" / "+labels[key],font=font,fill=(45,48,49))
            path=output/f"vanilla-{species}.png";canvas.save(path)
            previews_.append({"file":str(path),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
        diagnostic=material_images("oak",detail,resources,True)
        for key in ("stool_open","stool_folded"):
            path=output/f"diagnostic-{key}.png"
            render(meshes[key],diagnostic,(2.6,.5,.65),(1000,800)).save(path)
            previews_.append({"file":str(path),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
        # Same camera, same software rasterizer: isolate texture/source geometry
        # from Blockbench's renderer/antialiasing and from the union operation.
        _,_,faces=load_model(INPUT/MODELS["stool_open"][0])
        original={"groups":{r:{"triangles":[]} for r in ("wood_side","wood_end","cloth","metal")}}
        for face in faces:
            material="wood_side" if face.role=="wood" else face.role
            for indices in ((0,1,2),(0,2,3)):
                original["groups"][material]["triangles"].append({"p":[face.points[i] for i in indices],
                    "uv":[face.uv[i] for i in indices],"n":face.normal})
        atlas=np.asarray(Image.open(detail).convert("RGBA"))
        original_textures={r:atlas for r in original["groups"]}
        # Cloth maps and triangle positions are exactly unchanged (rounding only).
        original_cloth=original["groups"]["cloth"]["triangles"]
        final_cloth=meshes["stool_open"]["groups"]["cloth"]["triangles"]
        assert len(original_cloth)==len(final_cloth)
        max_cloth_delta=max(abs(float(x)-float(y))
            for a,b in zip(original_cloth,final_cloth)
            for field in ("p","uv") for va,vb in zip(a[field],b[field]) for x,y in zip(va,vb))
        assert max_cloth_delta<=5.01e-9
        comparison=Image.new("RGB",(1600,710),(232,230,220));draw=ImageDraw.Draw(comparison)
        draw.text((24,12),"原几何 + 原图集（相同 CPU 视角）",font=font,fill=(45,48,49))
        draw.text((824,12),"木材裁剪 + 原版材质（布带几何/UV不变）",font=font,fill=(45,48,49))
        comparison.paste(render(original,original_textures,size=(800,660)),(0,50))
        comparison.paste(render(meshes["stool_open"],material_images("oak",detail,resources),size=(800,660)),(800,50))
        path=output/"stool-source-comparison.png";comparison.save(path)
        previews_.append({"file":str(path),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
    return {"minecraftClientResources":str(MC_RESOURCES),"renderMethod":"CPU orthographic z-buffer, nearest-neighbour actual vanilla pixels, static directional light",
            "images":previews_,"postUnionCoplanarOverlaps":0,"inGameResourcePackReloadTested":False,
            "clothTrianglesBeforeAfter":len(original_cloth),"clothGeometryAndUVMaximumRoundingDelta":max_cloth_delta,
            "clothAppearanceNote":"Original weave gaps and light yarn pixels remain in identical source-geometry CPU rendering; anti-alias differs from source Blockbench preview."}
