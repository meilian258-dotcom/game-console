"""Build a two-wide cabinet at the original single-cabinet height and depth.

Original 01 geometry supplies both control groups; the current seam-fixed cabinet
supplies the shell and every UV. Existing JSON/PNG and original ZIP stay read-only.
"""
from __future__ import annotations
import argparse
import copy
import io
import json
import zipfile
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from import_subor_hardware import ASSETS,CATEGORY,encoded,sha,write_new
from render_rocket_arcade_preview import Quad,collect_quads,render_view

ARCHIVE=CATEGORY.parents[2]/'backups/fc-rocket-model-20260908/user-models-original.zip'
# CATEGORY is 制作Mod/03-街机模拟/PIQ-FC街机; its third parent is the workspace.
SOURCE_SHA='E3CF2113E30D81FFA6B96411805B804044A4D5DF05799488F3249B1C4B35E728'
LEGACY=ASSETS/'models/block/rocket_arcade_body.json'
LEGACY_SHA='E4BB95E7EBBB6B989070B952E7BA5D90CF1B16D8A00078BEE513A39344ED98F7'
TEXTURE=ASSETS/'textures/block/rocket_arcade_skin.png'
TEXTURE_SHA='789512ED7F867C015C6666D40809845DE430E85834CCF7BA4E48BCA57DE815E8'
MODEL=ASSETS/'models/block/dual_arcade_body.json'
OUT=CATEGORY/'双人街机顶牌比例修正-alpha9'
SCALE=1.0
MODEL_Y_OFFSET=5.6
LOWER_BY=6.4
OLD_BODY_SHA='197227F4050FF5E0C99C7CDA0C6943373A6F9F75D58D544E476BB5AA39C0DB24'
ALPHA7_BODY_SHA='62CF974350859BB24D9628FA0CFD640161BC6FDC9BCB3C23E06924E89A24CD42'
ALPHA8_BODY_SHA='4F26784430734E9DE44028291B23A64D7DD25D974F05886726A3C719A34C8E29'
SIDE_SHIFT=2.9466666666666668
OFFSET=np.array([8/3,0,3.150661616032783])
PIVOT=np.array([8.,0.,8.])
WIDEN=set(range(8))|set(range(36,42))|{48,49,50,51,55}


def inputs():
    if sha(LEGACY.read_bytes())!=LEGACY_SHA:raise ValueError('Frozen legacy body changed')
    if sha(TEXTURE.read_bytes())!=TEXTURE_SHA:raise ValueError('Approved legacy skin changed')
    with zipfile.ZipFile(ARCHIVE) as archive:
        names=[n for n in archive.namelist() if '/01_' in n and n.endswith('/arcade.json') and '/minecraft_assets/' not in n]
        if len(names)!=1:raise ValueError('Original 01 model must be unique')
        raw=archive.read(names[0])
    if sha(raw)!=SOURCE_SHA:raise ValueError('Original dual arcade JSON changed')
    return json.loads(raw),json.loads(LEGACY.read_bytes())


def donor_index(i):
    if i<68:return i
    if i<135:return i+10
    if i<202:return i-57
    if i<210:return i-57
    if i<215:return i-62
    if i<217:return i-62
    return i-149


def translate(element,delta):
    for key in ('from','to'):element[key]=(np.array(element[key])+delta).tolist()
    if 'rotation' in element:element['rotation']['origin']=(np.array(element['rotation']['origin'])+delta).tolist()


def build_alpha6():
    source,legacy=inputs();elements=[];mapping=[]
    if len(source['elements'])!=227 or len(legacy['elements'])!=155:raise ValueError('Unexpected source element inventory')
    for i,original in enumerate(source['elements']):
        donor=donor_index(i)
        e=copy.deepcopy(legacy['elements'][i] if i<68 else original)
        e['faces']=copy.deepcopy(legacy['elements'][donor]['faces'])
        if i in WIDEN:
            e['from'][0]-=SIDE_SHIFT;e['to'][0]+=SIDE_SHIFT
        elif 8<=i<22 or i==52 or 56<=i<62 or 68<=i<135:
            translate(e,[SIDE_SHIFT,0,0])
        elif 22<=i<36 or i==53 or 62<=i<68 or 135<=i<202:
            translate(e,[-SIDE_SHIFT,0,0])
        elif i==42:e['to'][0]+=SIDE_SHIFT
        elif i==43:e['from'][0]-=SIDE_SHIFT
        # The central screen and ARCADE lettering stay undistorted; enlarged back
        # shell fills the side spaces. No UV-coordinate invention or new bitmap.
        translate(e,OFFSET)
        for key in ('from','to'):e[key]=[round(v,10) for v in e[key]]
        if 'rotation' in e:e['rotation']['origin']=[round(v,10) for v in e['rotation']['origin']]
        elements.append(e);mapping.append(donor)
    return {'credit':'PIQ dual cabinet: original 01 controls, seam-fixed generic cabinet and unchanged legacy atlas; BER scale 1.5.',
            'ambientocclusion':True,'render_type':'minecraft:cutout','textures':copy.deepcopy(legacy['textures']),
            'elements':elements},mapping


