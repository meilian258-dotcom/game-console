// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.core;

import cn.piq.retro.libretro.*;
import cn.piq.sfcarcade.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

/** SFC adapter. Native work belongs to its owner; supported Windows modes default to common JNI. */
public final class LibretroSfcCore implements SfcCore {
    private static final int MAGIC=0x50534631, MAX=16*1024*1024;
    public static final String BUILD="mesen-s-piq1-8aca17e7";
    private final Thread owner=Thread.currentThread();
    private final SfcPcmResampler resampler=new SfcPcmResampler();
    private final java.util.function.Supplier<? extends LibretroRuntime> runtimeFactory;
    private volatile LibretroRuntime core;
    private volatile LibretroRuntime opening;
    private byte[] content,identity,rgba=new byte[0];
    private short[] pcm=new short[0];
    private SfcVideoMode mode;
    private long frame;
    private boolean closed;

    /** Shared, local-sync and server-hosted callers share the same native adapter. */
    public LibretroSfcCore(){this(LibretroRuntimes.Backend.JNI_TRIAL);}
    public LibretroSfcCore(LibretroRuntimes.Backend backend){
        this(()->LibretroRuntimes.create(profile(),LibretroSfcCore.class,backend));
        Objects.requireNonNull(backend);
    }
    /** A fresh owner-thread runtime is required after hard reset or SRAM recreation. */
    public LibretroSfcCore(java.util.function.Supplier<? extends LibretroRuntime> factory){runtimeFactory=Objects.requireNonNull(factory);}

