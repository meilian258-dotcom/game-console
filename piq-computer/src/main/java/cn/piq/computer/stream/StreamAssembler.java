package cn.piq.computer.stream;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/** One bounded frame only. Reject duplicate/out-of-order fragments and expired partial frames. */
public final class StreamAssembler {
    private final UUID computer,session;
    private long last=-1,sequence=-1,started,micros;
    private int next,count;
    private ByteArrayOutputStream buffer;
    public StreamAssembler(UUID computer,UUID session){this.computer=computer;this.session=session;}
    public byte[] accept(StreamPart p,long now){
        if(p.kind()!=0||!computer.equals(p.computer())||!session.equals(p.session())||p.sequence()<=last)return null;
        if(buffer!=null&&now-started>1_000_000_000L){last=sequence;buffer=null;}
        if(p.sequence()<=last)return null;
        if(p.index()==0){if(p.sequence()<=sequence)return null;sequence=p.sequence();started=now;micros=p.micros();next=0;count=p.count();buffer=new ByteArrayOutputStream(StreamPart.MAX_IMAGE);}
        if(buffer==null||p.sequence()!=sequence||p.index()!=next||p.count()!=count||p.micros()!=micros)return null;
        buffer.writeBytes(p.bytes());next++;
        if(next!=count)return null;
        byte[] result=buffer.toByteArray();buffer=null;last=sequence;return result;
    }
}
