package cn.piq.fcarcade;

import cn.piq.fcarcade.session.LockstepInputRun;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record ArcadeHistoryPayload(
        long sessionId,
        int epoch,
        long startFrame,
        List<LockstepInputRun> runs
) implements CustomPacketPayload {
    private static final int MAX_RUNS = cn.piq.fcarcade.session.LockstepTimeline.MAX_RETAINED_FRAMES;

    public ArcadeHistoryPayload {
        runs = List.copyOf(runs);
        long frameCount = runs.stream().mapToLong(LockstepInputRun::frames).sum();
        if (startFrame < 0 || startFrame % 3 != 0 || runs.size() > MAX_RUNS
                || frameCount > MAX_RUNS || startFrame > Long.MAX_VALUE - frameCount) {
            throw new IllegalArgumentException("锁步历史段数量超过限制");
        }
    }

    public static final Type<ArcadeHistoryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    FcArcadeMod.MOD_ID,
                    "arcade_history"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArcadeHistoryPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeLong(payload.sessionId);
                        buffer.writeVarInt(payload.epoch);
                        buffer.writeVarLong(payload.startFrame);
                        buffer.writeVarInt(payload.runs.size());
                        for (LockstepInputRun run : payload.runs) {
                            buffer.writeVarInt(run.frames());
                            buffer.writeByte(run.playerOneMask());
                            buffer.writeByte(run.playerTwoMask());
                            buffer.writeVarInt(run.zapperState());
                        }
                    },
                    buffer -> {
                        long sessionId = buffer.readLong();
                        int epoch = buffer.readVarInt();
                        long startFrame = buffer.readVarLong();
                        int count = buffer.readVarInt();
                        if (count < 0 || count > MAX_RUNS) {
                            throw new IllegalArgumentException("非法锁步历史段数量：" + count);
                        }
                        List<LockstepInputRun> runs = new ArrayList<>(count);
                        for (int index = 0; index < count; index++) {
                            runs.add(new LockstepInputRun(
                                    buffer.readVarInt(),
                                    buffer.readUnsignedByte(),
                                    buffer.readUnsignedByte(),buffer.readVarInt()));
                        }
                        return new ArcadeHistoryPayload(sessionId, epoch, startFrame, runs);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
