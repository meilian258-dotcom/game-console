package cn.piq.fcarcade.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Counts the instrumented FC/shared-cabinet and SFC-home outer payload codecs, before
 * Minecraft compression. Legacy SFC arcade core9's independent protocol is not instrumented.
 * No packet capture, payload retention, socket interception, or measurement of other mods.
 * Serverbound encode/clientbound decode run only at the client endpoint, including LAN hosts.
 */
public final class ModTrafficProbe {
    public interface Collector {
        void upload(int bytes);
        void download(int bytes);
        void video(UUID stream);
        default void upload(TrafficCategory category,int bytes){upload(bytes);}
        default void download(TrafficCategory category,int bytes){download(bytes);}
    }
    private record Endpoint(Object connection,Collector collector){}
    private static volatile Endpoint client;
    private ModTrafficProbe() {}
    public static void clientCollector(Collector value) { clientCollector(null,value); }
    public static void clientCollector(Object connection,Collector value) { client = value==null?null:new Endpoint(connection,value); }
    public static void upload(int bytes) { var c = client; if (c != null && bytes > 0) c.collector.upload(bytes); }
    public static void download(int bytes) { var c = client; if (c != null && bytes > 0) c.collector.download(bytes); }
    /** Bidirectional native lane has no direction in its codec. Charge its exact MC endpoint once. */
    public static void endpoint(Object connection,boolean sent,int bytes){
        var owner=client;
        if(connection==null||owner==null||owner.connection!=connection||bytes<=0)return;
        if(sent)owner.collector.upload(TrafficCategory.NETPLAY,bytes);else owner.collector.download(TrafficCategory.NETPLAY,bytes);
    }
    /** Capture the connection's collector, so a dying old worker cannot contaminate a new session. */
    public static Runnable videoMeter(UUID stream) {
        var owner = client;
        return () -> { if (owner != null && owner == client) owner.collector.video(stream); };
    }
    public static <T> StreamCodec<RegistryFriendlyByteBuf,T> toServer(StreamCodec<? super RegistryFriendlyByteBuf,T> codec) {
        return toServer(codec,TrafficCategory.OTHER);
    }
    public static <T> StreamCodec<RegistryFriendlyByteBuf,T> toServer(StreamCodec<? super RegistryFriendlyByteBuf,T> codec,TrafficCategory category) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf b) { return codec.decode(b); }
            public void encode(RegistryFriendlyByteBuf b,T value) {
                var owner=client;int before = b.writerIndex(); codec.encode(b,value);
                if(owner!=null&&owner==client)owner.collector.upload(category,b.writerIndex()-before);
            }
        };
    }
    public static <T> StreamCodec<RegistryFriendlyByteBuf,T> toClient(StreamCodec<? super RegistryFriendlyByteBuf,T> codec) {
        return toClient(codec,TrafficCategory.OTHER);
    }
    public static <T> StreamCodec<RegistryFriendlyByteBuf,T> toClient(StreamCodec<? super RegistryFriendlyByteBuf,T> codec,TrafficCategory category) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf b) {
                var owner=client;int before = b.readerIndex(); T value = codec.decode(b);
                if(owner!=null&&owner==client)owner.collector.download(category,b.readerIndex()-before);return value;
            }
            public void encode(RegistryFriendlyByteBuf b,T value) { codec.encode(b,value); }
        };
    }
}
