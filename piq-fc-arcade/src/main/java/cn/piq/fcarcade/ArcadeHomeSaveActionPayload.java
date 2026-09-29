package cn.piq.fcarcade;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record ArcadeHomeSaveActionPayload(UUID token,ArcadeSaveSlotActionPayload action) implements CustomPacketPayload {
    public ArcadeHomeSaveActionPayload{Objects.requireNonNull(token);}
    public static final Type<ArcadeHomeSaveActionPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"home_save_action"));
    /** A null action explicitly cancels; it cannot select, overwrite or start anything. */
    public static final StreamCodec<RegistryFriendlyByteBuf,ArcadeHomeSaveActionPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.token);b.writeBoolean(p.action!=null);if(p.action!=null)ArcadeSaveSlotActionPayload.STREAM_CODEC.encode(b,p.action);},b->{UUID id=b.readUUID();return new ArcadeHomeSaveActionPayload(id,b.readBoolean()?ArcadeSaveSlotActionPayload.STREAM_CODEC.decode(b):null);});
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
