// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.netplay.RollbackTimeline.Input;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JniNetplayCodecTest {
    @Test void allMessagesRoundTripAndRejectEveryTruncationAndTrailingByte() {
        List<JniNetplayCodec.Message> messages=List.of(
                new JniNetplayCodec.Hello(new NetplaySaveState.Identity("a".repeat(64),"b".repeat(64))),
                new JniNetplayCodec.Seed(50000,923,1),new JniNetplayCodec.Part(300,new byte[16000]),
                new JniNetplayCodec.End(),new JniNetplayCodec.Ready(923),
                new JniNetplayCodec.Commands(924,925,List.of(new Input(923,1,2,3),new Input(924,3,4,5,6,65536,1))),
                new JniNetplayCodec.Pad(924,65535),new JniNetplayCodec.Digest(924,"c".repeat(64)));
        for(var message:messages) {
            byte[] bytes=JniNetplayCodec.encode(message);
            assertArrayEquals(bytes,JniNetplayCodec.encode(JniNetplayCodec.decode(bytes)));
            for(int i=0;i<bytes.length;i++) {byte[] cut=Arrays.copyOf(bytes,i);assertThrows(IllegalArgumentException.class,()->JniNetplayCodec.decode(cut));}
            assertThrows(IllegalArgumentException.class,()->JniNetplayCodec.decode(Arrays.copyOf(bytes,bytes.length+1)));
            bytes[0]^=1;assertThrows(IllegalArgumentException.class,()->JniNetplayCodec.decode(bytes));
        }
    }
    @Test void allocationsAndFrameWindowsAndPortAreBounded() {
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Seed(JniNetplayCodec.SEED_LIMIT+1,0,1));
        for(int port=-1;port<4;port++){var seed=new JniNetplayCodec.Seed(9,0,port);assertEquals(seed,JniNetplayCodec.decode(JniNetplayCodec.encode(seed)));}
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Seed(9,0,4));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Seed(9,0,-2));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Part(0,new byte[16001]));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Commands(0,14,List.of(new Input(13,0,0,1))));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Commands(9,10,List.of(new Input(8,0,0,1),new Input(9,0,0,3))));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Pad(-1,0));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Pad(0,65536));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Ready(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->new JniNetplayCodec.Digest(0,"0"));
    }
    @Test void seedPartOwnsItsBytes() {
        byte[] bytes={1,2};var part=new JniNetplayCodec.Part(0,bytes);bytes[0]=7;part.data()[1]=8;
        assertArrayEquals(new byte[]{1,2},part.data());
    }
    @Test void oldWireVersionCannotBeMistakenForGunCapableRoom() {
        byte[] wire=JniNetplayCodec.encode(new JniNetplayCodec.Ready(0));wire[4]=1;
        assertThrows(IllegalArgumentException.class,()->JniNetplayCodec.decode(wire));
    }
}
