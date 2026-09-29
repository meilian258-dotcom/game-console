package cn.piq.fcarcade;

import cn.piq.fcarcade.rom.RomRepository;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RomReadyPayload(
        long sessionId,
        String sha256
) implements CustomPacketPayload {
    public RomReadyPayload {
        if (sessionId < 0 || sha256 == null
                || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM 就绪消息无效");
        }
    }

    public static final Type<RomReadyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "rom_ready"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RomReadyPayload>
            STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeLong(payload.sessionId);
                buffer.writeUtf(payload.sha256, 64);
            },
            buffer -> new RomReadyPayload(
                    buffer.readLong(),
                    buffer.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
