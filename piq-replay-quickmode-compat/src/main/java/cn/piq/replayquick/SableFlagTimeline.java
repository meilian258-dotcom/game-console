package cn.piq.replayquick;

import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.IOException;
import java.util.*;

/** Immutable original-recording state, addressed by target milliseconds, never live entity state. */
public final class SableFlagTimeline {
    public enum Flag { UNKNOWN, FALSE, TRUE }
    public static final int MAX_ENTITIES = 16_384, MAX_CHANGES = 262_144, MAX_WORLD_RESETS = 8_192;
    public static final int MAX_TIME = 86_400_000;
    private record Point(int time, long order, Flag flag, boolean player) {}
    private final Map<Integer, List<Point>> entities;
    private final List<Point> worlds;
    private final int changes;
    private SableFlagTimeline(Builder builder) {
        var copy = new HashMap<Integer, List<Point>>();
        builder.entities.forEach((id, points) -> copy.put(id, List.copyOf(points)));
        entities = Map.copyOf(copy); worlds = List.copyOf(builder.worlds); changes = builder.changes;
    }
    /** Background-only: checks bounded wire framing, then reads the archive through ReplayFile. */
    public static SableFlagTimeline read(ReplayFile file, PacketTypeRegistry registry) throws IOException {
        return RecordedSableIndex.read(file, registry);
    }
    /** Last raw packet at a given millisecond wins. Missing, despawned and wrong-world state is UNKNOWN. */
    public Flag flagAt(int entityId, int targetTime) {
        Point point=pointAt(entityId,targetTime);return point==null?Flag.UNKNOWN:point.flag;
    }
    public boolean isPlayerAt(int entityId,int targetTime){Point point=pointAt(entityId,targetTime);return point!=null&&point.player;}
    private Point pointAt(int entityId,int targetTime){
        if(targetTime<0||targetTime>MAX_TIME)return null;
        Point point=floor(entities.get(entityId),targetTime),reset=floor(worlds,targetTime);
        return point==null||reset!=null&&point.order<=reset.order?null:point;
    }
    public int entityCount() { return entities.size(); }
    public int changeCount() { return changes; }
    private static Point floor(List<Point> points, int time) {
        if (points == null) return null;
        int low = 0, high = points.size();
        while (low < high) { int middle = (low + high) >>> 1; if (points.get(middle).time <= time) low = middle + 1; else high = middle; }
        return low == 0 ? null : points.get(low - 1);
    }
    /** Build in raw stream order. No coordinates, UUIDs or original packet buffers are retained. */
    public static final class Builder {
        private final int maxEntities, maxChanges, maxWorlds;
        private final Map<Integer, List<Point>> entities = new HashMap<>();
        private final Map<Integer,Boolean> alive = new HashMap<>();
        private final List<Point> worlds = new ArrayList<>();
        private int changes, lastTime = -1;
        private long order, resetOrder = -1;
        public Builder() { this(MAX_ENTITIES, MAX_CHANGES, MAX_WORLD_RESETS); }
        Builder(int maxEntities, int maxChanges, int maxWorlds) {
            if (maxEntities < 1 || maxChanges < 1 || maxWorlds < 1) throw new IllegalArgumentException("index limits");
            this.maxEntities=maxEntities; this.maxChanges=maxChanges; this.maxWorlds=maxWorlds;
        }
        private void advance(int time) throws IOException {
            if (time < lastTime || time < 0 || time > MAX_TIME) throw new IOException("Invalid original state time");
            lastTime=time; order++;
        }
        public void worldReset(int time) throws IOException {
            advance(time); alive.clear(); resetOrder=order;
            var reset=new Point(time,order,Flag.UNKNOWN,false);
            if (!worlds.isEmpty() && worlds.getLast().time==time) worlds.set(worlds.size()-1,reset);
            else { if(worlds.size()>=maxWorlds)throw new IOException("Sable index world limit"); worlds.add(reset); }
        }
        public void spawn(int time,int id) throws IOException {
            spawn(time,id,false);
        }
        public void spawn(int time,int id,boolean player)throws IOException {
            advance(time);put(time,id,Flag.UNKNOWN,player);alive.put(id,player);
        }
        public void despawn(int time,int id) throws IOException {
            advance(time); if(entities.containsKey(id))put(time,id,Flag.UNKNOWN,false); alive.remove(id);
        }
        public void observe(int time,int id,Flag flag) throws IOException {
            Objects.requireNonNull(flag); advance(time);
            if(alive.containsKey(id))put(time,id,flag,alive.get(id)); // No spawn provenance means no valid state to infer.
        }
        private void put(int time,int id,Flag flag,boolean player) throws IOException {
            if(id<0)throw new IOException("Negative recorded entity ID");
            var points=entities.get(id);
            if(points==null){if(entities.size()>=maxEntities)throw new IOException("Sable index entity limit"); points=new ArrayList<>(); entities.put(id,points);}
            Point last=points.isEmpty()?null:points.getLast();
            if(last!=null && last.flag==flag && last.player==player && last.order>resetOrder)return;
            Point point=new Point(time,order,flag,player);
            if(last!=null && last.time==time)points.set(points.size()-1,point);
            else {if(changes>=maxChanges)throw new IOException("Sable index state limit");points.add(point);changes++;}
        }
        public SableFlagTimeline build(){return new SableFlagTimeline(this);}
    }
}
