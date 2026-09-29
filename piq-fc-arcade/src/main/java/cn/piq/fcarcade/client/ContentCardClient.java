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
    private static void install(){if(installed)return;installed=true;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)->tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->{writer=null;var d=download;download=null;if(d!=null)PrivateHomeClient.stopCartridge(d.message.system(),d.message.pos());});
    }
    public static void receive(Message m){
        install();var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null)return;
        if(m.op()==OPEN){if(writer!=null)writer.cancel();writer=new Writer(m);mc.setScreen(writer);return;}
        if(m.op()==DOWNLOAD){
            if(download!=null){send(with(m,STOP,0,new byte[0]));return;}
            var a=ContentCards.adapter(m.system());if(a==null||m.size()<1){send(with(m,STOP,0,new byte[0]));return;}
            try{new ContentCardStore.Entry(m.hash(),m.name(),m.size());download=new Download(m,mc.getConnection());send(with(m,GET,0,new byte[0]));}
            catch(RuntimeException invalid){send(with(m,STOP,0,new byte[0]));}return;
        }
        var d=download;
        if(d!=null&&d.message.token().equals(m.token())&&d.message.system().equals(m.system())&&d.message.pos().equals(m.pos())){
            if(m.op()==STOP){download=null;PrivateHomeClient.stopCartridge(m.system(),m.pos());return;}
            if(m.op()==DATA)receiveData(d,m);return;
        }
        var w=writer;if(w==null||!w.open.token().equals(m.token())||!w.open.system().equals(m.system()))return;
        w.status=m.name();w.last=System.nanoTime();
        if(m.op()==CANCEL){w.upload=null;w.closed=true;writer=null;if(mc.screen==w)mc.setScreen(null);notice(m.name());return;}
        if(m.op()==LIST){w.entries=m.entries();w.page=m.offset();w.loading=false;w.refreshWidgets();}
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
                var store=new ContentCardStore(root,a.extensions(),a.validator());var entry=store.store(m.name(),m.hash(),bytes);
                rom=root.resolve(entry.name());
            }catch(Exception failure){error="卡带校验/缓存失败："+failure.getMessage();}
            var path=rom;var failure=error;mc.execute(()->{
                if(download!=d||mc.getConnection()!=d.connection)return;
                if(failure!=null){stop(d,failure);return;}
                String failed=PrivateHomeClient.startCartridge(m.system(),m.pos(),path);
                if(failed!=null){stop(d,failed);return;}d.started=true;d.busy=false;
            });
        });}catch(RejectedExecutionException full){stop(d,"卡带 IO 繁忙，请重试");}
    }
    private static void tick(){
        var mc=Minecraft.getInstance();var w=writer;
        if(w!=null&&w.loading&&System.nanoTime()-w.last>120_000_000_000L){w.cancel();notice("写卡请求超时，原卡未改动");}
        var d=download;if(d==null)return;
        if(mc.getConnection()!=d.connection||mc.player==null){download=null;PrivateHomeClient.stopCartridge(d.message.system(),d.message.pos());return;}
        if(System.nanoTime()-d.opened>120_000_000_000L&&!d.ready){stop(d,"卡带启动超时");return;}
        if(!d.started)return;
        int status=PrivateHomeClient.cartridgeState(d.message.system(),d.message.pos());
        if(status<0){stop(d,null);return;}
        if(status==1&&!d.ready){d.ready=true;send(with(d.message,STARTED,0,new byte[0]));}
        if(d.ready&&++d.ticks%40==0)send(with(d.message,HEARTBEAT,0,new byte[0]));
    }
    private static void stop(Download d,String why){
        if(download!=d)return;download=null;
        if(Minecraft.getInstance().getConnection()==d.connection)send(with(d.message,STOP,0,new byte[0]));
        PrivateHomeClient.stopCartridge(d.message.system(),d.message.pos());if(why!=null)notice(why);
    }
    private static Message with(Message m,int op,int offset,byte[] data){return msg(op,m.system(),m.token(),m.pos(),"","",0,offset,data);}
    private static void notice(String message){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(message),false);}
    private static final class Download{
        final Message message;final Object connection;final long opened=System.nanoTime();byte[] bytes;int offset,ticks;boolean busy,started,ready;
        Download(Message m,Object connection){message=m;this.connection=connection;bytes=new byte[m.size()];}
    }
    private static final class Writer extends DeviceScreen {
        final Message open;final Object connection;List<ContentCardStore.Entry> entries=List.of();String status;int page,offset;byte[] upload;boolean loading=true,closed;long last=System.nanoTime();
        Writer(Message open){super(Component.literal("游戏卡带 · 老式电脑"));this.open=open;connection=Minecraft.getInstance().getConnection();status=open.name();}
        void refreshWidgets(){if(minecraft!=null&&minecraft.screen==this)rebuildWidgets();}
        void button(String text,int x,int y,int w,Runnable action,boolean enabled){addRenderableWidget(DeviceUi.button(font,text,x,y,w,20,action,enabled,DeviceUi.Tone.NORMAL));}
        @Override protected void init(){
            DeviceUi.prepare();
            if(height<278||width<250){button("返回（请降低 GUI 缩放）",10,height-32,width-20,this::onClose,true);return;}
            int w=Math.min(420,width-24),x=(width-w)/2,y=Math.max(28,(height-250)/2);
            button("本地游戏：选择后上传并写卡",x,y,w,this::local,!loading&&!closed);
            button("刷新服务器游戏",x,y+24,w,()->requestPage(page),!loading&&!closed);
            for(int i=0;i<entries.size();i++){var e=entries.get(i);button(font.plainSubstrByWidth(e.name(),w-12),x,y+50+i*20,w,()->{loading=true;status="正在校验并写卡…";last=System.nanoTime();send(msg(WRITE,open.system(),open.token(),open.pos(),e.hash(),"",0,0,new byte[0]));rebuildWidgets();},!loading&&!closed);}
            int bottom=Math.min(height-28,y+215);int third=(w-8)/3;
            button("上一页",x,bottom,third,()->requestPage(page-1),page>0&&!loading&&!closed);
            button("下一页",x+third+4,bottom,third,()->requestPage(page+1),entries.size()==8&&!loading&&!closed);
            button("返回",x+(third+4)*2,bottom,w-(third+4)*2,this::onClose,true);
        }
        private void requestPage(int target){loading=true;last=System.nanoTime();send(with(open,LIST,target,new byte[0]));rebuildWidgets();}
        private void local(){
            var a=ContentCards.adapter(open.system());if(a==null)return;
            Path dir=ConsoleStorage.location(minecraft.gameDirectory.toPath()).resolve("content-cards").resolve(open.system().getNamespace()).resolve(open.system().getPath());
            minecraft.setScreen(new LocalRomPickerScreen(Component.literal("选择 ROM：将上传到服务器并写入当前卡带"),dir,a.extensions(),Set.of(),"需管理员授予 ROM 上传权限；不会覆盖原游戏文件",path->{
                minecraft.setScreen(this);loading=true;last=System.nanoTime();status="读取并校验本地游戏…";rebuildWidgets();
                try{IO.execute(()->{byte[] data=null;String error=null;String digest=null;try{data=new ContentCardStore(dir,a.extensions(),a.validator()).readPath(path);digest=ContentCardStore.hash(data);new ContentCardStore.Entry(digest,path.getFileName().toString(),data.length);}catch(Exception failure){error=failure.getMessage();}
                    var bytes=data;var failure=error;var hash=digest;minecraft.execute(()->{
                        if(closed||writer!=this||minecraft.getConnection()!=connection)return;
                        if(failure!=null){loading=false;status="读取失败："+failure;rebuildWidgets();return;}
                        upload=bytes;offset=0;send(msg(UPLOAD,open.system(),open.token(),open.pos(),hash,path.getFileName().toString(),bytes.length,0,new byte[0]));
                    });
                });}catch(RejectedExecutionException full){loading=false;status="读取队列繁忙";rebuildWidgets();}
            },()->minecraft.setScreen(this)));
        }
        void cancel(){if(closed)return;closed=true;upload=null;if(minecraft!=null&&minecraft.getConnection()==connection)send(with(open,CANCEL,0,new byte[0]));if(writer==this)writer=null;}
        @Override public void onClose(){cancel();super.onClose();}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void render(GuiGraphics g,int mx,int my,float partial){
            g.fill(0,0,width,height,DeviceUi.BG);g.drawCenteredString(font,title,width/2,8,0xffffff);
            g.drawString(font,font.plainSubstrByWidth(status,width-24),12,height-12,0xffffff);super.render(g,mx,my,partial);
        }
    }
    private ContentCardClient(){}
}
