package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.session.NesCoreVariant;
import cn.piq.fcarcade.session.ZapperInput;
import java.nio.file.Path;

/** Headless FC factory. Core ABI and light-gun profiles never share a save namespace. */
public final class NesServerCoreFactory implements ServerCoreFactory {
    @Override public int maxPlayers(){return 2;}
    @Override public String unavailableReason(ServerCoreContext context){return cn.piq.fcarcade.core.libretro.LibretroNesCore.unavailableReason();}
    @Override public ServerCoreHandle open(ServerCoreContext context,Path rom){
        boolean gun=context.nesVariant().isZapper();
        return new ServerCoreWorker(2,gun,true,()->openCore(context,rom,gun));
    }
    public static int nesMask(int libretro){
        if((libretro&~4095)!=0)throw new IllegalArgumentException("FC input bits");
        return (libretro&0xfc)|((libretro&0x100)>>>8)|((libretro&1)<<1);
    }
    private static ServerCoreWorker.Core openCore(ServerCoreContext context,Path rom,boolean gun)throws Exception{
        byte[] bytes=ServerCoreFiles.read(rom,16,32*1024*1024);
        var managed=context.managedNesState();if(managed!=null&&!managed.romSha().equalsIgnoreCase(ServerCoreFiles.sha256(bytes)))throw new java.io.IOException("Managed FC ROM identity changed");
        var selected=NesCoreVariant.forRom(cn.piq.fcarcade.rom.INesHeader.parse(bytes),gun);
        // Cabinet contexts historically omit a variant; managed homes carry explicit authority.
        if(context.nesVariant()!=selected&&(managed!=null||context.nesVariant()!=NesCoreVariant.LEGACY))throw new java.io.IOException("Hosted FC core identity changed");
        NesCore core=cn.piq.fcarcade.core.NesCores.create(selected);HostedSaveFile save=null;
        try{
            core.loadRom(bytes);
            if(!core.stateNamespace().equals(selected.stateNamespace()))throw new java.io.IOException("Hosted FC core namespace mismatch");
            String module=ServerCoreFiles.resourceHash(NesServerCoreFactory.class,cn.piq.fcarcade.core.NesCores.moduleResource(selected),4*1024*1024);
            if(managed==null)save=new HostedSaveFile(context.saveDirectory("nes"),ServerCoreFiles.sha256(bytes),core.stateNamespace()+"/"+module,2*1024*1024);
            byte[] state=managed==null?save.load():managed.initial();if(state.length>0)core.loadPersistentState(state);
            core.setControllerState(0,0);core.setControllerState(1,0);if(gun)core.setZapperState(0,0,true,false);
            HostedSaveFile ownedSave=save;
            return new ServerCoreWorker.Core(){
                private final byte[] rgba=new byte[NesCore.RGBA_BYTES];private final float[] samples=new float[4096];private final NesHostedAudio audio=new NesHostedAudio();
                @Override public double targetFps(){return 60;}
                @Override public CabinetFrame runFrame(int a,int b,int c,int d,int packed){
                    if(c!=0||d!=0)throw new IllegalArgumentException("FC has two ports");
                    core.setControllerState(0,nesMask(a));core.setControllerState(1,nesMask(b));
                    if(gun){ZapperInput.validate(packed);core.setZapperState(ZapperInput.x(packed),ZapperInput.y(packed),ZapperInput.offscreen(packed),ZapperInput.trigger(packed));}
                    else if(packed!=ZapperInput.NEUTRAL)throw new IllegalArgumentException("Standard FC cannot consume light gun input");
                    core.runFrame();core.copyFrameRgba(rgba);int[] abgr=new int[NesCore.WIDTH*NesCore.HEIGHT];
                    for(int i=0;i<abgr.length;i++){int at=i*4;abgr[i]=0xff000000|(rgba[at]&255)|((rgba[at+1]&255)<<8)|((rgba[at+2]&255)<<16);}
                    return new CabinetFrame(256,240,abgr,4F/3F,0,audio.convert(samples,core.copyAudioSamples(samples)));
                }
                @Override public void reset(){core.reset();audio.reset();if(managed!=null)managed.resetComplete();}
                @Override public void persist()throws Exception{byte[] state=core.savePersistentState();if(managed==null)ownedSave.save(state);else managed.publish(state);}
                @Override public void close(){try{core.close();}finally{if(ownedSave!=null)ownedSave.close();}}
            };
        }catch(Throwable failure){try{core.close();}finally{if(save!=null)save.close();}throw failure;}
    }
}
