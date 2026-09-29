package cn.piq.fcarcade.home;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Immutable server grant; inventory NBT is only a receipt of this live grant. */
public record ZapperBinding(long sessionId,int epoch,UUID lease,ResourceLocation dimension,
        BlockPos consolePos,UUID consoleId,BlockPos tvPos,UUID tvId,UUID linkId) {
    public ZapperBinding {
        if(sessionId<=0||epoch<=0)throw new IllegalArgumentException("Invalid gun session");
        Objects.requireNonNull(lease);Objects.requireNonNull(dimension);Objects.requireNonNull(consoleId);Objects.requireNonNull(tvId);Objects.requireNonNull(linkId);
        consolePos=Objects.requireNonNull(consolePos).immutable();tvPos=Objects.requireNonNull(tvPos).immutable();
    }
    public ZapperBinding withEpoch(int value){return new ZapperBinding(sessionId,value,lease,dimension,consolePos,consoleId,tvPos,tvId,linkId);}
    public void write(RegistryFriendlyByteBuf b){b.writeVarLong(sessionId);b.writeVarInt(epoch);b.writeUUID(lease);b.writeResourceLocation(dimension);b.writeBlockPos(consolePos);b.writeUUID(consoleId);b.writeBlockPos(tvPos);b.writeUUID(tvId);b.writeUUID(linkId);}
    public static ZapperBinding read(RegistryFriendlyByteBuf b){return new ZapperBinding(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readResourceLocation(),b.readBlockPos(),b.readUUID(),b.readBlockPos(),b.readUUID(),b.readUUID());}
}
