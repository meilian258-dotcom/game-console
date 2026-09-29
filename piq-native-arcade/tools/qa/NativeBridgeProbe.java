package cn.piq.nativearcade.bridge;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real dedicated-process smoke; checks the same parent bridge called by Minecraft. */
public final class NativeBridgeProbe {
    static int checks;
    static void require(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
    static NativeProcessSession.Frame next(NativeProcessSession s)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){
            if(s.error()!=null){Thread.sleep(200);throw new AssertionError(s.error()+"\n"+s.diagnostics());}
            NativeProcessSession.Frame f=s.pollFrame();if(f!=null)return f;Thread.sleep(5);
        }throw new AssertionError("No timely frame");
    }
    static int hash(NativeProcessSession.Frame f){return Arrays.hashCode(f.abgr());}
    static NativeProcessSession.Frame settle(NativeProcessSession s,int n)throws Exception{
        NativeProcessSession.Frame last=null;for(int i=0;i<n;i++)last=next(s);return last;
    }
    public static void main(String[] args)throws Exception{
        Path runtime=Path.of(args[0]),rom=Path.of(args[1]);long start=System.nanoTime();String logs;
        try(NativeProcessSession s=new NativeProcessSession(runtime,rom)){
            try{new NativeProcessSession(runtime,rom);throw new AssertionError("Second process permitted");}
            catch(java.io.IOException expected){checks++;}
            NativeProcessSession.Frame idle=settle(s,30);
            require(idle.width()==260&&idle.height()==224,"Actual driver dimensions");
            require(idle.rotation()==1,"Actual driver rotation");
            require(idle.displayAspect()>1,"Raw unrotated DAR, observed "+idle.displayAspect());
            require(idle.abgr().length==idle.width()*idle.height(),"Pixels exact");
            require(Arrays.stream(idle.abgr()).allMatch(p->(p>>>24)==255),"ABGR opaque");
            int initial=hash(idle);
            int[] masks={1,1<<2,1<<3,1<<6,1<<7};
            for(int mask:masks){s.offerInput(mask,0);require(hash(settle(s,24))!=initial,"Actual input pixels "+mask);
                s.offerInput(0,0);require(hash(settle(s,24))==initial,"Input release "+mask);}
            s.offerInput(0,1<<3);require(hash(settle(s,24))!=initial,"Second port start");
            s.clearInput();require(hash(settle(s,24))==initial,"Clear input");
            // Queue a press and release in one host update; separate native frames must retain the edge.
            s.offerInput(1,0);s.offerInput(0,0);boolean sawPress=false;
            for(int i=0;i<24;i++)if(hash(next(s))!=initial)sawPress=true;
            require(sawPress,"Rapid press/release delivered to distinct native frames");
            require(hash(settle(s,24))==initial,"Rapid release returns to idle");
            Thread.sleep(130);NativeProcessSession.Frame merged=next(s);
            require(merged.pcm48k().length>6000&&merged.pcm48k().length<=32768,"Low host FPS merges audio");
            boolean audio=false;for(short v:merged.pcm48k())audio|=v!=0;require(audio,"Real nonzero PCM");
            require(s.error()==null,"No process errors");logs=s.diagnostics();
            System.out.println("FRAME="+idle.width()+"x"+idle.height()+" DAR="+idle.displayAspect()+" ROTATION="+idle.rotation());
            System.out.println("MERGED_PCM_SHORTS="+merged.pcm48k().length+" CHECKS="+checks);
        }
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until)Thread.sleep(10);
        require(!NativeProcessSession.hasLiveSession(),"Exact child exited before process slot release");
        System.out.println("PASS="+checks+" ELAPSED_MS="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        System.out.println("EXPECTED_ORIGINAL_FIRMWARE_CHECKSUM_WARNINGS="+logs.contains("WRONG CHECKSUMS"));
    }
}
