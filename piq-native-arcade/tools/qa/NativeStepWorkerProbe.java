package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual step session + real protocol/production Engine callbacks, test core only (no MAME DLL). */
public final class NativeStepWorkerProbe {
    private static int assertions;
    private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
    private static final class Fake implements InvocationHandler {
        Thread thread;int runs,loads,saves,unloads,deinits;boolean unavailable,badVideo,unloadFailure;
        NativeCoreWorker.Environment environment;NativeCoreWorker.Video video;NativeCoreWorker.Audio audio;NativeCoreWorker.InputState input;
        final List<int[]> sampled=new ArrayList<>();
        NativeStepWorker.States api(){return (NativeStepWorker.States)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{NativeStepWorker.States.class},this);}
        public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
            if(thread==null)thread=Thread.currentThread();check(thread==Thread.currentThread(),"one thread owns every native API");
            switch(method.getName()){
                case "retro_api_version":return 1;
                case "retro_set_environment":environment=(NativeCoreWorker.Environment)args[0];return null;
                case "retro_set_video_refresh":video=(NativeCoreWorker.Video)args[0];return null;
                case "retro_set_audio_sample":audio=(NativeCoreWorker.Audio)args[0];return null;
                case "retro_set_input_state":input=(NativeCoreWorker.InputState)args[0];return null;
                case "retro_load_game":return (byte)1;
                case "retro_get_system_av_info":{var av=(NativeCoreWorker.AvInfo)args[0];av.geometry.base_width=2;av.geometry.base_height=1;av.geometry.aspect_ratio=2;av.timing.fps=60;av.timing.sample_rate=48000;av.write();return null;}
                case "retro_run":{
                    runs++;int[] ports=new int[4];for(int p=0;p<4;p++)for(int bit=0;bit<16;bit++)ports[p]|=input.invoke(p,1,0,bit)<<bit;sampled.add(ports);
                    var av=new NativeCoreWorker.AvInfo();av.geometry.aspect_ratio=2;av.timing.fps=59.18560791015625;av.timing.sample_rate=48000;av.write();environment.invoke(32,av.getPointer());
                    audio.invoke((short)runs,(short)-runs);
                    if(badVideo){try(Memory m=new Memory(8)){video.invoke(m,3000,1,12000);}return null;}
                    if(runs>1){try(Memory m=new Memory(8)){m.setInt(0,0x112233);m.setInt(4,runs);video.invoke(m,2,1,8);}}
                    return null;}
                case "retro_serialize_size":return unavailable?0L:4L;
                case "retro_serialize":((Pointer)args[0]).setInt(0,runs);saves++;return (byte)1;
                case "retro_unserialize":runs=((Pointer)args[0]).getInt(0);loads++;return (byte)1;
                case "retro_unload_game":unloads++;if(unloadFailure)throw new IOException("test unload failure");return null;
                case "retro_deinit":deinits++;return null;
                default:return null;
            }
        }
    }
    private record Result(NativeStepWorker.Session session,DataInputStream replies,Throwable error){}
    private static byte[] commands(NativeStepProtocol.Request... commands)throws Exception{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(var c:commands)NativeStepProtocol.writeCommand(out,c);return bytes.toByteArray();}
    private static Result run(Fake core,byte[] commands)throws Exception{var bytes=new ByteArrayOutputStream();var s=new NativeStepWorker.Session(core.api(),Path.of("original.zip"),new DataOutputStream(bytes));Throwable caught=null;try{s.run(new DataInputStream(new ByteArrayInputStream(commands)));}catch(Throwable t){caught=t;}return new Result(s,new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),caught);}
    private static void stepping()throws Exception{
        var fake=new Fake();var out=new ByteArrayOutputStream();var s=new NativeStepWorker.Session(fake.api(),Path.of("original.zip"),new DataOutputStream(out));
        var commandPipe=new PipedInputStream(8192);var commands=new DataOutputStream(new PipedOutputStream(commandPipe));
        var done=new CompletableFuture<Throwable>();Thread t=new Thread(()->{try{s.run(new DataInputStream(commandPipe));done.complete(null);}catch(Throwable failure){done.complete(failure);}},"test-step-core");t.start();
        long until=System.nanoTime()+2_000_000_000L;while(out.size()<28&&System.nanoTime()<until)Thread.sleep(2);
        check(out.size()==28,"HELLO occurs before any core run");Thread.sleep(80);check(fake.runs==0,"idle does not advance core");
        NativeStepProtocol.writeCommand(commands,NativeStepProtocol.step(1,2,256,2048,8));commands.flush();
        while(fake.runs<1&&System.nanoTime()<until)Thread.sleep(2);
        NativeStepProtocol.writeCommand(commands,NativeStepProtocol.step(2,0,256,2048,8));
        NativeStepProtocol.writeCommand(commands,NativeStepProtocol.save(3));
        NativeStepProtocol.writeCommand(commands,NativeStepProtocol.close(4));commands.flush();
        check(done.get(3,TimeUnit.SECONDS)==null,"session closes normally");t.join(1000);
        var in=new DataInputStream(new ByteArrayInputStream(out.toByteArray()));var hello=NativeStepProtocol.readHello(in);check(hello.fps()==60,"HELLO load-time FPS retained");
        var first=(NativeStepProtocol.Frame)NativeStepProtocol.readReply(in,1);check(first.frame()==1&&!first.hasVideo()&&!first.freshVideo(),"first run is explicit empty video");check(first.fps()==59.18560791015625,"FRAME reports changed actual FPS");check(Arrays.equals(first.pcm48k(),new short[]{1,-1}),"first run PCM not lost");
        var second=(NativeStepProtocol.Frame)NativeStepProtocol.readReply(in,2);check(second.frame()==2&&second.hasVideo(),"one request one actual step");check(second.abgr()[0]==0xff332211,"production XRGB to ABGR conversion");check(Arrays.equals(second.pcm48k(),new short[]{2,-2}),"no PCM from previous run accumulated");
        check(Arrays.equals(fake.sampled.get(0),new int[]{256,2,2048,8}),"exact existing numbered-button mapping across four ports");
        check(Arrays.equals(fake.sampled.get(1),new int[]{0,2,2048,8}),"P1 neutral does not clear other players");
        var saved=(NativeStepProtocol.State)NativeStepProtocol.readReply(in,3);check(saved.frame()==2&&saved.state().length==4,"save keeps exact frame boundary");
        check(NativeStepProtocol.readReply(in,4)instanceof NativeStepProtocol.Closed,"close ACK present");check(fake.unloads==1&&fake.deinits==1&&s.teardownComplete,"ACK only after actual teardown calls");check(in.available()==0,"no unsolicited extra frame");
        var copy=new Fake();byte[] requests=commands(NativeStepProtocol.load(1,2,saved.state()),NativeStepProtocol.step(2,0,0,0,0),NativeStepProtocol.close(3));var restored=run(copy,requests);
        NativeStepProtocol.readHello(restored.replies);check(NativeStepProtocol.readReply(restored.replies,1).equals(new NativeStepProtocol.Loaded(1,2)),"restored counter set only after native success");var f=(NativeStepProtocol.Frame)NativeStepProtocol.readReply(restored.replies,2);check(f.frame()==3&&copy.runs==3&&copy.loads==1,"restored exact step index");check(Arrays.equals(f.pcm48k(),new short[]{3,-3}),"postload next-step audio exact");
    }
    private static void failures()throws Exception{
        for(int mode=0;mode<5;mode++){
            var f=new Fake();byte[] bytes;
            if(mode==0){f.unavailable=true;bytes=commands(NativeStepProtocol.save(1));}
            else if(mode==1)bytes=commands(NativeStepProtocol.step(2,0,0,0,0));
            else if(mode==2)bytes=commands(NativeStepProtocol.load(1,0,new byte[]{1}));
            else if(mode==3){f.badVideo=true;bytes=commands(NativeStepProtocol.step(1,0,0,0,0));}
            else bytes=Arrays.copyOf(commands(NativeStepProtocol.step(1,0,0,0,0)),17);
            var r=run(f,bytes);check(r.error!=null,"invalid IPC/native result fails closed");NativeStepProtocol.readHello(r.replies);check(NativeStepProtocol.readReply(r.replies,1)instanceof NativeStepProtocol.Failure,"bounded explicit error reply");check(f.deinits==1&&f.unloads==1&&r.session.teardownComplete,"failure cleans native lifecycle");check(f.loads==0,"invalid state not imported");
        }
        var badClose=new Fake();badClose.unloadFailure=true;var r=run(badClose,commands(NativeStepProtocol.close(1)));check(r.error!=null&&!r.session.teardownComplete&&badClose.deinits==1,"unload failure never fakes successful teardown");NativeStepProtocol.readHello(r.replies);check(r.replies.available()==0,"no false CLOSED after teardown failure");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("new classes dir and old helper jar");
        check(Path.of(NativeStepWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"new step implementation origin");
        check(Path.of(NativeCoreWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[1]).toRealPath()),"unchanged production Engine origin");
        stepping();failures();System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"real_step_worker_and_protocol\":true,\"core\":\"callback-producing-test-core\",\"mame_started\":false,\"minecraft_started\":false}");
    }
}
