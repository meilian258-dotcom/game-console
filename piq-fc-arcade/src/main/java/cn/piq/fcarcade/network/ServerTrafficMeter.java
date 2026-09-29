package cn.piq.fcarcade.network;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Server endpoint payload bytes, not TCP traffic. Fixed protocol buckets, no player/content retention. */
public final class ServerTrafficMeter {
    public enum Group { FC_HOME, SFC_HOME, CABINET, WATCH, NETPLAY }
    public static final class Session {
        private final ModTrafficCounter total;
        private final Map<Group,ModTrafficCounter> groups=new EnumMap<>(Group.class);
        public Session(LongSupplier clock) {
            total=new ModTrafficCounter(clock);
            for(var group:Group.values())groups.put(group,new ModTrafficCounter(clock));
        }
        public synchronized void add(Group group,boolean sent,int bytes) {
            add(group,TrafficCategory.OTHER,sent,bytes);
        }
        public synchronized void add(Group group,TrafficCategory category,boolean sent,int bytes) {
            if(bytes<=0)return;
            if(sent){total.upload(category,bytes);groups.get(group).upload(category,bytes);}
            else{total.download(category,bytes);groups.get(group).download(category,bytes);}
        }
        public synchronized ModTrafficCounter.Sample total(){return total.sample();}
        public synchronized ModTrafficCounter.Sample group(Group group){return groups.get(group).sample();}
        public synchronized void sample(){total.sample();groups.values().forEach(ModTrafficCounter::sample);}
    }
    private static volatile Session current;
    private ServerTrafficMeter() {}
    static void install(Session session){current=session;}
    static Session current(){return current;}
    public static void heartbeat(boolean sent,int bytes){var owner=current;if(owner!=null)owner.add(Group.WATCH,sent,bytes);}
    public static void netplay(boolean sent,int bytes){var owner=current;if(owner!=null)owner.add(Group.NETPLAY,TrafficCategory.NETPLAY,sent,bytes);}
    public static Group group(ResourceLocation id) {
        if(TrafficCategory.of(id)==TrafficCategory.NETPLAY)return Group.NETPLAY;
        if(id.getNamespace().equals("piq_sfc_home"))return Group.SFC_HOME;
        if(id.getPath().startsWith("watch_"))return Group.WATCH;
        if(id.getPath().startsWith("cabinet_"))return Group.CABINET;
        return Group.FC_HOME;
    }
    public static <T> StreamCodec<RegistryFriendlyByteBuf,T> wrap(ResourceLocation id,boolean clientbound,
            StreamCodec<? super RegistryFriendlyByteBuf,T> codec) {
        var group=group(id);var category=TrafficCategory.of(id);
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf b) {
                var owner=current;int before=b.readerIndex();T value=codec.decode(b);
                if(!clientbound&&owner!=null&&owner==current)owner.add(group,category,false,b.readerIndex()-before);
                return value;
            }
            public void encode(RegistryFriendlyByteBuf b,T value) {
                var owner=current;int before=b.writerIndex();codec.encode(b,value);
                if(clientbound&&owner!=null&&owner==current)owner.add(group,category,true,b.writerIndex()-before);
            }
        };
    }
}
