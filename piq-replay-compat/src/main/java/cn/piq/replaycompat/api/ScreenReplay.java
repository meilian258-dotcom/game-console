package cn.piq.replaycompat.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Optional presentation-only API v1; this package has no Minecraft, Replay or emulator dependency.
 * Addons use compileOnly and never embed these classes. Installed compat mod owns them once.
 */
public final class ScreenReplay {
    public static final int API_VERSION=1;
    public interface Service {
        boolean recording(); boolean playing(); long recordingTime();
        void submit(ScreenFrame frame); void close(String source,long time);
        List<ScreenFrame> at(long time);
    }
    private static final Service NONE=new Service(){
        public boolean recording(){return false;} public boolean playing(){return false;}
        public long recordingTime(){return 0;} public void submit(ScreenFrame f){}
        public void close(String s,long t){} public List<ScreenFrame> at(long t){return List.of();}
    };
    private static volatile Service service=NONE;
    private static final Map<String,Publisher> publishers=new ConcurrentHashMap<>();
    private ScreenReplay(){}
    /** Called once by the compatibility transport, never by a device. */
    public static synchronized void install(Service implementation){
        Objects.requireNonNull(implementation);
        if(service!=NONE && service!=implementation)throw new IllegalStateException("Replay service already installed");
        service=implementation;
    }
    public static boolean recording(){return service.recording();}
    public static boolean playing(){return service.playing();}
    public static long recordingTime(){return service.recordingTime();}
    public static void submit(ScreenFrame frame){if(recording()&&!playing())service.submit(Objects.requireNonNull(frame));}
    public static void close(String source,long time){if(recording()&&!playing())service.close(source,time);}
    public static List<ScreenFrame> at(long time){return playing()?List.copyOf(service.at(time)):List.of();}
    /** Register a namespaced device provider during client setup. No device allow-list is required. */
    public static synchronized Publisher register(String providerId,int version){
        if(version!=API_VERSION)throw new IllegalArgumentException("Unsupported screen API version: "+version);
        if(providerId==null||!providerId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")||providerId.length()>96)
            throw new IllegalArgumentException("Invalid provider id");
        if(publishers.containsKey(providerId))throw new IllegalStateException("Duplicate screen provider: "+providerId);
        if(publishers.size()>=128)throw new IllegalStateException("Screen provider limit");
        var p=new Publisher(providerId); publishers.put(providerId,p); return p;
    }
    public static final class Publisher {
        private final String id;
        private Publisher(String id){this.id=id;}
        public String id(){return id;}
        public boolean requested(){return recording()&&!playing();}
        private String source(String local){
            if(local==null||!local.matches("[A-Za-z0-9_.-]+")||local.length()>90)throw new IllegalArgumentException("Invalid local screen id");
            return id+"/"+local;
        }
        /** Call from the visible surface's render owner; passed arrays are copied, never retained. */
        public void frame(String localId,String dimension,int width,int height,int[] abgr,List<ScreenVertex> worldQuads){
            if(requested())submit(new ScreenFrame(source(localId),dimension,recordingTime(),width,height,abgr,worldQuads));
        }
        public void close(String localId){ScreenReplay.close(source(localId),recordingTime());}
    }
}
