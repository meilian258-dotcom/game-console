// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.rom;

import net.minecraft.Util;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Local library browser. Directory IO never runs in init/render/tick, and a click loads just one file. */
public class LocalRomPickerScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final Path directory;
    private final Set<String> extensions,excludedNames;
    private final String hint;
    private final Consumer<Path> selected;
    private final Runnable close;
    private List<LocalRomLibrary.Entry> entries=List.of(),filtered=List.of();
    private LocalRomPickerLayout.Layout layout;
    private EditBox searchBox,pathBox;
    private Path focusedPath;
    private String query="",explicit="",status="正在读取游戏文件夹…";
    private int page,revision;
    private boolean initialized,scanning,loading,closed,advanced,windowActive=true;
    private Object connection;

    public LocalRomPickerScreen(Component title,Path directory,Set<String> extensions,Set<String> excludedNames,
                                String hint,Consumer<Path> selected,Runnable close) {
        super(title);this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.extensions=Set.copyOf(extensions);this.excludedNames=Set.copyOf(excludedNames);
        this.hint=Objects.requireNonNull(hint);this.selected=Objects.requireNonNull(selected);this.close=Objects.requireNonNull(close);
    }
    @Override protected void init(){
        DeviceUi.prepare();
        if(searchBox!=null)query=searchBox.getValue();if(pathBox!=null)explicit=pathBox.getValue();
        searchBox=null;pathBox=null;
        filtered=entries.stream().filter(e->e.fileName().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))).toList();
        layout=LocalRomPickerLayout.create(width,height,filtered.size(),page);page=layout.page();
        if(!layout.supported())return;
        var toolbar=layout.toolbar();int third=(toolbar.width()-8)/3;
        button("打开 ROM 文件夹",toolbar.x(),toolbar.y(),third,this::openDirectory,!loading,directory.toString());
        button("刷新列表",toolbar.x()+third+4,toolbar.y(),third,this::refresh,!scanning&&!loading,"添加文件后刷新；返回游戏窗口也会自动刷新");
        button(advanced?"返回游戏列表":"高级：指定文件",toolbar.x()+2*(third+4),toolbar.y(),toolbar.width()-2*(third+4),()->{advanced=!advanced;rebuildWidgets();},!loading,"可选：粘贴现有 ROM 路径；普通操作不需要填写");
        var search=layout.search();
        if(!advanced){
            searchBox=new EditBox(font,search.x(),search.y(),search.width(),20,Component.literal("搜索游戏名称"));
            searchBox.setMaxLength(128);searchBox.setValue(query);searchBox.setHint(Component.literal("搜索游戏名称…"));
            searchBox.setResponder(value->{query=value;page=0;searchBox=null;rebuildWidgets();});
            searchBox.setEditable(!loading);addRenderableWidget(searchBox);setInitialFocus(searchBox);
            for(int i=layout.start();i<Math.min(filtered.size(),layout.start()+layout.rows());i++){
                var entry=filtered.get(i);var row=layout.row(i-layout.start());
                addRenderableWidget(DeviceUi.row(font,entry.fileName(),formatSize(entry.bytes()),row.x(),row.y(),row.width(),row.height(),
                    ()->{focusedPath=entry.path();rebuildWidgets();},entry.path().equals(focusedPath),false,!loading&&!scanning));
            }
        }else{
            pathBox=new EditBox(font,search.x(),search.y(),search.width(),20,Component.literal("本地 ROM 完整路径"));
            pathBox.setMaxLength(2048);pathBox.setValue(explicit);pathBox.setHint(Component.literal("粘贴本地 ROM 完整路径"));
            pathBox.setEditable(!loading);addRenderableWidget(pathBox);setInitialFocus(pathBox);
        }
        var detail=layout.details();var focused=focusedEntry();
        addRenderableWidget(DeviceUi.button(font,advanced?"打开指定文件":"开始所选游戏",detail.x()+6,detail.bottom()-26,detail.width()-12,20,
            ()->{if(advanced)chooseExplicit();else{var entry=focusedEntry();if(entry!=null)choose(entry.path());}},
            !loading&&!scanning&&(advanced||focused!=null),DeviceUi.Tone.PRIMARY));
        var footer=layout.footer();int col=(footer.width()-8)/3;
        button("上一页",footer.x(),footer.y(),col,()->{page--;rebuildWidgets();},!advanced&&!loading&&page>0,"");
        button("下一页",footer.x()+col+4,footer.y(),col,()->{page++;rebuildWidgets();},!advanced&&!loading&&page+1<layout.pages(),"");
        button("关闭",footer.x()+2*(col+4),footer.y(),footer.width()-2*(col+4),this::onClose,true,"");
        if(!initialized){initialized=true;connection=minecraft.getConnection();refresh();}
    }
    private LocalRomLibrary.Entry focusedEntry(){return filtered.stream().filter(e->e.path().equals(focusedPath)).findFirst().orElse(null);}
    private void button(String label,int x,int y,int w,Runnable action,boolean active,String tooltip){
        Button button=DeviceUi.button(font,label,x,y,w,20,action,active,DeviceUi.Tone.NORMAL);
        button.setTooltip(Tooltip.create(Component.literal(clean(tooltip.isEmpty()?label:tooltip))));addRenderableWidget(button);
    }
    private boolean current(int token){return !closed&&token==revision&&minecraft!=null&&minecraft.screen==this&&minecraft.getConnection()==connection;}
    private void refresh() {
        if(closed||scanning||loading)return;scanning=true;status="正在读取游戏文件夹…";int token=++revision;
        if(!LocalRomLibrary.submit(()->{
            LocalRomLibrary.Scan result=null;String error=null;
            try{result=LocalRomLibrary.scan(LocalRomLibrary.prepare(directory),extensions,excludedNames);}
            catch(Exception failure){error="读取失败："+failure.getMessage();}
            var found=result;var message=error;
            minecraft.execute(()->{if(!current(token))return;scanning=false;
                if(found!=null){entries=found.entries();status=entries.isEmpty()?"暂无游戏：打开文件夹放入 ROM，再点刷新":
                        "找到 "+entries.size()+" 个游戏 · 选择后点击开始"+(found.limited()?"（列表已达上限）":"");}
                else{entries=List.of();status=message;}rebuildWidgets();
            });
        })){scanning=false;status="文件任务繁忙，请稍后刷新";}
        rebuildWidgets();
    }
    private void openDirectory() {
        if(closed||loading)return;int token=revision;
        if(!LocalRomLibrary.submit(()->{
            try{Path prepared=LocalRomLibrary.prepare(directory);minecraft.execute(()->{
                if(!current(token))return;
                try{Util.getPlatform().openFile(prepared.toFile());status="将 ROM 放入文件夹，回来后刷新列表";}
                catch(RuntimeException failure){status="无法打开文件夹："+failure.getMessage();}
            });}catch(Exception failure){minecraft.execute(()->{if(current(token))status="文件夹不可用："+failure.getMessage();});}
        }))status="文件任务繁忙，请稍后重试";
    }
    private void chooseExplicit() {
        try{
            explicit=pathBox.getValue().strip();String value=explicit;
            if(value.length()>=2&&value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);
            Path file=Path.of(value);if(!file.isAbsolute())throw new IllegalArgumentException("请粘贴文件的完整路径");
            choose(file);
        }catch(RuntimeException failure){status=failure.getMessage();}
    }
    private void choose(Path file) {
        if(closed||loading||scanning)return;
        String name=file.getFileName()==null?"":file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(extensions.stream().noneMatch(s->name.endsWith(s.toLowerCase(Locale.ROOT)))||excludedNames.stream().anyMatch(s->s.equalsIgnoreCase(name))){status="请选择受支持的游戏 ROM，不要选择 BIOS";return;}
        loading=true;status="正在打开游戏…";int token=++revision;rebuildWidgets();
        if(!LocalRomLibrary.submit(()->{
            String error=null;try{LocalRomLibrary.validateFile(file);}catch(Exception failure){error=failure.getMessage();}
            var message=error;
            minecraft.execute(()->{if(!current(token))return;
                if(message!=null){loading=false;status="无法读取："+message;rebuildWidgets();return;}
                try{selected.accept(file);}catch(RuntimeException failure){status="启动失败："+failure.getMessage();}
                if(minecraft.screen==this){loading=false;rebuildWidgets();}
            });
        })){loading=false;status="文件任务繁忙，请稍后重试";rebuildWidgets();}
    }
    @Override public void tick() {
        if(!initialized||closed)return;
        boolean active=minecraft.isWindowActive();
        if(active&&!windowActive&&!scanning&&!loading)refresh();windowActive=active;
    }
    @Override public void removed(){closed=true;revision++;}
    @Override public void onClose(){if(closed)return;closed=true;revision++;close.run();if(minecraft.screen==this)minecraft.setScreen(null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        if(layout==null)return;
        g.fill(0,0,width,height,DeviceUi.BG);var panel=layout.panel();
        DeviceUi.panel(g,font,panel.x(),panel.y(),panel.width(),panel.height(),title.getString(),"本地游戏库 · "+String.join(" / ",extensions));
        if(!layout.supported()){DeviceUi.text(g,font,"减小 GUI 缩放或放大窗口；Esc 关闭",panel.x()+10,panel.y()+44,panel.width()-20,DeviceUi.MUTED);return;}
        var list=layout.list();var detail=layout.details();
        DeviceUi.section(g,list.x(),list.y(),list.width(),list.height());DeviceUi.section(g,detail.x(),detail.y(),detail.width(),detail.height());
        if(advanced)paragraph(g,"高级方式：粘贴已存在的完整文件路径，再点击开始。不会扫描其他目录。",list.x()+8,list.y()+8,list.width()-16,Math.max(1,(list.height()-16)/10),DeviceUi.MUTED);
        else if(filtered.isEmpty())paragraph(g,scanning?"正在扫描…":entries.isEmpty()?"打开文件夹放入 ROM，再刷新列表。":"没有匹配项，试试更短的名称。",list.x()+8,list.y()+8,list.width()-16,Math.max(1,(list.height()-16)/10),DeviceUi.MUTED);
        var focused=focusedEntry();String label=advanced?"直接打开本地文件":focused==null?"请选择一个游戏":focused.fileName();
        if(layout.split()){
            DeviceUi.text(g,font,"已选游戏",detail.x()+8,detail.y()+7,detail.width()-16,DeviceUi.MUTED);
            DeviceUi.text(g,font,label,detail.x()+8,detail.y()+21,detail.width()-16,DeviceUi.TEXT);
            if(detail.height()>=96)DeviceUi.text(g,font,focused==null?"选择不会启动模拟器":formatSize(focused.bytes())+" · 本地文件",detail.x()+8,detail.y()+38,detail.width()-16,DeviceUi.MUTED);
            if(detail.height()>=136)paragraph(g,hint,detail.x()+8,detail.y()+56,detail.width()-16,Math.max(1,(detail.height()-90)/10),DeviceUi.MUTED);
        }else DeviceUi.text(g,font,label,detail.x()+8,detail.y()+6,detail.width()-16,DeviceUi.TEXT);
        var statusBox=layout.status();
        DeviceUi.status(g,font,(advanced?"高级":(page+1)+" / "+layout.pages()+" 页")+" · "+status,statusBox.x(),statusBox.y(),statusBox.width(),loading||scanning);
        super.render(g,mx,my,partial);
        if(mx>=statusBox.x()&&mx<statusBox.right()&&my>=statusBox.y()&&my<statusBox.bottom())g.renderTooltip(font,Component.literal(clean(status+"\n"+hint)),mx,my);
    }
    private String fit(String text,int max){String value=clean(text);if(font.width(value)<=Math.max(0,max))return value;return font.plainSubstrByWidth(value,Math.max(0,max-font.width("…")))+"…";}
    private static String clean(String text){return text==null?"":text.replaceAll("[\\p{Cntrl}&&[^\\n]]"," ");}
    private void paragraph(GuiGraphics g,String text,int x,int y,int w,int lines,int color){var split=font.split(Component.literal(clean(text)),w);for(int i=0;i<Math.min(lines,split.size());i++)g.drawString(font,split.get(i),x,y+i*10,color,false);}
    private static String formatSize(long bytes){return bytes>=1024*1024?String.format(Locale.ROOT,"%.1f MiB",bytes/(1024.0*1024)):Math.max(1,bytes/1024)+" KiB";}
}