def shorten_shell_height(y):
    """Keep floor thickness, remove blank lower-cabinet height, translate upper shell."""
    if y<=1.06:return y
    if y>=13.4:return y-LOWER_BY
    return 1.06+(y-1.06)*(13.4-LOWER_BY-1.06)/(13.4-1.06)


def build_alpha7():
    model,mapping=build_alpha6()
    if sha(encoded(model))!=OLD_BODY_SHA:raise ValueError('Unreviewed alpha6 starting geometry')
    for i,e in enumerate(model['elements']):
        if i in (0,3):continue  # Keep solid base and kick strip thickness.
        if i in (1,2,4,8,18,22,32,222):
            if 'rotation' in e:raise ValueError('Only axis-aligned cabinet panels can shorten')
            e['from'][1]=shorten_shell_height(e['from'][1])
            e['to'][1]=shorten_shell_height(e['to'][1])
        elif 202<=i<=216:translate(e,[0,-4,0])  # Complete coin door: no squashed slots.
        elif 217<=i<=221:translate(e,[0,-.6,0])
        elif i in (223,225):
            center=(e['from'][1]+e['to'][1])/2
            translate(e,[0,shorten_shell_height(center)-center,0])
        else:translate(e,[0,-LOWER_BY,0])
        for key in ('from','to'):e[key]=[round(v,10) for v in e[key]]
        if 'rotation' in e:e['rotation']['origin']=[round(v,10) for v in e['rotation']['origin']]
    model['credit']='PIQ alpha7 dual cabinet: lower cabinet shortened by 6.4 model units; unchanged 4:3 screen and two control sets shifted down; BER scale 1.5.'
    return model,mapping


def build_alpha8():
    source,legacy=inputs();elements=[];mapping=[]
    for i,original in enumerate(source['elements']):
        donor=donor_index(i)
        e=copy.deepcopy(legacy['elements'][i] if i<68 else legacy['elements'][donor] if i<202 else original)
        e['faces']=copy.deepcopy(legacy['elements'][donor]['faces'])
        if 68<=i<202:
            translate(e,[16 if i<135 else 0,.035,0])
            e['name']=('P1 ' if i<135 else 'P2 ')+e.get('name','control')
        else:
            if i in WIDEN:e['from'][0]-=8.08;e['to'][0]+=8.08
            elif 8<=i<22 or i==52 or 56<=i<62:translate(e,[8.08,0,0])
            elif 22<=i<36 or i==53 or 62<=i<68:translate(e,[-8.08,0,0])
            translate(e,[8,0,0])
        elements.append(e);mapping.append(donor)
    def shape(i,lo,hi,rot=False):
        e=elements[i];e['from']=list(lo);e['to']=list(hi)
        if rot:e['rotation']={'angle':22.5,'axis':'x','origin':[16,16.55,5],'rescale':False}
        else:e.pop('rotation',None)
    # New, shallow screen housing: the old deep lower wedge crossed the handles.
    shape(40,[1.42,16.05,4.78],[30.58,16.45,5.38],True)
    shape(41,[1.46,16.2,5.38],[30.54,30.26,6.0],True)
    shape(42,[28,16.15,4.8],[28.65,30.25,5.35],True)
    shape(43,[3.35,16.15,4.8],[4,30.25,5.35],True)
    shape(44,[4,16.15,4.8],[28,16.55,5.35],True)
    shape(45,[4,30.05,4.9],[28,30.25,5.35],True)
    shape(46,[3.87,16.42,5.02],[28.13,30.18,5.11],True)
    shape(47,[4,16.55,5],[28,30.05,5.2],True)
    elements[47]['name']='16:9宽屏黑玻璃'
    # A slimmer top marquee leaves real room for the 24 x 13.5 display.
    shape(48,[1.24,30.05,3.03],[30.76,31.72,13.85])
    shape(50,[1.18,29.87,2.705],[30.82,30.25,4.65])
    shape(51,[1.18,31.55,2.705],[30.82,31.9,3.2])
    shape(52,[30.18,30.16,2.72],[30.83,31.7,3.21])
    shape(53,[1.17,30.16,2.72],[1.82,31.7,3.21])
    text_scale=1.3/2.76
    width=12.26*text_scale
    shape(54,[16-width/2,30.31,2.99],[16+width/2,31.61,3.1])
    shape(55,[1.42,29.245,10.60],[30.58,30.04,11.2])
    for start,target_x in ((56,26),(62,6)):
        center=(np.array(legacy['elements'][start]['from'])+legacy['elements'][start]['to'])/2
        for i in range(start,start+6):
            e=elements[i];old=legacy['elements'][i]
            for key in ('from','to'):e[key]=((np.array(old[key])-center)*.6+[target_x,29.57,10.31]).tolist()
    for e in elements:
        for key in ('from','to'):e[key]=[round(v,10) for v in e[key]]
        if 'rotation' in e:e['rotation']['origin']=[round(v,10) for v in e['rotation']['origin']]
    return {'credit':'PIQ alpha8: same 32-unit height and front depth as single cabinet; native-scale two-width body, 24x13.5 widescreen, separate raised single-cabinet control sets.',
            'ambientocclusion':True,'render_type':'minecraft:cutout','textures':copy.deepcopy(legacy['textures']),
            'elements':elements},mapping


