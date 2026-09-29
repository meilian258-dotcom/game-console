package cn.piq.fcarcade;

import cn.piq.fcarcade.session.ArcadeRole;
import cn.piq.fcarcade.session.ArcadeMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSessionPayload(
        BlockPos blockPos,
        long sessionId,
        ArcadeMode mode,
        ArcadeRole role,
        int memberCount,
        String playerNames,
        int viewDistance,
        int audioDistance,
        int audioVolumePercent,
        String romSha256,
        int epoch,
        boolean reset,
        boolean active,
        cn.piq.fcarcade.session.NesCoreVariant variant,
        boolean homeRuntime, boolean computeHost, java.util.UUID controllerLease, long controlRevision,
        boolean playerMedia
) implements CustomPacketPayload {
    public ArcadeSessionPayload(BlockPos pos,long id,ArcadeMode mode,ArcadeRole role,int members,String names,int view,int audio,int volume,String rom,int epoch,boolean reset,boolean active,cn.piq.fcarcade.session.NesCoreVariant variant,
                                boolean homeRuntime,boolean computeHost,java.util.UUID lease,long revision){
        this(pos,id,mode,role,members,names,view,audio,volume,rom,epoch,reset,active,variant,homeRuntime,computeHost,lease,revision,false);
    }
    public ArcadeSessionPayload(BlockPos pos,long id,ArcadeMode mode,ArcadeRole role,int members,String names,int view,int audio,int volume,String rom,int epoch,boolean reset,boolean active,cn.piq.fcarcade.session.NesCoreVariant variant){
        this(pos,id,mode,role,members,names,view,audio,volume,rom,epoch,reset,active,variant,false,false,null,0);
    }
    public ArcadeSessionPayload(BlockPos pos,long id,ArcadeMode mode,ArcadeRole role,int members,String names,int view,int audio,int volume,String rom,int epoch,boolean reset,boolean active){
        this(pos,id,mode,role,members,names,view,audio,volume,rom,epoch,reset,active,cn.piq.fcarcade.session.NesCoreVariant.LIBRETRO_V1);
    }
    public ArcadeSessionPayload {java.util.Objects.requireNonNull(variant);if(variant.isZapper()&&mode!=ArcadeMode.LOCKSTEP)throw new IllegalArgumentException("Gun requires authoritative frames");
        if(controlRevision<0||computeHost&&!homeRuntime||playerMedia&&!homeRuntime||controllerLease!=null&&!homeRuntime||homeRuntime&&active&&role.controllerIndex()>=0&&controllerLease==null)throw new IllegalArgumentException("Invalid appliance authority");}
    public static final Type<ArcadeSessionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_session"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSessionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeLong(payload.sessionId);
                        buffer.writeByte(payload.mode.ordinal());
                        buffer.writeByte(payload.role.ordinal());
                        buffer.writeVarInt(payload.memberCount);
                        buffer.writeUtf(payload.playerNames, 64);
                        buffer.writeVarInt(payload.viewDistance);
                        buffer.writeVarInt(payload.audioDistance);
                        buffer.writeVarInt(payload.audioVolumePercent);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeVarInt(payload.epoch);
                        buffer.writeBoolean(payload.reset);
                        buffer.writeBoolean(payload.active);
                        buffer.writeByte(payload.variant.ordinal());
                        buffer.writeBoolean(payload.homeRuntime);buffer.writeBoolean(payload.computeHost);
                        buffer.writeBoolean(payload.controllerLease!=null);if(payload.controllerLease!=null)buffer.writeUUID(payload.controllerLease);
                        buffer.writeVarLong(payload.controlRevision);
                        buffer.writeBoolean(payload.playerMedia);
                    },
                    buffer -> new ArcadeSessionPayload(
                            buffer.readBlockPos(),
                            buffer.readLong(),
                            ArcadeMode.fromNetwork(buffer.readUnsignedByte()),
                            ArcadeRole.fromNetwork(buffer.readUnsignedByte()),
                            buffer.readVarInt(),
                            buffer.readUtf(64),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readUtf(64),
                            buffer.readVarInt(),
                            buffer.readBoolean(),
                            buffer.readBoolean(),cn.piq.fcarcade.session.NesCoreVariant.fromNetwork(buffer.readUnsignedByte()),
                            buffer.readBoolean(),buffer.readBoolean(),buffer.readBoolean()?buffer.readUUID():null,buffer.readVarLong(),buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
