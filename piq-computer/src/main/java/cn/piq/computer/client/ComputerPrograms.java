package cn.piq.computer.client;

import cn.piq.computer.net.ComputerNetwork;
import cn.piq.computer.ProgramKind;
import cn.piq.computer.world.*;
import cn.piq.fcarcade.home.*;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.fml.ModList;
import org.lwjgl.system.MemoryUtil;

/** One local program, staged asynchronously. Never receives executable/content paths from the server. */
public final class ComputerPrograms {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Computer-Programs");t.setDaemon(true);return t;});
    private static final Properties OPTIONS=new Properties();
    private static boolean optionsLoaded,ending;
    private static Future<ProgramBackend> loading;
    private static ProgramBackend backend;
    private static ProgramKind running=ProgramKind.HARDWARE;
    private static ComputerNetwork.Open target;
    private static Object connection;
    private static DynamicTexture texture;private static ResourceLocation textureId;private static ByteBuffer pixels;
    private static String notice="仅硬件测试";
    private static Path optionsPath(){return Minecraft.getInstance().gameDirectory.toPath().resolve("config/piq-computer-client.properties");}
    private static void loadOptions(){if(optionsLoaded)return;optionsLoaded=true;var p=optionsPath();try{if(Files.isRegularFile(p)&&Files.size(p)<65536)try(var in=Files.newInputStream(p)){OPTIONS.load(in);}}catch(Exception ex){notice="程序设置读取失败："+ex.getMessage();}}
    public static String path(ComputerEntity pc,ProgramKind kind){loadOptions();String value=OPTIONS.getProperty(pc.hardwareId()+"."+kind.name().toLowerCase(Locale.ROOT),"");if(value.isBlank())return value;try{return cn.piq.retro.storage.ConsoleStorage.rebind(Minecraft.getInstance().gameDirectory.toPath(),Path.of(value)).toString();}catch(java.nio.file.InvalidPathException e){return value;}}
    public static ProgramKind selection(ComputerEntity pc){loadOptions();return ProgramKind.parse(OPTIONS.getProperty(pc.hardwareId()+".kind",path(pc,ProgramKind.PVZ).isEmpty()?"HARDWARE":"PVZ"));}
    public static boolean available(ProgramKind kind){return kind!=ProgramKind.PVZ||ModList.get().isLoaded("piq_pvz");}
    public static boolean busy(){return backend!=null||loading!=null||ending;}
    public static boolean pvzJni(){loadOptions();return "jni-v1".equals(OPTIONS.getProperty("pvz.engine","process"));}
    public static boolean pvzJni(boolean enabled){
        if(busy()){notice="请先结束当前程序再切换运行器";return false;}
        loadOptions();var previous=new Properties();previous.putAll(OPTIONS);OPTIONS.setProperty("pvz.engine",enabled?"jni-v1":"process");
        try{saveOptions();return true;}catch(Exception e){OPTIONS.clear();OPTIONS.putAll(previous);notice="运行器设置未保存："+e.getMessage();return false;}
    }
    public static boolean sharing(){loadOptions();return "true".equals(OPTIONS.getProperty("stream.enabled"));}
    public static int streamTier(){loadOptions();try{return Math.clamp(Integer.parseInt(OPTIONS.getProperty("stream.tier","1")),0,2);}catch(NumberFormatException e){return 1;}}
    public static void streamOptions(boolean enabled,int tier){loadOptions();OPTIONS.setProperty("stream.enabled",Boolean.toString(enabled));OPTIONS.setProperty("stream.tier",Integer.toString(Math.clamp(tier,0,2)));try{saveOptions();}catch(Exception e){notice="共享设置未保存："+e.getMessage();}}
    public static boolean fixedPointer(){loadOptions();return !"aim".equals(OPTIONS.getProperty("input.mode"));}
    public static double pointerSensitivity(){loadOptions();try{double v=Double.parseDouble(OPTIONS.getProperty("input.sensitivity","0.75"));return Double.isFinite(v)?Math.clamp(v,.25,2):.75;}catch(NumberFormatException e){return .75;}}
    public static boolean inputOptions(boolean fixed,double speed){
        loadOptions();var previous=new Properties();previous.putAll(OPTIONS);
        OPTIONS.setProperty("input.mode",fixed?"fixed":"aim");OPTIONS.setProperty("input.sensitivity",Double.toString(Double.isFinite(speed)?Math.clamp(speed,.25,2):.75));
        try{saveOptions();return true;}catch(Exception e){OPTIONS.clear();OPTIONS.putAll(previous);notice="输入设置保存失败："+e.getMessage();return false;}
    }
    private static void saveOptions()throws java.io.IOException{
        var p=optionsPath();Files.createDirectories(p.getParent());var temp=Files.createTempFile(p.getParent(),"computer-",".tmp");
        try{try(var out=Files.newOutputStream(temp)){OPTIONS.store(out,"Local program and input settings; never sent to a server");}Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
    }
    public static boolean configure(ComputerEntity pc,ProgramKind kind,String value){
        if(busy()){notice="请先结束当前程序，待退出完成后再切换";return false;}
        loadOptions();var previous=new Properties();previous.putAll(OPTIONS);String key=pc.hardwareId()+"."+kind.name().toLowerCase(Locale.ROOT);String clean=value.trim().replaceAll("^\"|\"$","");
        if(clean.length()>2048){notice="路径过长";return false;}
        if(kind!=ProgramKind.HARDWARE)try{var p=Path.of(clean);if(!p.isAbsolute()){notice="请选择本机文件的完整路径";return false;}p=cn.piq.retro.storage.ConsoleStorage.rebind(Minecraft.getInstance().gameDirectory.toPath(),p);if(!kind.accepts(p)||!Files.isRegularFile(p)){notice="请选择本机 "+(kind==ProgramKind.PVZ?"main.pak":".swf")+" 文件";return false;}clean=p.toString();}catch(Exception ex){notice="路径检查失败："+ex.getMessage();return false;}
        if(kind!=ProgramKind.HARDWARE)OPTIONS.setProperty(key,clean);OPTIONS.setProperty(pc.hardwareId()+".kind",kind.name());
        try{saveOptions();}
        catch(Exception ex){OPTIONS.clear();OPTIONS.putAll(previous);notice="设置保存失败："+ex.getMessage();return false;}
        notice="已选择"+kind.label+"；右键键鼠开始";return true;
    }
    public static void begin(ComputerNetwork.Open open){
        var pc=ComputerClient.current(open);if(pc==null)return;
        if(ComputerStreams.remote(open.id())){notice="接管共享电脑；游戏与存档仍在运行者端";return;}
        if(target!=null&&target.id().equals(open.id())&&connection==Minecraft.getInstance().getConnection()&&(backend!=null||loading!=null))return;
        if(backend!=null||loading!=null||ending){notice="另一程序正在运行或退出，请稍后再试";return;}
        var kind=selection(pc);var file=path(pc,kind);if(kind==ProgramKind.HARDWARE){notice="硬件输入测试";return;}
        if(!available(kind)){notice="运行 PvZ 需配套 PvZ prototype.11 附属";say(notice);return;}
        var mc=Minecraft.getInstance();var root=mc.gameDirectory.toPath();var player=mc.player.getUUID();
        target=open;connection=mc.getConnection();running=kind;notice="正在启动 "+kind.label+"…";
        if(sharing())ComputerStreams.start(open,kind.ordinal(),streamTier());
        boolean jni=pvzJni();
        loading=IO.submit(()->{var content=Path.of(file);if(!content.isAbsolute()||!kind.accepts(content))throw new java.io.IOException("程序文件路径无效，请重新选择");return kind==ProgramKind.PVZ?new PvzComputerBackend(root,content,player,jni):new FlashComputerBackend(root,content);});
    }
    private static boolean valid(){var pc=target==null?null:ComputerStreams.hosting()?ComputerStreams.current(new cn.piq.computer.net.ComputerStreamNetwork.Key(target.dimension(),target.pos(),target.id())):ComputerClient.current(target);var mc=Minecraft.getInstance();return pc!=null&&mc.getConnection()==connection&&mc.player.isAlive()&&!mc.player.isSpectator()&&pc.powered&&pc.inputReady()&&(ComputerStreams.hosting()||pc.operator==null||mc.player.getUUID().equals(pc.operator))&&pc.televisionPos()!=null&&mc.level.hasChunkAt(pc.televisionPos())&&mc.level.getBlockEntity(pc.televisionPos()) instanceof HomeTvBlockEntity tv&&tv.powered()&&HomeHardware.connected(mc.level,pc,tv);}
    public static boolean localInput(){return target!=null&&(backend!=null||loading!=null);}
    public static boolean localInput(UUID computer){return localInput()&&target.id().equals(computer);}
    public static ProgramBackend backend(){return backend;}
    public static String status(){return backend==null?notice:backend.status();}
    public static ResourceLocation texture(ComputerEntity pc){return backend!=null&&backend.ready()&&target!=null&&pc.hardwareId().equals(target.id())&&valid()?textureId:null;}
    private static void releaseTexture(){if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);else if(texture!=null)texture.close();textureId=null;texture=null;if(pixels!=null)MemoryUtil.memFree(pixels);pixels=null;}
    public static void stop(){
        ComputerStreams.stopHost();
        var old=backend;var pending=loading;backend=null;loading=null;target=null;connection=null;releaseTexture();
        if(old==null&&pending==null)return;ending=true;var kind=running;notice=kind==ProgramKind.PVZ?"正在结束并保存…":"正在结束 Flash…";
        IO.execute(()->{String problem="";try{var p=old!=null?old:pending.get();if(p!=null){problem=p.error();p.close();if(problem==null||problem.isEmpty())problem=p.error();}}catch(Exception ex){problem=ex.getMessage();}String error=problem;Minecraft.getInstance().execute(()->{ending=false;notice=error==null||error.isEmpty()?(kind==ProgramKind.PVZ?"PvZ 已正常结束；进度由游戏保存":"Flash 已结束（本版不保存进度）"):"退出未确认："+error;say(notice);});});
    }
    public static void maintain(boolean upload){
        if(target==null)return;
        if(!valid()){stop();return;}
        if(loading!=null&&loading.isDone())try{backend=loading.get();loading=null;}catch(Exception ex){ComputerStreams.stopHost();loading=null;target=null;connection=null;notice=running.label+" 启动失败："+(ex.getCause()==null?ex.getMessage():ex.getCause().getMessage());say(notice);return;}
        if(backend==null)return;var mc=Minecraft.getInstance();
        if(backend.finished()){String error=backend.error();stop();notice=running.label+" 已停止："+error;say(notice);return;}
        ComputerStreams.attach(backend);
        backend.pause(ComputerStreams.hosting()?mc.isPaused()||!ComputerStreams.playing():!mc.isWindowActive()||mc.isPaused()||mc.screen!=null||!ComputerWorldInput.controlling(target.id()));
        backend.volume(mc.options.getSoundSourceVolume(SoundSource.MASTER)*.7f);
        if(!upload)return;var data=backend.frame();if(data==null)return;
        try{RenderSystem.assertOnRenderThread();int w=backend.width(),h=backend.height();
            ComputerStreams.frame(data,w,h);
            if(texture==null){texture=new DynamicTexture(w,h,false);texture.setFilter(false,false);textureId=mc.getTextureManager().register("computer_program",texture);pixels=MemoryUtil.memAlloc(w*h*4);}
            pixels.clear();pixels.put(data).flip();RenderSystem.bindTexture(texture.getId());GlStateManager._pixelStore(3314,0);GlStateManager._pixelStore(3316,0);GlStateManager._pixelStore(3315,0);GlStateManager._pixelStore(3317,4);GlStateManager._texSubImage2D(3553,0,0,0,w,h,6408,5121,MemoryUtil.memAddress(pixels));
        }finally{backend.releaseFrame(data);}
    }
    public static void releaseInput(){if(backend!=null&&!ComputerStreams.hosting()){backend.releaseInput();backend.pause(true);}}
    private static void say(String text){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(text),false);}
    public static void shutdown(){stop();IO.shutdown();try{IO.awaitTermination(18,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
    private ComputerPrograms(){}
}
