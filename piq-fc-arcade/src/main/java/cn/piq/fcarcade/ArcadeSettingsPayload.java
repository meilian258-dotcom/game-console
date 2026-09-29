package cn.piq.fcarcade;

import cn.piq.fcarcade.config.ArcadeGlobalSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSettingsPayload(
        BlockPos blockPos,
        ArcadeGlobalSettings settings
) implements CustomPacketPayload {
    public ArcadeSettingsPayload {
        blockPos = blockPos.immutable();
        if (settings == null) {
            throw new IllegalArgumentException("街机全局设置不能为空");
        }
    }

    public static final Type<ArcadeSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSettingsPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeVarInt(payload.settings.viewDistance());
                        buffer.writeVarInt(payload.settings.audioDistance());
                        buffer.writeVarInt(payload.settings.audioVolumePercent());
                        buffer.writeVarInt(payload.settings.saveRetentionDays());
                    },
                    buffer -> new ArcadeSettingsPayload(
                            buffer.readBlockPos(),
                            new ArcadeGlobalSettings(
                                    buffer.readVarInt(),
                                    buffer.readVarInt(),
                                    buffer.readVarInt(),
                                    buffer.readVarInt())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
