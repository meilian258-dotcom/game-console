package cn.piq.fcarcade.client.cabinet;

import java.util.UUID;

/** Fixed seats. Only authenticated Seat messages can change the membership of a port. */
public final class CabinetPeerInputs {
    private final UUID[] members = new UUID[4];
    private final long[] sequences = {-1,-1,-1,-1};
    private final int[] masks = new int[4];
    public boolean seat(UUID member,int port,boolean joined) {
        if(member==null||port<0||port>=4)return false;
        if(joined){
            if(member.equals(members[port]))return false;
            if(members[port]!=null)return false; // Server must explicitly release the old occupant first.
            for(UUID existing:members)if(member.equals(existing))return false;
            members[port]=member;sequences[port]=-1;masks[port]=0;return true;
        }
        if(!member.equals(members[port]))return false;
        members[port]=null;sequences[port]=-1;masks[port]=0;return true;
    }
    public boolean input(UUID member,int port,long sequence,int mask) {
        if(port<0||port>=4||member==null||!member.equals(members[port])||sequence<0
                ||sequence<=sequences[port]||(mask&~4095)!=0)return false;
        sequences[port]=sequence;masks[port]=mask;return true;
    }
    public void local(UUID member,int port,int mask){
        if(port<0||port>=4||!member.equals(members[port])||(mask&~4095)!=0)throw new IllegalArgumentException("Unowned seat");
        masks[port]=mask;
    }
    public int mask(int port){return masks[port];}
    public boolean owns(UUID member,int port){return member!=null&&port>=0&&port<4&&member.equals(members[port]);}
    /** Read-only projection for one physical cabinet. Guests only know their own local buttons. */
    public int[] visualPair(int firstPort,int localPort,int localMask,boolean host){
        if(firstPort!=0&&firstPort!=2)return new int[2];
        return visualPair(firstPort,2,localPort,localMask,host);
    }
    public int[] visualPair(int firstPort,int count,int localPort,int localMask,boolean host){
        if(firstPort<0||count<1||count>2||firstPort+count>4||localPort<0||localPort>=4||(localMask&~4095)!=0)
            return new int[2];
        int[] result=new int[2];if(host)for(int i=0;i<count;i++)result[i]=masks[firstPort+i];
        if(localPort>=firstPort&&localPort<firstPort+count)result[localPort-firstPort]=localMask;
        return result;
    }
    public int guests(){int result=0;for(int i=1;i<4;i++)if(members[i]!=null)result++;return result;}
}
