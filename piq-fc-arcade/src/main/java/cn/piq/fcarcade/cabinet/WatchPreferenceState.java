package cn.piq.fcarcade.cabinet;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Bounded, connection-local negative preferences. Never grants a watch, seat or room. */
public final class WatchPreferenceState {
    public static final int LIMIT=64;
    public record Source(ResourceLocation provider,UUID source,UUID hostLease) {
        public Source {
            Objects.requireNonNull(provider);Objects.requireNonNull(source);Objects.requireNonNull(hostLease);
            if(provider.toString().length()>128)throw new IllegalArgumentException("watch provider");
        }
        public static Source of(WatchDescriptor d){return new Source(d.provider(),d.source(),d.hostLease());}
    }
    public enum Result { APPLIED, STALE, UNAUTHORIZED, FULL }
    private Object connection;
    private long sequence;
    private final Set<Source> paused=new LinkedHashSet<>();
    public boolean connection(Object value){
        if(connection==value)return false;
        connection=value;sequence=0;paused.clear();return true;
    }
    /** Authorization is an existing exact watch relationship, checked by the server caller. */
    public Result change(Object origin,long next,Source source,boolean pause,boolean authorized){
        Objects.requireNonNull(source);
        if(origin==null||origin!=connection||next<=sequence)return Result.STALE;
        sequence=next;
        if(pause){
            if(!paused.contains(source)&&!authorized)return Result.UNAUTHORIZED;
            if(!paused.contains(source)&&paused.size()>=LIMIT)return Result.FULL;
            paused.add(source);
        }else paused.remove(source); // Removing one's negative preference creates no authority.
        return Result.APPLIED;
    }
    public boolean paused(Source source){return paused.contains(source);}
    public Set<Source> paused(){return Set.copyOf(paused);}
    public long sequence(){return sequence;}
}
