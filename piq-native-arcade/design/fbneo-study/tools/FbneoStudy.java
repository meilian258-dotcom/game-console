import com.sun.jna.*;
import java.io.*;
import java.lang.ref.Reference;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Isolated research executable. No Minecraft, production helper, network, or user-file writes. */
public final class FbneoStudy {
    public interface Core extends Library {
        void retro_set_environment(Environment v);void retro_set_video_refresh(Video v);void retro_set_audio_sample(Audio v);void retro_set_audio_sample_batch(Batch v);void retro_set_input_poll(Poll v);void retro_set_input_state(Input v);
        void retro_init();void retro_deinit();byte retro_load_game(Game g);void retro_unload_game();void retro_run();void retro_get_system_av_info(Av v);void retro_get_system_info(Info v);
        long retro_serialize_size();byte retro_serialize(Pointer p,long size);byte retro_unserialize(Pointer p,long size);
    }
    public interface Environment extends Callback{byte invoke(int cmd,Pointer data);}public interface Video extends Callback{void invoke(Pointer p,int w,int h,long pitch);}public interface Audio extends Callback{void invoke(short l,short r);}public interface Batch extends Callback{long invoke(Pointer p,long frames);}public interface Poll extends Callback{void invoke();}public interface Input extends Callback{short invoke(int port,int device,int index,int id);}
    @Structure.FieldOrder({"path","data","size","meta"})public static class Game extends Structure{public Pointer path,data;public long size;public Pointer meta;}
    @Structure.FieldOrder({"width","height","maxWidth","maxHeight","aspect"})public static class Geometry extends Structure{public int width,height,maxWidth,maxHeight;public float aspect;}
    @Structure.FieldOrder({"fps","sampleRate"})public static class Timing extends Structure{public double fps,sampleRate;}
    @Structure.FieldOrder({"geometry","timing"})public static class Av extends Structure{public Geometry geometry;public Timing timing;}
    @Structure.FieldOrder({"name","version","extensions","fullPath","blockExtract"})public static class Info extends Structure{public Pointer name,version,extensions;public byte fullPath,blockExtract;}
    static String hash(byte[] value)throws Exception{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    static byte[] save(Core c)throws Exception{long n=c.retro_serialize_size();if(n<1||n>16*1024*1024)throw new IOException("State size "+n);try(Memory m=new Memory(n)){m.clear();if(c.retro_serialize(m,n)==0)throw new IOException("serialize failed");return m.getByteArray(0,(int)n);}}
    static void load(Core c,byte[] state)throws Exception{try(Memory m=new Memory(state.length)){m.write(0,state,0,state.length);if(c.retro_unserialize(m,state.length)==0)throw new IOException("unserialize failed");}}
    static final class Engine {
        final Path directory;final int context;int contextQueries;final Map<String,Memory> strings=new HashMap<>();final TreeMap<String,String> options=new TreeMap<>(),selected=new TreeMap<>();
        int format=0,width,height,rotation,pcmCount,realVideoFrames,duplicateVideoFrames,nonzeroAudioFrames;float aspect;double fps,sampleRate;int[] pixels;short[] pcm=new short[32768];int[] inputs=new int[4];Throwable failure;
        final Environment environment=this::environment;final Video video=this::video;final Poll poll=()->{};
        final Input input=(port,device,index,id)->(short)(port>=0&&port<4&&device==1&&index==0&&id>=0&&id<16?(inputs[port]>>>id)&1:0);
        final Audio audio=(l,r)->{try{if(pcmCount+2>pcm.length)throw new IOException("Audio bound");pcm[pcmCount++]=l;pcm[pcmCount++]=r;}catch(Throwable t){failure=t;}};
        final Batch batch=(p,n)->{try{if(n<0||n>16384||pcmCount+n*2>pcm.length)throw new IOException("Batch bound");p.read(0,pcm,pcmCount,(int)n*2);pcmCount+=(int)n*2;return n;}catch(Throwable t){failure=t;return 0;}};
        Engine(Path directory,int context){this.directory=directory;this.context=context;}
        Memory text(String s){return strings.computeIfAbsent(s,k->{byte[]b=k.getBytes(java.nio.charset.StandardCharsets.UTF_8);Memory m=new Memory(b.length+1);m.write(0,b,0,b.length);m.setByte(b.length,(byte)0);return m;});}
        byte environment(int cmd,Pointer p){try{switch(cmd){
            case 1:rotation=p.getInt(0);return 1;
            case 2:p.setByte(0,(byte)0);return 1;
            case 3:p.setByte(0,(byte)1);return 1;
            case 6:case 8:case 11:case 18:case 35:return 1;
            case 9:case 30:case 31:p.setPointer(0,text(directory.toString()));return 1;
            case 10:format=p.getInt(0);return(byte)(format>=0&&format<=2?1:0);
            case 15:{String key=p.getPointer(0).getString(0,"UTF-8"),value=options.get(key);if(key.equals("fbneo-samplerate"))value="48000";if(value==null)return 0;selected.put(key,value);p.setPointer(Native.POINTER_SIZE,text(value));return 1;}
            case 16:for(int i=0;i<2048;i++){Pointer entry=p.share((long)i*Native.POINTER_SIZE*2),key=entry.getPointer(0);if(key==null)break;String value=entry.getPointer(Native.POINTER_SIZE).getString(0,"UTF-8");options.put(key.getString(0,"UTF-8"),value.substring(value.indexOf(';')+1).trim().split("\\|")[0]);}return 1;
            case 17:p.setByte(0,(byte)0);return 1;
            case 32:fps=p.getDouble(24);sampleRate=p.getDouble(32);aspect=p.getFloat(16);return 1;
            case 37:aspect=p.getFloat(16);return 1;
            case 39:case 52:p.setInt(0,0);return 1;
            case 61:p.setInt(0,4);return 1;
            case 0x1002f:if(p!=null)p.setInt(0,3);return 1;
            case 0x10048:contextQueries++;if(p!=null)p.setInt(0,context);return 1;
            default:return 0;
        }}catch(Throwable t){failure=t;return 0;}}
        void video(Pointer p,int w,int h,long pitch){try{
            if(p==null){duplicateVideoFrames++;return;}realVideoFrames++;if(w<1||w>2048||h<1||h>2048||pitch<w*(format==1?4L:2L)||pitch>16384)throw new IOException("Video bound");
            width=w;height=h;if(pixels==null||pixels.length!=w*h)pixels=new int[w*h];
            short[] row=format==1?null:new short[w];for(int y=0;y<h;y++){if(format==1){p.read(y*pitch,pixels,y*w,w);for(int x=0;x<w;x++)pixels[y*w+x]&=0xffffff;}else{p.read(y*pitch,row,0,w);for(int x=0;x<w;x++){int raw=row[x]&65535,r=(raw>>(format==2?11:10))&31,g=(raw>>5)&(format==2?63:31),b=raw&31;pixels[y*w+x]=(r*255/31)<<16|(g*255/(format==2?63:31))<<8|b*255/31;}}}
        }catch(Throwable t){failure=t;}}
    }
    static int[] inputs(int frame){int[]v=new int[4];if(frame>=1600&&frame<1620)v[0]|=4;if(frame>=1800&&frame<1820)v[0]|=8;for(int p=0;p<4;p++)if(frame>=1950+p*50){v[p]|=1<<(4+(frame/24+p)%4);if(frame%11<4)v[p]|=1;if(frame%17<6)v[p]|=256;if(frame%37<8)v[p]|=2;}return v;}
    static String frame(Core c,Engine e,int i)throws Exception{e.inputs=inputs(i);e.pcmCount=0;c.retro_run();if(e.failure!=null)throw new IOException("Callback",e.failure);if(e.pixels==null&&i!=0)throw new IOException("No real video after initial buffer allocation");ByteBuffer v=ByteBuffer.allocate(e.pixels==null?0:e.pixels.length*4).order(ByteOrder.LITTLE_ENDIAN);if(e.pixels!=null)for(int n:e.pixels)v.putInt(n);ByteBuffer a=ByteBuffer.allocate(e.pcmCount*2).order(ByteOrder.LITTLE_ENDIAN);boolean nonzero=false;for(int n=0;n<e.pcmCount;n++){a.putShort(e.pcm[n]);nonzero|=e.pcm[n]!=0;}if(nonzero)e.nonzeroAudioFrames++;return i+","+e.width+","+e.height+","+Float.floatToIntBits(e.aspect)+","+e.rotation+","+e.pcmCount+","+hash(v.array())+","+hash(a.array());}
    static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r"," ")+"\"";}
    public static void main(String[]args)throws Exception{
        Path dll=Path.of(args[0]).toRealPath(),rom=Path.of(args[1]).toRealPath(),out=Path.of(args[2]).toAbsolutePath();Files.createDirectory(out);int split=Integer.parseInt(args[3]),count=Integer.parseInt(args[4]);
        Core c=Native.load(dll.toString(),Core.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));Engine e=new Engine(rom.getParent(),Integer.parseInt(args[6]));
        c.retro_set_environment(e.environment);c.retro_set_video_refresh(e.video);c.retro_set_audio_sample(e.audio);c.retro_set_audio_sample_batch(e.batch);c.retro_set_input_poll(e.poll);c.retro_set_input_state(e.input);Info info=new Info();c.retro_get_system_info(info);info.read();c.retro_init();boolean loaded=false;
        try{Game g=new Game();g.path=e.text(rom.toString());g.write();loaded=c.retro_load_game(g)!=0;if(!loaded)throw new IOException("Core rejected existing ROM/BIOS; no ROM repair attempted");Av av=new Av();c.retro_get_system_av_info(av);av.read();e.fps=av.timing.fps;e.sampleRate=av.timing.sampleRate;e.aspect=av.geometry.aspect;
            System.err.println("CORE "+info.name.getString(0)+" "+info.version.getString(0)+" fps="+e.fps+" actual_sample_rate="+e.sampleRate);
            if(!Double.isFinite(e.sampleRate)||e.sampleRate<20000||e.sampleRate>192000||!Double.isFinite(e.fps)||e.fps<40||e.fps>80)throw new IOException("Invalid actual core timing");List<String> baseline=new ArrayList<>();String initial=hash(save(c));
            for(int i=0;i<split;i++){baseline.add(frame(c,e,i));if(i%600==0)System.err.println("FRAME "+i);}byte[] state=save(c);String snapshot=hash(state),repeat=hash(save(c));Path pending=out.resolve("pending.bin");Files.write(pending,state,StandardOpenOption.CREATE_NEW);Files.move(pending,out.resolve("snapshot.bin"),StandardCopyOption.ATOMIC_MOVE);
            for(int i=split;i<split+count;i++)baseline.add(frame(c,e,i));Files.write(out.resolve("baseline.csv"),baseline,StandardOpenOption.CREATE_NEW);String end=hash(save(c));load(c,state);String after=hash(save(c));List<String> restored=new ArrayList<>();for(int i=split;i<split+count;i++)restored.add(frame(c,e,i));Files.write(out.resolve("restored.csv"),restored,StandardOpenOption.CREATE_NEW);String end2=hash(save(c)),crossEnd="";
            if(!args[5].equals("-")){Path peer=Path.of(args[5]);long until=System.nanoTime()+30_000_000_000L;while(!Files.exists(peer)&&System.nanoTime()<until)Thread.sleep(20);byte[]other=Files.readAllBytes(peer);if(other.length>16*1024*1024)throw new IOException("Peer state bound");load(c,other);List<String>cross=new ArrayList<>();for(int i=split;i<split+count;i++)cross.add(frame(c,e,i));Files.write(out.resolve("cross.csv"),cross,StandardOpenOption.CREATE_NEW);crossEnd=hash(save(c));}
            Files.writeString(out.resolve("options.txt"),e.selected.toString(),StandardOpenOption.CREATE_NEW);
            String json="{\"ok\":true,\"core_name\":"+quote(info.name.getString(0))+",\"core_version\":"+quote(info.version.getString(0))+",\"sample_rate\":"+e.sampleRate+",\"fps\":"+e.fps+",\"pixel_format\":"+e.format+",\"initial_sha256\":"+quote(initial)+",\"snapshot_sha256\":"+quote(snapshot)+",\"immediate_repeat_save_sha256\":"+quote(repeat)+",\"restored_before_step_sha256\":"+quote(after)+",\"ending_state_sha256\":"+quote(end)+",\"restored_ending_state_sha256\":"+quote(end2)+",\"cross_ending_state_sha256\":"+quote(crossEnd)+",\"snapshot_bytes\":"+state.length+"}";
            if(e.realVideoFrames<split+count-1||e.nonzeroAudioFrames<100)throw new IOException("Insufficient actual video/audio activity");
            if(e.contextQueries<5)throw new IOException("Official savestate context not queried");
            json=json.substring(0,json.length()-1)+",\"real_video_frames\":"+e.realVideoFrames+",\"duplicate_video_frames\":"+e.duplicateVideoFrames+",\"nonzero_audio_frames\":"+e.nonzeroAudioFrames+",\"savestate_context\":"+e.context+",\"savestate_context_queries\":"+e.contextQueries+"}";
            Files.writeString(out.resolve("result.json"),json,StandardOpenOption.CREATE_NEW);
        }finally{try{if(loaded)c.retro_unload_game();c.retro_deinit();System.err.println("NATIVE_TEARDOWN_COMPLETED");}finally{Reference.reachabilityFence(e);Reference.reachabilityFence(c);}}
    }
}
