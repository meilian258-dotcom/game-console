"""Audit the exported alpha9 header and unchanged alpha8 playable surfaces.

Topology is checked from actual rotated quads. Touch/overlap connectivity uses
axis-aligned physical panels, not a cosmetic picture or only a whole-body AABB.
"""
from __future__ import annotations
import argparse
import collections
import io
import json
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_dual_arcade_model import MODEL,OUT,TEXTURE,build_alpha8,world_quads,encoded,sha,write_new
from render_rocket_arcade_preview import render_view

HEADER_CHANGED={4,9,17,21,23,31,35}|set(range(48,68))


def closed_surface(quads):
    edges=collections.Counter()
    for q in quads:
        for a,b in zip(q.vertices,np.roll(q.vertices,-1,axis=0)):
            edge=tuple(sorted((tuple(np.round(a,8)),tuple(np.round(b,8)))))
            edges[edge]+=1
    return len(edges)==12 and all(n==2 for n in edges.values())


def analyze(model=None):
    raw=MODEL.read_bytes();model=json.loads(raw) if model is None else model
    current=world_quads(model);old=world_quads(build_alpha8()[0])
    groups={i:[q for q in current if q.element_index==i] for i in range(227)}
    boxes={i:np.array([np.concatenate([q.vertices for q in v]).min(0),np.concatenate([q.vertices for q in v]).max(0)]) for i,v in groups.items() if v}
    def contact(a,b):return bool(np.all(boxes[a][1]>=boxes[b][0]-1e-8) and np.all(boxes[b][1]>=boxes[a][0]-1e-8))
    # Each is an actual closed cuboid with six faces. Connecting closed panels
    # overlap at the canopy/roof/back so increasing the header creates no gap.
    # The sign solid stops .05 units ahead of the rear panel. Its closed south
    # face seals that gap; roof49 and speaker55 bridge it to the rear panel.
    shell_links=((48,49),(48,17),(48,31),(48,55),(55,4),(49,4),(49,17),(49,31))
    connections=[{'panels':list(pair),'connected':contact(*pair)} for pair in shell_links]
    unchanged=[]
    for i in range(227):
        if i in HEADER_CHANGED:continue
        before=[q for q in old if q.element_index==i];after=groups[i]
        same=len(before)==len(after) and all(np.allclose(a.vertices,b.vertices,atol=1e-10) and np.array_equal(a.uv,b.uv) for a,b in zip(before,after))
        if not same:unchanged.append(i)
    letters=boxes[54];old_model=build_alpha8()[0];original=old_model['elements'][54]
    original_ratio=(original['to'][0]-original['from'][0])/(original['to'][1]-original['from'][1])
    ratio=(letters[1,0]-letters[0,0])/(letters[1,1]-letters[0,1])
    checks={'header_main_and_speaker_backer_are_closed_six_face_solids':closed_surface(groups[48]) and closed_surface(groups[55]),
            'top_side_back_canopy_have_connected_closed_shell':all(v['connected'] for v in connections),
            'every_non_header_world_quad_equals_frozen_alpha8':not unchanged,
            'large_lettering_keeps_original_glyph_ratio':abs(ratio-original_ratio)<1e-8 and abs(letters[1,0]-letters[0,0]-16)<1e-8,
            'header_bottom_and_top_leave_lettering_clearance':letters[0,1]>boxes[50][1,1] and letters[1,1]<boxes[51][0,1],
            'raw_elements_stay_in_vanilla_loader_range':all(min(e['from'])>=-16 and max(e['to'])<=32 for e in model['elements'])}
    checks={name:bool(value) for name,value in checks.items()}
    return {'ok':all(checks.values()),'checks':checks,'model_sha256':sha(raw),'shell_connections':connections,
            'changed_non_header_elements':unchanged,'header_bounds_world_units':boxes[48].tolist(),
            'letter_bounds_world_units':letters.tolist(),'letter_aspect':float(ratio),
            'limits':['Actual exported rotated-quad topology and axis-aligned panel contact; not an in-game screenshot',
                      'Closed-cuboid overlap is a sealed-solid certificate for the rebuilt header, not a universal manifold proof for all old overlapping shell pieces',
                      'Only header-region components may differ; screen, lower bezel, deck, controls, coin door remain frozen alpha8 world geometry']},current


def preview(quads):
    selected=[q for q in quads if q.element_index in HEADER_CHANGED or q.element_index in (41,45)]
    textures={'piq_fc_arcade:block/rocket_arcade_skin':np.array(Image.open(TEXTURE).convert('RGBA'))}
    canvas=Image.new('RGB',(1640,810),'#19222c');d=ImageDraw.Draw(canvas)
    bold=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',25);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
    d.text((22,16),'alpha9 顶部实际网格检查 · 加大字牌、封闭顶侧与灯箱底板',font=bold,fill='#edf4f8')
    # Clip the long rear/spine panels only in the QA view by excluding them.
    # The exported model is untouched; main sign, roof, speakers are full quads.
    selected=[q for q in selected if q.element_index not in (4,9,21,23,35)]
    for n,(title,view) in enumerate((('正面字牌与喇叭',(0,.08,-1)),('侧前方接缝',(1,.22,-1.7)),('仰视：灯箱底面存在',(1,-.65,-1.7)))):
        picture,_=render_view(selected,textures,view,size=(520,640),supersample=2)
        canvas.paste(picture.convert('RGB'),(10+n*545,96));d.text((17+n*545,61),title,font=font,fill='#c7dce8')
    d.text((22,761),'只调整取景，不编辑网格；底板为实际模型面。离线几何QA，不是Minecraft截图。',font=font,fill='#aac4d2')
    result={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        result['双人街机_顶牌侧壳与底面QA.'+suffix]=b.getvalue()
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    report,quads=analyze()
    if args.write:
        outputs={OUT/name:data for name,data in preview(quads).items()};report['preview_sha256']={str(p):sha(v) for p,v in outputs.items()}
        outputs[OUT/'header-geometry-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
