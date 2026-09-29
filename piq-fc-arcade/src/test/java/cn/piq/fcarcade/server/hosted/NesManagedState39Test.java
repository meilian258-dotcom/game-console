package cn.piq.fcarcade.server.hosted;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NesManagedState39Test {
    @Test void initialAndPublishedStatesAreNotSharedMutableBuffers(){var output=new AtomicReference<byte[]>();byte[] initial={1,2};var state=new NesManagedState("a".repeat(64),initial,output::set);initial[0]=4;assertArrayEquals(new byte[]{1,2},state.initial());var read=state.initial();read[0]=5;assertEquals(1,state.initial()[0]);byte[] snapshot={6,7};state.publish(snapshot);snapshot[0]=9;assertArrayEquals(new byte[]{6,7},output.get());}
    @Test void noSaveModeCanStartWithoutAnInitialStateOrDiskStore(){var state=new NesManagedState("a".repeat(64),null,bytes->{});assertEquals(0,state.initial().length);}
    @Test void badIdentityAndUnboundedStatesFailClosed(){assertThrows(IllegalArgumentException.class,()->new NesManagedState("../rom",null,bytes->{}));assertThrows(IllegalArgumentException.class,()->new NesManagedState("a".repeat(64),new byte[2*1024*1024+1],bytes->{}));var state=new NesManagedState("a".repeat(64),null,bytes->fail("rejected state reached sink"));assertThrows(IllegalArgumentException.class,()->state.publish(new byte[0]));assertThrows(IllegalArgumentException.class,()->state.publish(new byte[2*1024*1024+1]));}
    @Test void resetAcknowledgmentBelongsToActualCoreCallback(){var count=new AtomicInteger();var state=new NesManagedState("a".repeat(64),null,bytes->{},count::incrementAndGet);assertEquals(0,count.get());state.resetComplete();assertEquals(1,count.get());}
}
