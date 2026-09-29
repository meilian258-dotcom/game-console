package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Bounded host upload validation. Never inflates untrusted media on the server thread. */
final class CabinetRoomMedia {
    static final int CHUNK=24576, MAX_FRAME=131072, MAX_CHUNKS=6, BYTES_PER_SECOND=1572864;
    record Part(long sequence,int kind,int index,int count,int width,int height,float aspect,int rotation,int rawLength,byte[] data){
        Part{check(sequence,kind,index,count,width,height,aspect,rotation,rawLength,data);data=data.clone();}
        @Override public byte[] data(){return data.clone();}
    }
    static void check(long sequence,int kind,int index,int count,int width,int height,float aspect,int rotation,int rawLength,byte[] data){
        if(sequence<0||(kind!=0&&kind!=1)||count<1||count>MAX_CHUNKS||index<0||index>=count||data==null||data.length<1||data.length>CHUNK
                ||(index<count-1&&data.length!=CHUNK)||!Float.isFinite(aspect)||aspect<.1F||aspect>10F||rotation<0||rotation>3)
            throw new IllegalArgumentException("Invalid cabinet media part");
        if(kind==0){if(width<1||width>384||height<1||height>288||rawLength!=(long)width*height*2)throw new IllegalArgumentException("Invalid video dimensions");}
        else if(width!=0||height!=0||aspect!=1F||rotation!=0||count!=1||index!=0||rawLength<4||rawLength>19200||(rawLength&3)!=0||data.length!=rawLength)
            throw new IllegalArgumentException("Invalid stereo PCM block");
        if((long)(count-1)*CHUNK+(index==count-1?data.length:1)>MAX_FRAME)throw new IllegalArgumentException("Media frame exceeds bound");
    }
    private static final class Pending {
        final Part first;final List<Part> parts=new ArrayList<>();int bytes;final long began;
        Pending(Part first,long now){this.first=first;began=now;}
        boolean same(Part p){return p.sequence==first.sequence&&p.kind==first.kind&&p.count==first.count&&p.width==first.width&&p.height==first.height
                &&p.aspect==first.aspect&&p.rotation==first.rotation&&p.rawLength==first.rawLength;}
    }
    private final Pending[] pending=new Pending[2];
    private final long[] last={-1,-1};
    private final CabinetRoomLedger.Window bytes=new CabinetRoomLedger.Window();
    private final CabinetRoomLedger.Window[] frames={new CabinetRoomLedger.Window(),new CabinetRoomLedger.Window()};
    List<Part> accept(Part part,long now){
        if(!bytes.allow(now,part.data.length+128,BYTES_PER_SECOND))return List.of();
        int kind=part.kind;Pending p=pending[kind];
        if(p!=null&&now-p.began>=40){pending[kind]=null;p=null;}
        if(part.index==0){
            if(part.sequence<=last[kind]||!frames[kind].allow(now,1,32))return List.of();
            last[kind]=part.sequence;pending[kind]=p=new Pending(part,now);
        }
        if(p==null||!p.same(part)||part.index!=p.parts.size())return List.of();
        if((long)p.bytes+part.data.length>MAX_FRAME){pending[kind]=null;return List.of();}
        p.parts.add(part);p.bytes+=part.data.length;
        if(p.parts.size()!=part.count)return List.of();
        pending[kind]=null;return List.copyOf(p.parts);
    }
}
