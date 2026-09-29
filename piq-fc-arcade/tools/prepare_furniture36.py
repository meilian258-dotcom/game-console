"""Convert the untouched bench/stool handoff into material-role triangle meshes.

This is a model compiler, not an image editor: wood surfaces never sample the
owned source atlas (which is retained unchanged for cloth and metal only).
All cuboids are unioned by subtracting the other convex volumes from each face;
coplanar outward faces receive one deterministic owner. Coordinates stay in the
author's 16-units-per-block system. Run with --preview for offline texture QA.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
import hashlib
import io
import json
import math
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "piq-fc-arcade"
INPUT = ROOT / "model-handoffs/bench-stool-20260913-1710/original"
ASSETS = PROJECT / "src/main/resources/assets/piq_fc_arcade"
OUTPUT = ROOT / "outputs/furniture36/models"
EPS = 2e-7
AREA_EPS = 1e-9
MODELS = {
    "bench": ("01_长条凳/老式木长凳_像素木纹版.bbmodel", 111),
    "stool_open": ("02_小马扎/小马扎_展开_修正版.bbmodel", 88),
    "stool_folded": ("02_小马扎/小马扎_收起_修正版.bbmodel", 131),
}


def add(a, b): return tuple(x+y for x, y in zip(a, b))
def sub(a, b): return tuple(x-y for x, y in zip(a, b))
def mul(a, s): return tuple(x*s for x in a)
def dot(a, b): return sum(x*y for x, y in zip(a, b))
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def norm(v): return mul(v, 1 / math.sqrt(dot(v, v)))
def rounded(v): return [round(x, 8) for x in v]


def rotate(v, angles):
    x, y, z = v
    for axis, angle in enumerate(angles):
        c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        if axis == 0: y, z = y*c-z*s, y*s+z*c
        if axis == 1: x, z = x*c+z*s, -x*s+z*c
        if axis == 2: x, y = x*c-y*s, x*s+y*c
    return x, y, z


def area(poly):
    if len(poly) < 3: return 0.0
    return sum(math.sqrt(dot(c, c))/2 for c in
               (cross(sub(poly[i], poly[0]), sub(poly[i+1], poly[0])) for i in range(1, len(poly)-1)))


def bounds(points):
    return [tuple(min(p[i] for p in points) for i in range(3)),
            tuple(max(p[i] for p in points) for i in range(3))]


def overlaps(a, b):
    return all(a[0][i] <= b[1][i]+EPS and a[1][i] >= b[0][i]-EPS for i in range(3))


def clean(poly):
    result = []
    for p in poly:
        if not result or dot(sub(p, result[-1]), sub(p, result[-1])) > EPS*EPS:
            result.append(p)
    if len(result) > 2 and dot(sub(result[-1], result[0]), sub(result[-1], result[0])) < EPS*EPS:
        result.pop()
    return result if area(result) > AREA_EPS else []


def split(poly, n, d):
    """Two closed halves, n.p <= d and >= d; never move the cut plane."""
    inside, outside = [], []
    for i, p in enumerate(poly):
        q = poly[(i+1) % len(poly)]
        dp, dq = dot(n, p)-d, dot(n, q)-d
        if abs(dp) < EPS: dp = 0.0
        if abs(dq) < EPS: dq = 0.0
        if dp <= 0: inside.append(p)
        if dp >= 0: outside.append(p)
        if dp*dq < 0:
            v = add(p, mul(sub(q, p), dp/(dp-dq)))
            inside.append(v); outside.append(v)
    return clean(inside), clean(outside)


@dataclass
class Face:
    points: list
    normal: tuple
    uv: list
    element: int
    name: str
    direction: str
    role: str


@dataclass
class Solid:
    name: str
    points: list
    planes: list
    bbox: list
    grain: tuple
    member: str


def role(name):
    if any(x in name for x in ("织带", "折带", "垂带", "布带")): return "cloth"
    if any(x in name for x in ("转轴", "垫片", "铆钉")): return "metal"
    return "wood"


def member(name):
    if "外撇木腿" in name: return name.split("_")[0]
    if "横木_" in name: return name.split("_")[0]
    return name


def load_model(path):
    doc = json.loads(path.read_text(encoding="utf-8"))
    assert all(e.get("type") == "cube" and not e.get("rescale") for e in doc["elements"])
    solids, faces = [], []
    resolution = doc["resolution"]
    for ei, e in enumerate(doc["elements"]):
        lo, hi = e["from"], e["to"]
        rot, origin = e.get("rotation", [0, 0, 0]), e["origin"]
        assert sum(abs(x) > 1e-8 for x in rot) <= 1
        x0,y0,z0 = lo; x1,y1,z1 = hi
        # Winding is outward, and UV follows Java cube face orientation.
        quads = {
            "north": [(x1,y1,z0),(x1,y0,z0),(x0,y0,z0),(x0,y1,z0)],
            "south": [(x0,y1,z1),(x0,y0,z1),(x1,y0,z1),(x1,y1,z1)],
            "east": [(x1,y1,z1),(x1,y0,z1),(x1,y0,z0),(x1,y1,z0)],
            "west": [(x0,y1,z0),(x0,y0,z0),(x0,y0,z1),(x0,y1,z1)],
            "up": [(x0,y1,z0),(x0,y1,z1),(x1,y1,z1),(x1,y1,z0)],
            "down": [(x0,y0,z1),(x0,y0,z0),(x1,y0,z0),(x1,y0,z1)],
        }
        normals = {"north":(0,0,-1), "south":(0,0,1), "east":(1,0,0),
                   "west":(-1,0,0), "up":(0,1,0), "down":(0,-1,0)}
        points, planes = [], []
        for direction, quad in quads.items():
            world = [add(origin, rotate(sub(p, origin), rot)) for p in quad]
            n = rotate(normals[direction], rot)
            assert dot(cross(sub(world[1],world[0]),sub(world[2],world[0])), n) > 0
            points.extend(world); planes.append((n, dot(n, world[0])))
            f = e["faces"].get(direction)
            if not f or f.get("texture") is None: continue
            u0,v0,u1,v1 = f["uv"]
            uv = [(u0/resolution["width"],v0/resolution["height"]),
                  (u0/resolution["width"],v1/resolution["height"]),
                  (u1/resolution["width"],v1/resolution["height"]),
                  (u1/resolution["width"],v0/resolution["height"])]
            turn = int(f.get("rotation",0))//90
            uv = uv[turn:] + uv[:turn]
            faces.append(Face(world, n, uv, ei, e["name"], direction, role(e["name"])))
        axis = max(range(3), key=lambda j: hi[j]-lo[j])
        grain = rotate(tuple(1 if j == axis else 0 for j in range(3)), rot)
        solids.append(Solid(e["name"], points, planes, bounds(points), grain, member(e["name"])))
    # Tiny stair-step foot patches inherit the continuous leg's grain.
    grains = {s.member:s.grain for s in solids if "连续斜腿" in s.name}
    for s in solids:
        if s.member in grains: s.grain = grains[s.member]
    return doc, solids, faces


def union_faces(solids, faces):
    output, removals = [], []
    for face in faces:
        # Cloth weave intentionally overlaps in depth. Boolean-cutting every
        # crossing adds thousands of invisible fragments without repairing any
        # exposed plane. Keep the author's cloth/metal cuboids and union wood.
        if face.role != "wood":
            output.append((face,face.points))
            continue
        pieces = [face.points]
        for si, solid in enumerate(solids):
            if role(solid.name) != "wood" or si == face.element or not overlaps(bounds(face.points), solid.bbox): continue
            # Coincident exterior plane: the lower original element owns it.
            same_exterior = any(dot(n, face.normal) > 1-EPS and
                                abs(dot(n, face.points[0])-d) < EPS for n,d in solid.planes)
            if same_exterior and si > face.element: continue
            result = []
            for poly in pieces:
                if not overlaps(bounds(poly), solid.bbox):
                    result.append(poly); continue
                # Strictly outside any plane implies no volume intersection.
                if any(min(dot(n,p)-d for p in poly) > EPS for n,d in solid.planes):
                    result.append(poly); continue
                remaining = poly
                for n,d in solid.planes:
                    if not remaining: break
                    inside, outside = split(remaining, n, d)
                    # On-plane polygon belongs to inside, not both halves.
                    if all(abs(dot(n,p)-d) <= EPS for p in remaining): outside = []
                    if outside: result.append(outside)
                    remaining = inside
            pieces = result
            if not pieces: break
        original, after = area(face.points), sum(area(p) for p in pieces)
        if original-after > AREA_EPS:
            removals.append({"element":face.name,"face":face.direction,"removedArea":original-after})
        output.extend((face,p) for p in pieces)
    return output, removals


def uv_mapper(face, solid, solids):
    n, g = face.normal, solid.grain
    if face.role != "wood":
        p, a, b = face.points[0], sub(face.points[1],face.points[0]), sub(face.points[3],face.points[0])
        def atlas(v):
            va,vb = dot(sub(v,p),a)/dot(a,a), dot(sub(v,p),b)/dot(b,b)
            return add(face.uv[0],add(mul(sub(face.uv[1],face.uv[0]),va),mul(sub(face.uv[3],face.uv[0]),vb)))
        return face.role, atlas
    if abs(dot(n,g)) > 0.98:
        u = norm(cross(g, (0,1,0) if abs(g[1]) < 0.9 else (1,0,0)))
        v = norm(cross(g,u))
        vertices = [p for s in solids if s.member == solid.member for p in s.points]
        ulo,uhi = min(dot(p,u) for p in vertices),max(dot(p,u) for p in vertices)
        vlo,vhi = min(dot(p,v) for p in vertices),max(dot(p,v) for p in vertices)
        return "wood_end", lambda p: ((dot(p,u)-ulo)/(uhi-ulo),(dot(p,v)-vlo)/(vhi-vlo))
    v = norm(sub(g,mul(n,dot(g,n))))
    u = norm(cross(v,n))
    return "wood_side", lambda p: (dot(p,u)/16, dot(p,v)/16)


def split_uv(poly, mapper):
    """Repeat each 16-unit timber texture with geometry cuts, atlas-safe UV 0..1."""
    pieces = [poly]
    for component in range(2):
        values = [mapper(p)[component] for p in poly]
        for tile in range(math.floor(min(values)+EPS)+1, math.ceil(max(values)-EPS)):
            result = []
            for part in pieces:
                # Affine UV plane solved on this face using its two basis vectors.
                p,a,b = part[0],sub(part[1],part[0]),sub(part[-1],part[0])
                aa,ab,bb = dot(a,a),dot(a,b),dot(b,b)
                det = aa*bb-ab*ab
                if det < 1e-18: result.append(part); continue
                base=mapper(p)[component]
                da,db=mapper(part[1])[component]-base,mapper(part[-1])[component]-base
                n=add(mul(a,(da*bb-db*ab)/det),mul(b,(db*aa-da*ab)/det))
                one,two=split(part,n,dot(n,p)+tile-base)
                if one: result.append(one)
                if two: result.append(two)
            pieces=result
    return pieces


def compile_mesh(key, write=True):
    relative,count = MODELS[key]
    path = INPUT/relative
    doc,solids,faces=load_model(path)
    assert len(solids) == count
    polygons, removals=union_faces(solids,faces)
    groups={r:{"triangles":[]} for r in ("wood_side","wood_end","cloth","metal")}
    for face,poly in polygons:
        material,mapper=uv_mapper(face,solids[face.element],solids)
        pieces=split_uv(poly,mapper) if material == "wood_side" else [poly]
        for piece in pieces:
            uv=[mapper(p) for p in piece]
            if material == "wood_side":
                tile=[math.floor(sum(v[i] for v in uv)/len(uv)) for i in range(2)]
                uv=[tuple(max(0,min(1,v[i]-tile[i])) for i in range(2)) for v in uv]
            for i in range(1,len(piece)-1):
                pts=[piece[j] for j in (0,i,i+1)]
                if area(pts) <= AREA_EPS: continue
                groups[material]["triangles"].append({"p":[rounded(p) for p in pts],
                    "uv":[rounded(uv[j]) for j in (0,i,i+1)],"n":rounded(face.normal)})
    original_bounds=bounds([p for s in solids for p in s.points])
    final_bounds=bounds([p for g in groups.values() for t in g["triangles"] for p in t["p"]])
    assert max(abs(original_bounds[j][i]-final_bounds[j][i]) for j in range(2) for i in range(3)) < 1e-6
    for g in groups.values():
        for t in g["triangles"]:
            assert all(-EPS <= c <= 1+EPS for uv in t["uv"] for c in uv)
            assert dot(cross(sub(t["p"][1],t["p"][0]),sub(t["p"][2],t["p"][0])),t["n"]) >= -EPS
    mesh={"version":1,"unitsPerBlock":16,"bounds":[rounded(x) for x in final_bounds],"groups":groups}
    target=ASSETS/f"meshes/furniture/{key}.json"
    encoded=(json.dumps(mesh,ensure_ascii=False,separators=(",",":"))+"\n").encode("utf-8")
    if write:
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(encoded)
    else:
        assert target.read_bytes()==encoded,f"generated asset drift: {target}"
    report={"input":relative,"inputSha256":hashlib.sha256(path.read_bytes()).hexdigest(),
            "inputElements":count,"inputFaces":len(faces),"originalBounds":original_bounds,"outputBounds":final_bounds,
            "survivingPolygons":len(polygons),"triangles":{k:len(v["triangles"]) for k,v in groups.items()},
            "originalFaceArea":sum(area(f.points) for f in faces),
            "visibleUnionArea":sum(area(p) for f,p in polygons),"removedSurfaces":removals,
            "outputFile":str(target.relative_to(PROJECT/"src/main/resources")),
            "outputSha256":hashlib.sha256(encoded).hexdigest(),
            "geometryMethod":"wood convex face-minus-volume union; same-plane outward tie has one owner; cloth/metal cuboids retained without wasteful invisible weave fragments; no color hiding",
            "woodUV":"fresh longitudinal side UV at 16 model units/tile; full end grain UV shared by member; tile-split [0,1]",
            "minecraftTextureCopied":False}
    return mesh,report,(solids,faces,polygons)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--preview",action="store_true")
    parser.add_argument("--verify-only",action="store_true",help="regenerate in memory and verify every asset; write nothing")
    args=parser.parse_args()
    if args.verify_only:
        assert not args.preview,"--verify-only cannot render/write previews"
        checked={key:compile_mesh(key,False)[1]["outputSha256"] for key in MODELS}
        source_detail=INPUT/"02_小马扎/小马扎_修正版共用UV.png"
        assert source_detail.read_bytes()==(ASSETS/"textures/block/furniture/stool_details.png").read_bytes()
        from preview_furniture36 import geometry_checks
        checks=geometry_checks()
        print(json.dumps({"verified":True,"writeCount":0,"meshSha256":checked,
                          "remainingCoplanarOverlaps":sum(len(v["remainingCoplanarOverlaps"]) for v in checks.values())}))
        return
    OUTPUT.mkdir(parents=True,exist_ok=True)
    before={str(p.relative_to(INPUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in INPUT.rglob("*") if p.is_file()}
    detail=ASSETS/"textures/block/furniture/stool_details.png"
    detail.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(INPUT/"02_小马扎/小马扎_修正版共用UV.png",detail)
    report={"schema":"piq-furniture-model-36-v1","models":{},"inputFiles":before,
            "detailAtlasSha256":hashlib.sha256(detail.read_bytes()).hexdigest(),"inGameRenderingTested":False}
    meshes={}
    for key in MODELS:
        meshes[key],report["models"][key],_=compile_mesh(key)
        print(key,report["models"][key]["triangles"],flush=True)
    assert before == {str(p.relative_to(INPUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in INPUT.rglob("*") if p.is_file()}
    report["inputFilesUnchanged"]=True
    if args.preview:
        from preview_furniture36 import previews
        report["preview"]=previews(meshes,detail,OUTPUT)
    (OUTPUT/"geometry-report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


if __name__ == "__main__": main()
