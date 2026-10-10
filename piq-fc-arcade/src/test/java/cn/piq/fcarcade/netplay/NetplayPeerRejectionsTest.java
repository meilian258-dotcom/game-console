// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetplayPeerRejectionsTest {
    @Test void evidenceKeepsOnlyFirstAndLatestAndNeverRawExceptionText(){
        var evidence=new NetplayPeerRejections();
        assertEquals("主持拒绝记录：0",evidence.snapshot().text());
        var first=evidence.record(NetplayPeerRejections.Code.WIRE_SEQUENCE,1,60,62);
        int logs=0;
        for(int i=2;i<=10000;i++){
            var snapshot=evidence.record(NetplayPeerRejections.Code.INPUT_SEQUENCE,-1,i,i+2);
            if(NetplayPeerRejections.shouldLog(snapshot.count()))logs++;
        }
        var end=evidence.snapshot();assertEquals(10000,end.count());assertEquals(first.first(),end.first());
        assertEquals(10000,end.last().confirmed());assertEquals(13,logs);
        assertEquals(1,first.count());assertSame(first.first(),first.last());
        String secret="private-player C:/private/game.sfc\nsecret-token";
        var code=NetplayPeerRejections.classify(new IllegalArgumentException(secret));
        assertEquals(NetplayPeerRejections.Code.MALFORMED,code);
        assertFalse(evidence.record(code,1,2,3).text().contains("private"));
        assertTrue(end.text().length()<500);assertFalse(NetplayPeerRejections.shouldLog(0));
    }
    @Test void fixedReasonsAreClassifiedWithoutChangingTheirOriginalChecks(){
        assertEquals(NetplayPeerRejections.Code.WIRE_SEQUENCE,NetplayPeerRejections.classify(new IllegalArgumentException("JNI Netplay 包序列或授权席位异常")));
        assertEquals(NetplayPeerRejections.Code.STATE_DIGEST,NetplayPeerRejections.classify(new IllegalArgumentException("JNI Netplay 确认状态 CRC 不一致，未继续覆盖存档")));
        assertEquals(NetplayPeerRejections.Code.INPUT_SEQUENCE,NetplayPeerRejections.classify(new IllegalArgumentException("2P 预输入超限")));
        assertEquals(NetplayPeerRejections.Code.PHASE,NetplayPeerRejections.classify(new IllegalArgumentException("重复 2P 授权")));
        assertEquals(NetplayPeerRejections.Code.MALFORMED,NetplayPeerRejections.classify(new IllegalArgumentException()));
    }
    @Test void realHostRetainsCrcRejectionWhileContinuingAndPeerStillFailsClosed()throws Exception{
        try(var room=new JniNetplaySessionTest.Room(false)){
            JniNetplaySessionTest.until(room.host::ready,room);
            var peer=room.join(1);JniNetplaySessionTest.until(peer::ready,room);
            AtomicBoolean corrupted=new AtomicBoolean();
            room.peerPackets=packet->{
                if(packet.kind()==NetplayChunk.DATA&&JniNetplayCodec.decode(packet.bytes()) instanceof JniNetplayCodec.Digest digest&&corrupted.compareAndSet(false,true))
                    return new NetplayChunk(packet.session(),packet.ticket(),packet.kind(),packet.sequence(),JniNetplayCodec.encode(new JniNetplayCodec.Digest(digest.frame(),"f".repeat(64))));
                return packet;
            };
            await(()->peer.error()!=null);
            assertTrue(corrupted.get());assertNull(room.host.error(),room.host.diagnostic());assertTrue(room.host.ready());
            assertTrue(room.host.diagnostic().contains("code=STATE_DIGEST"),room.host.diagnostic());
            assertTrue(room.host.diagnostic().contains("确认状态 CRC"));assertTrue(peer.error().contains("连接已结束"));
            long before=room.host.framesReceived();await(()->room.host.framesReceived()>before+5);
            // A second bad peer changes only the latest event, never the initiating evidence.
            var ticket=UUID.randomUUID();room.host.receive(new NetplayChunk(700,ticket,NetplayChunk.OPEN,0,new byte[0]),-1);
            room.host.receive(new NetplayChunk(700,ticket,NetplayChunk.DATA,Long.MAX_VALUE,new byte[]{0}),-1);
            await(()->room.host.diagnostic().contains("last={code=WIRE_SEQUENCE"));
            assertTrue(room.host.diagnostic().contains("first={code=STATE_DIGEST"));assertNull(room.host.error());
        }
    }
    @Test void ordinaryPeerCloseDoesNotInventAHostRejection()throws Exception{
        try(var room=new JniNetplaySessionTest.Room(false)){
            JniNetplaySessionTest.until(room.host::ready,room);var peer=room.join(1);JniNetplaySessionTest.until(peer::ready,room);
            room.disconnect(peer);long before=room.host.framesReceived();await(()->room.host.framesReceived()>before+3);
            assertTrue(room.host.diagnostic().contains("主持拒绝记录：0"));assertNull(room.host.error());
        }
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"production session condition timed out");
    }
}
