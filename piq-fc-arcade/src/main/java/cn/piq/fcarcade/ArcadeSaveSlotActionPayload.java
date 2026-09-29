package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeSaveSlotActionPayload(
        BlockPos blockPos,
        String romSha256,
        int slot,
        int action,
        String name,
        int players,
        boolean resume
) implements CustomPacketPayload {
    public static final int PLAY = 0;
    public static final int RENAME = 1;
    public static final int DELETE = 2;

    public ArcadeSaveSlotActionPayload {
        blockPos = blockPos.immutable();
        name = name == null ? "" : name.strip();
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (slot < 1 || slot > 3 || action < PLAY || action > DELETE) {
            throw new IllegalArgumentException("存档槽操作无效");
        }
        if (name.length() > 32
                || name.chars().anyMatch(Character::isISOControl)
                || players < 1 || players > 2) {
            throw new IllegalArgumentException("存档槽参数无效");
        }
    }

    public static final Type<ArcadeSaveSlotActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_save_slot_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSaveSlotActionPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeVarInt(payload.slot);
                        buffer.writeVarInt(payload.action);
                        buffer.writeUtf(payload.name, 32);
                        buffer.writeVarInt(payload.players);
                        buffer.writeBoolean(payload.resume);
                    },
                    buffer -> new ArcadeSaveSlotActionPayload(
                            buffer.readBlockPos(),
                            buffer.readUtf(64),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readUtf(32),
                            buffer.readVarInt(),
                            buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
