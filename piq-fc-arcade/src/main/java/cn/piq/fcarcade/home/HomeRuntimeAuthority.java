package cn.piq.fcarcade.home;
import java.util.*;
/** Computing ownership is independent from either physical controller socket. */
public final class HomeRuntimeAuthority<C> {
    public record Control<C>(UUID player,C connection,UUID lease,int port) {}
    private final UUID host; private final C connection; private final boolean gunMode;
    private final Map<Integer,Control<C>> ports=new HashMap<>();
    private UUID buttonInputLease, buttonGunLease;
    private long revision=1; private boolean closed;
    public HomeRuntimeAuthority(UUID host,C connection){this(host,connection,false);}
    public HomeRuntimeAuthority(UUID host,C connection,boolean gunMode){this.host=Objects.requireNonNull(host);this.connection=Objects.requireNonNull(connection);this.gunMode=gunMode;}
    public boolean gunMode(){return gunMode;}
    public static boolean controllerInRange(double distanceSquared){return Double.isFinite(distanceSquared)&&distanceSquared>=0&&distanceSquared<=36;}
    public UUID host(){return host;}
    public boolean host(UUID player,C source){return !closed&&host.equals(player)&&connection==source;}
    public long revision(){return revision;}
    public Control<C> port(int port){return ports.get(port);}
    /** P1 is the primary role when one person owns both a controller and a gun. */
    public Control<C> player(UUID player){for(int port=0;port<2;port++){var c=ports.get(port);if(c!=null&&c.player().equals(player))return c;}return null;}
    public List<Control<C>> controls(UUID player){var result=new ArrayList<Control<C>>(2);for(int port=0;port<2;port++){var c=ports.get(port);if(c!=null&&c.player().equals(player))result.add(c);}return List.copyOf(result);}
    public boolean take(UUID player,C source,UUID lease,int port){
        if(gunMode&&port!=0)return false;
        return takeSocket(player,source,lease,port);
    }
    public boolean takeGun(UUID player,C source,UUID lease){return gunMode&&takeSocket(player,source,lease,1);}
    private boolean takeSocket(UUID player,C source,UUID lease,int port){
        if(closed||player==null||source==null||lease==null||port<0||port>1||ports.containsKey(port))return false;
        var existing=player(player);if(existing!=null&&(!gunMode||existing.connection()!=source||existing.lease().equals(lease)))return false;
        ports.put(port,new Control<>(player,source,lease,port));revision++;return true;
    }
    public boolean authorized(UUID player,C source,UUID lease,int port){var c=ports.get(port);return !closed&&c!=null&&c.player().equals(player)&&c.connection()==source&&c.lease().equals(lease);}
    /** A real P1 lease wins. A gun may feed P1 only while that physical socket is unoccupied. */
    public int buttonPort(UUID player,C source,UUID lease){
        if(authorized(player,source,lease,0))return 0;
        if(gunMode&&ports.get(0)==null&&authorized(player,source,lease,1))return 0;
        return !gunMode&&authorized(player,source,lease,1)?1:-1;
    }
    /** Called only after the authoritative input FIFO accepted this sequence. */
    public boolean recordButtons(UUID player,C source,UUID inputLease,UUID gunLease){
        if(buttonPort(player,source,inputLease)!=0)return false;
        if(gunLease!=null&&(!gunMode||!authorized(player,source,gunLease,1)))return false;
        if(gunMode&&authorized(player,source,inputLease,1)&&!inputLease.equals(gunLease))return false;
        buttonInputLease=inputLease;buttonGunLease=gunLease;return true;
    }
    public boolean clearGunButtons(UUID gunLease){
        if(gunLease==null||!gunLease.equals(buttonGunLease))return false;
        var p1=ports.get(0);boolean same=p1==null||p1.lease().equals(buttonInputLease);
        clearButtonSource();return same;
    }
    public void clearButtonSource(){buttonInputLease=null;buttonGunLease=null;}
    public Control<C> release(UUID player,C source){var c=player(player);if(c==null||c.connection()!=source)return null;ports.remove(c.port());revision++;return c;}
    public Control<C> release(UUID player,C source,UUID lease,int port){if(!authorized(player,source,lease,port))return null;var c=ports.remove(port);revision++;return c;}
    public void reset(){if(!closed){clearButtonSource();revision++;}}
    public void close(){closed=true;ports.clear();clearButtonSource();revision++;}
    public boolean running(){return !closed;}
}
