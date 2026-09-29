package cn.piq.nativearcade.bridge;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
public final class NativeBridgeTerminationProbe {
    public static void main(String[] args)throws Exception{
        long start=System.nanoTime();NativeProcessSession s=new NativeProcessSession(Path.of(args[0]),Path.of(args[1]));
        if(args[2].equals("shutdown")){
            while(!s.isReady()&&s.error()==null&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(15))Thread.sleep(10);
            if(!s.isReady())throw new AssertionError("True helper never became ready: "+s.error());
            long child=ProcessHandle.current().children().filter(ProcessHandle::isAlive).mapToLong(ProcessHandle::pid).findFirst().orElseThrow();
            System.out.println("CHILD_PID="+child);System.out.flush();
            System.exit(0); // Deliberately no close(): actual JVM shutdown hook must reap the exact child.
        }
        while(s.error()==null&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(20))Thread.sleep(10);
        String error=s.error();if(error==null||!error.contains("15-second"))throw new AssertionError("Missing native timeout: "+error);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until)Thread.sleep(10);
        if(NativeProcessSession.hasLiveSession())throw new AssertionError("Timed-out exact child remains live");
        System.out.println("TIMEOUT_REAP_PASS elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
    }
}

