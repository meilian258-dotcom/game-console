// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exact-artifact Netplay checkpoint. Never treated as a legacy core's snapshot or a bare .sav. */
public final class NetplaySaveState {
    public static final int MAX_STATE = 16 * 1024 * 1024, MAX_RAM = 4 * 1024 * 1024, MAX_RTC = 65536;
    private static final int MAGIC = 0x504e5331, HEADER = 124;
    public static final int MAX_BYTES = HEADER + MAX_STATE + MAX_RAM + MAX_RTC;
    private NetplaySaveState() {}

    public record Identity(String profile, String content) {
        public Identity { checkHash(profile); checkHash(content); }
    }
    public record Parts(Identity identity, long frame, byte[] state, byte[] ram, byte[] rtc) {
        public Parts {
            Objects.requireNonNull(identity);
            if (frame < 0 || state == null || ram == null || rtc == null || state.length < 1
                    || state.length > MAX_STATE || ram.length > MAX_RAM || rtc.length > MAX_RTC)
                throw new IllegalArgumentException("Netplay 存档长度或帧号无效");
            state=state.clone(); ram=ram.clone(); rtc=rtc.clone();
        }
        @Override public byte[] state(){return state.clone();}
        @Override public byte[] ram(){return ram.clone();}
        @Override public byte[] rtc(){return rtc.clone();}
    }
    /** A canonical descriptor can be computed from authorized server manifests without reading ROM bodies. */
    public static Identity identity(NetplayProfile profile, String romHash, Map<String,String> auxiliaries) {
        checkHash(romHash);
        if(auxiliaries.size()>4)throw new IllegalArgumentException("Netplay 辅助文件数量无效");
        var descriptor=new StringBuilder("PIQ-Netplay-save-v1\n").append(profile.sha().toLowerCase(Locale.ROOT))
                .append('\n').append(profile.contentName()).append('\n').append(profile.device()).append(':')
                .append(profile.ports()).append(':').append(profile.sampleRate()).append('\n').append(profile.config());
        var content=new StringBuilder(romHash.toLowerCase(Locale.ROOT)).append('\n');
        new TreeMap<>(auxiliaries).forEach((name,sha)->{
            if(!NetplayProfile.safeName(name)||name.equals(profile.contentName()))throw new IllegalArgumentException("Netplay 辅助文件名无效");
            checkHash(sha);content.append(name).append('=').append(sha.toLowerCase(Locale.ROOT)).append('\n');
        });
        return new Identity(hash(descriptor.toString().getBytes(StandardCharsets.UTF_8)),hash(content.toString().getBytes(StandardCharsets.UTF_8)));
    }
    public static String hash(byte[] bytes){return HexFormat.of().formatHex(digest(bytes,bytes.length));}
    private static void checkHash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Netplay 存档身份无效");}
    public static byte[] encode(Parts p) {
        ByteBuffer b=ByteBuffer.allocate(HEADER+p.state.length+p.ram.length+p.rtc.length);
        b.putInt(MAGIC).putInt(1).put(HexFormat.of().parseHex(p.identity.profile)).put(HexFormat.of().parseHex(p.identity.content))
                .putLong(p.frame).putInt(p.state.length).putInt(p.ram.length).putInt(p.rtc.length)
                .put(p.state).put(p.ram).put(p.rtc);
        b.put(digest(b.array(),b.position()));return b.array();
    }
    public static Parts decode(byte[] bytes, Identity expected) {
        if(bytes==null||bytes.length<=HEADER||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Netplay 存档大小无效");
        ByteBuffer b=ByteBuffer.wrap(bytes);
        if(b.getInt()!=MAGIC||b.getInt()!=1)throw new IllegalArgumentException("Netplay 存档格式不兼容");
        byte[] profile=new byte[32],content=new byte[32];b.get(profile);b.get(content);
        var identity=new Identity(HexFormat.of().formatHex(profile),HexFormat.of().formatHex(content));
        long frame=b.getLong();int state=b.getInt(),ram=b.getInt(),rtc=b.getInt();
        if(expected!=null&&!expected.equals(identity))throw new IllegalArgumentException("Netplay 存档核心、选项或游戏/BIOS 不匹配");
        if(frame<0||state<1||state>MAX_STATE||ram<0||ram>MAX_RAM||rtc<0||rtc>MAX_RTC
                ||(long)state+ram+rtc!=bytes.length-HEADER
                ||!MessageDigest.isEqual(digest(bytes,bytes.length-32),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))
            throw new IllegalArgumentException("Netplay 存档损坏，原存档未覆盖");
        byte[] s=new byte[state],r=new byte[ram],c=new byte[rtc];b.get(s);b.get(r);b.get(c);
        return new Parts(identity,frame,s,r,c);
    }
    /** Private working-directory seed; Java verifies identities and checksum before exposing it to native code. */
    public static byte[] nativeSeed(Parts p){return ByteBuffer.allocate(12+p.state.length+p.ram.length+p.rtc.length)
            .putInt(p.state.length).putInt(p.ram.length).putInt(p.rtc.length).put(p.state).put(p.ram).put(p.rtc).array();}
    private static byte[] digest(byte[] bytes,int length){
        try{var md=MessageDigest.getInstance("SHA-256");md.update(bytes,0,length);return md.digest();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
