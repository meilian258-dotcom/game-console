package cn.piq.fcarcade.home;

import java.util.*;

/** Persistent identities only; world/permission validation is performed by the service. */
public final class ZapperStandLinks {
    public static final int MAX_LINKS=128;
    public record End(String dimension,int x,int y,int z,UUID id) {
        public End {if(dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||dimension.length()>160||id==null||id.equals(new UUID(0,0))
                ||Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||y< -2048||y>2047)throw new IllegalArgumentException("Stand endpoint");}
    }
    public record Link(UUID id,End stand,End console) {
        public Link {if(id==null||id.equals(new UUID(0,0))||!compatible(stand,console))throw new IllegalArgumentException("Stand link");}
    }
    private final LinkedHashMap<UUID,Link> links=new LinkedHashMap<>();
    public static boolean compatible(End a,End b){if(a==null||b==null||!a.dimension.equals(b.dimension)||a.id.equals(b.id))return false;
        long dx=(long)a.x-b.x,dy=(long)a.y-b.y,dz=(long)a.z-b.z;return dx*dx+dy*dy+dz*dz<=256&&(dx!=0||dy!=0||dz!=0);}
    private static boolean sameSite(End a,End b){return a.id.equals(b.id)||a.dimension.equals(b.dimension)&&a.x==b.x&&a.y==b.y&&a.z==b.z;}
    public boolean restore(Link link){if(link==null||links.size()>=MAX_LINKS||links.containsKey(link.id))return false;
        for(var old:links.values())for(var a:List.of(old.stand,old.console))for(var b:List.of(link.stand,link.console))if(sameSite(a,b))return false;
        links.put(link.id,link);return true;}
    public Link connect(End stand,End console){if(!compatible(stand,console))return null;var link=new Link(UUID.randomUUID(),stand,console);return restore(link)?link:null;}
    public Link at(End end){for(var link:links.values())if(link.stand.equals(end)||link.console.equals(end))return link;return null;}
    public boolean remove(Link exact){return exact!=null&&links.remove(exact.id,exact);}
    public List<Link> snapshot(){return List.copyOf(links.values());}
}
