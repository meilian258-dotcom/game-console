package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.content.*;
import cn.piq.fcarcade.client.rom.LocalRomPickerScreen;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.retro.storage.ConsoleStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.piq.fcarcade.home.content.ContentCardNetwork.*;

/** A shared, explicit-upload writer and bounded download-to-private-runtime handoff. */
public final class ContentCardClient {
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"GameConsole-card-client-io");t.setDaemon(true);return t;});
    private static Writer writer;private static Download download;private static boolean installed;
    static boolean starting(){return download!=null&&!download.ready;}
    /** Optional handheld/runtime adapter. Main-thread callbacks; no IO/native work in start. */
    public interface Runtime {
        default boolean accept(Message message){return true;}
        String start(Message message,Path rom);
        int state(Message message);
        void stop(Message message);
    }
    private static final Map<net.minecraft.resources.ResourceLocation,Runtime> RUNTIMES=new HashMap<>();
    private static final Runtime HOME=new Runtime(){
        public String start(Message m,Path p){return PrivateHomeClient.startCartridge(m.system(),m.pos(),p);}
        public int state(Message m){return PrivateHomeClient.cartridgeState(m.system(),m.pos());}
        public void stop(Message m){PrivateHomeClient.stopCartridge(m.system(),m.pos());}
    };
    public static void registerRuntime(net.minecraft.resources.ResourceLocation system,Runtime runtime){if(RUNTIMES.putIfAbsent(system,Objects.requireNonNull(runtime))!=null)throw new IllegalArgumentException("Duplicate card runtime");}
    private static Runtime runtime(Message m){return RUNTIMES.getOrDefault(m.system(),HOME);}
    private static void install(){if(installed)return;installed=true;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)->tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->{writer=null;var d=download;download=null;if(d!=null)runtime(d.message).stop(d.message);});
    }
    public static void receive(Message m){
        install();var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null)return;
        if(m.op()==OPEN){if(writer!=null)writer.cancel();writer=new Writer(m);mc.setScreen(writer);return;}
        if(m.op()==DOWNLOAD){
            if(download!=null){send(with(m,STOP,0,new byte[0]));return;}
            var a=ContentCards.adapter(m.system());if(a==null||m.size()<1||m.size()>a.maxBytes()){send(with(m,STOP,0,new byte[0]));return;}
            try{if(!runtime(m).accept(m)){send(with(m,STOP,0,new byte[0]));return;}new ContentCardStore.Entry(m.hash(),m.name(),m.size());download=new Download(m,mc.getConnection());send(with(m,GET,0,new byte[0]));}
            catch(RuntimeException invalid){send(with(m,STOP,0,new byte[0]));}return;
        }
        var d=download;
        if(d!=null&&d.message.token().equals(m.token())&&d.message.system().equals(m.system())&&d.message.pos().equals(m.pos())){
            if(m.op()==STOP){download=null;runtime(m).stop(m);return;}
            if(m.op()==DATA)receiveData(d,m);return;
        }
        var w=writer;if(w==null||!w.open.token().equals(m.token())||!w.open.system().equals(m.system()))return;
        w.last=System.nanoTime();
        if(m.op()==CARD){w.current=m.entries().isEmpty()?null:m.entries().getFirst();w.cardTitle=m.name();w.permissions=m.size();if(w.draft.isBlank())w.draft=m.name();w.refreshWidgets();return;}
        w.status=m.name();
        if(m.op()==CANCEL){w.upload=null;w.closed=true;writer=null;if(mc.screen==w)mc.setScreen(null);notice(m.name());return;}
        if(m.op()==LIST){w.entries=m.entries();w.page=m.offset();w.total=m.size();w.loading=false;w.refreshWidgets();}
        if(m.op()==STATUS){w.upload=null;w.loading=false;w.refreshWidgets();}
        if(m.op()==READY&&w.upload!=null){
            if(m.offset()!=w.offset||w.offset>=w.upload.length){w.cancel();return;}
            int end=Math.min(w.offset+ContentCardStore.CHUNK,w.upload.length);var part=Arrays.copyOfRange(w.upload,w.offset,end);int offset=w.offset;w.offset=end;
            send(with(w.open,PART,offset,part));
        }
    }
    private static void receiveData(Download d,Message m){
        if(d.started||d.busy||m.offset()!=d.offset||m.data().length<1||m.data().length>d.bytes.length-d.offset
                ||!m.hash().equals(d.message.hash())||m.size()!=d.bytes.length){stop(d,"卡带下载分片无效");return;}
        var part=m.data();System.arraycopy(part,0,d.bytes,d.offset,part.length);d.offset+=part.length;
        if(d.offset<d.bytes.length){send(with(m,GET,d.offset,new byte[0]));return;}
        d.busy=true;var mc=Minecraft.getInstance();var a=ContentCards.adapter(m.system());
        Path root=ConsoleStorage.location(mc.gameDirectory.toPath()).resolve("content-card-cache").resolve(m.system().getNamespace()).resolve(m.system().getPath());
        byte[] bytes=d.bytes;d.bytes=null;
        try{IO.execute(()->{
            Path rom=null;String error=null;
            try{
                var store=new ContentCardStore(root,a.extensions(),a.validator(),a.maxBytes());var entry=store.store(m.name(),m.hash(),bytes);
                rom=root.resolve(entry.name());
            }catch(Exception failure){error="卡带校验/缓存失败："+failure.getMessage();}
            var path=rom;var failure=error;mc.execute(()->{
                if(download!=d||mc.getConnection()!=d.connection)return;
                if(failure!=null){stop(d,failure);return;}
                try{String failed=runtime(m).start(m,path);
                    if(failed!=null){stop(d,failed);return;}d.started=true;d.busy=false;
                }catch(RuntimeException|LinkageError problem){stop(d,"卡带运行器启动失败："+problem.getClass().getSimpleName());}
            });
        });}catch(RejectedExecutionException full){stop(d,"卡带 IO 繁忙，请重试");}
    }
    private static void tick(){
        var mc=Minecraft.getInstance();var w=writer;
        if(w!=null&&w.loading&&System.nanoTime()-w.last>120_000_000_000L){w.cancel();notice("写卡请求超时；结果未确认，请重新打开核对卡带");}
        var d=download;if(d==null)return;
        if(mc.getConnection()!=d.connection||mc.player==null){download=null;runtime(d.message).stop(d.message);return;}
        if(System.nanoTime()-d.opened>120_000_000_000L&&!d.ready){stop(d,"卡带启动超时");return;}
        if(!d.started)return;
        int status;
        try{status=runtime(d.message).state(d.message);}catch(RuntimeException|LinkageError problem){stop(d,"卡带运行器状态异常");return;}
        if(status<0){stop(d,null);return;}
        if(status==1&&!d.ready){d.ready=true;send(with(d.message,STARTED,0,new byte[0]));}
        if(d.ready&&++d.ticks%40==0)send(with(d.message,HEARTBEAT,0,new byte[0]));
    }
    private static void stop(Download d,String why){
        if(download!=d)return;download=null;
        if(Minecraft.getInstance().getConnection()==d.connection)send(with(d.message,STOP,0,new byte[0]));
        runtime(d.message).stop(d.message);if(why!=null)notice(why);
    }
    private static Message with(Message m,int op,int offset,byte[] data){return msg(op,m.system(),m.token(),m.pos(),"","",0,offset,data);}
    private static void notice(String message){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(message),false);}
    private static final class Download{
        final Message message;final Object connection;final long opened=System.nanoTime();byte[] bytes;int offset,ticks;boolean busy,started,ready;
        Download(Message m,Object connection){message=m;this.connection=connection;bytes=new byte[m.size()];}
    }
    private static final class Writer extends DeviceScreen {
        final Message open;final Object connection;List<ContentCardStore.Entry> entries=List.of();String status;int page,offset,total,permissions,view;byte[] upload;boolean loading=true,closed,localMode;long last=System.nanoTime();
        ContentCardStore.Entry current;String cardTitle="",draft="",query="";Choice selected;
        List<Choice> locals=List.of();int searchTicks=-1;
        cn.piq.fcarcade.client.ui.DeviceLayout.Browser layout;
        cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout bar;
        private record Choice(String name,int size,String hash,Path path){}
        Writer(Message open){super(Component.literal("游戏卡带 · 老式电脑"));this.open=open;connection=Minecraft.getInstance().getConnection();status=open.name();}
        void refreshWidgets(){if(minecraft!=null&&minecraft.screen==this)rebuildWidgets();}
        void button(String text,int x,int y,int w,Runnable action,boolean enabled){addRenderableWidget(DeviceUi.button(font,text,x,y,w,20,action,enabled,DeviceUi.Tone.NORMAL));}
        void button(String text,cn.piq.fcarcade.client.ui.DeviceLayout.Rect r,Runnable action,boolean enabled){button(text,r.x(),r.y(),r.width(),action,enabled);}
        boolean ready(){return !loading&&!closed&&minecraft.getConnection()==connection;}
        boolean has(int flag){return (permissions&flag)!=0;}
        Path directory(){return ConsoleStorage.location(minecraft.gameDirectory.toPath()).resolve("content-cards").resolve(open.system().getNamespace()).resolve(open.system().getPath());}
        List<Choice> shown(){return localMode?locals.stream().filter(c->c.name.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))).toList()
                :entries.stream().map(e->new Choice(e.name(),e.size(),e.hash(),null)).toList();}
        @Override protected void init(){
            DeviceUi.prepare();
            layout=cn.piq.fcarcade.client.ui.DeviceLayout.browser(width,height,2);bar=cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout.of(layout);
            if(!layout.supported()){button("返回（请降低 GUI 缩放）",10,height-32,width-20,this::onClose,true);return;}
            var name=bar.name();var edit=new net.minecraft.client.gui.components.EditBox(font,name.x(),name.y(),name.width(),name.height(),Component.literal("卡带名称"));
            edit.setMaxLength(128);edit.setValue(draft);edit.setResponder(v->draft=v);edit.setEditable(ready());addRenderableWidget(edit);
            button("保存名称",bar.saveName(),this::rename,ready()&&current!=null);
            button("单人 · 个人档",bar.players(),()->{},false);
            button(localMode?"服务器":"✓ 服务器",bar.gamesTab(),()->{localMode=false;selected=null;view=0;requestPage(0);},ready()&&localMode);
            button(localMode?"✓ 本地":"本地",bar.coversTab(),()->{localMode=true;selected=null;page=0;view=0;scanLocal();},ready()&&!localMode);
            var search=bar.search();var box=new net.minecraft.client.gui.components.EditBox(font,search.x(),search.y(),search.width(),search.height(),Component.literal("搜索游戏"));
            box.setMaxLength(64);box.setHint(Component.literal("搜索游戏…"));box.setValue(query);
            box.setResponder(v->{query=v;searchTicks=24;});box.setEditable(ready());addRenderableWidget(box);
            button("刷新",bar.refresh(),()->{view=0;if(localMode)scanLocal();else requestPage(0);},ready());
            var list=shown();int rows=layout.rows();view=Math.min(view,Math.max(0,(list.size()-1)/rows));int from=view*rows;
            for(int i=0;i<Math.min(rows,list.size()-from);i++){var c=list.get(from+i);var r=layout.row(i);
                addRenderableWidget(DeviceUi.row(font,c.name,localMode?"本地":"服务器",r.x(),r.y(),r.width(),r.height(),()->{
                    selected=c;draft=c.name.replaceFirst("(?i)\\.[^.]+$","");status="已选中，尚未写入；确认名称和来源后点击写入。";refreshWidgets();
                },c.equals(selected),current!=null&&current.hash().equals(c.hash),ready()));}
            button("ROM目录",bar.romFolder(),this::openDirectory,ready());
            button("选择文件…",bar.coverFolder(),this::local,ready());
            button("上一页",bar.previous(),()->{if(view>0){view--;refreshWidgets();}else requestPage(page-1);},ready()&&(view>0||!localMode&&page>0));
            button("下一页",bar.next(),()->{if((view+1)*rows<list.size()){view++;refreshWidgets();}else requestPage(page+1);},ready()&&((view+1)*rows<list.size()||!localMode&&(page+1)*8<total));
            button("返回",bar.close(),this::onClose,true);
            boolean permitted=selected!=null&&has(selected.path==null?cn.piq.fcarcade.access.PlayerContentPolicy.SERVER_ROM_USE:cn.piq.fcarcade.access.PlayerContentPolicy.ROM_UPLOAD);
            button(selected!=null&&selected.path!=null?"上传并写入卡带":"写入卡带",layout.primary(),this::confirm,ready()&&permitted);
        }
        private void requestPage(int target){loading=true;view=0;selected=null;last=System.nanoTime();searchTicks=-1;status="正在读取服务器目录…";
            send(msg(LIST,open.system(),open.token(),open.pos(),"",query,0,target,new byte[0]));refreshWidgets();}
        private void rename(){
            if(!ready()||current==null)return;
            try{String name=ContentCardWorkbench.title(draft);loading=true;last=System.nanoTime();status="正在保存名称…";
                send(msg(RENAME,open.system(),open.token(),open.pos(),current.hash(),name,0,0,new byte[0]));refreshWidgets();
            }catch(IllegalArgumentException error){status=error.getMessage();}
        }
        private void scanLocal(){
            loading=true;last=System.nanoTime();searchTicks=-1;status="扫描本地游戏；尚未上传…";refreshWidgets();var dir=directory();var a=ContentCards.adapter(open.system());
            try{IO.execute(()->{var found=new ArrayList<Choice>();String error=null;
                try{cn.piq.fcarcade.cabinet.CabinetGameStore.directory(dir);try(var files=Files.list(dir)){
                    var paths=files.limit(ContentCardStore.MAX_FILES+1L).toList();if(paths.size()>ContentCardStore.MAX_FILES)throw new java.io.IOException("目录超过256项，请整理");
                    for(var path:paths.stream().sorted().toList()){
                        String name=path.getFileName().toString();int dot=name.lastIndexOf('.');if(dot<0||!a.extensions().contains(name.substring(dot+1).toLowerCase(Locale.ROOT)))continue;
                        long size=cn.piq.fcarcade.cabinet.CabinetGameStore.regular(path).size();if(size<1||size>a.maxBytes())continue;
                        found.add(new Choice(name,(int)size,"",path));
                    }
                }}catch(Exception ex){error=ex.getMessage();}
                var failure=error;minecraft.execute(()->{if(closed||writer!=this||minecraft.getConnection()!=connection)return;
                    loading=false;locals=List.copyOf(found);view=0;status=failure==null?"本地文件只预览；点击上传并写入后才发送服务器":"扫描失败："+failure;refreshWidgets();});
            });}catch(RejectedExecutionException full){loading=false;status="读取队列繁忙";refreshWidgets();}
        }
        private void openDirectory(){
            var dir=directory();loading=true;last=System.nanoTime();status="准备本机游戏目录…";refreshWidgets();
            try{IO.execute(()->{String error=null;try{cn.piq.fcarcade.cabinet.CabinetGameStore.directory(dir);}catch(Exception failure){error=failure.getMessage();}
                var failure=error;minecraft.execute(()->{if(closed||writer!=this||minecraft.getConnection()!=connection)return;
                    loading=false;status=failure==null?"已打开本机目录；服务器文件需放在服务器对应目录":"目录打开失败："+failure;
                    if(failure==null)net.minecraft.Util.getPlatform().openFile(dir.toFile());refreshWidgets();});
            });}catch(RejectedExecutionException full){loading=false;status="读取队列繁忙";refreshWidgets();}
        }
        private void local(){
            var a=ContentCards.adapter(open.system());if(a==null)return;
            minecraft.setScreen(new LocalRomPickerScreen(Component.literal("选择游戏（尚未上传或写卡）"),directory(),a.extensions(),Set.of(),"返回工作台确认后才上传；需要管理终端授予上传权限",path->{
                if(closed||writer!=this||Minecraft.getInstance().getConnection()!=connection)return;
                selected=new Choice(path.getFileName().toString(),0,"",path);draft=selected.name.replaceFirst("(?i)\\.[^.]+$","");
                status="已选本地文件，尚未上传；点击上传并写入卡带确认。";minecraft.setScreen(this);
            },()->{if(!closed&&writer==this&&Minecraft.getInstance().getConnection()==connection)minecraft.setScreen(this);else minecraft.setScreen(null);}));
        }
        private void confirm(){
            if(!ready()||selected==null)return;
            final String title;try{title=ContentCardWorkbench.title(draft);}catch(IllegalArgumentException error){status=error.getMessage();return;}
            var choice=selected;
            if(choice.path==null){
                loading=true;last=System.nanoTime();status="正在校验并写卡…";
                send(msg(WRITE,open.system(),open.token(),open.pos(),choice.hash,title,0,0,new byte[0]));refreshWidgets();return;
            }
            Path path=choice.path,dir=directory();var a=ContentCards.adapter(open.system());
            loading=true;last=System.nanoTime();status="确认上传：读取并校验本地游戏…";refreshWidgets();
                try{IO.execute(()->{byte[] data=null;String error=null;String digest=null;try{data=new ContentCardStore(dir,a.extensions(),a.validator(),a.maxBytes()).readPath(path);digest=ContentCardStore.hash(data);new ContentCardStore.Entry(digest,path.getFileName().toString(),data.length);}catch(Exception failure){error=failure.getMessage();}
                    var bytes=data;var failure=error;var hash=digest;minecraft.execute(()->{
                        if(closed||writer!=this||minecraft.getConnection()!=connection)return;
                        if(failure!=null){loading=false;status="读取失败："+failure;rebuildWidgets();return;}
                        upload=bytes;offset=0;send(msg(UPLOAD,open.system(),open.token(),open.pos(),hash,path.getFileName().toString(),bytes.length,0,title.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    });
                });}catch(RejectedExecutionException full){loading=false;status="读取队列繁忙";rebuildWidgets();}
        }
        void cancel(){if(closed)return;closed=true;upload=null;if(minecraft!=null&&minecraft.getConnection()==connection)send(with(open,CANCEL,0,new byte[0]));if(writer==this)writer=null;}
        @Override public void onClose(){cancel();super.onClose();}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void tick(){
            if(closed||minecraft.getConnection()!=connection){onClose();return;}
            if(searchTicks>=0&&!loading&&--searchTicks==0){view=0;selected=null;if(localMode)refreshWidgets();else requestPage(0);}
        }
        @Override public void render(GuiGraphics g,int mx,int my,float partial){
            g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();
            DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"游戏卡带 · "+ContentCards.adapter(open.system()).label()+" · 老式电脑","当前："+(current==null?"空白卡带":cardTitle));
            if(!layout.supported()){super.render(g,mx,my,partial);return;}
            var list=layout.list();var detail=layout.details();
            DeviceUi.section(g,list.x(),list.y(),list.width(),list.height());DeviceUi.section(g,detail.x(),detail.y(),detail.width(),detail.height());
            if(shown().isEmpty())DeviceUi.text(g,font,loading?"正在读取…":query.isBlank()?"目录暂无游戏":"没有匹配名称",list.x()+6,list.y()+8,list.width()-12,DeviceUi.MUTED);
            String info=selected==null?"选择游戏后预览\n点击写入才修改卡带":"待写入："+selected.name+"\n来源："+(selected.path==null?"服务器（无需上传）":"本地（确认后上传）")+"\n"+(selected.size>0?selected.size+" 字节":"将检查文件大小");
            var lines=font.split(Component.literal(info),detail.width()-12);
            for(int i=0;i<Math.min(lines.size(),Math.max(0,(layout.primary().y()-detail.y()-8)/10));i++)g.drawString(font,lines.get(i),detail.x()+6,detail.y()+5+i*10,DeviceUi.TEXT,false);
            var r=layout.status();DeviceUi.status(g,font,status,r.x(),r.y(),r.width(),loading);super.render(g,mx,my,partial);
            if(mx>=detail.x()&&mx<detail.right()&&my>=detail.y()&&my<layout.primary().y())g.renderTooltip(font,Component.literal(info+"\n私人1P，本机个人进度；此卡目前不携带存档或封面贴图。"),mx,my);
            else if(mx>=r.x()&&mx<r.right()&&my>=r.y()&&my<r.bottom())g.renderTooltip(font,Component.literal(status+"\n目录：game-console/content-cards/"+open.system().getNamespace()+"/"+open.system().getPath()+"\n“ROM目录”打开本机目录；服务器目录需在服务器对应位置放文件。"),mx,my);
        }
    }
    private ContentCardClient(){}
}
