"""Render the actual single and alpha9 cabinets at matching physical scales.

This is generated-geometry QA, never an in-game screenshot. Both comparison
front columns use 14 px/model-unit; side columns use 12. Perspective never auto-fits.
"""
from __future__ import annotations
import argparse
import io
import json
import math
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from build_dual_arcade_model import MODEL,TEXTURE,OUT,inputs,world_quads,sha,encoded,write_new
from render_rocket_arcade_preview import raster_triangle,collect_quads,render_view

EYE=np.array([16.,25.92,-16.])
VIEW_SIZE=(630,630)
FOV=70.


def standing_projection(vertices):
    relative=np.asarray(vertices)-EYE
    focal=VIEW_SIZE[1]/(2*math.tan(math.radians(FOV)/2))
    return np.column_stack((-relative[:,0]/relative[:,2]*focal+VIEW_SIZE[0]/2,
                            -relative[:,1]/relative[:,2]*focal+VIEW_SIZE[1]/2,
                            -relative[:,2]))


def raster(quads,atlas,size,project,view=(0,0,-1)):
    pixels=np.zeros((size[1],size[0],4),dtype=np.uint8)
    pixels[:]=[25,34,44,255];depth=np.full((size[1],size[0]),-np.inf)
    for q in quads:
        normal=np.cross(q.vertices[1]-q.vertices[0],q.vertices[2]-q.vertices[0])
        if np.dot(normal,view)<=1e-9:continue
        vertices=project(q.vertices)
        for indices in ((0,1,2),(0,2,3)):
            raster_triangle(pixels,depth,vertices[list(indices)],q.uv[list(indices)],atlas)
    return Image.fromarray(pixels).convert('RGB')


def analyze():
    model=json.loads(MODEL.read_bytes());quads=world_quads(model)
    screen=next(q for q in quads if q.element_index==47)
    corners=standing_projection(screen.vertices)
    height=np.max(np.concatenate([q.vertices for q in quads]),axis=0)[1]
    controls=np.concatenate([q.vertices for q in quads if q.element_index==37])
    _,single=inputs();single_points=np.concatenate([q.vertices for q in collect_quads(single)])
    checks={'only_header_adds_point35_blocks':abs(height-37.6)<1e-8 and abs(height-single_points[:,1].max()-5.6)<1e-8,
            'eye_height_inside_static_glass':screen.vertices[:,1].min()<25.92<screen.vertices[:,1].max(),
            'complete_glass_within_level_camera':bool(((corners[:,:2]>0)&(corners[:,:2]<VIEW_SIZE)).all()),
            'control_surface_below_player_chest':controls[:,1].max()<19.2,
            'controls_above_one_half_block':controls[:,1].min()>8}
    checks={key:bool(value) for key,value in checks.items()}
    return {'ok':all(checks.values()),'checks':checks,'body_sha256':sha(MODEL.read_bytes()),
            'standing_eye_model_units':EYE.tolist(),'player_height_blocks':1.8,'player_eye_blocks':1.62,
            'vertical_fov_degrees':FOV,'perspective_size':VIEW_SIZE,'screen_projected_pixels':corners[:,:2].tolist(),
            'control_surface_world_y_units':[float(controls[:,1].min()),float(controls[:,1].max())],
            'model_top_blocks':height/16,'limits':['Actual exported JSON and atlas offline rasterization, not Minecraft execution',
              'Single and dual cabinets share fixed scales in each front/side comparison; controls-only view is enlarged for inspection',
              'Perspective is a fixed, level 70-degree vertical-FOV eye camera with no auto-fit; affine UV interpolation is diagnostic only']},model


def preview(model):
    atlas=np.array(Image.open(TEXTURE).convert('RGBA'));_,single=inputs()
    canvas=Image.new('RGB',(1740,1460),'#19222c');draw=ImageDraw.Draw(canvas)
    bold=ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc',25)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    draw.text((24,15),'alpha9 实际模型 · 只加高顶牌 / 屏与控台不动 / 原前沿深度',font=bold,fill='#edf4f8')
    bodies=((single,180,360,'普通单人'),(model,320,640,'双人宽屏'))
    x=12
    for body,center,width,label in bodies:
        q=world_quads(body)
        picture=raster(q,atlas,(width,620),lambda p:np.column_stack((-(p[:,0]-(8 if body is single else 16))*14+center,580-p[:,1]*14,-p[:,2])))
        d=ImageDraw.Draw(picture)
        for units,color in ((0,'#506775'),(16.245,'#f3c55b'),(32,'#6cb5d8'),(37.6,'#d494e7')):
            y=580-units*14;d.line((0,y,width,y),fill=color,width=2)
        d.text((15,12),label+'（相同比例尺）',font=bold,fill='#edf4f8')
        canvas.paste(picture,(x,75));x+=width
    draw.text((28,712),'青线：原2格顶面   紫线：新2.35格头顶   黄线：控台原高度',font=font,fill='#b8cdda')
    for x,body,label in ((12,single,'单人侧面'),(512,model,'双人侧面')):
        picture=raster(world_quads(body),atlas,(500,540),
            lambda p:np.column_stack(((p[:,2]-7.5)*12+245,500-p[:,1]*12,p[:,0])),view=(1,0,0))
        d=ImageDraw.Draw(picture)
        for units,color in ((0,'#506775'),(16.245,'#f3c55b'),(32,'#6cb5d8'),(37.6,'#d494e7')):
            y=500-units*12;d.line((0,y,500,y),fill=color,width=2)
        front=(.3820101013-7.5)*12+245;d.line((front,40,front,500),fill='#9cce99',width=2)
        d.text((15,12),label+'（前沿/高度同尺）',font=bold,fill='#edf4f8')
        canvas.paste(picture,(x,765))
    controls=[q for q in world_quads(model) if 36<=q.element_index<=40 or 68<=q.element_index<202]
    textures={'piq_fc_arcade:block/rocket_arcade_skin':atlas}
    close,_=render_view(controls,textures,(0,1,-1),size=(690,400),supersample=2)
    canvas.paste(close.convert('RGB'),(1030,100));draw.text((1040,66),'实际控台近景（仅视图取景放大）',font=bold,fill='#edf4f8')
    draw.text((1040,505),'两套原尺寸控件，底部贴面，后方净距>1单位',font=font,fill='#b8cdda')
    perspective=raster(world_quads(model),atlas,VIEW_SIZE,standing_projection)
    canvas.paste(perspective,(1060,723));draw.text((1040,690),'站立1.62格眼高，70°垂直视场平视',font=bold,fill='#edf4f8')
    draw.line((1060,1038,1690,1038),fill='#e9be56',width=2)
    draw.text((28,1355),'绿线：单人与双人前沿深度对齐；侧面统一12像素/模型单位。',font=font,fill='#b8cdda')
    draw.text((28,1412),'使用实际导出JSON和原贴图。离线光栅预览，不是Minecraft截图；屏幕与控台逐点等于alpha8，不整体放大。',font=font,fill='#92abb9')
    outputs={}
    for suffix in ('png','jpg'):
        buffer=io.BytesIO();canvas.save(buffer,format='PNG' if suffix=='png' else 'JPEG',**({} if suffix=='png' else {'quality':90,'optimize':True}))
        outputs['单双人街机_顶牌加高与原控台对照.'+suffix]=buffer.getvalue()
    return outputs




def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    report,model=analyze()
    if args.write:
        outputs={OUT/name:data for name,data in preview(model).items()}
        report['preview_sha256']={str(p):sha(data) for p,data in outputs.items()}
        outputs[OUT/'standing-view-audit.json']=encoded(report);write_new(outputs)
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
