package cn.piq.fcarcade;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record ArcadeHomeReadyPayload(long sessionId,int epoch) implements CustomPacketPayload{
    public ArcadeHomeReadyPayload{if(sessionId<=0||epoch<=0)throw new IllegalArgumentException("Invalid appliance epoch");}
    public static final Type<ArcadeHomeReadyPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"home_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArcadeHomeReadyPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.sessionId());b.writeVarInt(p.epoch());},b->new ArcadeHomeReadyPayload(b.readVarLong(),b.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
