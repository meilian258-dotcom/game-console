package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import java.io.*;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Private one-request/one-step helper; no autonomous emulation clock and no Minecraft classes. */
public final class NativeStepWorker {
    private NativeStepWorker(){}
    public interface States extends NativeCoreWorker.Retro {
        long retro_serialize_size();
        byte retro_serialize(Pointer target,long bytes);
        byte retro_unserialize(Pointer source,long bytes);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2||Native.POINTER_SIZE!=8||!System.getProperty("os.name","").startsWith("Windows"))throw new IllegalArgumentException("Private Windows x64 step worker arguments");
        Path dll=Path.of(args[0]).toAbsolutePath().normalize(),rom=Path.of(args[1]).toAbsolutePath().normalize();
        regular(dll);regular(rom);
        if(!rom.getFileName().toString().matches("[a-z0-9_]{1,32}\\.zip")||Files.size(rom)<22||Files.size(rom)>BridgeProtocol.MAX_ROM)throw new IOException("Invalid staged ROM ZIP");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(dll)){byte[] b=new byte[131072];for(int n;(n=in.read(b))!=-1;)hash.update(b,0,n);}
        if(!HexFormat.of().formatHex(hash.digest()).equalsIgnoreCase(BridgeProtocol.CORE_SHA))throw new IOException("Fixed MAME runtime identity mismatch");
        NativeCoreWorker.BinaryPipe pipe=new NativeCoreWorker.BinaryPipe();
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(pipe,65536));
        System.setOut(new PrintStream(System.err,true,StandardCharsets.UTF_8));
        NativeCoreWorker.Crt crt=Native.load("msvcrt",NativeCoreWorker.Crt.class);crt.fflush(null);
        if(crt._dup2(2,1)!=0||!pipe.kernel.SetStdHandle(-11,pipe.kernel.GetStdHandle(-12)))throw new IOException("Cannot isolate native stdout");
        States core=Native.load(dll.toString(),States.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        Session session=new Session(core,rom,out);int exit=1;
        try{session.run(new DataInputStream(new BufferedInputStream(System.in)));exit=0;}
        catch(Throwable failure){failure.printStackTrace(System.err);}
        finally{
            try{out.flush();}catch(IOException ignored){}pipe.close();
            if(session.teardownComplete){System.err.println("PIQ_STEP_NATIVE_TEARDOWN_COMPLETED");System.err.flush();
                // Only this dedicated helper JVM: upstream MAME may retain native threads after deinit.
                Runtime.getRuntime().halt(exit);}
        }
        if(!session.teardownComplete)throw new IOException("Native teardown did not complete");
    }
    private static void regular(Path path)throws IOException{
        BasicFileAttributes file=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!file.isRegularFile()||file.isSymbolicLink()||file.isOther())throw new IOException("Runtime/ROM must be a regular file");
        for(Path p=path.getParent();p!=null;p=p.getParent()){var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!a.isDirectory()||a.isSymbolicLink()||a.isOther())throw new IOException("Linked runtime/ROM parent");}
    }
    /** Also exercised with a callback-producing test core; this class itself never opens a DLL. */
    static final class Session {
        final States core;final NativeCoreWorker.Engine engine;final DataOutputStream out;
        final NativeCoreWorker.Video video;
        boolean freshVideo,teardownComplete;long frame,nextRequest=1;
        Session(States core,Path rom,DataOutputStream out){this.core=Objects.requireNonNull(core);this.out=Objects.requireNonNull(out);engine=new NativeCoreWorker.Engine(core,rom,out);
            video=(data,w,h,pitch)->{if(data!=null)freshVideo=true;engine.video.invoke(data,w,h,pitch);};}
        void run(DataInputStream in)throws Exception{
            boolean initialized=false,loaded=false;long closeRequest=0;
            try{
                if(core.retro_api_version()!=1)throw new IOException("Unsupported libretro ABI");
                core.retro_set_environment(engine.environment);core.retro_set_video_refresh(video);core.retro_set_audio_sample(engine.audio);core.retro_set_audio_sample_batch(engine.batch);core.retro_set_input_poll(engine.poll);core.retro_set_input_state(engine.input);
                initialized=true;core.retro_init();
                var game=new NativeCoreWorker.GameInfo();game.path=engine.string(engine.rom.toString());game.write();
                loaded=core.retro_load_game(game)!=0;if(!loaded)throw new IOException("MAME rejected ROM set");
                var av=new NativeCoreWorker.AvInfo();core.retro_get_system_av_info(av);av.read();engine.timing(av.timing.fps,av.timing.sample_rate);engine.aspect=av.geometry.aspect_ratio;callbackCheck();
                NativeStepProtocol.writeHello(out,new NativeStepProtocol.Hello(engine.fps,48000,4));out.flush();
                for(;;){
                    NativeStepProtocol.Request request=NativeStepProtocol.readRequest(in,nextRequest);
                    if(request.kind()==NativeStepProtocol.CLOSE){closeRequest=request.id();break;}
                    NativeStepProtocol.Reply reply=switch(request.kind()){
                        case NativeStepProtocol.STEP->step(request);
                        case NativeStepProtocol.SAVE->new NativeStepProtocol.State(request.id(),frame,save());
                        case NativeStepProtocol.LOAD->{load(request.state());frame=request.frame();yield new NativeStepProtocol.Loaded(request.id(),frame);}
                        default->throw new IOException("Unknown native operation");};
                    NativeStepProtocol.writeReply(out,reply);out.flush();
                    if(nextRequest==Long.MAX_VALUE)throw new IOException("Request sequence exhausted");nextRequest++;
                }
            }catch(Throwable failure){
                try{NativeStepProtocol.writeReply(out,new NativeStepProtocol.Failure(nextRequest,shortError(failure)));out.flush();}catch(IOException|RuntimeException ignored){}
                throw failure;
            }finally{
                boolean unloaded=!loaded,deinitialized=!initialized;
                try{if(loaded)core.retro_unload_game();unloaded=true;}
                finally{try{if(initialized)core.retro_deinit();deinitialized=true;}
                    finally{teardownComplete=unloaded&&deinitialized;Reference.reachabilityFence(engine);Reference.reachabilityFence(video);}}
            }
            if(closeRequest!=0){NativeStepProtocol.writeReply(out,new NativeStepProtocol.Closed(closeRequest));out.flush();}
        }
        private NativeStepProtocol.Frame step(NativeStepProtocol.Request request)throws IOException{
            if(frame==Long.MAX_VALUE)throw new IOException("Frame counter exhausted");
            engine.current=new int[]{request.p1(),request.p2(),request.p3(),request.p4()};
            engine.pcmCount=0;freshVideo=false;core.retro_run();callbackCheck();frame++;
            boolean has=engine.pixels!=null;int w=has?engine.width:0,h=has?engine.height:0;
            float dar=has?(engine.aspect>0?((engine.rotation&1)!=0?1f/engine.aspect:engine.aspect):(float)w/h):0;
            int[] abgr=has?new int[engine.pixels.length]:new int[0];
            if(has)for(int i=0;i<abgr.length;i++){int rgb=engine.pixels[i];abgr[i]=0xff000000|((rgb&255)<<16)|(rgb&0xff00)|((rgb>>>16)&255);}
            return new NativeStepProtocol.Frame(request.id(),frame,engine.fps,48000,has,freshVideo,w,h,dar,engine.rotation,abgr,Arrays.copyOf(engine.pcm,engine.pcmCount));
        }
        private byte[] save()throws IOException{
            long length=core.retro_serialize_size();if(length<1||length>NativeStepProtocol.MAX_STATE)throw new IOException("Native state is unavailable or outside limit");
            try(Memory state=new Memory(length)){state.clear();if(core.retro_serialize(state,length)==0)throw new IOException("MAME state save rejected");callbackCheck();return state.getByteArray(0,(int)length);}
        }
        private void load(byte[] bytes)throws IOException{
            long size=core.retro_serialize_size();if(size<1||size>NativeStepProtocol.MAX_STATE||bytes.length!=size)throw new IOException("State size does not match loaded driver");
            engine.current=new int[4];engine.pcmCount=0;engine.pixels=null;engine.width=engine.height=0;freshVideo=false;
            try(Memory state=new Memory(bytes.length)){state.write(0,bytes,0,bytes.length);if(core.retro_unserialize(state,bytes.length)==0)throw new IOException("MAME state load rejected");callbackCheck();}
        }
        private void callbackCheck()throws IOException{if(engine.failure!=null)throw new IOException("Native callback failed",engine.failure);}
    }
    private static String shortError(Throwable failure){String text=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());if(text.length()>2000)text=text.substring(0,2000);while(text.getBytes(StandardCharsets.UTF_8).length>NativeStepProtocol.MAX_ERROR)text=text.substring(0,text.length()-1);return text;}
}
