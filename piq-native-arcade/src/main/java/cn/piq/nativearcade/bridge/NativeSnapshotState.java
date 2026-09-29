// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.nativearcade.NativeSnapshotProfile;
import java.io.*;
import java.util.*;

/** Full opaque native state plus deterministic frame/content/profile identity; no masks or normalization. */
public final class NativeSnapshotState {
    private NativeSnapshotState(){}
    private static final int MAGIC=0x504e5353,VERSION=1;
    public static final int MAX_BYTES=16*1024*1024,HEADER_BYTES=4+4+8+32*4+4;
    public record Decoded(long internalFrame,byte[] payload){
        public Decoded{payload=payload.clone();}
        @Override public byte[] payload(){return payload.clone();}
    }
    public static byte[] encode(long frame,String romHash,byte[] payload)throws IOException{
        identity(frame,romHash);if(payload==null||payload.length<1||payload.length>MAX_BYTES-HEADER_BYTES)throw new IOException("Native snapshot payload exceeds bounded envelope");
        var bytes=new ByteArrayOutputStream(HEADER_BYTES+payload.length);
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(frame);
            out.write(hex(NativeSnapshotProfile.PROFILE_SHA));out.write(hex(romHash));out.write(hex(NativeSnapshotProfile.BIOS_SHA));
            out.write(hex(NativeSnapshotProfile.hash(payload)));out.writeInt(payload.length);out.write(payload);
        }
        return bytes.toByteArray();
    }
    public static Decoded decode(byte[] bytes,String romHash)throws IOException{
        if(bytes==null||bytes.length<=HEADER_BYTES||bytes.length>MAX_BYTES)throw new IOException("Invalid bounded native snapshot envelope");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported native snapshot schema");
            long frame=in.readLong();identity(frame,romHash);
            if(!Arrays.equals(in.readNBytes(32),hex(NativeSnapshotProfile.PROFILE_SHA))||!Arrays.equals(in.readNBytes(32),hex(romHash))||!Arrays.equals(in.readNBytes(32),hex(NativeSnapshotProfile.BIOS_SHA)))throw new IOException("快照不属于当前同步核心/配置/ROM/BIOS");
            byte[] expected=in.readNBytes(32);int length=in.readInt();
            if(length<1||length!=bytes.length-HEADER_BYTES)throw new IOException("Native snapshot length mismatch");
            byte[] payload=in.readNBytes(length);if(!Arrays.equals(expected,hex(NativeSnapshotProfile.hash(payload)))||in.read()!=-1)throw new IOException("Native snapshot SHA mismatch");
            return new Decoded(frame,payload);
        }
    }
    /** Network restore's authoritative logical frame must bind the opaque native frame exactly. */
    public static Decoded decode(byte[] bytes,String romHash,long logicalFrame)throws IOException{
        if(logicalFrame<0||logicalFrame>Long.MAX_VALUE-NativeSnapshotProfile.BOOTSTRAP_FRAMES)throw new IOException("Invalid logical snapshot frame");
        var value=decode(bytes,romHash);
        if(value.internalFrame()!=logicalFrame+NativeSnapshotProfile.BOOTSTRAP_FRAMES)throw new IOException("快照内部帧与当前联机恢复帧不一致");
        return value;
    }
    private static byte[] hex(String value)throws IOException{try{return HexFormat.of().parseHex(value);}catch(IllegalArgumentException ex){throw new IOException("Invalid snapshot identity",ex);}}
    private static void identity(long frame,String romHash)throws IOException{
        if(frame<NativeSnapshotProfile.BOOTSTRAP_FRAMES||!NativeSnapshotProfile.ROMS.containsValue(romHash))throw new IOException("Snapshot frame/ROM is outside the verified profile");
    }
}
