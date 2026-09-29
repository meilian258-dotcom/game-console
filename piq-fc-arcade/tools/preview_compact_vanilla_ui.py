"""Actual compiled compact layout + original Minecraft button sprites. No game/texture edits.

Fonts and sample entries are illustrative; geometry comes only from the current two Java
layout classes. This intentionally does not reuse the old dark-panel preview geometry.
"""
from __future__ import annotations
import argparse, hashlib, html, io, json, re, subprocess, sys, tempfile, zipfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar')
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',9)
TITLE=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
LINE_HEIGHT=9
SOURCES={
 'layout':ROOT/'src/main/java/cn/piq/fcarcade/client/ui/DeviceLayout.java',
 'workbench':ROOT/'src/main/java/cn/piq/fcarcade/client/ui/CartridgeWorkbenchLayout.java',
 'paint':ROOT/'src/main/java/cn/piq/fcarcade/client/ui/DeviceUi.java',
 'fc':ROOT/'src/main/java/cn/piq/fcarcade/client/ClientCartridgeEditor.java',
 'sfc':ROOT.parent/'piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcCardEditorScreen.java',
 'sfc_labels':ROOT.parent/'piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcWorkbenchDisplay.java',
}

def digest(raw):return hashlib.sha256(raw).hexdigest().upper()
def read_sources():
    text={k:p.read_text(encoding='utf-8') for k,p in SOURCES.items()}
    for key in ('fc','sfc'):
        assert 'CartridgeWorkbenchLayout.of(' in text[key]
        for part in ('name','saveName','players','gamesTab','coversTab','search','refresh','romFolder','coverFolder','previous','next','close','clearCover','restoreCover'):
            assert 'workbench.'+part+'()' in text[key],(key,part)
        assert '"待应用封面"' in text[key] and '"待写入游戏"' in text[key]
        assert '"> 游戏库"' in text[key] and '"> 封面"' in text[key]
        assert 'setHint(Component.literal("搜索名称…"))' in text[key]
    assert '!busy&&coversTab)' in text['fc'] and '!busy&&!coversTab)' in text['fc']
    assert 'gamesTabButton.active=!busy()&&!scanning&&coverTab;' in text['sfc']
    assert 'coversTabButton.active=!busy()&&!scanning&&!coverTab;' in text['sfc']
    assert 'void renderWidget(' not in text['paint'],'Preview requires actual vanilla widget background'
    assert 'void renderString(' in text['paint'] and 'getMessage().getString()' in text['paint']
    return text

def actual_layouts():
    dump=ROOT/'tools/qa/CompactVanillaUiLayoutDump.java'
    with tempfile.TemporaryDirectory(prefix='piq-compact-layout-') as td:
        subprocess.run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-d',td,SOURCES['layout'],SOURCES['workbench'],dump],check=True,capture_output=True,timeout=60)
        p=subprocess.run([JAVA/'java.exe','-cp',td,'CompactVanillaUiLayoutDump'],check=True,capture_output=True,timeout=60)
    result=json.loads(p.stdout)
    for item in result:
        assert len(item['workbench'])==14 and item['supported']
        x,y,w,h=item['panel'];assert abs(2*x+w-item['width'])<=1 and abs(2*y+h-item['height'])<=1
        for name,r in item['workbench'].items():
            rx,ry,rw,rh=r;assert rw>0 and rh==20 and x<=rx and y<=ry and rx+rw<=x+w and ry+rh<=y+h,(name,item)
    return result

def sprites():
    out={};evidence={}
    with zipfile.ZipFile(MC) as jar:
        for state,suffix in (('normal',''),('disabled','_disabled'),('hover','_highlighted')):
            path='assets/minecraft/textures/gui/sprites/widget/button'+suffix+'.png'
            assert path in jar.namelist(),path
            raw=jar.read(path);meta=jar.read(path+'.mcmeta');scaling=json.loads(meta)['gui']['scaling']
            assert scaling['type']=='nine_slice'
            image=Image.open(io.BytesIO(raw)).convert('RGBA');assert image.size==(scaling['width'],scaling['height'])
            out[state]=(image,scaling['border']);evidence[path]={'sha256':digest(raw),'metadata_sha256':digest(meta),'scaling':scaling}
    return out,evidence

