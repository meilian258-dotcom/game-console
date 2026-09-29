"""Independent actual-quad seam diagnostics, with no generator or model writes."""
import json
from collections import defaultdict
import numpy as np
from verify_home_fc_alpha9_geometry import points


def inspect(model):
    quads,_=points(model);groups=defaultdict(list);overlaps=[]
    for q in quads:
        ranges=np.ptp(q.vertices,axis=0);axes=np.flatnonzero(ranges<1e-9)
        if len(axes)!=1:raise ValueError('Expected axis-aligned exported CRT faces')
        axis=int(axes[0]);plane=float(q.vertices[0,axis]);other=[i for i in range(3) if i!=axis]
        lo=q.vertices[:,other].min(0);hi=q.vertices[:,other].max(0)
        groups[(axis,round(plane,8),q.direction)].append((q,lo,hi))
    pairs=0
    for group in groups.values():
        for i,(a,alo,ahi) in enumerate(group):
            for b,blo,bhi in group[i+1:]:
                pairs+=1;size=np.minimum(ahi,bhi)-np.maximum(alo,blo)
                if np.all(size>1e-8):
                    overlaps.append({'a':a.element_index,'b':b.element_index,
                        'a_name':model['elements'][a.element_index].get('name'),
                        'b_name':model['elements'][b.element_index].get('name'),
                        'face':a.direction,'overlap_area':float(np.prod(size))})
    # Named frame layers are four disjoint boxes; test contiguous wall intervals.
    layer={}
    for prefix in ('mouth','recess-wall','inner-shadow'):
        parts={e.get('name','')[-1]:e for e in model['elements'] if e.get('name','').startswith(prefix)}
        if set(parts)!={'L','R','B','T'}:raise ValueError('Missing complete CRT frame layer: '+prefix)
        layer[prefix]={'outer':[parts['L']['from'][0],parts['B']['from'][1],parts['R']['to'][0],parts['T']['to'][1]],
            'inner':[parts['L']['to'][0],parts['B']['to'][1],parts['R']['from'][0],parts['T']['from'][1]],
            'z':[parts['L']['from'][2],parts['L']['to'][2]]}
    gaps=[]
    for front,back in (('mouth','recess-wall'),('recess-wall','inner-shadow')):
        a=layer[front];b=layer[back]
        margin=np.array(a['inner'])-np.array(b['outer'])
        # Back outer bounds must contain the front inner rectangle at a shared Z.
        if margin[0]<-1e-8 or margin[1]<-1e-8 or margin[2]>1e-8 or margin[3]>1e-8 or b['z'][0]>a['z'][1]+1e-8:
            gaps.append({'front':front,'back':back,'front_inner':a['inner'],'back_outer':b['outer'],'front_end_z':a['z'][1],'back_start_z':b['z'][0]})
    glass=[e for e in model['elements'] if e.get('name')=='完整4比3黑屏']
    if len(glass)!=1:raise ValueError('Missing unique black glass')
    if glass[0]['from'][2]>layer['inner-shadow']['z'][1]+1e-8:
        gaps.append({'front':'inner-shadow','back':'black-glass','gap_z':glass[0]['from'][2]-layer['inner-shadow']['z'][1]})
    return {'ok':not overlaps and not gaps,'actual_quads':len(quads),'same_plane_face_pairs':pairs,
        'coplanar_same_facing_overlaps':overlaps,'frame_continuity_gaps':gaps,
        'limits':['Same-facing coplanar area overlaps only; opposite-facing shared internal walls are not z-fighting.',
            'Continuity checks cover three aperture layers and their glass contact, not arbitrary camera/player gameplay.']}


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('model');args=parser.parse_args()
    from pathlib import Path
    result=inspect(json.loads(Path(args.model).read_bytes()));print(json.dumps(result,ensure_ascii=True,indent=2))
    if not result['ok']:raise SystemExit(1)
