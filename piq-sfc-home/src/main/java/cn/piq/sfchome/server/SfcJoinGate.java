package cn.piq.sfchome.server;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** One consent-bound, finite transaction. P1's core/state is never replaced by this gate. */
public final class SfcJoinGate {
    public static final int MAX_STATE=16*1024*1024,CHUNK=30*1024;
    public enum Phase { APPROVAL, LOADING, CAPTURE, APPLYING, COMMITTED, CLOSED }
    public final UUID token,host,applicant,hostLease,console;
    private final long created,deadline;
    private long pauseDeadline=Long.MAX_VALUE;
    private Phase phase=Phase.APPROVAL;
    private int frame=-1,received,total;
    private String digest="";
    private byte[] state;
    public SfcJoinGate(UUID token,UUID host,UUID applicant,UUID hostLease,UUID console,long now){
        this.token=Objects.requireNonNull(token);this.host=Objects.requireNonNull(host);this.applicant=Objects.requireNonNull(applicant);
        this.hostLease=Objects.requireNonNull(hostLease);this.console=Objects.requireNonNull(console);
        if(host.equals(applicant)||now<0||now>Long.MAX_VALUE-2400)throw new IllegalArgumentException("Invalid invitation");created=now;deadline=now+2400;
    }
    public Phase phase(){return phase;}public int frame(){return frame;}public String digest(){return digest;}
    public boolean live(long now){return now>=created&&now<deadline&&now<pauseDeadline&&phase!=Phase.CLOSED&&phase!=Phase.COMMITTED;}
    public boolean approve(UUID actor,UUID nonce,boolean accept,long now){
        if(!host.equals(actor)||!token.equals(nonce)||phase!=Phase.APPROVAL||!live(now))return false;
        phase=accept?Phase.LOADING:Phase.CLOSED;return true;
    }
    public boolean capture(UUID actor,int frame,long now){
        if(!applicant.equals(actor)||phase!=Phase.LOADING||!live(now)||frame<0)return false;
        this.frame=frame;pauseDeadline=now+Math.min(600,deadline-now);phase=Phase.CAPTURE;return true;
    }
    public boolean append(UUID actor,UUID nonce,int frame,int total,int offset,String hash,byte[] data,long now){
        if(!host.equals(actor)||!token.equals(nonce)||phase!=Phase.CAPTURE||!live(now)||this.frame!=frame
                ||total<1||total>MAX_STATE||offset!=received||data==null||data.length<1||data.length>CHUNK
                ||(long)offset+data.length>total||hash==null||!hash.matches("[0-9a-f]{64}"))return false;
        if(state==null){if(offset!=0)return false;this.total=total;digest=hash;state=new byte[total];}
        if(this.total!=total||!digest.equals(hash))return false;
        System.arraycopy(data,0,state,received,data.length);received+=data.length;
        if(received==total){if(!sha(state).equals(digest)){close();return false;}phase=Phase.APPLYING;}
        return true;
    }
    /** Server owns this array; no untrusted caller receives it. */
    byte[] bytes(){return phase==Phase.APPLYING?state:null;}
    public boolean commit(UUID actor,UUID nonce,int frame,String hash,long now){
        if(!applicant.equals(actor)||!token.equals(nonce)||phase!=Phase.APPLYING||!live(now)||frame!=this.frame||!digest.equals(hash))return false;
        phase=Phase.COMMITTED;state=null;return true;
    }
    public void close(){state=null;phase=Phase.CLOSED;}
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
