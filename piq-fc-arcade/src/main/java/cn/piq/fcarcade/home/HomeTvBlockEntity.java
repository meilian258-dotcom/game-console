package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public final class HomeTvBlockEntity extends HomeEndpointBlockEntity {
    private static final java.util.Set<HomeTvBlockEntity> CLIENT_LOADED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Client-thread lifecycle index, including televisions outside the camera view. */
    public static java.util.List<HomeTvBlockEntity> clientLoaded() { return java.util.List.copyOf(CLIENT_LOADED); }
    private boolean structureInstalled;
    private boolean standaloneRemoved;
    private boolean scanlinesEnabled;
    private boolean powerAnimationEnabled = true;
    private boolean noSignalToneEnabled = true;
    private boolean muted;
    private boolean emptyConsolePowered;
    private final TelevisionPowerTransition powerTransition = new TelevisionPowerTransition();
    private final TelevisionState presentation = new TelevisionState(false, TelevisionState.DEFAULT_VOLUME);
    public HomeTvBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOME_TV.get(), pos, state);
    }

    @Override HomeLinkLedger.Kind kind() { return HomeLinkLedger.Kind.TV; }
    public BlockPos consolePos() { return peerPos(); }
    public boolean scanlinesEnabled() { return scanlinesEnabled; }
    public boolean powerAnimationEnabled() { return powerAnimationEnabled; }
    public boolean noSignalToneEnabled() { return noSignalToneEnabled; }
    public boolean muted() { return muted; }
    /** A connected console is intentionally powered without media; no emulator is running. */
    public boolean emptyConsolePowered() { return powered() && emptyConsolePowered; }
    public boolean powered() { return presentation.powered(); }
    public boolean signalPresent() { return presentation.signal(); }
    public int volume() { return presentation.volume(); }
    public float audioGain() { return muted ? 0 : presentation.gain(); }
    public double powerVisualAmount(double gameTick) {
        return powerAnimationEnabled ? powerTransition.amount(gameTick) : powered() ? 1 : 0;
    }
    boolean setPowerAnimationEnabled(boolean enabled) {
        if (!authoritative() || powerAnimationEnabled == enabled) return false;
        powerAnimationEnabled = enabled; changed(); return true;
    }
    boolean setNoSignalToneEnabled(boolean enabled) {
        if (!authoritative() || noSignalToneEnabled == enabled) return false;
        noSignalToneEnabled = enabled; changed(); return true;
    }
    boolean setMuted(boolean value) {
        if (!authoritative() || muted == value) return false;
        muted = value; changed(); return true;
    }
    boolean setPower(boolean on) {
        if (!authoritative() || powered() == on) return false;
        presentation.power(on); syncLight(); changed(); return true;
    }
    boolean setVolume(int value) {
        if (!authoritative() || volume() == TelevisionState.clamp(value)) return false;
        presentation.volume(value); changed(); return true;
    }
    void signal(boolean active) {
        active &= powered();
        if (authoritative() && signalPresent() != active) { presentation.signal(active); changed(); }
    }
    void emptyConsole(boolean active) {
        active &= powered();
        if (authoritative() && emptyConsolePowered != active) { emptyConsolePowered = active; changed(); }
    }
    private boolean authoritative() {
        return level instanceof net.minecraft.server.level.ServerLevel server && server.getServer().isSameThread()
                && !isRemoved() && server.hasChunkAt(worldPosition) && server.getBlockEntity(worldPosition) == this;
    }
    /** Vanilla state light, not a temporary invisible block or a client-only shader effect. */
    void syncLight() {
        if (!authoritative()) return;
        var state = getBlockState();
        if (state.hasProperty(RetroTvBlock.LIT) && state.getValue(RetroTvBlock.LIT) != powered())
            level.setBlock(worldPosition, state.setValue(RetroTvBlock.LIT, powered()),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
    @Override public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) { CLIENT_LOADED.add(this); powerTransition.loaded(powered()); }
        if (level != null && !level.isClientSide) { presentation.signal(false); emptyConsolePowered = false; syncLight(); changed(); }
    }
    @Override public void setRemoved() {
        if (level != null && level.isClientSide) { CLIENT_LOADED.remove(this); powerTransition.unloaded(); }
        super.setRemoved();
    }
    @Override public void onChunkUnloaded() {
        if (level != null && level.isClientSide) { CLIENT_LOADED.remove(this); powerTransition.unloaded(); }
        super.onChunkUnloaded();
    }

    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        // A full chunk snapshot is state, not an interaction, regardless of onLoad ordering.
        loadWithComponents(tag, registries);
        powerTransition.loaded(powered());
    }

    @Override public void onDataPacket(net.minecraft.network.Connection connection,
            net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        if (packet.getTag().isEmpty()) return;
        loadWithComponents(packet.getTag(), registries);
        if (level != null && level.isClientSide)
            powerTransition.observe(powered(), powerAnimationEnabled, TelevisionPowerTransition.presentationTime());
    }

    /** Only the loaded, authoritative television may change its presentation state. */
    boolean setScanlinesEnabled(boolean enabled) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread() || isRemoved()
                || !serverLevel.hasChunkAt(worldPosition) || serverLevel.getBlockEntity(worldPosition) != this
                || scanlinesEnabled == enabled) return false;
        scanlinesEnabled = enabled;
        changed();
        return true;
    }

    public boolean structureInstalled() { return HomeTvStructure.singleBlock(getBlockState()) || structureInstalled; }
    void installStructure() { structureInstalled = true; changed(); }
    boolean claimStandaloneRemoval() {
        if (standaloneRemoved) return false;
        standaloneRemoved = true; return true;
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        structureInstalled = tag.getBoolean("LargeTvStructure");
        scanlinesEnabled = tag.getBoolean("CrtScanlines");
        powerAnimationEnabled = !tag.contains("TvPowerAnimation") || tag.getBoolean("TvPowerAnimation");
        noSignalToneEnabled = !tag.contains("TvNoSignalTone") || tag.getBoolean("TvNoSignalTone");
        muted = tag.getBoolean("TvMuted");
        emptyConsolePowered = tag.getBoolean("TvEmptyConsole");
        presentation.power(!tag.contains("TvPowered") || tag.getBoolean("TvPowered"));
        presentation.volume(tag.contains("TvVolume") ? tag.getInt("TvVolume") : TelevisionState.DEFAULT_VOLUME);
        presentation.signal(tag.getBoolean("TvSignal"));
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("LargeTvStructure", structureInstalled);
        tag.putBoolean("CrtScanlines", scanlinesEnabled);
        tag.putBoolean("TvPowerAnimation", powerAnimationEnabled);
        tag.putBoolean("TvNoSignalTone", noSignalToneEnabled);
        tag.putBoolean("TvMuted", muted);
        tag.putBoolean("TvPowered", powered());
        tag.putInt("TvVolume", volume());
        tag.putBoolean("TvSignal", signalPresent());
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = super.getUpdateTag(registries);
        // Sent to observers, not written to disk. Authority is a transient server-side object.
        tag.putBoolean("TvEmptyConsole", emptyConsolePowered());
        return tag;
    }
}
