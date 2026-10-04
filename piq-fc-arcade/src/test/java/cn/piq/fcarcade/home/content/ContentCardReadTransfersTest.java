package cn.piq.fcarcade.home.content;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardReadTransfersTest {
    private final ContentCardReadTransfers<Object> reads=new ContentCardReadTransfers<>();
    private final UUID player=UUID.randomUUID(),token=UUID.randomUUID();private final Object connection=new Object(),value=new Object();
    @Test void runningControlPlayIsIndependentFromTwoObserverReadsAndTheirCancellation(){
        var controls=new HashMap<UUID,Object>();controls.put(player,value);var first=new Object();var second=new Object();var t2=UUID.randomUUID();
        assertTrue(reads.add(player,token,connection,1024,first,8192));assertTrue(reads.add(player,t2,connection,1024,second,8192));
        assertTrue(reads.remove(player,token,first));assertSame(value,controls.get(player));assertSame(second,reads.get(player,t2,connection));
        assertFalse(reads.remove(player,token,first));assertEquals(1024,reads.reservedBytes());
    }
    @Test void sameTokenFromDifferentConnectionOrPlayerNeverSelectsTheOldRead(){
        assertTrue(reads.add(player,token,connection,4,value,0));
        assertNull(reads.get(player,token,new Object()));assertNull(reads.get(UUID.randomUUID(),token,connection));
        assertFalse(reads.add(player,token,new Object(),4,new Object(),0));assertSame(value,reads.get(player,token,connection));
        assertTrue(reads.remove(player,token,value));var next=new Object();assertTrue(reads.add(player,token,next,4,next,0));
        assertFalse(reads.remove(player,token,value));assertSame(next,reads.get(player,token,next));
    }
    @Test void maximumFourPerPlayerAndSixteenGloballyDoNotEvictAnyLease(){
        for(int i=0;i<4;i++)assertTrue(reads.add(player,UUID.randomUUID(),connection,1,new Object(),0));
        assertFalse(reads.add(player,token,connection,1,value,0));
        for(int i=4;i<16;i++)assertTrue(reads.add(UUID.randomUUID(),UUID.randomUUID(),connection,1,new Object(),0));
        assertFalse(reads.add(UUID.randomUUID(),token,connection,1,value,0));assertEquals(16,reads.snapshot().size());
    }
    @Test void originalSixtyFourMiBBudgetIncludesRuntimeAndUploadReservations(){
        int size=32*1024*1024;assertTrue(reads.add(player,token,connection,size,value,32L*1024*1024));
        assertFalse(reads.add(player,UUID.randomUUID(),connection,1,new Object(),32L*1024*1024));
        assertTrue(reads.remove(player,token,value));assertTrue(reads.add(player,token,connection,size,value,0));
        assertTrue(reads.add(player,UUID.randomUUID(),connection,size,new Object(),0));
        assertFalse(reads.add(player,UUID.randomUUID(),connection,1,new Object(),0));
    }
    @Test void invalidSizesAndShutdownCannotLeaveReservations(){
        for(int n:new int[]{0,-1,ContentCardStore.MAX_BYTES+1})assertFalse(reads.add(player,token,connection,n,value,0));
        assertTrue(reads.add(player,token,connection,12,value,0));reads.clear();assertEquals(0,reads.reservedBytes());assertNull(reads.get(player,token,connection));
    }
    @Test void productionRoutesReadsSeparatelyAndNeverPromotesThemToRuntime()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/content/ContentCards.java"));
        assertTrue(source.contains("s.reads.get(p.getUUID(),m.token(),p.connection.getConnection())"));
        assertTrue(source.contains("!downloadOnly&&(s.plays.containsKey(p.getUUID())||s.plays.size()>=4)"));
        assertTrue(source.contains("play.downloadOnly?s.reads.remove(p.getUUID(),play.token,play):s.plays.remove(p.getUUID(),play)"));
        assertTrue(source.contains("if(play.downloadOnly){s.reads.remove(p.getUUID(),play.token,play);play.bytes=null;}"));
        assertTrue(source.contains("play.downloadOnly||!play.token.equals(token)"));
        assertTrue(source.contains("bytes+s.reads.reservedBytes()"));assertTrue(source.contains("online(p,play.connection)&&play.authorized.getAsBoolean()"));
    }
}
