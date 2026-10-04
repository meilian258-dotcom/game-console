package cn.piq.fcarcade.home.content;

import java.io.IOException;

/** Single-owner, bounded assembler for download-only content; no paths, runtime or save authority. */
public final class ContentCardDownloadBuffer {
    private final String hash;
    private final int size;
    private byte[] bytes;
    private int offset;
    public ContentCardDownloadBuffer(String hash,int size,int maximum) {
        if(hash==null||!hash.matches("[0-9a-f]{64}")||maximum<1||maximum>ContentCardStore.MAX_BYTES||size<1||size>maximum)
            throw new IllegalArgumentException("Download size/hash");
        this.hash=hash;this.size=size;bytes=new byte[size];
    }
    public int offset(){return offset;}
    public void accept(String hash,int total,int at,byte[] part) {
        if(bytes==null||!this.hash.equals(hash)||size!=total||at!=offset||part==null||part.length<1
                ||part.length>ContentCardStore.CHUNK||part.length>size-offset)
            throw new IllegalArgumentException("Download chunk identity/order/size");
        System.arraycopy(part,0,bytes,offset,part.length);offset+=part.length;
    }
    public boolean complete(){return bytes!=null&&offset==size;}
    /** Detaches only a complete buffer; caller validates off the main thread before publishing it. */
    public byte[] take() {
        if(!complete())throw new IllegalStateException("Download incomplete");
        var result=bytes;bytes=null;return result;
    }
    public static void validate(String hash,byte[] bytes,ContentCardStore.Validator validator)throws IOException {
        if(!ContentCardStore.hash(bytes).equals(hash))throw new IOException("ROM hash mismatch");
        validator.validate(bytes);
        if(!ContentCardStore.hash(bytes).equals(hash))throw new IOException("ROM changed during validation");
    }
}
