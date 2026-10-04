package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.netplay.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchPreparationTest {
    private static NetplayProcess.Grant observer(long wire){return new NetplayProcess.Grant(wire,UUID.randomUUID(),false,false);}
    private static CabinetBackend.NetplayContent content(){return new CabinetBackend.NetplayContent(NetplayProfile.fc(),new byte[16],Map.of());}
    @Test void legacyPreparationCreatesOnlyAnObserverAndKeepsExactWire(){
        var data=content();var p=new NetplayWatchContent.Preparation(()->data,()->{});
        var grant=observer(41);var run=p.open(grant,data,c->{});
        assertEquals(grant,run.grant());assertFalse(run.nativeSlotHeld());
        run.close();assertTrue(run.terminated().isDone());
    }
    @Test void trustedFactoryCannotBeCalledWithHostOrPlayerAuthority(){
        var calls=new AtomicInteger();var data=content();
        var p=new NetplayWatchContent.Preparation(()->data,()->{},false,(grant,body,sender)->{calls.incrementAndGet();return new NetplayProcess(grant,body::rom,sender);});
        assertThrows(IllegalArgumentException.class,()->p.open(new NetplayProcess.Grant(1,UUID.randomUUID(),true,false),data,c->{}));
        assertThrows(IllegalArgumentException.class,()->p.open(new NetplayProcess.Grant(1,UUID.randomUUID(),false,true),data,c->{}));
        assertEquals(0,calls.get());var run=p.open(observer(2),data,c->{});assertEquals(1,calls.get());run.close();
    }
    @Test void separatePreparationCancellationDoesNotTouchAnotherSource(){
        var a=new AtomicInteger();var b=new AtomicInteger();var data=content();
        var first=new NetplayWatchContent.Preparation(()->data,a::incrementAndGet);
        var second=new NetplayWatchContent.Preparation(()->data,b::incrementAndGet);
        first.cancel().run();assertEquals(1,a.get());assertEquals(0,b.get());
        var run=second.open(observer(7),data,c->{});assertEquals(7,run.grant().session());run.close();
    }
    @Test void fcTrustedFactoryPreservesJniAndGunConstructorWithoutOpeningNativeCode(){
        for(boolean gun:new boolean[]{false,true}){
            var p=new NetplayWatchContent.Preparation(WatchPreparationTest::content,()->{},false,
                    (grant,body,sender)->new NetplayProcess(grant,body::rom,sender,true,gun));
            var run=p.open(observer(gun?4:3),content(),c->{});
            assertFalse(run.nativeSlotHeld());assertTrue(run.diagnostic().contains("JNI"));
            run.close();assertTrue(run.terminated().isDone());
        }
    }
}
