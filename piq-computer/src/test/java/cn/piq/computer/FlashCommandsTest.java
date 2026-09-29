package cn.piq.computer;
import cn.piq.computer.flash.FlashCommands;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FlashCommandsTest {
    @Test void thousandsOfMouseMovesDoNotOverflow(){var q=new FlashCommands();for(int i=0;i<10000;i++)assertTrue(q.offer("move "+i,0));assertEquals(1,q.size());assertEquals("move 9999",q.poll());assertNull(q.poll());}
    @Test void retainsClickEdgesAndKeyOrdering(){var q=new FlashCommands();q.offer("move",0);q.offer("down",1);q.offer("drag",1);q.offer("key down",-1);q.offer("drag2",1);q.offer("up",0);assertEquals("move",q.poll());assertEquals("drag",q.poll());assertEquals("key down",q.poll());assertEquals("drag2",q.poll());assertEquals("up",q.poll());}
    @Test void refusesEdgeFloodAndCanRelease(){var q=new FlashCommands();for(int i=0;i<128;i++)assertTrue(q.offer("key "+i,-1));assertFalse(q.offer("overflow",-1));assertEquals(128,q.size());q.clear();assertTrue(q.offer("release",-1));assertEquals("release",q.poll());}
}
