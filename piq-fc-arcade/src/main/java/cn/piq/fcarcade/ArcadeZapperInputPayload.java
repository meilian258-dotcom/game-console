package cn.piq.fcarcade;

import java.util.UUID;
import cn.piq.fcarcade.session.ZapperInput;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeZapperInputPayload(long sessionId,int epoch,UUID lease,int sequence,int x,int y,boolean offscreen,boolean trigger,boolean forceRelease) implements CustomPacketPayload {
    public ArcadeZapperInputPayload{
        if(sessionId<=0||epoch<=0||lease==null||sequence<0||x<0||x>=256||y<0||y>=240||offscreen&&(x!=0||y!=0)||forceRelease&&(!offscreen||trigger))throw new IllegalArgumentException("Invalid gun input");
    }
    public int packed(){return ZapperInput.pack(x,y,offscreen,trigger);}
    public static final Type<ArcadeZapperInputPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"zapper_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ArcadeZapperInputPayload> STREAM_CODEC=StreamCodec.of((b,p)->{
        b.writeVarLong(p.sessionId);b.writeVarInt(p.epoch);b.writeUUID(p.lease);b.writeVarInt(p.sequence);b.writeByte(p.x);b.writeByte(p.y);b.writeBoolean(p.offscreen);b.writeBoolean(p.trigger);b.writeBoolean(p.forceRelease);
    },b->new ArcadeZapperInputPayload(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readVarInt(),b.readUnsignedByte(),b.readUnsignedByte(),b.readBoolean(),b.readBoolean(),b.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
