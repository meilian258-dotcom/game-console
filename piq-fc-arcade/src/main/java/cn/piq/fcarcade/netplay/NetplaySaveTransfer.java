// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;

/** Bounded disk/wire envelope; decompression only on IO workers, never packet or MC threads. */
public final class NetplaySaveTransfer {
    public static final int MAX_PACKED=8*1024*1024,CHUNK=16384;
    private NetplaySaveTransfer(){}
    public static byte[] pack(byte[] state)throws IOException{
        if(state==null||state.length<1||state.length>NetplaySaveState.MAX_BYTES)throw new IOException("存档长度无效");
        var out=new ByteArrayOutputStream();out.write(ByteBuffer.allocate(4).putInt(state.length).array());
        Deflater deflater=new Deflater(Deflater.BEST_SPEED);
        try{
            deflater.setInput(state);deflater.finish();byte[] buffer=new byte[8192];
            while(!deflater.finished()){
                int n=deflater.deflate(buffer);if(n<1||out.size()+n>MAX_PACKED)throw new IOException("存档压缩后超过 8 MiB，未覆盖旧档");
                out.write(buffer,0,n);
            }
        }finally{deflater.end();}
        return out.toByteArray();
    }
    public static byte[] unpack(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length<5||bytes.length>MAX_PACKED)throw new IOException("存档压缩包大小异常");
        int size=ByteBuffer.wrap(bytes).getInt();if(size<1||size>NetplaySaveState.MAX_BYTES)throw new IOException("存档展开长度异常");
        byte[] result=new byte[size];Inflater inflater=new Inflater();int offset=0;
        try{
            inflater.setInput(bytes,4,bytes.length-4);
            while(offset<size&&!inflater.finished()){
                int n=inflater.inflate(result,offset,size-offset);if(n==0)throw new IOException("截断的 Netplay 存档");offset+=n;
            }
            if(offset!=size||!inflater.finished()||inflater.getRemaining()!=0)throw new IOException("Netplay 存档展开长度不符");
        }catch(DataFormatException bad){throw new IOException("Netplay 存档压缩数据损坏",bad);}
        finally{inflater.end();}
        return result;
    }
    /** One exact transaction. Never accept a duplicate, stale offset or allocation without an offer. */
    public static final class Assembly {
        private final byte[] bytes;private int offset;
        public Assembly(int length){if(length<1||length>MAX_PACKED)throw new IllegalArgumentException("存档传输大小异常");bytes=new byte[length];}
        public int offset(){return offset;}
        public boolean complete(){return offset==bytes.length;}
        public void append(int position,byte[] chunk){
            if(position!=offset||chunk==null||chunk.length<1||chunk.length>CHUNK||chunk.length>bytes.length-offset)throw new IllegalArgumentException("存档片段过期或不连续");
            System.arraycopy(chunk,0,bytes,offset,chunk.length);offset+=chunk.length;
        }
        public byte[] finish(){if(!complete())throw new IllegalStateException("存档尚未传完");return bytes.clone();}
    }
}
