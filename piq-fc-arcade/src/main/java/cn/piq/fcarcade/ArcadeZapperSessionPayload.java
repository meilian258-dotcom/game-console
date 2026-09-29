package cn.piq.fcarcade;

import cn.piq.fcarcade.home.ZapperBinding;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeZapperSessionPayload(ZapperBinding binding,boolean active) implements CustomPacketPayload {
    public ArcadeZapperSessionPayload{java.util.Objects.requireNonNull(binding);}
    public static final Type<ArcadeZapperSessionPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"zapper_session"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArcadeZapperSessionPayload> STREAM_CODEC=StreamCodec.of((b,p)->{p.binding.write(b);b.writeBoolean(p.active);},b->new ArcadeZapperSessionPayload(ZapperBinding.read(b),b.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
