// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Private child-process core. Never load this class/JNA in Minecraft. */
public final class GbaCore implements AutoCloseable {
    public static final String CORE_SHA="D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B";
    public static final int MAX_ROM=32*1024*1024,MAX_SAVE=1024*1024,MAX_STATE=8*1024*1024;
    public interface Retro extends Library {
        int retro_api_version(); void retro_get_system_info(Info out);
        void retro_set_environment(Environment value); void retro_set_video_refresh(Video value);
        void retro_set_audio_sample(Audio value); void retro_set_audio_sample_batch(Batch value);
        void retro_set_input_poll(Poll value); void retro_set_input_state(Input value);
        void retro_init(); void retro_deinit(); byte retro_load_game(Game value); void retro_unload_game();
        void retro_get_system_av_info(Av out); void retro_run();
        Pointer retro_get_memory_data(int type); long retro_get_memory_size(int type);
        long retro_serialize_size(); byte retro_serialize(Pointer memory,long size); byte retro_unserialize(Pointer memory,long size);
    }
    public interface Environment extends Callback { byte invoke(int command,Pointer data); }
    public interface Video extends Callback { void invoke(Pointer pixels,int width,int height,long pitch); }
    public interface Audio extends Callback { void invoke(short left,short right); }
    public interface Batch extends Callback { long invoke(Pointer data,long frames); }
    public interface Poll extends Callback { void invoke(); }
    public interface Input extends Callback { short invoke(int port,int device,int index,int id); }
    @Structure.FieldOrder({"name","version","extensions","fullpath","blockExtract"})
    public static final class Info extends Structure { public Pointer name,version,extensions;public byte fullpath,blockExtract; }
    @Structure.FieldOrder({"path","data","size","meta"})
    public static final class Game extends Structure { public Pointer path,data;public long size;public Pointer meta; }
    @Structure.FieldOrder({"width","height","maxWidth","maxHeight","aspect"})
    public static final class Geometry extends Structure { public int width,height,maxWidth,maxHeight;public float aspect; }
    @Structure.FieldOrder({"fps","sampleRate"})
    public static final class Timing extends Structure { public double fps,sampleRate; }
    @Structure.FieldOrder({"geometry","timing"})
    public static final class Av extends Structure { public Geometry geometry;public Timing timing; }
    public record Frame(int width,int height,int[] abgr,short[] pcm48k) {}
    private final Thread owner=Thread.currentThread();
    private final Retro core;
    private final Map<String,Memory> strings=new HashMap<>();
    private final Map<String,String> options=new HashMap<>();
    private final Path directory;
    private Memory romMemory;
    private int mask,pixelFormat=-1,width,height,pcmCount; private int[] pixels;
    private final short[] pcm=new short[32768];
    private Throwable failure; private boolean initialized,loaded,closed,started;
    private double fps,sampleRate; private final PcmResampler resampler=new PcmResampler();
    private String version;
    // Strong callback references retained for the entire native lifetime.
    private final Environment environment=this::environment;
    private final Video video=this::video;
    private final Audio audio=(l,r)->{try{sample(l,r);}catch(Throwable t){failure=t;}};
    private final Batch batch=(p,n)->{try{
        if(n<0||n>16384||pcmCount+n*2>pcm.length||p==null)throw new IOException("Audio bounds");
        p.read(0,pcm,pcmCount,(int)n*2);pcmCount+=(int)n*2;return n;
    }catch(Throwable t){failure=t;return 0;}};
    private final Poll poll=()->{};
    private final Input input=(port,device,index,id)->(short)(port==0&&device==1&&index==0&&id>=0&&id<12?((mask>>>id)&1):0);

