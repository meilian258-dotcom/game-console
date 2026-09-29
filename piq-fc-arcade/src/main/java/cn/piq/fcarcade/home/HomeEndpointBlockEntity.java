package cn.piq.fcarcade.home;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

abstract class HomeEndpointBlockEntity extends BlockEntity {
    private UUID hardwareId = UUID.randomUUID();
    private UUID linkId;
    private UUID peerId;
    private BlockPos peerPos;
    // Captured only when a paid link is made. Assembly/disassembly is not a live-session migration.
    private UUID linkShipId;
    private int maintenanceTicks;
    private boolean physicallyRemoved;
    private boolean visualPowered;
    private boolean occupancyVisible = true;
    private Boolean joinApprovalRequired;
    private boolean adminDefaultsInitialized;
    private int observationRange;
    private cn.piq.fcarcade.cabinet.CabinetSyncMode synchronizationMode = cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC;

    HomeEndpointBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public final UUID hardwareId() { return hardwareId; }
    public final UUID linkId() { return linkId; }
    public final int observationRange(int fallback) { return cn.piq.fcarcade.config.GameConsoleAdminPolicy.persistedRange(observationRange, fallback); }
    final void setObservationRange(int range) {
        requireLiveConsole();
        if (!cn.piq.fcarcade.config.GameConsoleAdminPolicy.validRange(range)) throw new IllegalArgumentException("Observation range");
        if (observationRange != range) { observationRange = range; changed(); }
    }
    public final boolean occupancyVisible() { return occupancyVisible; }
    public final boolean joinApprovalRequired() {
        String system = this instanceof HomeConsoleBlockEntity ? "piq_fc_arcade:nes"
                : this instanceof ExternalHomeConsoleBlockEntity external ? external.systemId().toString() : "";
        return Defaults.joinApproval(system, joinApprovalRequired);
    }
    /** Pure default resolution; null means absent, never an explicit user choice. */
    static final class Defaults {
        private Defaults() {}
        static boolean joinApproval(String system, Boolean stored) {
            return stored != null ? stored
                    : !("piq_fc_arcade:nes".equals(system) || "piq_sfc_home:sfc".equals(system));
        }
    }
    final void occupancyVisible(boolean visible) {
        requireLiveConsole();
        if (occupancyVisible != visible) { occupancyVisible = visible; changed(); }
    }
    final void joinApprovalRequired(boolean required) {
        requireLiveConsole();
        if (!java.util.Objects.equals(joinApprovalRequired, required)) { joinApprovalRequired = required; changed(); }
    }
    private void requireLiveConsole() {
        if (kind() != HomeLinkLedger.Kind.CONSOLE || !(level instanceof net.minecraft.server.level.ServerLevel server)
                || !server.getServer().isSameThread() || isRemoved() || !server.hasChunkAt(worldPosition)
                || server.getBlockEntity(worldPosition) != this) throw new IllegalStateException("Inactive home console");
    }
    /** Machine preference, never a live-session switch. Only HomeSyncSettings may mutate it. */
    public final cn.piq.fcarcade.cabinet.CabinetSyncMode synchronizationMode() { return synchronizationMode; }
    final void synchronizationMode(cn.piq.fcarcade.cabinet.CabinetSyncMode mode) {
        if (kind() != HomeLinkLedger.Kind.CONSOLE || !(level instanceof net.minecraft.server.level.ServerLevel server)
                || !server.getServer().isSameThread() || isRemoved() || !server.hasChunkAt(worldPosition)
                || server.getBlockEntity(worldPosition) != this) throw new IllegalStateException("Inactive home console");
        if (synchronizationMode != java.util.Objects.requireNonNull(mode)) { synchronizationMode = mode; changed(); }
    }
    /** Server-derived presentation only; never authority to start or control an emulator. */
    public final boolean visualPowered() { return visualPowered; }
    final void visualPowered(boolean running) {
        if (level instanceof net.minecraft.server.level.ServerLevel server
                && server.getServer().isSameThread() && !isRemoved() && server.hasChunkAt(worldPosition)
                && server.getBlockEntity(worldPosition) == this && visualPowered != running) {
            visualPowered = running; changed();
        }
    }
    final UUID peerId() { return peerId; }
    final BlockPos peerPos() { return peerPos; }
    final boolean linkSpaceValid() {
        return cn.piq.fcarcade.compat.HomeShipSpace.bindingMatches(
                cn.piq.fcarcade.compat.HomeShipSpace.at(level,worldPosition),linkShipId);
    }
    abstract HomeLinkLedger.Kind kind();

