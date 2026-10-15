// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ArcadeExitPromptPayload(long sessionId)
        implements CustomPacketPayload {
    public static final Type<ArcadeExitPromptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_exit_prompt"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeExitPromptPayload>
            STREAM_CODEC = StreamCodec.of(
                    (buffer, payload) -> buffer.writeVarLong(payload.sessionId),
                    buffer -> new ArcadeExitPromptPayload(buffer.readVarLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