def build():
    model,mapping=build_alpha8()
    if sha(encoded(model))!=ALPHA8_BODY_SHA:raise ValueError('Frozen alpha8 model changed')
    elements=model['elements']
    # Only the header is taller; the screen, skirt, coin door and both control
    # groups keep their exact alpha8 world-space geometry.
    for i in (9,17,21,23,31,35):elements[i]['to'][1]+=MODEL_Y_OFFSET
    elements[4]['to'][1]=37.35
    translate(elements[49],[0,MODEL_Y_OFFSET,0])
    elements[49]['name']='37.6单位顶盖'
    def shape(i,lo,hi):
        elements[i]['from']=list(lo);elements[i]['to']=list(hi)
        elements[i].pop('rotation',None)
    shape(48,[1.24,32.55,3.03],[30.76,37.35,13.85])
    shape(50,[1.18,32.45,2.705],[30.82,33.05,4.65])
    shape(51,[1.18,37.05,2.705],[30.82,37.5,3.2])
    shape(52,[30.18,32.95,2.72],[30.83,37.3,3.21])
    shape(53,[1.17,32.95,2.72],[1.82,37.3,3.21])
    # Original ARCADE glyph aspect, now comfortably legible on the two-wide sign.
    shape(54,[8,33.3,2.99],[24,33.3+16*2.76/12.26,3.1])
    shape(55,[1.42,29.245,10.60],[30.58,32.60,13.90])
    _,legacy=inputs()
    for start,target_x in ((56,26),(62,6)):
        center=(np.array(legacy['elements'][start]['from'])+legacy['elements'][start]['to'])/2
        for i in range(start,start+6):
            for key in ('from','to'):
                elements[i][key]=(np.array(legacy['elements'][i][key])-center+[target_x,31,10.31]).tolist()
    # Minecraft's ordinary BlockElement loader requires every from/to in -16..32.
    # Rebase, never scale: BER adds +5.6/16 so all unchanged surfaces stay put.
    for e in elements:
        translate(e,[0,-MODEL_Y_OFFSET,0])
        for key in ('from','to'):e[key]=[round(v,10) for v in e[key]]
        if 'rotation' in e:e['rotation']['origin']=[round(v,10) for v in e['rotation']['origin']]
    model['credit']='PIQ alpha9: only the header raised to 37.6 world units, unchanged alpha8 16:9 glass and controls; JSON Y rebased -5.6 with BER +0.35 blocks, scale 1.'
    return model,mapping


def model_y_offset(model):
    return MODEL_Y_OFFSET if model.get('credit','').startswith('PIQ alpha9:') else 0


def world_quads(model,turns=0,model_scale=SCALE):
    angle=-np.pi/2*(turns%4);c,s=np.cos(angle),np.sin(angle)
    rotation=np.array([[c,0,s],[0,1,0],[-s,0,c]])
    offset=np.array([0,model_y_offset(model),0])
    return [Quad(((q.vertices+offset)*model_scale-PIVOT)@rotation.T+PIVOT,q.uv,q.texture,q.element_index,q.direction) for q in collect_quads(model)]


