package cn.piq.fcarcade.netplay;

import java.util.UUID;

/** A bounded piece of an authenticated, ordered native TCP stream. */
public record NetplayChunk(long session, UUID ticket, int kind, long sequence, byte[] bytes) {
    public static final int OPEN=0, ACK=1, DATA=2, CLOSE=3, LIMIT=16_384;
    public NetplayChunk {
        if(session<0||ticket==null||kind<0||kind>3||sequence<0||bytes==null||bytes.length>LIMIT
                ||kind!=DATA&&bytes.length!=0||kind==DATA&&bytes.length==0)throw new IllegalArgumentException("Netplay chunk bounds");
        bytes=bytes.clone();
    }
    @Override public byte[] bytes(){return bytes.clone();}
    public int byteLength(){return bytes.length;}
}