def sliced(source,border,width,height):
    """Nine-slice native widget; target height is the unscaled vanilla 20 GUI units."""
    result=Image.new('RGBA',(width,height));sw,sh=source.size
    sx=(0,border,sw-border,sw);sy=(0,border,sh-border,sh)
    dx=(0,border,width-border,width);dy=(0,border,height-border,height)
    for row in range(3):
        for col in range(3):
            tile=source.crop((sx[col],sy[row],sx[col+1],sy[row+1]))
            # For button sprites the horizontally repeated centers are uniform by x.
            result.alpha_composite(tile.resize((dx[col+1]-dx[col],dy[row+1]-dy[row]),Image.Resampling.NEAREST),(dx[col],dy[row]))
    return result

def palette(paint):
    return {k:tuple(bytes.fromhex(v[-6:])) for k,v in re.findall(r'(BG|PANEL|SURFACE|TEXT|MUTED|ACCENT|DANGER)=(0x[0-9A-F]+)',paint)}

def fit(draw,value,width):
    if width<=0:return ''
    clean=re.sub(r'[\x00-\x1f\x7f]',' ',value)
    if draw.textlength(clean,font=FONT)<=width:return clean
    if draw.textlength('…',font=FONT)>width:
        while clean and draw.textlength(clean,font=FONT)>width:clean=clean[:-1]
        return clean
    while clean and draw.textlength(clean+'…',font=FONT)>width:clean=clean[:-1]
    return clean+'…'

