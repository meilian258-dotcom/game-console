package cn.piq.fcarcade.world;

import cn.piq.fcarcade.LeaderboardPanelConfigPayload;
import cn.piq.fcarcade.LeaderboardPanelConfigUpdatePayload;
import cn.piq.fcarcade.server.ArcadeLeaderboardText;
import cn.piq.fcarcade.server.IdleDisplaySchedule;
import cn.piq.fcarcade.server.LeaderboardPanelDisplay;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import cn.piq.fcarcade.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class LeaderboardPanelBlockEntity extends BlockEntity {
    public static final int DEFAULT_INTERVAL_SECONDS = 3;
    public static final int DEFAULT_SCALE_PERCENT = 100;

    private boolean enabled = true;
    private int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    private int scalePercent = DEFAULT_SCALE_PERCENT;
    private int offsetXHundredths;
    private int offsetYHundredths;
    private int depthHundredths;
    private int nextRefreshTick;

    public LeaderboardPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEADERBOARD_PANEL.get(), pos, state);
    }

    public LeaderboardPanelConfigPayload snapshot() {
        return new LeaderboardPanelConfigPayload(
                worldPosition,
                enabled,
                intervalSeconds,
                scalePercent,
                offsetXHundredths,
                offsetYHundredths,
                depthHundredths);
    }

    public void apply(LeaderboardPanelConfigUpdatePayload payload) {
        enabled = payload.enabled();
        intervalSeconds = clamp(payload.intervalSeconds(), 1, 60);
        scalePercent = clamp(payload.scalePercent(), 25, 300);
        offsetXHundredths = clamp(payload.offsetXHundredths(), -200, 200);
        offsetYHundredths = clamp(payload.offsetYHundredths(), -200, 200);
        depthHundredths = clamp(payload.depthHundredths(), -25, 100);
        nextRefreshTick = 0;
        setChangedAndSync();
        if (level instanceof ServerLevel serverLevel) refreshNow(serverLevel);
    }

    public static void serverTick(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            LeaderboardPanelBlockEntity panel
    ) {
        int tick = level.getServer().getTickCount();
        if (panel.nextRefreshTick != 0 && tick < panel.nextRefreshTick) return;
        panel.nextRefreshTick = tick + panel.intervalSeconds * 20;
        panel.refreshNow(level);
    }

    public void removeDisplay() {
        if (level instanceof ServerLevel serverLevel) {
            LeaderboardPanelDisplay.remove(serverLevel, worldPosition);
        }
    }

    private void refreshNow(ServerLevel level) {
        Direction facing = getBlockState().getValue(LeaderboardPanelBlock.FACING);
        var entries = ServerArcadeSessions.topRoadRaceScores(level.getServer());
        int pageStart = IdleDisplaySchedule.pageStart(
                level.getServer().getTickCount(),
                entries.size(),
                ArcadeLeaderboardText.DISPLAY_LIMIT,
                intervalSeconds * 20);
        LeaderboardPanelDisplay.refresh(
                level,
                worldPosition,
                facing,
                enabled,
                scalePercent,
                offsetXHundredths,
                offsetYHundredths,
                depthHundredths,
                entries,
                pageStart);
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(
                    worldPosition,
                    state,
                    state,
                    Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        intervalSeconds = clamp(
                tag.getInt("IntervalSeconds"),
                1,
                60,
                DEFAULT_INTERVAL_SECONDS);
        scalePercent = clamp(
                tag.getInt("ScalePercent"),
                25,
                300,
                DEFAULT_SCALE_PERCENT);
        offsetXHundredths = clamp(tag.getInt("OffsetX"), -200, 200);
        offsetYHundredths = clamp(tag.getInt("OffsetY"), -200, 200);
        depthHundredths = clamp(tag.getInt("Depth"), -25, 100);
        nextRefreshTick = 0;
    }

    @Override
    protected void saveAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Enabled", enabled);
        tag.putInt("IntervalSeconds", intervalSeconds);
        tag.putInt("ScalePercent", scalePercent);
        tag.putInt("OffsetX", offsetXHundredths);
        tag.putInt("OffsetY", offsetYHundredths);
        tag.putInt("Depth", depthHundredths);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum,
            int fallback
    ) {
        return value == 0 ? fallback : clamp(value, minimum, maximum);
    }
}
