// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.gba.bridge.GbaSession;
import cn.piq.gba.bridge.GbaProcessSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Production close barrier against a deterministic core; no Minecraft or native launch. */
public final class GbaSafeEjectProbe {
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static final class Core implements GbaSession {
        final List<String> calls=new ArrayList<>();
        boolean closed=true;String error;String throwsAt;boolean linkage;
        void step(String name){calls.add(name);if(name.equals(throwsAt)){if(linkage)throw new UnsatisfiedLinkError("test native failure");throw new IllegalStateException("test failure");}}
        public boolean isReady(){throw new AssertionError("Eject must not run a frame");}
        public void offerInput(int mask){throw new AssertionError("Eject must not submit input");}
        public void clearInput(){throw new AssertionError("Client releases input, barrier only confirms close");}
        public GbaProcessSession.Frame pollFrame(){throw new AssertionError("Eject must not poll a frame");}
        public void close(){step("close");}
        public boolean awaitClosed(long ms){check(ms==5000,"Bounded five-second confirmation");step("await");return closed;}
        public String error(){step("error");return error;}
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0)check(Path.of(GbaSafeEject.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(args[0]).toRealPath()),"Production barrier loaded from final JAR");
        check(GbaSafeEject.finish(null).safe(),"Never started core has no pending save");
        for(String error:new String[]{null,"","  "}){
            var core=new Core();core.error=error;var result=GbaSafeEject.finish(core);
            check(result.safe()&&result.failure().isEmpty(),"Confirmed clean shutdown permits eject");
            check(core.calls.equals(List.of("close","await","error")),"Save must finish before success/error check");
        }
        var timeout=new Core();timeout.closed=false;var result=GbaSafeEject.finish(timeout);
        check(!result.safe()&&!result.failure().isBlank(),"Timeout retains cartridge");
        check(timeout.calls.equals(List.of("close","await")),"No success inferred from pending save");
        timeout.closed=true;check(GbaSafeEject.finish(timeout).safe(),"Retry may eject after the same pending save really finishes");
        var failedSave=new Core();failedSave.error="disk full";result=GbaSafeEject.finish(failedSave);
        check(!result.safe()&&result.failure().equals("disk full"),"Closed core with failed save cannot eject");
        check(!GbaSafeEject.finish(failedSave).safe(),"Repeated close does not turn a final save error into success");
        for(String where:List.of("close","await","error"))for(boolean linkage:new boolean[]{false,true}){
            var core=new Core();core.throwsAt=where;core.linkage=linkage;result=GbaSafeEject.finish(core);
            check(!result.safe()&&!result.failure().isBlank(),"Failure at "+where+" retains cartridge");
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\""+(args.length>0?"final-jar-only":"source-actual-api")+"\",\"minecraft_or_native_core_started\":false}");
    }
}