    final HomeLinkLedger.Endpoint endpoint() {
        return new HomeLinkLedger.Endpoint(hardwareId, level.dimension().location().toString(),
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), kind());
    }

    final void attach(HomeLinkLedger.Link link, UUID shipId) {
        var other = link.other(endpoint());
        if (other == null) throw new IllegalArgumentException("Wrong link owner");
        linkId = link.id(); peerId = other.id();
        peerPos = new BlockPos(other.x(), other.y(), other.z());
        linkShipId = shipId;
        changed();
    }

    final void clearLink() {
        linkId = null; peerId = null; peerPos = null; linkShipId = null;
        changed();
    }

    final boolean beginRemoval() {
        if (physicallyRemoved) return false;
        physicallyRemoved = true;
        return true;
    }

    final void changed() {
        setChanged();
        if (level != null && !level.isClientSide)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public final void maintenanceTick() {
        if (maintenanceTicks++ % 20 == 0) {
            HomeHardware.reconcile(this);
            if (level instanceof net.minecraft.server.level.ServerLevel server) {
                if (this instanceof HomeTvBlockEntity) HomeApplianceService.refresh(server, worldPosition);
                else HomeApplianceService.refreshConsole(server, this);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof net.minecraft.server.level.ServerLevel server && !adminDefaultsInitialized) {
            adminDefaultsInitialized = true;
            if (kind() == HomeLinkLedger.Kind.CONSOLE) {
                observationRange = cn.piq.fcarcade.config.GameConsoleAdminSettings.defaultRange(server.getServer());
                synchronizationMode = HomeSyncSettings.placementMode(server, this, synchronizationMode);
            }
        }
        if (level != null && !level.isClientSide) { visualPowered = false; changed(); }
    }

    @Override
    public void onChunkUnloaded() {
        HomeHardware.unloaded(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Loading even an old tag is not new placement. Missing fields preserve legacy behavior.
        adminDefaultsInitialized = true;
        observationRange = cn.piq.fcarcade.config.GameConsoleAdminPolicy.persistedRange(tag.getInt("ConsoleWatchRange"), 0);
        visualPowered = tag.getBoolean("VisualPowered");
        occupancyVisible = !tag.contains("HomeOccupancyVisible") || tag.getBoolean("HomeOccupancyVisible");
        // Keep every stored boolean; a malformed present field fails closed.
        joinApprovalRequired = !tag.contains("HomeJoinApproval") ? null
                : !tag.contains("HomeJoinApproval", net.minecraft.nbt.Tag.TAG_BYTE) || tag.getBoolean("HomeJoinApproval");
        synchronizationMode = HomeSyncPolicy.persisted(tag.getString("HomeSyncMode"));
        if (tag.hasUUID("HardwareId")) hardwareId = tag.getUUID("HardwareId");
        if (tag.hasUUID("LinkId") && tag.hasUUID("PeerId") && tag.contains("PeerPos")) {
            linkId = tag.getUUID("LinkId"); peerId = tag.getUUID("PeerId");
            peerPos = BlockPos.of(tag.getLong("PeerPos"));
            linkShipId = tag.hasUUID("LinkShipId") ? tag.getUUID("LinkShipId") : null;
        } else { linkId = null; peerId = null; peerPos = null; linkShipId = null; }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("VisualPowered", visualPowered);
        if (kind() == HomeLinkLedger.Kind.CONSOLE) {
            if (observationRange != 0) tag.putInt("ConsoleWatchRange", observationRange);
            tag.putBoolean("HomeOccupancyVisible", occupancyVisible);
            tag.putBoolean("HomeJoinApproval", joinApprovalRequired());
        }
        tag.putUUID("HardwareId", hardwareId);
        if (kind() == HomeLinkLedger.Kind.CONSOLE) tag.putString("HomeSyncMode", synchronizationMode.name());
        if (linkId != null && peerId != null && peerPos != null) {
            tag.putUUID("LinkId", linkId); tag.putUUID("PeerId", peerId);
            tag.putLong("PeerPos", peerPos.asLong());
            if (linkShipId != null) tag.putUUID("LinkShipId",linkShipId);
        }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public boolean onlyOpCanSetNbt() { return true; }
}
