package cn.piq.nativearcade.bridge;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Rejects a byte-exact old helper before a child process or native library can start. */
public final class NativeHelperIdentity26Probe {
    public static void main(String[]args)throws Exception{
        if(!Path.of(NativeProcessSession.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[2]).toRealPath()))throw new AssertionError("Wrong parent origin");
        try(var session=new NativeProcessSession(Path.of(args[0]),Path.of(args[1]))){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<deadline)Thread.sleep(5);
            if(NativeProcessSession.hasLiveSession())throw new AssertionError("Rejected helper retained process ownership");
            if(session.error()==null||!session.error().contains("Matching v3 numbered-button helper required"))throw new AssertionError("Missing actionable version error: "+session.error());
            var process=NativeProcessSession.class.getDeclaredField("process");process.setAccessible(true);
            if(process.get(session)!=null)throw new AssertionError("Old helper launched a child");
            if(!session.isClosed()||session.isReady()||session.pollFrame()!=null)throw new AssertionError("Rejected helper exposed ready/frame state");
        }
        System.out.println("{\"ok\":true,\"assertions\":5,\"old_helper_rejected_before_process_launch\":true,\"actionable_error\":true,\"native_core_started\":false,\"minecraft_started\":false}");
    }
}
