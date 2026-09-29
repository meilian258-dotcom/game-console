package cn.piq.fcarcade;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** Reborrowing the same socket cannot authorize queued input from an old loan. */
public record ArcadeHomeInputPayload(UUID lease,ArcadeInputPayload input) implements CustomPacketPayload {
    public ArcadeHomeInputPayload{java.util.Objects.requireNonNull(lease);java.util.Objects.requireNonNull(input);}
    public static final Type<ArcadeHomeInputPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"home_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArcadeHomeInputPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease());ArcadeInputPayload.STREAM_CODEC.encode(b,p.input());},b->new ArcadeHomeInputPayload(b.readUUID(),ArcadeInputPayload.STREAM_CODEC.decode(b)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
