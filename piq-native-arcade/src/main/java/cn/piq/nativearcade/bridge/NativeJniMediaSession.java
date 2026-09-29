// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.retro.libretro.*;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** MAME media adapter. All native work/teardown belongs to one worker, never an MC thread. */
public final class NativeJniMediaSession implements AutoCloseable {
    public record Frame(int width,int height,int[] abgr,float displayAspect,int rotation,short[] pcm48k){}
    private final NativeInputPorts inputs=new NativeInputPorts();
    private final AtomicBoolean closing=new AtomicBoolean();
    private final Object images=new Object();
    private final short[] pcm=new short[32768];
    private int start,size;
    private Frame latest;
    private volatile boolean ready,terminated;
    private volatile String failure;
    private volatile LibretroJniRuntime core;
    public static LibretroProfile profile(){
        return new LibretroProfile("MAME","zip",true,List.of(1,1,1,1),false,
            Map.of("mame_buttons_profiles","disabled","mame_thread_mode","disabled","mame_cheats_enable","disabled",
                "mame_throttle","disabled","mame_boot_to_bios","disabled","mame_boot_to_osd","disabled",
                "mame_read_config","disabled","mame_write_config","disabled","mame_auto_save","disabled"),
            Map.of("windows-x64",new LibretroProfile.Artifact(
                "/native-runtime/win-x64-v1/piq-native-arcade/runtime/mame_libretro.dll",BridgeProtocol.CORE_SHA,372431360)));
    }
    /** Kept signature-compatible with the media adapter; no helper/JNA is loaded from runtime. */
    public NativeJniMediaSession(Path runtime,Path rom)throws IOException {
        String why=unavailableReason();if(why!=null)throw new IOException(why);
        Path source=rom.toAbsolutePath().normalize();String name=source.getFileName().toString();
        if(!name.matches("[a-z0-9_]{1,32}\\.zip"))throw new IOException("街机 ZIP 必须保留驱动原名");
        Thread worker=new Thread(()->run(source,name),"GameConsole-MAME-JNI-owner");worker.setDaemon(true);worker.start();
    }
    public static String unavailableReason(){String reason=LibretroRuntimes.jniUnavailableReason();return !reason.isEmpty()?reason:LibretroRuntimes.isJniBusy()?"公共 JNI 会话容量已满或仍在退出":null;}
    public int maxPlayers(){return 4;}
    public boolean isReady(){return ready&&!closing.get()&&error()==null;}
    public boolean isTerminated(){return terminated;}
    public String error(){var c=core;String nativeError=c==null?"":c.diagnosticError();return failure!=null?failure:nativeError.isEmpty()?null:nativeError;}
    public void offerInput(int a,int b){offerInputs(a,b,0,0);}
    public void offerInputs(int a,int b,int c,int d){if(!closing.get()&&!inputs.offer(a,b,c,d)){failure="街机输入队列已满";close();}}
    public void releasePort(int port){inputs.releasePort(port);}
    public void releaseGameplayPortKeepingCoin(int port){inputs.releaseGameplayPortKeepingCoin(port);}
    public void clearInput(){inputs.clear();}
    public Frame pollFrame(){synchronized(images){if(latest==null)return null;short[] audio=new short[size];for(int n=0;n<size;n++)audio[n]=pcm[(start+n)%pcm.length];
        var f=latest;latest=null;start=size=0;return new Frame(f.width,f.height,f.abgr,f.displayAspect,f.rotation,audio);}}
    @Override public void close(){closing.set(true);inputs.clear();synchronized(images){latest=null;start=size=0;}}
    private void run(Path rom,String name){boolean safe=true;
        try {
            if(closing.get())return;
            var files=new TreeMap<String,Path>();files.put(name,rom);
            for(String bios:NativeRomStaging.AUXILIARY_NAMES){Path path=rom.getParent().resolve(bios);if(!bios.equals(name)&&Files.exists(path,LinkOption.NOFOLLOW_LINKS))files.put(bios,path);}
            core=new LibretroJniRuntime(profile(),NativeJniMediaSession.class);
            var info=core.loadFiles(name,files,null);timing(info);
            long due=System.nanoTime();
            while(!closing.get()) {
                int[] pads=inputs.nextFrame();for(int p=0;p<4;p++)pads[p]=NativeArcadeButtons.toMame(pads[p]);
                var out=core.run(List.of(new LibretroProcess.Controls(pads,0)),3);timing(out.info());
                if(out.rgba().length>0) {
                    int[] pixels=new int[out.rgba().length/4];ByteBuffer.wrap(out.rgba()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
                    // MAME supplies rotated DAR; CabinetFrame requires unrotated DAR.
                    float dar=out.info().aspect();if((core.rotation()&1)!=0)dar=1/dar;
                    synchronized(images){if(!closing.get()){
                        latest=new Frame(out.info().width(),out.info().height(),pixels,dar,core.rotation(),new short[0]);
                        for(short sample:out.stereo()){if(size==pcm.length){start=(start+1)%pcm.length;size--;}pcm[(start+size++)%pcm.length]=sample;}
                    }}ready=true;
                }
                long now=System.nanoTime();due+=(long)(1e9/out.info().fps());if(due<now-200_000_000L)due=now;
                LockSupport.parkNanos(Math.max(0,due-System.nanoTime()));
            }
        } catch(Exception|LinkageError e){failure="MAME JNI："+e.getMessage();}
        finally {
            closing.set(true);ready=false;inputs.clear();
            if(core!=null)try{core.close();}catch(RuntimeException|LinkageError e){safe=false;failure="MAME JNI 退出未确认，请正常退出客户端后重试："+e.getMessage();}
            terminated=safe; // A timed-out or blocked native close never releases an imaginary slot.
        }
    }
    private static void timing(LibretroProcess.Info info)throws IOException {
        if(info.sampleRate()!=48000||info.fps()<20||info.fps()>240)throw new IOException("MAME 音画时序与已核验配置不匹配");
    }
}
