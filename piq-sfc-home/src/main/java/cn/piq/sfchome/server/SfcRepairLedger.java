package cn.piq.sfchome.server;

import java.security.MessageDigest;
import java.util.*;

/** Bounded input history and one isolated repair. The execution host's timeline is never paused. */
public final class SfcRepairLedger {
    public static final int INTERVAL=600,HISTORY=8192,CHUNK=30*1024,MAX_STATE=16*1024*1024,BATCH=256;
    public static final int MAX_ACTIVATION_LAG=120;
    public enum Phase { UPLOAD, SEND, RESTORED, REPLAY, DONE }
    public record Mismatch(UUID player,int frame,String expected){}
    public record Replay(int first,int[] p1,int[] p2){}
    private record Digest(int frame,String sha){}
    private final int[] a=new int[HISTORY],b=new int[HISTORY];
    private int next;
    private final LinkedHashMap<Integer,String> host=new LinkedHashMap<>();
    private final Map<UUID,Digest> peers=new HashMap<>();
    private final Map<UUID,Integer> last=new HashMap<>();
    private long nextRepair;
    private Repair active;
    public void append(int first,int[] p1,int[] p2){
        if(first!=next||p1.length!=p2.length||p1.length<1||p1.length>4)throw new IllegalArgumentException("Noncontiguous authoritative history");
        for(int i=0;i<p1.length;i++)if(p1[i]<0||p1[i]>4095||p2[i]<0||p2[i]>4095)throw new IllegalArgumentException("Input mask");
        for(int i=0;i<p1.length;i++){a[next%HISTORY]=p1[i];b[next%HISTORY]=p2[i];next++;}
    }
    public List<Mismatch> report(UUID player,boolean isHost,int frame,String sha){
        if(player==null||frame<=0||frame%INTERVAL!=0||frame>next||frame<Math.max(0,next-HISTORY)||!hash(sha)||frame<=last.getOrDefault(player,-1))return List.of();
        if(!last.containsKey(player)&&last.size()>=3)return List.of();last.put(player,frame);
        if(isHost){host.put(frame,sha);while(host.size()>4)host.remove(host.keySet().iterator().next());}
        else{if(!peers.containsKey(player)&&peers.size()>=2)return List.of();peers.put(player,new Digest(frame,sha));}
        var out=new ArrayList<Mismatch>();
        for(var e:peers.entrySet()){String expected=host.get(e.getValue().frame);if(expected!=null&&!expected.equals(e.getValue().sha))out.add(new Mismatch(e.getKey(),e.getValue().frame,expected));}
        return out;
    }
    public Repair start(Mismatch mismatch,UUID lease,long tick){
        if(active!=null||tick<nextRepair||lease==null||mismatch==null||!Objects.equals(host.get(mismatch.frame),mismatch.expected))return null;
        active=new Repair(this,UUID.randomUUID(),mismatch.player,lease,mismatch.frame,mismatch.expected,tick);peers.remove(mismatch.player);nextRepair=tick+1200;return active;
    }
    public Repair active(){return active;}
    public Mismatch latest(UUID player){if(player==null||host.isEmpty())return null;int frame=host.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();return new Mismatch(player,frame,host.get(frame));}
    public boolean isolated(UUID player){return active!=null&&active.player.equals(player);}
    public void forget(UUID player){peers.remove(player);last.remove(player);if(isolated(player))cancel();}
    public void cancel(){if(active!=null)active.bytes=null;active=null;}
    public Replay replay(int first){
        if(first<Math.max(0,next-HISTORY)||first>next)throw new IllegalStateException("SFC repair history expired");
        int count=Math.min(BATCH,next-first);int[] p1=new int[count],p2=new int[count];for(int i=0;i<count;i++){p1[i]=a[(first+i)%HISTORY];p2[i]=b[(first+i)%HISTORY];}return new Replay(first,p1,p2);
    }
    public int next(){return next;}
    /** Read-only observer comparison; does not enrol observers in controller repair slots. */
    public String hostDigest(int frame){return host.get(frame);}
    public static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    public static final class Repair {
        public final UUID token,player,lease;public final int frame;public final String sha;public final long deadline;
        public Phase phase=Phase.UPLOAD;public int received,sent,replay,resume=-1;private int total;private byte[] bytes;
        private final MessageDigest digest;
        private final SfcRepairLedger ledger;
        private long uploadTick=-1;private int packets;
        private Repair(SfcRepairLedger ledger,UUID token,UUID player,UUID lease,int frame,String sha,long tick){this.ledger=ledger;this.token=token;this.player=player;this.lease=lease;this.frame=frame;this.sha=sha;deadline=tick+600;replay=frame;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception impossible){throw new AssertionError(impossible);}}
        public boolean live(long tick){return tick>=0&&tick<deadline;}
        public boolean append(UUID token,int frame,int total,int offset,String hash,byte[] part,long tick){
            if(!live(tick)||phase!=Phase.UPLOAD||!this.token.equals(token)||frame!=this.frame||!sha.equals(hash)||total<1||total>MAX_STATE||offset!=received||part==null||part.length<1||part.length>CHUNK||(long)offset+part.length>total)return false;
            if(uploadTick!=tick){uploadTick=tick;packets=0;}if(++packets>8)return false;
            if(bytes==null){if(offset!=0)return false;this.total=total;bytes=new byte[total];}if(this.total!=total)return false;
            System.arraycopy(part,0,bytes,offset,part.length);digest.update(part);received+=part.length;
            if(received==total){if(!HexFormat.of().formatHex(digest.digest()).equals(sha))return false;phase=Phase.SEND;}return true;
        }
        public int total(){return total;}
        public byte[] peekPart(){if(phase!=Phase.SEND||bytes==null)return null;return Arrays.copyOfRange(bytes,sent,Math.min(total,sent+CHUNK));}
        public void sentPart(int size){if(phase!=Phase.SEND||size!=Math.min(CHUNK,total-sent))throw new IllegalStateException("Repair part commitment");sent+=size;if(sent==total){bytes=null;phase=Phase.RESTORED;}}
        public byte[] part(){byte[] part=peekPart();if(part!=null)sentPart(part.length);return part;}
        public boolean restored(UUID token,int frame,String hash,boolean success){if(phase!=Phase.RESTORED||!this.token.equals(token)||this.frame!=frame||!sha.equals(hash)||!success)return false;phase=Phase.REPLAY;return true;}
        public boolean done(UUID token,int frame,boolean success){long lag=(long)ledger.next-frame;return phase==Phase.DONE&&this.token.equals(token)&&resume>=0&&frame>=resume&&success&&lag>=0&&lag<=MAX_ACTIVATION_LAG;}
    }
}
