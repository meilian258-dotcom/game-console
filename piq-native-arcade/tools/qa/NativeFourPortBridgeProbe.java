package cn.piq.nativearcade.bridge;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Original diagnostic firmware only; P3/P4 parser/callback proof is a separate helper probe. */
public final class NativeFourPortBridgeProbe {
    public static void main(String[]args)throws Exception{
        Path runtime=Path.of(args[0]),rom=Path.of(args[1]);int checks=0;
        if(args.length>2){
            if(!Path.of(NativeProcessSession.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[2]).toRealPath()))throw new AssertionError("Parent not from supplied final mod JAR");checks++;
        }
        try(var s=new NativeProcessSession(runtime,rom)){
            if(s.maxPlayers()!=4)throw new AssertionError("Four-port capability missing");checks++;
            int idle=NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,30));
            s.offerInputs(1,0,256,512);int p1=NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24));if(p1==idle)throw new AssertionError("Original P1 input missing");checks++;
            s.releasePort(2);if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))!=p1)throw new AssertionError("P3 release changed P1");checks++;
            s.releasePort(3);if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))!=p1)throw new AssertionError("P4 release changed P1");checks++;
            s.releasePort(0);if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))!=idle)throw new AssertionError("P1 independent release failed");checks++;
            s.offerInputs(0,8,0,0);int p2=NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24));if(p2==idle)throw new AssertionError("P2 input missing");checks++;
            s.releasePort(0);if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))!=p2)throw new AssertionError("P1 release changed P2");checks++;
            s.releasePort(1);if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))!=idle)throw new AssertionError("P2 release failed");checks++;
            s.offerInputs(1,0,4,8);s.offerInputs(0,0,0,0);s.releasePort(2);s.releasePort(3);boolean pressed=false;
            for(int n=0;n<30;n++)pressed|=NativeBridgeProbe.hash(NativeBridgeProbe.next(s))!=idle;
            if(!pressed)throw new AssertionError("Other ports' release discarded P1 tap");checks++;
            if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,20))!=idle)throw new AssertionError("Rapid release was lost");checks++;
            s.offerInput(1,0);if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))==idle)throw new AssertionError("Legacy two-port call broken");checks++;
            s.clearInput();if(NativeBridgeProbe.hash(NativeBridgeProbe.settle(s,24))!=idle)throw new AssertionError("Explicit clear-all failed");checks++;
            if(s.error()!=null)throw new AssertionError(s.error());checks++;
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<deadline)Thread.sleep(10);
        if(NativeProcessSession.hasLiveSession())throw new AssertionError("Exact child not reaped");checks++;
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_mame_bridge\":true,\"original_diagnostic_only\":true,\"minecraft_started\":false,\"four_player_commercial_game_tested\":false}");
    }
}