def audit(model,mapping):
    source,legacy=inputs();quads=world_quads(model);points=np.concatenate([q.vertices for q in quads])
    screen=next(q for q in quads if q.element_index==47)
    normal=np.cross(screen.vertices[1]-screen.vertices[0],screen.vertices[2]-screen.vertices[0]);normal/=np.linalg.norm(normal)
    bounds=[points.min(0).tolist(),points.max(0).tolist()]
    if not np.allclose(bounds,[[.6,0,.3820101013],[31.4,37.6,14.65]],atol=1e-8):raise ValueError('Final two-wide bounds differ')
    for i,e in enumerate(model['elements']):
        if e['faces']!=legacy['elements'][mapping[i]]['faces']:raise ValueError('Invented or changed legacy UV')
        if min(e['from'])<-16 or max(e['to'])>32:raise ValueError('Illegal vanilla element bounds')
    image=np.array(Image.open(TEXTURE).convert('RGBA'))
    uv=legacy['elements'][47]['faces']['north']['uv'];x1,y1,x2,y2=np.array(uv)*128
    screen_pixels=image[int(np.ceil(y1)):int(np.floor(y2)),int(np.ceil(x1)):int(np.floor(x2))]
    if not np.all(screen_pixels==[0,0,0,255]):raise ValueError('Static screen contains nonblack pixels')
    turns=[]
    for turn in range(4):
        p=np.concatenate([q.vertices for q in world_quads(model,turn)]);turns.append([p.min(0).tolist(),p.max(0).tolist()])
    return {'ok':True,'model_sha256':sha(encoded(model)),'source_01_sha256':SOURCE_SHA,'legacy_body_sha256':LEGACY_SHA,
            'texture_sha256':TEXTURE_SHA,'elements':len(model['elements']),'world_scale':SCALE,
            'world_rotation_pivot':PIVOT.tolist(),'world_bounds_north':bounds,'world_bounds_4turns':turns,
            'screen_quad_world_units':screen.vertices.tolist(),'screen_normal':normal.tolist(),
            'screen_width':24,'screen_height':13.5,'screen_uv':uv,
            'player_eye_world_units':25.92,'top_blocks':2.35,'model_y_offset_units':MODEL_Y_OFFSET,'control_surface_y':[16.145,16.245],
            'original_to_atlas_donor_element':mapping,
            'limits':['Offline actual JSON/PNG rendering, not an in-game screenshot',
                      'Blockstate stays empty; BER scale 1, +0.35 block Y rebasing, pivot (8,0,8), negative 90 degrees per turn',
                      'Both P1/P2 use the existing single-player atlas controls; existing skin layouts remain compatible']}


def preview(model):
    textures={'piq_fc_arcade:block/rocket_arcade_skin':np.array(Image.open(TEXTURE).convert('RGBA'))}
    canvas=Image.new('RGB',(1650,1040),'#19222c');draw=ImageDraw.Draw(canvas)
    title=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',27);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    draw.text((24,16),'双人街机 · 厚实顶牌与喇叭条 / 两格宽、2.35格高 / 原16:9屏幕',font=title,fill='#eef5fa')
    for i,(label,view) in enumerate((('正面 · 两套原比例摇杆与六键',(0,.15,-1)),('侧前 · 通用皮肤、黑屏与加宽操作台',(1,.25,-1.7)),('背面 · 两格深占位内的完整柜体',(-1,.2,1.7)))):
        picture,_=render_view(world_quads(model),textures,view,size=(530,880),supersample=2)
        canvas.paste(picture.convert('RGB'),(10+i*550,96));draw.text((15+i*550,64),label,font=font,fill='#d8e8ef')
    draw.text((24,995),'只加高头部，操作台、两组控件与屏幕不动；原贴图、原字形比例。离线预览，非游戏截图。',font=font,fill='#b9cdd8')
    outputs={}
    for suffix in ('png','jpg'):
        b=io.BytesIO();canvas.save(b,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}));outputs['双人街机实际模型预览.'+suffix]=b.getvalue()
    return outputs


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');parser.add_argument('--check-only',action='store_true');parser.add_argument('--update-reviewed-model',action='store_true');args=parser.parse_args()
    model,mapping=build();report=audit(model,mapping);raw=encoded(model)
    if args.check_only and MODEL.read_bytes()!=raw:raise ValueError('Runtime body differs from deterministic build')
    if args.update_reviewed_model:
        if MODEL.is_symlink() or sha(MODEL.read_bytes()) not in (ALPHA8_BODY_SHA,sha(raw)):raise ValueError('Refusing to replace an unreviewed runtime model')
        # Deterministic generated geometry only; original model and old alpha6 previews remain read-only.
        MODEL.write_bytes(raw)
    if args.write:
        outputs={MODEL:raw,**{OUT/name:data for name,data in preview(json.loads(raw)).items()}}
        report['output_sha256']={str(path):sha(data) for path,data in outputs.items()};outputs[OUT/'双人街机模型校验.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))


if __name__=='__main__':main()
