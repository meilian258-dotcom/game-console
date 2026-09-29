package cn.piq.computer.client;
import cn.piq.computer.ProgramKind;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded, non-recursive local file picker. Browsing never starts a program. */
final class ProgramFileScreen extends DeviceScreen {
    private record Entry(Path path,boolean directory){}
    private final Screen parent;private final ProgramKind kind;private final Consumer<String> selected;
    private Path directory;private List<Entry> entries=List.of();private String status="";
    private EditBox folder;private int page,rows,request;
    ProgramFileScreen(Screen parent,ProgramKind kind,String current,Consumer<String> selected){super(Component.literal("选择 "+(kind==ProgramKind.PVZ?"main.pak":"Flash 文件")));this.parent=parent;this.kind=kind;this.selected=selected;
        directory=net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();try{var p=Path.of(current.trim().replaceAll("^\"|\"$",""));if(p.isAbsolute())directory=Files.isDirectory(p)?p:p.getParent();}catch(Exception ignored){}
    }
    @Override protected void init(){rows=Math.max(1,Math.min(9,(height-145)/23));widgets();scan();}
    private void widgets(){clearWidgets();int left=width/2-150;
        folder=new EditBox(font,left,35,300,20,Component.literal("目录"));folder.setMaxLength(2048);folder.setValue(directory.toString());addRenderableWidget(folder);
        addRenderableWidget(Button.builder(Component.literal("上级目录"),b->{if(directory.getParent()!=null){directory=directory.getParent();page=0;widgets();scan();}}).bounds(left,61,96,20).build());
        addRenderableWidget(Button.builder(Component.literal("切换磁盘"),b->{var roots=File.listRoots();if(roots.length>0){int at=0;for(int i=0;i<roots.length;i++)if(roots[i].toPath().equals(directory.getRoot()))at=(i+1)%roots.length;directory=roots[at].toPath();page=0;widgets();scan();}}).bounds(left+102,61,96,20).build());
        addRenderableWidget(Button.builder(Component.literal("打开目录"),b->{try{var p=Path.of(folder.getValue());if(!p.isAbsolute())throw new IllegalArgumentException();directory=p.normalize();page=0;widgets();scan();}catch(Exception ex){status="请输入本机目录的完整路径";}}).bounds(left+204,61,96,20).build());
        for(int i=0;i<rows;i++){int index=page*rows+i;if(index>=entries.size())break;var entry=entries.get(index);String name=(entry.directory?"[目录] ":"")+entry.path.getFileName();addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name,280)),b->{if(entry.directory){directory=entry.path;page=0;widgets();scan();}else selected.accept(entry.path.toString());}).bounds(left,89+i*23,300,20).build());}
        addRenderableWidget(Button.builder(Component.literal("上一页"),b->{page--;widgets();}).bounds(left,height-42,96,20).build()).active=page>0;
        addRenderableWidget(Button.builder(Component.literal("下一页"),b->{page++;widgets();}).bounds(left+102,height-42,96,20).build()).active=(page+1)*rows<entries.size();
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+204,height-42,96,20).build());
    }
    private void scan(){int serial=++request;Path p=directory;status="正在读取目录…";entries=List.of();widgets();
        CompletableFuture.supplyAsync(()->{try(var paths=Files.list(p)){return paths.limit(4097).map(f->new Entry(f,Files.isDirectory(f))).filter(e->e.directory||kind.accepts(e.path)).sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(e->e.path.getFileName().toString(),String.CASE_INSENSITIVE_ORDER)).toList();}catch(Exception ex){throw new RuntimeException("无法读取此目录",ex);}}).whenComplete((list,error)->minecraft.execute(()->{if(request!=serial||minecraft.screen!=this)return;entries=error==null?list:List.of();page=0;status=error==null?(entries.isEmpty()?"没有找到文件":"仅显示目录及匹配文件；大目录最多扫描 4097 项"):"无法读取目录，请检查路径或权限";widgets();}));
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){request++;minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int x,int y,float dt){g.fill(0,0,width,height,0xf0101820);g.drawCenteredString(font,title,width/2,15,0xffffff);g.drawString(font,font.plainSubstrByWidth(status,300),width/2-150,height-57,0xe1c77b,false);super.render(g,x,y,dt);}
}
