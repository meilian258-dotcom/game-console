package cn.piq.nativearcade.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Independent, bounded big-endian RPC. The old media bridge v3 is unchanged. */
public final class NativeStepProtocol {
    private NativeStepProtocol(){}
    public static final int MAGIC=0x50495153,VERSION=1;
    public static final int STEP=1,SAVE=2,LOAD=3,CLOSE=4;
    public static final int FRAME=1,STATE=2,LOADED=3,CLOSED=4,ERROR=5;
    public static final int MAX_STATE=16*1024*1024,MAX_ERROR=4096,MAX_PIXELS=2048*2048,MAX_DIM=2048,MAX_PCM=16384;
    public record Hello(double fps,double sampleRate,int maxPorts){
        public Hello{if(!Double.isFinite(fps)||fps<20||fps>240||sampleRate!=48000||maxPorts!=4)throw new IllegalArgumentException("Invalid native timing/capability");}
    }
    public record Request(int kind,long id,long frame,int p1,int p2,int p3,int p4,byte[] state){
        public Request{identity(id);if(kind<STEP||kind>CLOSE||frame<0)throw new IllegalArgumentException("Invalid native request");
            if(((p1|p2|p3|p4)&~4095)!=0)throw new IllegalArgumentException("Only four 12-bit input masks are accepted");
            if(kind==LOAD){state=copyState(state);}else if(state!=null||frame!=0)throw new IllegalArgumentException("Unexpected request payload");
            if(kind!=STEP&&(p1|p2|p3|p4)!=0)throw new IllegalArgumentException("Unexpected controller payload");}
        @Override public byte[] state(){return state==null?null:state.clone();}
    }
    public sealed interface Reply permits Frame,State,Loaded,Closed,Failure {long id();}
    public record Frame(long id,long frame,double fps,double sampleRate,boolean hasVideo,boolean freshVideo,int width,int height,float displayAspect,int rotation,int[] abgr,short[] pcm48k)implements Reply{
        public Frame{identity(id);new Hello(fps,sampleRate,4);if(frame<1)throw new IllegalArgumentException("Invalid step counter");frameBounds(hasVideo,freshVideo,width,height,displayAspect,rotation,pcm48k==null?-1:pcm48k.length);
            if(abgr==null||abgr.length!=(long)width*height)throw new IllegalArgumentException("Invalid picture length");abgr=abgr.clone();pcm48k=pcm48k.clone();}
        @Override public int[] abgr(){return abgr.clone();}@Override public short[] pcm48k(){return pcm48k.clone();}
    }
    public record State(long id,long frame,byte[] state)implements Reply{public State{identity(id);if(frame<0)throw new IllegalArgumentException("Invalid state counter");state=copyState(state);}@Override public byte[] state(){return state.clone();}}
    public record Loaded(long id,long frame)implements Reply{public Loaded{identity(id);if(frame<0)throw new IllegalArgumentException("Invalid restored counter");}}
    public record Closed(long id)implements Reply{public Closed{identity(id);}}
    public record Failure(long id,String message)implements Reply{public Failure{identity(id);if(message==null||message.getBytes(StandardCharsets.UTF_8).length>MAX_ERROR)throw new IllegalArgumentException("Error length");}}
    private static void identity(long id){if(id<1)throw new IllegalArgumentException("Invalid request ID");}
    private static byte[] copyState(byte[] state){if(state==null||state.length<1||state.length>MAX_STATE)throw new IllegalArgumentException("State outside 1..16 MiB");return state.clone();}
    public static void frameBounds(boolean has,boolean fresh,int w,int h,float aspect,int rotation,int pcm){
        if(rotation<0||rotation>3||pcm<0||pcm>MAX_PCM||(pcm&1)!=0)throw new IllegalArgumentException("Invalid frame header");
        if(has){if(w<1||h<1||w>MAX_DIM||h>MAX_DIM||(long)w*h>MAX_PIXELS||!Float.isFinite(aspect)||aspect<.1f||aspect>10)throw new IllegalArgumentException("Invalid video header");}
        else if(fresh||w!=0||h!=0||Float.floatToIntBits(aspect)!=0)throw new IllegalArgumentException("Empty video must be explicit");
    }
    public static Request step(long id,int p1,int p2,int p3,int p4){return new Request(STEP,id,0,p1,p2,p3,p4,null);}
    public static Request save(long id){return new Request(SAVE,id,0,0,0,0,0,null);}
    public static Request load(long id,long frame,byte[] state){return new Request(LOAD,id,frame,0,0,0,0,state);}
    public static Request close(long id){return new Request(CLOSE,id,0,0,0,0,0,null);}
    public static void writeHello(DataOutput out,Hello value)throws IOException{out.writeInt(MAGIC);out.writeInt(VERSION);out.writeDouble(value.fps);out.writeDouble(value.sampleRate);out.writeInt(value.maxPorts);}
    public static Hello readHello(DataInput in)throws IOException{if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Native step protocol mismatch");try{return new Hello(in.readDouble(),in.readDouble(),in.readInt());}catch(IllegalArgumentException bad){throw new IOException(bad);}}
    public static void writeRequest(DataOutput out,Request r)throws IOException{
        out.writeInt(r.kind);out.writeLong(r.id);switch(r.kind){case STEP->{out.writeInt(r.p1);out.writeInt(r.p2);out.writeInt(r.p3);out.writeInt(r.p4);}case LOAD->{out.writeLong(r.frame);out.writeInt(r.state.length);out.write(r.state);}default->{}}
    }
    public static void writeCommand(DataOutput out,Request r)throws IOException{writeRequest(out,r);}
    public static Request readRequest(DataInput in,long expectedId)throws IOException{
        int kind=in.readInt();long id=in.readLong();if(id!=expectedId||id<1)throw new IOException("Native command sequence mismatch");
        try{return switch(kind){case STEP->step(id,in.readInt(),in.readInt(),in.readInt(),in.readInt());case SAVE->save(id);case LOAD->{long frame=in.readLong();yield load(id,frame,readState(in));}case CLOSE->close(id);default->throw new IOException("Unknown native step command");};}catch(IllegalArgumentException bad){throw new IOException(bad);}
    }
    private static byte[] readState(DataInput in)throws IOException{int length=in.readInt();if(length<1||length>MAX_STATE)throw new IOException("Native state length outside bound");byte[] bytes=new byte[length];in.readFully(bytes);return bytes;}
    public static void writeReply(DataOutput out,Reply r)throws IOException{
        int kind=r instanceof Frame?FRAME:r instanceof State?STATE:r instanceof Loaded?LOADED:r instanceof Closed?CLOSED:ERROR;
        out.writeInt(kind);out.writeLong(r.id());
        if(r instanceof Frame f){out.writeLong(f.frame);out.writeDouble(f.fps);out.writeDouble(f.sampleRate);out.writeBoolean(f.hasVideo);out.writeBoolean(f.freshVideo);out.writeInt(f.width);out.writeInt(f.height);out.writeFloat(f.displayAspect);out.writeInt(f.rotation);out.writeInt(f.pcm48k.length);for(int p:f.abgr)out.writeInt(p);for(short p:f.pcm48k)out.writeShort(p);}
        else if(r instanceof State s){out.writeLong(s.frame);out.writeInt(s.state.length);out.write(s.state);}
        else if(r instanceof Loaded l)out.writeLong(l.frame);
        else if(r instanceof Failure f){byte[] bytes=f.message.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    }
    public static Reply readReply(DataInput in,long expectedId)throws IOException{
        int kind=in.readInt();long id=in.readLong();if(id!=expectedId||id<1)throw new IOException("Native reply sequence mismatch");
        try{return switch(kind){
            case FRAME->{long frame=in.readLong();double fps=in.readDouble(),rate=in.readDouble();new Hello(fps,rate,4);boolean has=in.readBoolean(),fresh=in.readBoolean();int w=in.readInt(),h=in.readInt();float aspect=in.readFloat();int rotation=in.readInt(),samples=in.readInt();frameBounds(has,fresh,w,h,aspect,rotation,samples);
                int[] pixels=new int[w*h];short[] pcm=new short[samples];for(int i=0;i<pixels.length;i++)pixels[i]=in.readInt();for(int i=0;i<pcm.length;i++)pcm[i]=in.readShort();yield new Frame(id,frame,fps,rate,has,fresh,w,h,aspect,rotation,pixels,pcm);}
            case STATE->{long frame=in.readLong();yield new State(id,frame,readState(in));}
            case LOADED->new Loaded(id,in.readLong());case CLOSED->new Closed(id);
            case ERROR->{int n=in.readInt();if(n<0||n>MAX_ERROR)throw new IOException("Error record length");byte[] bytes=new byte[n];in.readFully(bytes);yield new Failure(id,new String(bytes,StandardCharsets.UTF_8));}
            default->throw new IOException("Unknown native reply");};}catch(IllegalArgumentException bad){throw new IOException(bad);}
    }
}
