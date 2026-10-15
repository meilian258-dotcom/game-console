package cn.piq.replaycompat.io;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenVertex;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;

/** Versioned, bounded, self-contained chunks. Untrusted tracks never name paths or executable code. */
public final class TrackCodec {
    public static final int MAX_CHUNK_FRAMES=8, MAX_CHUNK_BYTES=40*1024*1024;
    private static final int MAGIC=0x50525131;
    private TrackCodec(){}
    public static void write(OutputStream output,List<ScreenFrame> frames)throws IOException{
        if(frames.size()>MAX_CHUNK_FRAMES)throw new IOException("Too many chunk frames");
        var out=new DataOutputStream(output);out.writeInt(MAGIC);out.writeInt(frames.size());
        byte[] pixelBytes=new byte[16*1024];var pixelInts=ByteBuffer.wrap(pixelBytes).asIntBuffer();
        for(var f:frames){
            out.writeUTF(f.source());out.writeUTF(f.dimension());out.writeLong(f.timestampMillis());
            out.writeInt(f.width());out.writeInt(f.height());out.writeInt(f.vertices().size());
            for(var v:f.vertices()){
                out.writeDouble(v.x());out.writeDouble(v.y());out.writeDouble(v.z());
                out.writeFloat(v.u());out.writeFloat(v.v());out.writeInt(v.color());
            }
            var bytes=new ByteArrayOutputStream();var deflater=new Deflater(Deflater.BEST_SPEED);
            try(var compressed=new DataOutputStream(new DeflaterOutputStream(bytes,deflater))){
                int[] pixels=f.abgr();
                for(int offset=0;offset<pixels.length;){
                    int length=Math.min(pixelInts.capacity(),pixels.length-offset);
                    pixelInts.position(0);pixelInts.put(pixels,offset,length);
                    compressed.write(pixelBytes,0,length*Integer.BYTES);offset+=length;
                }
            }finally{deflater.end();}
            out.writeInt(bytes.size());bytes.writeTo(out);
        }
        out.flush();
    }
    public static List<ScreenFrame> read(InputStream input)throws IOException{
        var in=new DataInputStream(new Limited(input,MAX_CHUNK_BYTES));
        if(in.readInt()!=MAGIC)throw new IOException("Unknown screen track version");
        int count=in.readInt();if(count<0||count>MAX_CHUNK_FRAMES)throw new IOException("Invalid chunk count");
        var frames=new ArrayList<ScreenFrame>(count);
        // Inflate in bounded bulk reads, not four InflaterInputStream calls per pixel.
        byte[] pixelBytes=new byte[16*1024];
        var pixelInts=ByteBuffer.wrap(pixelBytes).asIntBuffer(); // v1 pixels are big endian.
        for(int n=0;n<count;n++){
            String source=in.readUTF(),dimension=in.readUTF();long time=in.readLong();
            int w=in.readInt(),h=in.readInt(),nv=in.readInt();
            if(w<0||h<0||w>1024||h>1024||(long)w*h>ScreenFrame.MAX_PIXELS
                    ||nv<0||nv>ScreenFrame.MAX_VERTICES||nv%4!=0)throw new IOException("Invalid frame dimensions");
            var vertices=new ArrayList<ScreenVertex>(nv);
            try{
                for(int v=0;v<nv;v++)vertices.add(new ScreenVertex(in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat(),in.readInt()));
                int size=in.readInt();if(size<0||size>ScreenFrame.MAX_PIXELS*4+8192)throw new IOException("Invalid compressed length");
                byte[] bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException("Truncated screen pixels");
                int[] pixels=new int[w*h];var inflater=new Inflater();var raw=new ByteArrayInputStream(bytes);
                try(var z=new DataInputStream(new InflaterInputStream(raw,inflater))){
                    for(int offset=0;offset<pixels.length;){
                        int length=Math.min(pixelInts.capacity(),pixels.length-offset);
                        z.readFully(pixelBytes,0,length*Integer.BYTES);
                        pixelInts.position(0);pixelInts.get(pixels,offset,length);offset+=length;
                    }
                    if(z.read()!=-1||!inflater.finished()||inflater.getRemaining()!=0||raw.available()!=0)throw new IOException("Excess pixel data");
                }finally{inflater.end();}
                frames.add(new ScreenFrame(source,dimension,time,w,h,pixels,vertices));
            }catch(IllegalArgumentException invalid){throw new IOException("Invalid screen frame",invalid);}
        }
        if(in.read()!=-1)throw new IOException("Trailing chunk data");
        return List.copyOf(frames);
    }
    private static final class Limited extends FilterInputStream {
        private int remaining;
        Limited(InputStream in,int limit){super(in);remaining=limit;}
        @Override public int read()throws IOException{
            if(remaining<=0)throw new IOException("Screen chunk limit");int b=super.read();if(b>=0)remaining--;return b;
        }
        @Override public int read(byte[] b,int o,int len)throws IOException{
            if(len==0)return 0;if(remaining<=0)throw new IOException("Screen chunk limit");
            int n=super.read(b,o,Math.min(len,remaining));if(n>0)remaining-=n;return n;
        }
    }
}