def paint(item,system,covers,source,colors,widgets):
    width,height=item['width'],item['height']
    im=Image.new('RGBA',(width,height),(100,113,118,255));im=Image.alpha_composite(im,Image.new('RGBA',im.size,(0,0,0,136)))
    d=ImageDraw.Draw(im);controls=[];truncated=[]
    def fill(r,color):x,y,w,h=r;d.rectangle((x,y,x+w-1,y+h-1),fill=color)
    def text(value,x,y,w,color='TEXT'):
        shown=fit(d,value,w)
        if shown!=value:truncated.append({'full':value,'shown':shown,'width':w})
        d.text((x,y-2),shown,font=FONT,fill=colors.get(color,color))
    def button(label,r,enabled=True,row=False,badge='',selected=False,current=False):
        x,y,w,h=r;sprite,border=widgets['normal' if enabled else 'disabled'];im.alpha_composite(sliced(sprite,border,w,h),(x,y))
        color=(255,255,255) if enabled else (160,160,160);ty=y+(h-LINE_HEIGHT)//2
        if row:
            bw=min(w//3,int(d.textlength(badge,font=FONT)))
            text('>' if selected else '·' if current else '',x+5,ty,8,color)
            text(label,x+17,ty,max(1,w-25-(bw+8 if bw else 0)),color)
            if bw:text(badge,x+w-bw-6,ty,bw,color)
        else:
            shown=fit(d,label,w-14);tw=d.textlength(shown,font=FONT)
            if shown!=label:truncated.append({'full':label,'shown':shown,'width':w-14,'button':True})
            text(shown,x+(w-tw)/2,ty,w-14,color)
        controls.append({'label':label,'rect':r,'enabled':enabled,'row':row})
    def field(value,r,hint=False):
        x,y,w,h=r;fill(r,(160,160,160));fill((x+1,y+1,w-2,h-2),(0,0,0));text(value,x+4,y+(h-LINE_HEIGHT)//2,w-8,(100,100,100) if hint else (224,224,224))
        controls.append({'field':value,'rect':r})
    def section(r):
        x,y,w,h=r;fill(r,colors['SURFACE']);fill((x,y,w,1),(119,119,119));fill((x,y,1,h),(119,119,119));fill((x,y+h-1,w,1),(232,232,232));fill((x+w-1,y,1,h),(232,232,232))
    x,y,w,h=item['panel'];fill((x-1,y-1,w+2,h+2),(16,16,16));fill(item['panel'],colors['PANEL'])
    fill((x,y,w-1,2),(255,255,255));fill((x,y,2,h-1),(255,255,255));fill((x+w-2,y+2,2,h-2),(85,85,85));fill((x+2,y+h-2,w-2,2),(85,85,85))
    title=system+' / 卡带工作台';text(title,x+10,y+11,w-20)
    current='示例卡带';text('当前卡带 · '+current,x+10,y+27,w-20,'MUTED')
    bench=item['workbench'];field(current,bench['name'])
    # Labels below are checked against the live screen source; sample state is idle/local selected.
    def source_label(key):
        match=re.search(r'(?:button|action)\("([^"\n]+)",\s*workbench\.'+key+r'\(\)',source[system.lower()])
        assert match,(system,key);return match.group(1)
    button(source_label('saveName'),bench['saveName']);button('人数：单人',bench['players'])
    button('游戏库' if covers else '> 游戏库',bench['gamesTab'],covers)
    button('> 封面' if covers else '封面',bench['coversTab'],not covers)
    field('搜索名称…',bench['search'],True);button(source_label('refresh'),bench['refresh'])
    section(item['list']);section(item['details'])
    suffix='.nes' if system=='FC' else '.sfc'
    names=['示例封面.png','另一张封面.png','含较长自定义名称的封面.png','像素封面.png','横向封面.png','备用封面.png'] if covers else ['原创示例游戏'+suffix,'另一款示例'+suffix,'示例双人冒险'+suffix,'带有较长名称的诊断游戏'+suffix,'示例动作'+suffix,'示例赛车'+suffix]
    for index,r in enumerate(item['rows'][:len(names)]):button(names[index],r,row=True,badge='PNG' if covers and system=='FC' else '本地',selected=index==0)
    dx,dy,dw,dh=item['details'];text_y=dy+3;bottom=(bench['clearCover'][1] if covers else item['primary'][1])-2
    lines=max(0,(bottom-text_y+3)//12) if system=='FC' else max(0,int((bottom-text_y-LINE_HEIGHT)/12)+1)
    heading='待应用封面' if covers else '待写入游戏';origin='本地 PNG' if covers else '本地 ROM' if system=='FC' else '本地文件';chosen=names[0]
    detail_lines=[heading,'名称：'+chosen,'来源：'+origin] if lines>=3 else [heading+' · '+chosen,'来源：'+origin] if lines>=2 else [heading+' · '+chosen] if lines==1 else []
    for index,value in enumerate(detail_lines):text(value,dx+(8 if system=='FC' else 6),text_y+index*12,dw-(16 if system=='FC' else 12),'TEXT' if index==0 else 'MUTED')
    if covers:button(source_label('clearCover'),bench['clearCover']);button(source_label('restoreCover'),bench['restoreCover'])
    primary=('上传并应用封面' if covers else '上传并写入卡带') if system=='FC' else ('应用所选封面' if covers else '写入所选游戏')
    assert '"'+primary+'"' in source[system.lower()];button(primary,item['primary'])
    for key in ('romFolder','coverFolder','previous','next','close'):button(source_label(key),bench[key],key!='previous')
    status='1 / '+str((len(names)+len(item['rows'])-1)//len(item['rows']))+' 页 · 请选择文件' if system=='FC' else '1 / '+str((len(names)+len(item['rows'])-1)//len(item['rows']))+' 页 · 6 项 · 本地 6 个'
    sx,sy,sw,sh=item['status'];text(status,sx+2,sy+5,sw-4,'MUTED')
    for i,a in enumerate(controls):
        ax,ay,aw,ah=a['rect']
        for b in controls[i+1:]:
            bx,by,bw,bh=b['rect'];assert not(ax<bx+bw and ax+aw>bx and ay<by+bh and ay+ah>by),(system,covers,a,b)
    return im.convert('RGB'),{'system':system,'covers':covers,'width':width,'height':height,'detail_lines':detail_lines,'visible_row_count':len(item['rows']),'controls':controls,'bounded_ellipsis':truncated}

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,default=ROOT/'design/compact-vanilla-ui-20260910-v1');args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    source_hashes={k:digest(p.read_bytes()) for k,p in SOURCES.items()}
    source=read_sources();geometry=actual_layouts();widgets,sprite_evidence=sprites();colors=palette(source['paint'])
    assert colors['PANEL']==(198,198,198) and colors['TEXT']==(48,48,48)
    cases=[];html_tiles=[]
    for g in geometry:
        width,height=g['width'],g['height'];scale=2 if width<=512 else 1;tilew=width*scale;tileh=height*scale
        board=Image.new('RGB',(tilew*2+36,tileh*2+138),'#dedede');draw=ImageDraw.Draw(board)
        draw.text((12,8),'实际 Java 布局 + 原版按钮 / '+str(width)+' × '+str(height)+' GUI',font=TITLE,fill='#303030')
        draw.text((12,32),'离线示意，非游戏截图；微软雅黑近似字体，示例文件不来自 ROM。',font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',13),fill='#505050')
        for index,(system,covers) in enumerate((('FC',False),('SFC',False),('FC',True),('SFC',True))):
            im,report=paint(g,system,covers,source,colors,widgets);cases.append(report)
            name=system.lower()+('-covers-' if covers else '-games-')+str(width)+'x'+str(height)+'.png'
            im.save(args.out/name);shown=im.resize((tilew,tileh),Image.Resampling.NEAREST)
            bx=12+(index%2)*(tilew+12);by=62+(index//2)*(tileh+34)
            draw.text((bx,by),system+' / '+('封面' if covers else '游戏库'),font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14),fill='#303030');board.paste(shown,(bx,by+24))
            html_tiles.append('<figure><figcaption>'+html.escape(system+(' 封面' if covers else ' 游戏库')+' '+str(width)+'×'+str(height))+'</figcaption><img src="'+name+'"></figure>')
        board.save(args.out/('comparison-'+str(width)+'x'+str(height)+'.png'))
    assert source_hashes=={k:digest(p.read_bytes()) for k,p in SOURCES.items()},'Source changed during preview: regenerate before review'
    report={'ok':True,'boundary':'Offline actual Java DeviceLayout + all 14 CartridgeWorkbenchLayout rectangles. No Minecraft/core started. Font metrics approximate; samples invented. Original Minecraft sprite bytes read only. No old preview edited.',
        'source_sha256':source_hashes,'sprite_sources':sprite_evidence,'actual_java_geometry':geometry,'cases':cases,'production_or_png_assets_changed':False}
    (args.out/'geometry.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    (args.out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>紧凑原版写卡预览</title><style>body{background:#ddd;color:#303030;font:16px system-ui;margin:24px}figure{display:inline-block;vertical-align:top;margin:12px}figcaption{padding:8px}img{image-rendering:pixelated;max-width:100%;border:1px solid #777}</style><h1>紧凑原版写卡 GUI</h1><p>非 Minecraft 截图。实际生产 Java 计算布局和全部14个工作台控件区域；真实原版按钮 sprite 与 nine-slice 元数据。示例列表与字体为近似，未启动游戏，不代表真实字体/模组冲突实测。</p>'+''.join(html_tiles),encoding='utf-8')
    print(json.dumps({'ok':True,'cases':len(cases),'out':str(args.out),'panel_sizes':[g['panel'][2:] for g in geometry]},ensure_ascii=False))
if __name__=='__main__':main()
