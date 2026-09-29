"""Actual DeviceFormLayout geometry rendered offline; approximate font, not a game screenshot."""
import json, subprocess, tempfile, hashlib
from pathlib import Path
from PIL import Image,ImageDraw
from preview_device_ui import ROOT,JAVA,FONT,TITLEFONT,COLORS,fit
OUT=ROOT/'design/device-ui-preview'
def paint(item):
    im=Image.new('RGB',(item['width'],item['height']),'#101619');d=ImageDraw.Draw(im)
    def fill(x,y,w,h,c):d.rectangle((x,y,x+w-1,y+h-1),fill=c)
    def text(s,x,y,w,c='TEXT'):d.text((x,y-2),fit(d,s,w),font=FONT,fill=COLORS[c])
    def button(s,x,y,w,tone='normal'):
        fill(x,y,w,20,'#315D54' if tone=='primary' else '#2C3538');d.line((x,y,x+w-1,y),fill=COLORS['ACCENT'] if tone=='primary' else '#536164');shown=fit(d,s,w-14);text(shown,x+(w-d.textlength(shown,font=FONT))/2,y+6,w-14,'DANGER' if tone=='danger' else 'TEXT')
    x,y,w,h=item['panel'];fill(x-1,y-1,w+2,h+2,'#4A5557');fill(x,y,w,h,COLORS['PANEL']);fill(x,y,w,2,COLORS['ACCENT']);fill(x+10,y+12,3,9,COLORS['ACCENT'])
    kind=item['kind'];text({'settings':'FC / 设备设置','leaderboard':'FC / 排行榜面板','slots':'FC / 我的三个存档'}[kind],x+20,y+11,w-30)
    text('当前游戏 · 魂斗罗' if kind=='slots' else '修改后保存；取消不会提交',x+10,y+27,w-20,'MUTED')
    left=item['left'];bw=item['bodyWidth'];ys=item['rowY'];half=(bw-6)//2
    if kind=='slots':
        third=(bw-8)//3
        for i,label in enumerate(['槽位 1 · 已有','槽位 2 · 空','槽位 3 · 已有']):button(label,left+i*(third+4),ys[0],bw-2*(third+4) if i==2 else third,'primary' if i==0 else 'normal')
        text('魂斗罗 · 2026-09-10 20:30',left,ys[1]+6,bw,'MUTED');text('存档名称',left,ys[2]+6,item['labelWidth'])
        fill(item['fieldX'],ys[2],item['fieldWidth'],20,'#101618');text('第一关',item['fieldX']+5,ys[2]+6,item['fieldWidth']-10)
        for i,label in enumerate(['双人存档','应用信息','删除存档…']):button(label,left+i*(third+4),ys[3],bw-2*(third+4) if i==2 else third,'danger' if i==2 else 'normal')
        button('继续游戏',left,ys[4],half,'primary');button('从头开始…',left+half+6,ys[4],bw-half-6,'danger')
        footer=['按键设置','取消'];status='槽位 1 · 仅操作此槽；删除与重开需确认'
    else:
        labels=['可视距离','声音距离','音量百分比','存档保留天数'] if kind=='settings' else ['排行榜显示','轮播间隔（秒）','缩放百分比','水平偏移','垂直偏移','前后深度']
        values=['48','24','70','30'] if kind=='settings' else ['已启用','8','100','0.00','0.00','0.00']
        for i,(label,value) in enumerate(zip(labels,values)):
            text(label,left,ys[i]+6,item['labelWidth'])
            if kind=='leaderboard' and i==0:button(value,item['fieldX'],ys[i],item['fieldWidth'])
            else:fill(item['fieldX'],ys[i],item['fieldWidth'],20,'#101618');text(value,item['fieldX']+5,ys[i]+6,item['fieldWidth']-10)
        footer=['取消','保存设置'];status='数值将由服务器再次验证'
    fill(left,item['statusY'],bw,18,'#151B1E');text(status,left+8,item['statusY']+5,bw-16,'MUTED')
    button(footer[0],left,item['footerY'],half);button(footer[1],left+half+6,item['footerY'],bw-half-6,'primary' if kind!='slots' else 'normal')
    return im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST)
def main():
    sources=[ROOT/'src/main/java/cn/piq/fcarcade/client/ui'/n for n in ['DeviceLayout.java','DeviceFormLayout.java']]
    with tempfile.TemporaryDirectory(prefix='piq-form-preview-') as td:
        subprocess.run([JAVA/'javac.exe','-encoding','UTF-8','-d',td,*sources,ROOT/'tools/qa/DeviceFormPreview.java'],check=True,capture_output=True,timeout=60)
        result=subprocess.run([JAVA/'java.exe','-cp',td,'DeviceFormPreview'],check=True,capture_output=True,timeout=60);items=json.loads(result.stdout)
    OUT.mkdir(parents=True,exist_ok=True);rows=[]
    for item in items:
        name=f"{item['kind']}-{item['width']}x{item['height']}.png";paint(item).save(OUT/name);rows.append(f'<figure><figcaption>{item["kind"]} · {item["width"]}×{item["height"]}</figcaption><img src="{name}"></figure>')
    (OUT/'forms.html').write_text('<!doctype html><meta charset="utf-8"><title>设备表单布局预览</title><style>body{background:#101619;color:#e7e3d8;font:16px system-ui;margin:24px}figure{display:inline-block;margin:10px}img{max-width:100%;image-rendering:pixelated}</style><h1>辅助表单 · 代码布局预览</h1><p>非 Minecraft 游戏截图。布局坐标来自真实生产 DeviceFormLayout，文字示例及字体为离线近似。</p><a href="index.html">主界面预览</a>'+''.join(rows),encoding='utf-8')
    (OUT/'forms-geometry.json').write_text(json.dumps({'boundary':'actual production geometry; no game launched; approximate font/content','sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'cases':items},ensure_ascii=False,indent=2),encoding='utf-8');print(OUT/'forms.html')
if __name__=='__main__':main()
