package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Launched ONLY by NativeProcessSession. Minecraft never loads this class or JNA. */
public final class NativeCoreWorker {
    public interface Retro extends Library {
        int retro_api_version();
        void retro_set_environment(Environment callback);
        void retro_set_video_refresh(Video callback);
        void retro_set_audio_sample(Audio callback);
        void retro_set_audio_sample_batch(AudioBatch callback);
        void retro_set_input_poll(InputPoll callback);
        void retro_set_input_state(InputState callback);
        void retro_init();void retro_deinit();
        byte retro_load_game(GameInfo game);void retro_unload_game();void retro_run();
        void retro_get_system_av_info(AvInfo info);
    }
    public interface Crt extends Library { int fflush(Pointer stream);int _dup2(int source,int target); }
    public interface Kernel extends StdCallLibrary{
        Pointer GetCurrentProcess();Pointer GetStdHandle(int which);
        boolean DuplicateHandle(Pointer sourceProcess,Pointer source,Pointer targetProcess,PointerByReference target,int access,boolean inherit,int options);
        boolean SetStdHandle(int which,Pointer handle);
        boolean WriteFile(Pointer handle,byte[] data,int size,IntByReference written,Pointer overlapped);
        boolean CloseHandle(Pointer handle);
    }
    static final class BinaryPipe extends OutputStream{
        final Kernel kernel;final Pointer handle;
        BinaryPipe()throws IOException{
            kernel=Native.load("kernel32",Kernel.class);Pointer process=kernel.GetCurrentProcess();PointerByReference out=new PointerByReference();
            if(!kernel.DuplicateHandle(process,kernel.GetStdHandle(-11),process,out,0,false,2))throw new IOException("Cannot duplicate binary stdout handle");
            handle=out.getValue();
        }
        @Override public void write(int b)throws IOException{write(new byte[]{(byte)b});}
        @Override public void write(byte[] data,int offset,int length)throws IOException{
            byte[] bytes=offset==0&&length==data.length?data:Arrays.copyOfRange(data,offset,offset+length);
            int at=0;while(at<bytes.length){
                byte[] remaining=at==0?bytes:Arrays.copyOfRange(bytes,at,bytes.length);IntByReference written=new IntByReference();
                if(!kernel.WriteFile(handle,remaining,remaining.length,written,null)||written.getValue()<1)throw new IOException("Binary pipe closed");
                at+=written.getValue();
            }
        }
        @Override public void close(){kernel.CloseHandle(handle);}
    }
    public interface Environment extends Callback { byte invoke(int cmd,Pointer data); }
    public interface Video extends Callback { void invoke(Pointer data,int width,int height,long pitch); }
    public interface Audio extends Callback { void invoke(short left,short right); }
    public interface AudioBatch extends Callback { long invoke(Pointer data,long frames); }
    public interface InputPoll extends Callback { void invoke(); }
    public interface InputState extends Callback { short invoke(int port,int device,int index,int id); }
    @Structure.FieldOrder({"path","data","size","meta"})
    public static final class GameInfo extends Structure { public Pointer path,data;public long size;public Pointer meta; }
    @Structure.FieldOrder({"base_width","base_height","max_width","max_height","aspect_ratio"})
    public static final class Geometry extends Structure { public int base_width,base_height,max_width,max_height;public float aspect_ratio; }
    @Structure.FieldOrder({"fps","sample_rate"})
    public static final class Timing extends Structure { public double fps,sample_rate; }
    @Structure.FieldOrder({"geometry","timing"})
    public static final class AvInfo extends Structure { public Geometry geometry;public Timing timing; }

