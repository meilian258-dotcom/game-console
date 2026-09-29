import cn.piq.nativearcade.bridge.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Real parent IPC smoke and restore evidence; does NOT qualify native netplay. */
public final class NativeStepMameProbe {
    static int assertions;
    static void check(boolean b,String text){assertions++;if(!b)throw new AssertionError(text);}
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String video(NativeStepProtocol.Frame f)throws Exception{
        int[] pixels=f.abgr();ByteBuffer b=ByteBuffer.allocate(pixels.length*4+16);
        b.putInt(f.width()).putInt(f.height()).putInt(f.rotation()).putFloat(f.displayAspect());for(int p:pixels)b.putInt(p);
        return sha(b.array());
    }
    static String audio(NativeStepProtocol.Frame f)throws Exception{
        short[] samples=f.pcm48k();ByteBuffer b=ByteBuffer.allocate(samples.length*2);for(short p:samples)b.putShort(p);return sha(b.array());
    }
    static int[] masks(int index){
        if(index<2000)return new int[4];
        int key=new int[]{0,1,8,9,10,11}[(index/17)%6];
        int p1=(1<<key)|(index%180<90?1<<6:1<<7);
        if(index==2000||index==2060)p1|=1<<2;
        if(index==2030||index==2090)p1|=1<<3;
        return new int[]{p1,index%80<20?1<<8:0,index%90<20?1<<9:0,index%100<20?1<<10:0};
    }
    static NativeStepProtocol.Frame run(NativeStepSession s,int i)throws Exception{
        int[] p=masks(i);var f=s.step(p[0],p[1],p[2],p[3]);check(f.frame()==i+1,"one step, one frame");return f;
    }
    public static void main(String[] a)throws Exception{
        var files=new NativeStepSession.RuntimeFiles(Path.of(a[0]),a[1],Path.of(a[2]),BridgeProtocol.CORE_SHA,Path.of(a[3]),a[4]);
        long began=System.nanoTime(),pid=-1;String[] v=new String[600],pcm=new String[600];
        int empty=0,videoDifferences=0,audioDifferences=0,stateDifferences=0,stateBytes=0;boolean idleStateEqual=false,loadResaveEqual=false;
        double actualFps=0;long samples=0;String stateSha="";
        NativeStepSession session=null;
        try{
            session=new NativeStepSession(files,Path.of(a[5]));pid=session.ownedPid();check(pid>0,"exact child exists");
            check(session.hello().maxPorts()==4&&session.hello().sampleRate()==48000,"hello");
            try{session.step(4096,0,0,0);throw new AssertionError("invalid mask accepted");}catch(IllegalArgumentException expected){assertions++;}
            try{session.loadState(-1,new byte[]{1});throw new AssertionError("negative frame accepted");}catch(IllegalArgumentException expected){assertions++;}
            try(var other=new NativeStepSession(files,Path.of(a[5]))){throw new AssertionError("overlapping native child");}catch(java.io.IOException expected){assertions++;}
            for(int i=0;i<2200;i++){var f=run(session,i);if(!f.hasVideo())empty++;else check(f.width()>0&&f.height()>0,"real video");actualFps=f.fps();samples+=f.pcm48k().length;}
            check(session.frame()==2200,"frame after local invalid requests");
            byte[] state=session.saveState();stateBytes=state.length;stateSha=sha(state);
            Thread.sleep(250);byte[] idle=session.saveState();idleStateEqual=Arrays.equals(state,idle);
            check(session.frame()==2200,"idle/save cannot step");
            for(int i=0;i<600;i++){var f=run(session,2200+i);v[i]=video(f);pcm[i]=audio(f);}
            byte[] finalState=session.saveState();
            session.loadState(2200,state);check(session.frame()==2200,"restore frame receipt");
            loadResaveEqual=Arrays.equals(state,session.saveState());
            for(int i=0;i<600;i++){var f=run(session,2200+i);if(!v[i].equals(video(f)))videoDifferences++;if(!pcm[i].equals(audio(f)))audioDifferences++;}
            byte[] restoredFinal=session.saveState();
            if(restoredFinal.length!=finalState.length)stateDifferences=-1;
            else for(int i=0;i<finalState.length;i++)if(finalState[i]!=restoredFinal[i])stateDifferences++;
            check(session.error()==null,"no IPC failure");check(samples>0&&actualFps>50&&actualFps<70,"actual core audio/timing");
        }finally{
            if(session!=null){session.close();long until=System.nanoTime()+5_000_000_000L;
                while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until)Thread.sleep(10);
                check(!NativeProcessSession.hasLiveSession(),"shared lease released");
                check(ProcessHandle.of(pid).map(p->!p.isAlive()).orElse(true),"exact child dead");
            }
        }
        System.out.println("{\"ipc_ok\":true,\"assertions\":"+assertions+",\"owned_pid\":"+pid+
            ",\"actual_fps\":"+actualFps+",\"pcm_rate\":48000,\"initial_empty_frames\":"+empty+
            ",\"state_bytes\":"+stateBytes+",\"state_sha256\":\""+stateSha+"\",\"idle_state_equal\":"+idleStateEqual+
            ",\"load_resave_equal\":"+loadResaveEqual+",\"restored_video_mismatches_of_600\":"+videoDifferences+
            ",\"restored_pcm_mismatches_of_600\":"+audioDifferences+",\"restored_final_state_different_bytes\":"+stateDifferences+
            ",\"elapsed_seconds\":"+(System.nanoTime()-began)/1e9+",\"native_sync_qualified\":false}");
    }
}
