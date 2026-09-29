"""Offline preview of real DeviceLayout output. Fonts are an approximation; never a Minecraft screenshot."""
import argparse, hashlib, html, json, re, subprocess, tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',9)
TITLEFONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14)
NAMES={'cartridge':'FC / 卡带工作台','cover':'FC / 卡带工作台 · 封面','library':'FC / 机柜游戏库','picker':'MAME / 本地游戏库','backend':'ARCADE / 模拟器'}
NAMES.update(skin='FC / 机柜外观',catalog='FC / 存档资料库')
def rgb(value):return '#'+value[-6:]
source=(ROOT/'src/main/java/cn/piq/fcarcade/client/ui/DeviceUi.java').read_text(encoding='utf-8')
COLORS={k:rgb(v) for k,v in re.findall(r'(BG|PANEL|SURFACE|TEXT|MUTED|ACCENT|DANGER)=(0x[0-9A-F]+)',source)}
def fit(draw,text,w):
    if w<=0:return ''
    if draw.textlength(text,font=FONT)<=w:return text
    while text and draw.textlength(text+'…',font=FONT)>w:text=text[:-1]
    return text+'…' if text else ''
def paint(item):
    im=Image.new('RGB',(item['width'],item['height']),'#101619');d=ImageDraw.Draw(im)
    def fill(r,color):x,y,w,h=r;d.rectangle((x,y,x+w-1,y+h-1),fill=color)
    def text(value,x,y,w,color='TEXT'):
        d.text((x,y-2),fit(d,value,w),font=FONT,fill=COLORS.get(color,color))
    def button(label,r,tone='normal',selected=False,badge=''):
        x,y,w,h=r;fill(r,'#28443F' if selected else '#315D54' if tone=='primary' else '#171D20' if tone=='row' else '#2C3538')
        if tone!='row' or selected:d.line((x,y,x+w-1,y),fill=COLORS['ACCENT'] if selected or tone=='primary' else '#536164')
        if selected:fill([x,y,2,h],COLORS['ACCENT'])
        if tone=='row':
            bw=min(w//3,int(d.textlength(badge,font=FONT)))
            text('>' if selected else '',x+5,y+6,8,'ACCENT');text(label,x+17,y+6,w-25-(bw+8 if bw else 0))
            if badge:text(badge,x+w-bw-6,y+6,bw,'MUTED')
        else:
            shown=fit(d,label,w-14);tw=d.textlength(shown,font=FONT);text(shown,x+(w-tw)/2,y+6,w-14,'DANGER' if tone=='danger' else 'TEXT')
    def columns(labels,rect,dy=0,danger=-1):
        x,y,w,h=rect;n=len(labels);cell=(w-4*(n-1))//n
        for i,label in enumerate(labels):button(label,[x+i*(cell+4),y+dy,w-i*(cell+4) if i==n-1 else cell,20],'danger' if i==danger else 'normal')
    p=item['panel'];x,y,w,h=p;fill([x-1,y-1,w+2,h+2],'#4A5557');fill(p,COLORS['PANEL']);fill([x,y,w,2],COLORS['ACCENT']);fill([x+10,y+12,3,9],COLORS['ACCENT'])
    kind=item['kind'];text(NAMES[kind],x+20,y+11,w-30)
    current='当前机柜 · FC / NES' if kind=='backend' else '当前卡带 · 超级玛丽' if kind in ('cartridge','cover') else '当前游戏 · 魂斗罗' if kind=='library' else '支持 .zip / .7z · 选择本机 ROM'
    if kind=='skin':current='当前外观 · 内置默认外观'
    if kind=='catalog':current='浏览归属与进度 · 删除需要再次确认'
    text(current,x+10,y+27,w-20,'MUTED')
    toolbar=item['toolbar'];tx,ty,tw,th=toolbar
    if kind in ('cartridge','cover'):
        fill([tx,ty,tw-82,20],'#101618');text('超级玛丽',tx+5,ty+6,tw-92);button('保存名称',[tx+tw-78,ty,78,20])
        columns(['游戏库','> 封面','清空封面','恢复原封面'] if kind=='cover' else ['> 游戏库','封面','人数：单人','刷新'],toolbar,24,2 if kind=='cover' else -1)
    elif kind=='library':
        columns(['ROM 目录','刷新','存档库','设置','外观'],toolbar)
        columns(['存档：共享','双人游戏','重命名','删除…'],toolbar,24,3)
    elif kind=='picker':
        columns(['打开 ROM 文件夹','刷新列表','高级：指定文件'],toolbar)
        fill([tx,ty+24,tw,20],'#101618');text('搜索游戏名称…',tx+5,ty+30,tw-10,'MUTED')
    elif kind=='skin':columns(['打开皮肤文件夹','刷新列表'],toolbar)
    else:text('存档列表 / 选择后查看详情' if kind=='catalog' else '可用模拟器 / 先选择，再确认',tx,ty+6,tw,'MUTED')
    for area in ('list','details'):fill(item[area],COLORS['SURFACE'])
    names=['FC / NES','SFC / SNES','MAME / 原生街机'] if kind=='backend' else ['kof97.zip','dino.zip','mslug.zip','sf2.zip','1943.zip','超长游戏文件名的完整名称会出现在悬停提示中.zip'] if kind=='picker' else ['封面_超级玛丽.png','封面_魂斗罗.png','封面_松鼠大战.png','自定义封面.png'] if kind=='cover' else ['超级玛丽.nes','魂斗罗.nes','赤色要塞.nes','松鼠大战.nes','冒险岛.nes','双截龙.nes','热血格斗.nes','星之卡比.nes']
    if kind=='skin':names=['内置默认外观','深蓝机柜','经典红白机柜','个人外观.png']
    if kind=='catalog':names=['魂斗罗','超级玛丽','冒险岛','松鼠大战']
    for i,r in enumerate(item['rows'][:len(names)]):button(names[i],r,'row',i==0,'玩家 PIQ' if kind=='catalog' else '当前' if i==0 and kind not in ('picker','cover') else 'PNG' if kind=='cover' else '本地' if kind!='backend' else '可用')
    dx,dy,dw,dh=item['details'];chosen=names[0]
    if kind in ('cartridge','cover') and item['split']:
        text('待应用的封面' if kind=='cover' else '待写入的游戏',dx+8,dy+7,dw-16,'MUTED');text(chosen,dx+8,dy+21,dw-16)
        text('PNG · 512 × 256' if kind=='cover' else '服务器已有 · 直接写入',dx+8,dy+38,dw-16,'MUTED')
        if dh>=132:
            text('选择不会修改卡带',dx+8,dy+58,dw-16,'ACCENT');text('确认后点击下方主要行动',dx+8,dy+73,dw-16,'MUTED');text('仅 OP · 卡带需始终留在手中',dx+8,dy+91,dw-16,'MUTED')
    else:
        text(chosen,dx+6,dy+6,dw-12)
        if item['split']:
            text({'library':'服务器已有 · Mapper 0','picker':'本地文件 · 独立运行','skin':'内置默认外观','catalog':'玩家 PIQ · 2 人','backend':'piq_fc_arcade:nes'}[kind],dx+6,dy+24,dw-12,'MUTED')
            if kind=='catalog':
                text('第一关',dx+6,dy+42,dw-12,'MUTED')
                if dh>115:text('2026-09-10 20:30',dx+6,dy+62,dw-12,'MUTED')
            elif dh>112:text('选择不会立刻改变机柜' if kind=='skin' else '选择后点击下方主要行动',dx+6,dy+52,dw-12,'MUTED')
    button({'cartridge':'写入所选游戏','cover':'上传并应用封面','library':'使用所选游戏','picker':'开始所选游戏','backend':'使用此模拟器','skin':'应用所选外观','catalog':'删除所选存档…'}[kind],item['primary'],'danger' if kind=='catalog' else 'primary')
    nav=['ROM 目录','封面目录','上一页','下一页','关闭'] if kind=='cartridge' else ['封面目录','刷新','上一页','下一页','关闭'] if kind=='cover' else ['上一页','下一页','排行：开','返回'] if kind=='library' else ['上一页','返回','下一页'] if kind=='backend' else ['上一页','下一页','关闭']
    if kind in ('skin','catalog'):nav=['上一页','下一页','返回']
    columns(nav,item['navigation']);sx,sy,sw,sh=item['status'];fill(item['status'],'#151B1E');fill([sx,sy,2,sh],'#465558');text('1 / 2 页 · 先选择，再确认操作',sx+8,sy+5,sw-16,'MUTED')
    return im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,default=ROOT/'design/device-ui-preview');args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    layout=ROOT/'src/main/java/cn/piq/fcarcade/client/ui/DeviceLayout.java';probe=ROOT/'tools/qa/DeviceLayoutPreview.java'
    with tempfile.TemporaryDirectory(prefix='piq-device-preview-') as td:
        subprocess.run([JAVA/'javac.exe','-encoding','UTF-8','-d',td,layout,probe],check=True,capture_output=True,timeout=60)
        result=subprocess.run([JAVA/'java.exe','-cp',td,'DeviceLayoutPreview'],check=True,capture_output=True,timeout=60)
        items=json.loads(result.stdout)
    rows=[];tiles=[]
    for item in items:
        name=f"{item['kind']}-{item['width']}x{item['height']}.png";im=paint(item);im.save(args.out/name)
        rows.append(f'<figure><figcaption>{html.escape(NAMES[item["kind"]])} · {item["width"]}×{item["height"]}</figcaption><img src="{name}"></figure>')
        if item['width']==512:tiles.append((item,im))
    contact=Image.new('RGB',(1048,tiles[0][1].height*len(tiles)+44*len(tiles)+36),'#101619');draw=ImageDraw.Draw(contact)
    draw.text((12,8),'代码布局预览 · 非 Minecraft 游戏截图 · 字体为近似替代',font=TITLEFONT,fill='#E7E3D8');top=36
    for item,im in tiles:draw.text((12,top+4),NAMES[item['kind']]+' / 512 × 278',font=TITLEFONT,fill='#98A4A3');contact.paste(im,(12,top+30));top+=im.height+44
    contact.save(args.out/'overview.png')
    (args.out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>设备 UI 代码布局预览</title><style>body{background:#101619;color:#e7e3d8;font:16px system-ui;margin:24px}figure{display:inline-block;margin:10px;vertical-align:top}figcaption{padding:8px}img{max-width:100%;image-rendering:pixelated;border:1px solid #465558}p{max-width:900px;color:#98a4a3}</style><h1>设备 UI 代码布局预览</h1><p>非 Minecraft 游戏截图。所有面板、列表、行、详情、主要行动、导航和状态区域来自直接编译执行的生产 DeviceLayout；设备颜色取自生产 DeviceUi。示例内容和字体使用离线近似，控件组合按当前屏幕代码呈现。未运行游戏、模拟器或服务器。</p>'+''.join(rows),encoding='utf-8')
    report={'boundary':'offline code-layout preview, not a Minecraft screenshot; real production geometry, approximate font/sample content','production_layout_sha256':hashlib.sha256(layout.read_bytes()).hexdigest(),'production_paint_sha256':hashlib.sha256((ROOT/'src/main/java/cn/piq/fcarcade/client/ui/DeviceUi.java').read_bytes()).hexdigest(),'cases':items}
    (args.out/'geometry.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(args.out/'index.html')
if __name__=='__main__':main()