    public static void main(String[] args)throws Exception{
        if(args.length!=2||Native.POINTER_SIZE!=8)throw new IllegalArgumentException("Private Windows x64 worker arguments");
        BinaryPipe pipe=new BinaryPipe();
        DataOutputStream binary=new DataOutputStream(new BufferedOutputStream(pipe,65536));
        System.setOut(new PrintStream(System.err,true,java.nio.charset.StandardCharsets.UTF_8));
        // MAME's imported msvcrt stdout is redirected separately; it must never pollute the binary pipe.
        Crt crt=Native.load("msvcrt",Crt.class);crt.fflush(null);
        if(crt._dup2(2,1)!=0)throw new IOException("Cannot isolate native stdout");
        if(!pipe.kernel.SetStdHandle(-11,pipe.kernel.GetStdHandle(-12)))throw new IOException("Cannot redirect native Win32 stdout");
        Path rom=Path.of(args[1]).toAbsolutePath();
        if(!rom.getFileName().toString().matches("[a-z0-9_]{1,32}\\.zip")
            ||Files.size(rom)>BridgeProtocol.MAX_ROM)throw new IOException("Invalid staged ZIP");
        Retro core=Native.load(Path.of(args[0]).toAbsolutePath().toString(),Retro.class,
            Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        if(core.retro_api_version()!=1)throw new IOException("Unsupported libretro ABI");
        new Engine(core,rom,binary).run();
    }
    static final class Engine {
        final Retro core;final Path rom;final DataOutputStream output;
        final Map<String,Memory> strings=new HashMap<>();final Map<String,String> options=new HashMap<>();
        final NativeInputPorts buttons=new NativeInputPorts();volatile int[] current=new int[4];
        volatile boolean running=true;volatile Throwable failure;
        int width,height,rotation;float aspect;double fps=60;
        int[] pixels;final short[] pcm=new short[BridgeProtocol.MAX_PCM];int pcmCount;
        final Environment environment=this::environment;
        final Video video=this::video;
        final Audio audio=(l,r)->{try{sample(l,r);}catch(Throwable t){failure=t;}};
        final AudioBatch batch=(data,n)->{try{
            if(n<0||n>BridgeProtocol.MAX_PCM/2)throw new IOException("Native audio batch too large");
            if(pcmCount+n*2>pcm.length)throw new IOException("Native audio frame overflow");
            data.read(0,pcm,pcmCount,(int)n*2);pcmCount+=(int)n*2;return n;
        }catch(Throwable t){failure=t;return 0;}};
        final InputPoll poll=()->{};
        final InputState input=(port,device,index,id)->{
            if(port<0||port>3||device!=1||index!=0||id<0||id>15)return 0;
            return (short)((NativeArcadeButtons.toMame(current[port])>>>id)&1);
        };
        Engine(Retro core,Path rom,DataOutputStream output){this.core=core;this.rom=rom;this.output=output;}
        Memory string(String value){return strings.computeIfAbsent(value,v->{
            byte[] b=v.getBytes(java.nio.charset.StandardCharsets.UTF_8);Memory m=new Memory(b.length+1);
            m.write(0,b,0,b.length);m.setByte(b.length,(byte)0);return m;
        });}
        void sample(short l,short r)throws IOException{
            if(pcmCount+2>pcm.length)throw new IOException("Native audio frame overflow");
            pcm[pcmCount++]=l;pcm[pcmCount++]=r;
        }
        byte environment(int cmd,Pointer data){try{
            switch(cmd){
                case 1:rotation=data.getInt(0);if(rotation<0||rotation>3)throw new IOException("Rotation outside range");return 1;
                case 2:data.setByte(0,(byte)0);return 1;
                case 3:data.setByte(0,(byte)1);return 1;
                case 6:case 8:case 11:case 18:case 35:return 1;
                case 9:case 30:case 31:data.setPointer(0,string(rom.getParent().toString()));return 1;
                case 10:return (byte)(data.getInt(0)==1?1:0); // software XRGB8888 only; never GL context.
                case 15:{
                    String key=data.getPointer(0).getString(0,"UTF-8");String value=options.get(key);
                    // Pin the baseline layout; otherwise some NeoGeo/CPS drivers
                    // silently redefine numbered buttons despite the host's labels.
                    if(key.equals("mame_buttons_profiles")||key.matches(".*_(thread_mode|cheats_enable|throttle|boot_to_bios|boot_to_osd|read_config|write_config|auto_save)"))value="disabled";
                    if(value==null)return 0;data.setPointer(Native.POINTER_SIZE,string(value));return 1;
                }
                case 16:
                    for(int i=0;i<256;i++){
                        Pointer p=data.share((long)i*Native.POINTER_SIZE*2);Pointer key=p.getPointer(0);if(key==null)break;
                        String def=p.getPointer(Native.POINTER_SIZE).getString(0,"UTF-8");
                        options.put(key.getString(0,"UTF-8"),def.substring(def.indexOf(';')+1).trim().split("\\|")[0]);
                    }return 1;
                case 17:data.setByte(0,(byte)0);return 1;
                case 32:
                    timing(data.getDouble(24),data.getDouble(32));
                    // fall through: Geometry is the first member of retro_system_av_info.
                case 37:aspect=data.getFloat(16);return 1;
                case 39:case 52:data.setInt(0,0);return 1;
                case 61:data.setInt(0,4);return 1;
                default:return 0; // No VFS, hardware render, callbacks requiring extra lifecycle or arbitrary command support.
            }
        }catch(Throwable t){failure=t;return 0;}}
        void timing(double newFps,double sampleRate)throws IOException{
            if(!Double.isFinite(newFps)||newFps<20||newFps>240||sampleRate!=48000)throw new IOException("Unsupported core frame/audio timing");
            fps=newFps;
        }
        void video(Pointer data,int w,int h,long pitch){try{
            if(data==null)return;
            if(w<1||h<1||w>BridgeProtocol.MAX_DIM||h>BridgeProtocol.MAX_DIM
                ||(long)w*h>BridgeProtocol.MAX_PIXELS||pitch<w*4L||pitch>16384)throw new IOException("Video bounds exceeded");
            width=w;height=h;if(pixels==null||pixels.length!=w*h)pixels=new int[w*h];
            for(int y=0;y<h;y++)data.read(y*pitch,pixels,y*w,w);
        }catch(Throwable t){failure=t;}}
        void commands(){
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(System.in))){
                while(running){
                    int cmd=in.readInt();
                    if(cmd==BridgeProtocol.INPUT||cmd==BridgeProtocol.INPUT4){
                        int p1=in.readInt(),p2=in.readInt(),p3=cmd==BridgeProtocol.INPUT4?in.readInt():0,p4=cmd==BridgeProtocol.INPUT4?in.readInt():0;
                        if(!buttons.offer(p1,p2,p3,p4))throw new IOException("Input edge queue overflow");
                    }else if(cmd==BridgeProtocol.CLEAR){buttons.clear();}
                    else if(cmd==BridgeProtocol.RELEASE_PORT){buttons.releasePort(in.readInt());}
                    else if(cmd==BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN){buttons.releaseGameplayPortKeepingCoin(in.readInt());}
                    else if(cmd==BridgeProtocol.CLOSE){running=false;return;}
                    else throw new IOException("Unknown input command");
                }
            }catch(Throwable t){if(running)failure=t;running=false;}
        }
        void nextInputFrame(){current=buttons.nextFrame();}
        void run()throws Exception{
            Thread inputThread=new Thread(this::commands,"PIQ native command reader");inputThread.setDaemon(true);inputThread.start();
            core.retro_set_environment(environment);core.retro_set_video_refresh(video);
            core.retro_set_audio_sample(audio);core.retro_set_audio_sample_batch(batch);
            core.retro_set_input_poll(poll);core.retro_set_input_state(input);
            core.retro_init();boolean loaded=false;
            try{
                GameInfo game=new GameInfo();game.path=string(rom.toString());game.write();
                loaded=core.retro_load_game(game)!=0;if(!loaded)throw new IOException("Core rejected ROM set");
                AvInfo info=new AvInfo();core.retro_get_system_av_info(info);info.read();
                timing(info.timing.fps,info.timing.sample_rate);aspect=info.geometry.aspect_ratio;
                output.writeInt(BridgeProtocol.MAGIC);output.writeInt(BridgeProtocol.VERSION);output.flush();
                long due=System.nanoTime();
                while(running){
                    nextInputFrame();
                    core.retro_run();if(failure!=null)throw new IOException("Native callback/pipe failed",failure);
                    if(pixels!=null){
                        // Pinned MAME window.cpp already swaps geometry.aspect for libretro rotation.
                        // Our public bridge contract is UNROTATED DAR; the client rotates/inverts once.
                        float dar=aspect>0?((rotation&1)!=0?1f/aspect:aspect):(float)width/height;
                        BridgeProtocol.frameBounds(width,height,dar,rotation,pcmCount);
                        output.writeInt(BridgeProtocol.FRAME);output.writeInt(width);output.writeInt(height);
                        output.writeFloat(dar);output.writeInt(rotation);output.writeInt(pcmCount);
                        for(int rgb:pixels)output.writeInt(0xff000000|((rgb&255)<<16)|(rgb&0xff00)|((rgb>>>16)&255));
                        for(int i=0;i<pcmCount;i++)output.writeShort(pcm[i]);output.flush();pcmCount=0;
                    }
                    due+=(long)(1_000_000_000d/fps);long now=System.nanoTime();
                    if(due>now)LockSupport.parkNanos(due-now);else if(now-due>100_000_000)due=now;
                }
            }finally{running=false;if(loaded)core.retro_unload_game();core.retro_deinit();}
        }
    }
}
