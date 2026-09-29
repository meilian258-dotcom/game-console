// Diagnostic only: final-JAR frame arrival pacing, no Minecraft performance claim.
import cn.piq.pvz.runtime.PvzRuntime;
import java.nio.file.*;
import java.util.*;
public final class FrameBenchmark {
    public static void main(String[] a)throws Exception {
        try(var r=new PvzRuntime(Path.of(a[0]),Path.of(a[1]),UUID.fromString("ab2e309f-0fd0-44fa-b71c-f6b59ce74219"))){
            r.volume(0);Thread.sleep(8000);byte[] warm=r.poll();
            if(warm!=null)try{PvzRuntime.class.getMethod("releaseFrame",byte[].class).invoke(r,(Object)warm);}catch(NoSuchMethodException ignored){}
            long start=System.nanoTime(),last=start;var gaps=new ArrayList<Double>();int frames=0;
            while(System.nanoTime()-start<15_000_000_000L){
                byte[] frame=r.poll();if(frame!=null){long now=System.nanoTime();gaps.add((now-last)/1e6);last=now;frames++;
                    // prototype.2 optional return-to-pool; leave v1 baseline untouched.
                    try{PvzRuntime.class.getMethod("releaseFrame",byte[].class).invoke(r,(Object)frame);}catch(NoSuchMethodException ignored){}
                }
                if(r.finished())throw new IllegalStateException(r.error());Thread.sleep(1);
            }
            Collections.sort(gaps);double seconds=(System.nanoTime()-start)/1e9;
            System.out.printf(Locale.ROOT,"{\"frames\":%d,\"seconds\":%.3f,\"fps\":%.2f,\"gapMedianMs\":%.2f,\"gapP95Ms\":%.2f,\"gapMaxMs\":%.2f}%n",frames,seconds,frames/seconds,gaps.get(gaps.size()/2),gaps.get((int)(gaps.size()*.95)),gaps.get(gaps.size()-1));
            r.close();if(!r.error().isEmpty())throw new IllegalStateException(r.error());
        }
    }
}
