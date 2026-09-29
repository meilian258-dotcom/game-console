package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import java.io.ByteArrayOutputStream;

/** One bounded in-flight video frame; TCP preserves order, so gaps discard rather than wait. */
public final class CabinetMediaAssembler {
    public record Complete(CabinetMediaPacket header,byte[] bytes) {}
    private CabinetMediaPacket header;
    private ByteArrayOutputStream bytes;
    private int next;
    private long deadline,lastVideo=-1,lastAudio=-1;
    public Complete accept(CabinetMediaPacket part,long now){
        if(part.kind()==1){
            if(part.sequence()<=lastAudio)return null;
            if(part.index()!=0||part.count()!=1||part.data().length!=part.rawLength()||part.rawLength()>19200||(part.rawLength()&3)!=0)
                throw new IllegalArgumentException("Invalid audio packet");
            lastAudio=part.sequence();return new Complete(part,part.data());
        }
        if(header!=null&&now>deadline)clear();
        if(part.sequence()<=lastVideo)return null;
        if(part.index()==0){
            if(header!=null&&part.sequence()<=header.sequence())return null;
            clear();header=part;bytes=new ByteArrayOutputStream(Math.min(131072,part.count()*24576));next=0;deadline=now+2_000_000_000L;
        }
        if(header==null||part.sequence()!=header.sequence())return null;
        if(part.index()!=next||part.count()!=header.count()||part.width()!=header.width()||part.height()!=header.height()
                ||part.rawLength()!=header.rawLength()||part.rotation()!=header.rotation()||Float.compare(part.aspect(),header.aspect())!=0){clear();throw new IllegalArgumentException("Inconsistent video fragments");}
        byte[] data=part.data();
        if(data.length>24576||bytes.size()+data.length>131072){clear();throw new IllegalArgumentException("Video budget exceeded");}
        bytes.writeBytes(data);next++;
        if(next<header.count())return null;
        Complete result=new Complete(header,bytes.toByteArray());lastVideo=header.sequence();clear();return result;
    }
    private void clear(){header=null;bytes=null;next=0;}
}
