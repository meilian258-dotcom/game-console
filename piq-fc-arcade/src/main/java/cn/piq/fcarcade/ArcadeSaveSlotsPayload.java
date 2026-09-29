package cn.piq.fcarcade;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record ArcadeSaveSlotsPayload(
        BlockPos blockPos,
        String romSha256,
        String romName,
        List<ArcadeSaveSlotEntry> slots
) implements CustomPacketPayload {
    public ArcadeSaveSlotsPayload {
        blockPos = blockPos.immutable();
        slots = List.copyOf(slots);
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (romName == null || romName.isBlank() || romName.length() > 160) {
            throw new IllegalArgumentException("ROM 显示名无效");
        }
        if (slots.size() != 3) {
            throw new IllegalArgumentException("玩家存档槽数量无效");
        }
    }

    public static final Type<ArcadeSaveSlotsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_save_slots"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeSaveSlotsPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.blockPos);
                        buffer.writeUtf(payload.romSha256, 64);
                        buffer.writeUtf(payload.romName, 160);
                        for (ArcadeSaveSlotEntry slot : payload.slots) {
                            buffer.writeVarInt(slot.slot());
                            buffer.writeBoolean(slot.occupied());
                            buffer.writeUtf(slot.name(), 32);
                            buffer.writeVarInt(slot.players());
                            buffer.writeLong(slot.modifiedEpochMillis());
                            buffer.writeUtf(slot.romSha256(), 64);
                            buffer.writeUtf(slot.romName(), 160);
                        }
                    },
                    buffer -> {
                        BlockPos pos = buffer.readBlockPos();
                        String sha256 = buffer.readUtf(64);
                        String romName = buffer.readUtf(160);
                        List<ArcadeSaveSlotEntry> slots = new ArrayList<>(3);
                        for (int index = 0; index < 3; index++) {
                            slots.add(new ArcadeSaveSlotEntry(
                                    buffer.readVarInt(),
                                    buffer.readBoolean(),
                                    buffer.readUtf(32),
                                    buffer.readVarInt(),
                                    buffer.readLong(),
                                    buffer.readUtf(64),
                                    buffer.readUtf(160)));
                        }
                        return new ArcadeSaveSlotsPayload(
                                pos,
                                sha256,
                                romName,
                                slots);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