    public GbaCore(Path dll,Path rom,Path privateDirectory)throws Exception {
        if(!System.getProperty("os.name","").startsWith("Windows")||Native.POINTER_SIZE!=8)throw new IOException("Windows x64 required");
        if(!Files.isRegularFile(dll,LinkOption.NOFOLLOW_LINKS)||Files.size(dll)>8*1024*1024
                ||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bounded(dll,8*1024*1024))).equalsIgnoreCase(CORE_SHA))
            throw new IOException("Unapproved mGBA core");
        if(!Files.isRegularFile(rom,LinkOption.NOFOLLOW_LINKS)||Files.size(rom)<192||Files.size(rom)>MAX_ROM
                ||!rom.getFileName().toString().endsWith(".gba"))throw new IOException("Bounded GBA ROM required");
        directory=privateDirectory.toRealPath();
        core=Native.load(dll.toRealPath().toString(),Retro.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        try {
            if(core.retro_api_version()!=1)throw new IOException("libretro ABI mismatch");
            Info info=new Info();core.retro_get_system_info(info);info.read();
            version=info.version.getString(0,"UTF-8");
            if(!version.equals("0.11-219-e31759b"))throw new IOException("Unexpected core version "+version);
            core.retro_set_environment(environment);core.retro_set_video_refresh(video);core.retro_set_audio_sample(audio);
            core.retro_set_audio_sample_batch(batch);core.retro_set_input_poll(poll);core.retro_set_input_state(input);
            core.retro_init();initialized=true;
            byte[] bytes=bounded(rom,MAX_ROM);if(bytes.length<192)throw new IOException("ROM changed");
            romMemory=new Memory(bytes.length);romMemory.write(0,bytes,0,bytes.length);
            Game game=new Game();game.path=string(rom.toRealPath().toString());game.data=romMemory;game.size=bytes.length;game.write();
            loaded=core.retro_load_game(game)!=0;if(!loaded)throw new IOException("mGBA rejected ROM");
            Av av=new Av();core.retro_get_system_av_info(av);av.read();setTiming(av.timing.fps,av.timing.sampleRate);
            checkFailure();
        } catch(Throwable t) {close();throw t;}
    }
    private static byte[] bounded(Path file,int max)throws IOException {
        byte[] bytes;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(max+1);}
        if(bytes.length>max)throw new IOException("File grew beyond bound");return bytes;
    }
    private Memory string(String value){return strings.computeIfAbsent(value,v->{byte[] b=v.getBytes(java.nio.charset.StandardCharsets.UTF_8);Memory m=new Memory(b.length+1);m.write(0,b,0,b.length);m.setByte(b.length,(byte)0);return m;});}
    private byte environment(int command,Pointer p){try {
        switch(command){
            case 3:p.setByte(0,(byte)1);return 1;
            case 9:case 30:case 31:p.setPointer(0,string(directory.toString()));return 1;
            case 10:int fmt=p.getInt(0);if(fmt!=1&&fmt!=2)return 0;pixelFormat=fmt;return 1;
            case 15:String key=p.getPointer(0).getString(0,"UTF-8"),value=options.get(key);
                if(key.equals("mgba_use_bios"))value="OFF";
                if(key.equals("mgba_skip_bios"))value="ON";
                if(value==null)return 0;p.setPointer(Native.POINTER_SIZE,string(value));return 1;
            case 16:for(int i=0;i<256;i++){Pointer item=p.share((long)i*Native.POINTER_SIZE*2),keyPtr=item.getPointer(0);if(keyPtr==null)return 1;
                String def=item.getPointer(Native.POINTER_SIZE).getString(0,"UTF-8");int split=def.indexOf(';');if(split<0)throw new IOException("Bad option");
                options.put(keyPtr.getString(0,"UTF-8"),def.substring(split+1).trim().split("\\|")[0]);}throw new IOException("Too many core options");
            case 17:p.setByte(0,(byte)0);return 1;
            case 32:Av av=new Av();byte[] bytes=p.getByteArray(0,av.size());av.getPointer().write(0,bytes,0,bytes.length);av.read();setTiming(av.timing.fps,av.timing.sampleRate);return 1;
            case 37:return 1;
            case 52:p.setInt(0,0);return 1; // Core options v0: request legacy variable declarations.
            case 61:p.setInt(0,1);return 1;
            default:return 0; // No VFS/camera/GL/sensors or unowned asynchronous callbacks.
        }
    }catch(Throwable t){failure=t;return 0;}}
    private void setTiming(double f,double rate)throws IOException {if(!Double.isFinite(f)||f<50||f>65||!Double.isFinite(rate)||rate<8000||rate>192000||rate!=(int)rate)throw new IOException("Unsupported timing");fps=f;sampleRate=rate;}
    private void sample(short l,short r)throws IOException {if(pcmCount+2>pcm.length)throw new IOException("Audio overflow");pcm[pcmCount++]=l;pcm[pcmCount++]=r;}
    private void video(Pointer p,int w,int h,long pitch){try {
        if(p==null)return;
        int bytes=pixelFormat==2?2:pixelFormat==1?4:0;
        if(bytes==0||w!=240||h!=160||pitch<(long)w*bytes||pitch>4096)throw new IOException("Non-GBA video geometry/format");
        int[] next=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int c=bytes==2?Short.toUnsignedInt(p.getShort(y*pitch+x*2L)):p.getInt(y*pitch+x*4L);
            int r=bytes==2?((c>>>11)&31)*255/31:(c>>>16)&255,g=bytes==2?((c>>>5)&63)*255/63:(c>>>8)&255,b=bytes==2?(c&31)*255/31:c&255;
            next[y*w+x]=0xff000000|(b<<16)|(g<<8)|r;
        }
        width=w;height=h;pixels=next;
    }catch(Throwable t){failure=t;}}
    public Frame step(int libretroMask)throws IOException {active();if((libretroMask&~0xfff)!=0)throw new IllegalArgumentException("Input mask");mask=libretroMask;pcmCount=0;started=true;core.retro_run();checkFailure();return pixels==null?null:new Frame(width,height,pixels,resampler.convert(pcm,pcmCount,(int)sampleRate));}
    public byte[] saveRam()throws IOException {active();long length=core.retro_get_memory_size(0);Pointer p=core.retro_get_memory_data(0);if(length<0||length>MAX_SAVE||length>0&&p==null)throw new IOException("SaveRAM bounds");return length==0?new byte[0]:p.getByteArray(0,(int)length);}
    public void loadRam(byte[] bytes)throws IOException {active();long length=core.retro_get_memory_size(0);Pointer p=core.retro_get_memory_data(0);
        // mGBA starts AUTODETECT with a 128-KiB buffer, then chooses SRAM/EEPROM on first execution.
        // A known cartridge-size file may initialize a prefix ONLY before the first frame.
        if(bytes==null||!Set.of(512,8192,32768,65536,131072).contains(bytes.length)||length<1||length>MAX_SAVE
                ||bytes.length>length||started&&bytes.length!=length||p==null)throw new IOException("SaveRAM identity/length");
        if(!started)p.setMemory(0,length,(byte)0xff);p.write(0,bytes,0,bytes.length);}
    public byte[] snapshot()throws IOException {active();long size=core.retro_serialize_size();if(size<1||size>MAX_STATE)throw new IOException("Snapshot bound");try(Memory data=new Memory(size)){if(core.retro_serialize(data,size)==0)throw new IOException("Snapshot rejected");return data.getByteArray(0,(int)size);}}
    public void restore(byte[] bytes)throws IOException {active();if(bytes==null||bytes.length<1||bytes.length>MAX_STATE)throw new IOException("Snapshot bound");try(Memory data=new Memory(bytes.length)){data.write(0,bytes,0,bytes.length);if(core.retro_unserialize(data,bytes.length)==0)throw new IOException("Restore rejected");}mask=0;resampler.reset();}
    public String version(){return version;}public double fps(){return fps;}public double sampleRate(){return sampleRate;}public int pixelFormat(){return pixelFormat;}
    private void checkFailure()throws IOException {if(failure!=null)throw new IOException("Native callback failed",failure);}
    private void active()throws IOException {if(Thread.currentThread()!=owner||closed||!loaded)throw new IOException("Wrong owner or closed core");}
    @Override public void close(){if(closed)return;if(Thread.currentThread()!=owner)throw new IllegalStateException("Native core close on owner only");closed=true;mask=0;
        try{if(loaded)core.retro_unload_game();}finally{try{if(initialized)core.retro_deinit();}finally{if(romMemory!=null)romMemory.close();strings.values().forEach(Memory::close);strings.clear();}}}
}
