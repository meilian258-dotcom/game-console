package cn.piq.replayquick;

import com.replaymod.replaystudio.protocol.Packet;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public final class QuickSableScopeTest {
    static int checks;
    static void require(boolean valid,String why){checks++;if(!valid)throw new AssertionError(why);}
    static Packet packet(){return new Packet(null,0,null,com.github.steveice10.netty.buffer.Unpooled.buffer());}
    public static void main(String[] args)throws Exception {
        var outer=new QuickSableState(null,null);var inner=new QuickSableState(null,null);var first=packet();var second=packet();
        try{
            require(QuickSableScope.forPacket(first)==null,"no initial scope");
            QuickSableScope.generated(first,()->require(QuickSableScope.forPacket(first)==null,"generated outside seek inactive"));
            QuickSableScope.seek(outer,5,()->{
                require(QuickSableScope.forPacket(first)==null,"seek without provenance inactive");
                QuickSableScope.generated(first,()->{
                    require(QuickSableScope.forPacket(first).state()==outer&&QuickSableScope.forPacket(first).targetTime()==5,"outer identity/time");
                    require(QuickSableScope.forPacket(second)==null,"different Packet rejected");
                    try{QuickSableScope.seek(inner,10,()->QuickSableScope.generated(second,()->{
                        require(QuickSableScope.forPacket(second).state()==inner&&QuickSableScope.forPacket(second).targetTime()==10,"nested identity/time");
                        require(QuickSableScope.forPacket(first)==null,"nested provenance exact");throw new IOException("sentinel");
                    }));throw new AssertionError("Expected sentinel");}catch(IOException expected){require(expected.getMessage().equals("sentinel"),"original exception retained");}
                    require(QuickSableScope.forPacket(first).state()==outer&&QuickSableScope.forPacket(first).targetTime()==5,"exception restores outer scope");
                    require(QuickSableScope.forPacket(second)==null,"inner marker cleared");
                    var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{
                        try{require(QuickSableScope.forPacket(first)==null,"scope not inherited by another thread");
                            QuickSableScope.seek(inner,15,()->QuickSableScope.generated(second,()->require(QuickSableScope.forPacket(second).state()==inner,"independent thread scope")));
                            require(QuickSableScope.forPacket(second)==null,"worker cleanup");
                        }catch(Throwable issue){failure.set(issue);}
                    });thread.start();try{thread.join(5_000);}catch(InterruptedException issue){Thread.currentThread().interrupt();throw new IOException(issue);}
                    require(!thread.isAlive()&&failure.get()==null,"worker finished without leaking state");
                    require(QuickSableScope.forPacket(first).state()==outer,"worker cannot modify caller scope");
                });require(QuickSableScope.forPacket(first)==null,"generated marker cleared after success");
            });require(QuickSableScope.forPacket(first)==null,"seek cleared after success");
            try{QuickSableScope.seek(outer,20,()->QuickSableScope.generated(first,()->{throw new IllegalStateException("runtime");}));}
            catch(IllegalStateException expected){require(expected.getMessage().equals("runtime"),"runtime failure retained");}
            require(QuickSableScope.forPacket(first)==null,"runtime exception finally clears both scopes");
        }finally{first.release();second.release();}
        System.out.println("QuickSableScopeTest checks="+checks+" PASS");
    }
}