    public static LibretroProfile profile(){return new LibretroProfile("Mesen-S","sfc",false,List.of(257,257),false,Map.ofEntries(
        Map.entry("mesen-s_region","Auto"),Map.entry("mesen-s_ramstate","All 0s"),
        Map.entry("mesen-s_ntsc_filter","Disabled"),Map.entry("mesen-s_aspect_ratio","Auto"),
        Map.entry("mesen-s_overscan_vertical","None"),Map.entry("mesen-s_overscan_horizontal","None"),
        Map.entry("mesen-s_blend_high_res","disabled"),Map.entry("mesen-s_cubic_interpolation","disabled"),
        Map.entry("mesen-s_overclock","None"),Map.entry("mesen-s_overclock_type","Before NMI"),
        Map.entry("mesen-s_superfx_overclock","100%"),Map.entry("mesen-s_hle_coprocessor","enabled")),Map.of(
        "windows-x64",new LibretroProfile.Artifact("/core/sfc-libretro/windows-x64/mesen-s_libretro.dll","8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42")));
    }
    /** This first candidate is Windows x64 only; do not advertise an untested Linux core as ready. */
    public static String unavailableReason(){try{return profile().cores().containsKey(LibretroProcess.platform())?"":"SFC libretro 测试版暂仅支持 Windows x64";}catch(RuntimeException e){return "SFC libretro 测试版暂仅支持 Windows x64";}}
    public static String saveNamespace(){return "sfc-libretro-state-v1/"+BUILD;}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("SFC core accessed outside worker");if(closed)throw new IllegalStateException("SFC core closed");}
    private void loaded(){check();if(core==null)throw new IllegalStateException("SFC ROM not loaded");}
    @Override public String backendName(){check();return "Mesen-S / libretro";}
    /** Thread-safe watchdog display only; it must never enter the owner-thread native API. */
    public String diagnosticError(){var runtime=core;if(runtime==null)runtime=opening;return runtime==null?"":runtime.diagnosticError();}
    @Override public void loadRom(SfcRomImage rom){check();if(content!=null)throw new IllegalStateException("SFC ROM already loaded");content=rom.copyPayload();start();}
    private void start(){
        String unavailable=unavailableReason();if(!unavailable.isEmpty())throw new IllegalStateException(unavailable);
        LibretroRuntime next=Objects.requireNonNull(runtimeFactory.get(),"Runtime factory returned null");
        opening=next;
        try{mode=video(next.load(content));identity=next.persistenceIdentity();core=next;clear();opening=null;}
        catch(RuntimeException|Error failure){
            try{next.close();opening=null;}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private static SfcVideoMode video(LibretroProcess.Info av){
        if(av.sampleRate()!=SfcPcmResampler.INPUT||!(av.fps()>=49&&av.fps()<=51||av.fps()>=59&&av.fps()<=61))
            throw new IllegalStateException("SFC pinned core returned unsupported AV timing");
        double pixel=av.aspect()>0?av.aspect()*av.height()/av.width():1;
        return new SfcVideoMode(av.width(),av.height(),av.width()*4,pixel,av.fps());
    }
    private void clear(){frame=0;resampler.clear();rgba=new byte[mode.requiredRgbaBytes()];pcm=new short[0];}
    @Override public SfcFrameResult runFrame(SfcControllerState a,SfcControllerState b){
        loaded();var out=core.run(List.of(new LibretroProcess.Controls(new int[]{a.mask(),b.mask()},0)),LibretroProcess.VIDEO|LibretroProcess.AUDIO);
        mode=video(out.info());rgba=out.rgba();pcm=resampler.convert(out.stereo());frame++;
        return new SfcFrameResult(mode,pcm.length/2,frame);
    }
    @Override public void copyRgbaFrame(byte[] to){loaded();if(to.length<rgba.length)throw new IllegalArgumentException("SFC video destination");System.arraycopy(rgba,0,to,0,rgba.length);}
    @Override public int copyAudioPcm16(short[] to){loaded();if(to.length<pcm.length)throw new IllegalArgumentException("SFC audio destination");System.arraycopy(pcm,0,to,0,pcm.length);return pcm.length/2;}
    @Override public byte[] saveState(){
        loaded();byte[] nativeState=core.serialize();
        try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.write(identity);out.writeLong(frame);out.writeInt(resampler.phase);out.writeShort(resampler.left);out.writeShort(resampler.right);
            out.writeInt(mode.width());out.writeInt(mode.height());out.writeDouble(mode.pixelAspectRatio());out.writeDouble(mode.targetFramesPerSecond());
            out.writeInt(rgba.length);out.write(rgba);out.writeInt(nativeState.length);out.write(nativeState);
        }byte[] body=bytes.toByteArray();if(body.length+32>MAX)throw new IllegalStateException("SFC state exceeds limit");return ByteBuffer.allocate(body.length+32).put(body).put(hash(body)).array();}
        catch(IOException impossible){throw new AssertionError(impossible);}
    }
    @Override public void loadState(byte[] state){
        loaded();if(state==null||state.length<116||state.length>MAX)throw new IllegalArgumentException("SFC state bounds");
        byte[] body=Arrays.copyOf(state,state.length-32);
        if(!MessageDigest.isEqual(hash(body),Arrays.copyOfRange(state,state.length-32,state.length)))throw new IllegalArgumentException("SFC state checksum");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){
            if(in.readInt()!=MAGIC||!MessageDigest.isEqual(identity,in.readNBytes(32)))throw new IllegalArgumentException("SFC state belongs to a different ROM/core/profile; old WASM saves are not imported");
            long number=in.readLong();int phase=in.readInt();short left=in.readShort(),right=in.readShort();
            if(number<0||phase<0||phase>=SfcPcmResampler.INPUT)throw new IllegalArgumentException("SFC state timing");
            int width=in.readInt(),height=in.readInt();double aspect=in.readDouble(),fps=in.readDouble();
            SfcVideoMode restored=new SfcVideoMode(width,height,width*4,aspect,fps);
            if(Math.abs(fps-mode.targetFramesPerSecond())>.01)throw new IllegalArgumentException("SFC state FPS");
            int size=in.readInt();if(size!=restored.requiredRgbaBytes()||size>in.available())throw new IllegalArgumentException("SFC state image");
            byte[] picture=in.readNBytes(size);int nativeSize=in.readInt();if(nativeSize<1||nativeSize!=in.available())throw new IllegalArgumentException("SFC native state size");
            byte[] raw=in.readNBytes(nativeSize);core.restore(raw);mode=restored;rgba=picture;pcm=new short[0];frame=number;
            resampler.phase=phase;resampler.left=left;resampler.right=right;
        }catch(IOException error){throw new IllegalArgumentException("Invalid SFC state",error);}
    }
    @Override public byte[] saveSram(){loaded();if(core.memory(1).length!=0)throw new IllegalStateException("SFC RTC save is not supported by this candidate");return core.memory(0);}
    @Override public void loadSram(byte[] data){
        loaded();Objects.requireNonNull(data);if(data.length>8*1024*1024)throw new IllegalArgumentException("SFC SRAM size");
        // Legacy callers probe a frame then reset before restoring SRAM. Recreate a clean native session.
        core.close();core=null;start();var initial=core.saveMemory();
        if(data.length==0&&initial.ram().length>0)return;
        if(initial.rtc().length!=0)throw new IllegalStateException("SFC RTC save is not supported by this candidate");
        core.restoreSaveMemory(new LibretroSaveMemory(data,new byte[0]));
    }
    @Override public void reset(boolean hard){loaded();if(hard){var memory=core.saveMemory();core.close();core=null;start();core.restoreSaveMemory(memory);}else{mode=video(core.reset());clear();}}
    @Override public void close(){
        if(closed)return;check();var active=core==null?opening:core;
        if(active!=null)active.close(); // Do not claim closed or forget a runtime before native teardown is confirmed.
        core=null;opening=null;closed=true;
    }
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
