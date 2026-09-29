package cn.piq.fcarcade.cabinet;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Exact ordered chunks. A stale/duplicate chunk never changes the current assembly. */
public final class CabinetSyncState {
    public static final int CHUNK=24576;
    private final UUID token;
    private final byte[] bytes;
    private final String hash;
    private int offset;
    public CabinetSyncState(UUID token,int length,String hash){
        if(token==null||length<1||length>CabinetSyncCore.MAX_STATE_BYTES||!validHash(hash))throw new IllegalArgumentException("Invalid state offer");
        this.token=token;this.bytes=new byte[length];this.hash=hash;
    }
    public boolean append(UUID id,int position,byte[] data){
        if(!token.equals(id)||position!=offset||data==null||data.length<1||data.length>CHUNK||data.length>bytes.length-offset)return false;
        System.arraycopy(data,0,bytes,offset,data.length);offset+=data.length;return true;
    }
    public boolean complete(){return offset==bytes.length;}
    public byte[] finish(){if(!complete()||!hash(bytes).equals(hash))throw new IllegalArgumentException("Snapshot hash mismatch");return bytes;}
    /** Ownership transfer to a verifier/worker. Does not claim that the hash is verified. */
    public byte[] bytesAfterAssembly(){if(!complete())throw new IllegalStateException("Incomplete snapshot");return bytes;}
    public int length(){return bytes.length;}
    public static boolean validHash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    public static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
