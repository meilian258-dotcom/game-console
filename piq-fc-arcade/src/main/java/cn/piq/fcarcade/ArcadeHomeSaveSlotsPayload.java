package cn.piq.fcarcade;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record ArcadeHomeSaveSlotsPayload(UUID token,boolean gun,ArcadeSaveSlotsPayload slots,boolean cartridge) implements CustomPacketPayload {
    public ArcadeHomeSaveSlotsPayload(UUID token,boolean gun,ArcadeSaveSlotsPayload slots){this(token,gun,slots,false);}
    public ArcadeHomeSaveSlotsPayload{Objects.requireNonNull(token);Objects.requireNonNull(slots);}
    public static final Type<ArcadeHomeSaveSlotsPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"home_save_slots"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArcadeHomeSaveSlotsPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.token);b.writeBoolean(p.gun);ArcadeSaveSlotsPayload.STREAM_CODEC.encode(b,p.slots);b.writeBoolean(p.cartridge);},b->new ArcadeHomeSaveSlotsPayload(b.readUUID(),b.readBoolean(),ArcadeSaveSlotsPayload.STREAM_CODEC.decode(b),b.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
