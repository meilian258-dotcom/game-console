"""Read exported alpha8 triangles and reject actual control/cabinet intersections."""
from __future__ import annotations
import argparse
import json
import numpy as np
from build_dual_arcade_model import MODEL,OUT,world_quads,encoded,sha,write_new


def intersect_triangles(a,b,epsilon=1e-8):
    ea=np.roll(a,-1,axis=0)-a;eb=np.roll(b,-1,axis=0)-b
    na=np.cross(ea[0],ea[1]);nb=np.cross(eb[0],eb[1])
    # Include in-plane edge normals so disjoint coplanar triangles also separate.
    axes=[na,nb]+[np.cross(x,y) for x in ea for y in eb]
    axes.extend(np.cross(e,na) for e in ea);axes.extend(np.cross(e,nb) for e in eb)
    for axis in axes:
        length=np.linalg.norm(axis)
        if length<1e-12:continue
        pa=a@(axis/length);pb=b@(axis/length)
        if pa.max()<pb.min()-epsilon or pb.max()<pa.min()-epsilon:return False
    return True


def analyze(model=None):
    raw=MODEL.read_bytes();model=json.loads(raw) if model is None else model
    quads=world_quads(model);controls=[];shell=[]
    for q in quads:
        for corners in ((0,1,2),(0,2,3)):
            value=(q.vertices[list(corners)],q.element_index)
            if 68<=q.element_index<202:controls.append(value)
            elif q.element_index!=37:shell.append(value)  # Base contact with the top support is intentional.
    mins=np.array([t.min(0) for t,i in shell]);maxs=np.array([t.max(0) for t,i in shell])
    crossings=[];candidates=0
    for triangle,index in controls:
        mask=np.all(triangle.max(0)>=mins-1e-8,axis=1)&np.all(triangle.min(0)<=maxs+1e-8,axis=1)
        for j in np.flatnonzero(mask):
            candidates+=1
            if intersect_triangles(triangle,shell[j][0]):crossings.append([index,shell[j][1]])
    points=np.concatenate([t for t,i in controls]);p1=np.concatenate([t for t,i in controls if i<135]);p2=np.concatenate([t for t,i in controls if i>=135])
    n=np.array([0,.3826834323650898,-.9238795325112867]);dist=(points-[16,16.55,5])@n
    checks={'no_control_intersects_actual_shell_triangles':not crossings,
            'base_sits_exactly_on_support_not_inside':abs(points[:,1].min()-16.245)<1e-8,
            'all_controls_above_support':bool((points[:,1]>=16.245-1e-8).all()),
            'two_controls_have_real_horizontal_separation':p1[:,0].min()-p2[:,0].max()>8,
            'whole_control_surface_is_in_front_of_glass_plane':dist.min()>1.0,
            'all_buttons_fit_inside_control_deck_depth':points[:,2].min()>=.9 and points[:,2].max()<=5.05}
    checks={k:bool(v) for k,v in checks.items()}
    return {'ok':all(checks.values()),'checks':checks,'model_sha256':sha(raw),
            'control_triangles':len(controls),'shell_triangles':len(shell),'triangle_candidate_pairs':candidates,
            'intersection_element_pairs':sorted(set(tuple(v) for v in crossings)),
            'minimum_front_of_screen_plane_model_units':float(dist.min()),
            'inter_player_gap_model_units':float(p1[:,0].min()-p2[:,0].max()),
            'lowest_control_y_model_units':float(points[:,1].min()),
            'limits':['Actual transformed quad triangles including rotations; conservative AABB rejection then triangle SAT',
                      'Contact with the support surface (element37) is intentional; it is tested separately at exactly16.245',
                      'This is offline geometry, not a live Minecraft test']}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    report=analyze()
    if args.write:write_new({OUT/'control-clearance-audit.json':encoded(report)})
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
