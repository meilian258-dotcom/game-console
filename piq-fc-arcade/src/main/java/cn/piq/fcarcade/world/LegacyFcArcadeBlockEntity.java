package cn.piq.fcarcade.world;

import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.cabinet.CabinetBackends;
import cn.piq.fcarcade.cabinet.ServerCabinets;
import cn.piq.fcarcade.cabinet.CabinetTarget;
import cn.piq.fcarcade.cabinet.CabinetLinks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

public class LegacyFcArcadeBlockEntity extends BlockEntity {
    private static final String SKIN_HASH_TAG = "SkinHash";
    private static final String SKIN_NAME_TAG = "SkinName";

    private String skinHash = "";
    private String skinName = "";
    private UUID cabinetId = UUID.randomUUID();
    private ResourceLocation cabinetBackend = CabinetBackends.NES;
    // Constructor is placement; old saved cabinets without this tag deliberately stay free.
    private boolean coinRequired = true;
    private boolean autoPowerOffOnExit = true;
    private int idleShutdownSeconds = cn.piq.fcarcade.cabinet.CabinetPowerSettings.DEFAULT_SECONDS;
    private int screenRenderDistance = cn.piq.fcarcade.cabinet.CabinetPowerSettings.DEFAULT_RENDER_DISTANCE;
    private boolean adminDefaultsInitialized;
    private int placedDefaultMode = -1;
    private int observationRange;
    private UUID visualLinkId;
    private CabinetTarget visualLinkPeer;
    private boolean visualPowered;
    private cn.piq.fcarcade.cabinet.CabinetGameProfile displayProfile=cn.piq.fcarcade.cabinet.CabinetGameProfile.EMPTY;

    public LegacyFcArcadeBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.LEGACY_FC_ARCADE.get(), pos, state);
    }

    protected LegacyFcArcadeBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                       BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String skinHash() {
        return skinHash;
    }

    public String skinName() {
        return skinName;
    }

    public UUID cabinetId() { return cabinetId; }
    public ResourceLocation cabinetBackend() { return cabinetBackend; }
    public boolean coinRequired() { return coinRequired; }
    public boolean autoPowerOffOnExit() { return autoPowerOffOnExit; }
    public int idleShutdownSeconds() { return idleShutdownSeconds; }
    public int screenRenderDistance() { return screenRenderDistance; }
    public void setPowerSettings(boolean enabled,int seconds,int range) {
        if (!(level instanceof ServerLevel server)||!server.getServer().isSameThread()) return;
        if (!cn.piq.fcarcade.cabinet.CabinetPowerSettings.validSeconds(seconds)||!cn.piq.fcarcade.cabinet.CabinetPowerSettings.validRenderDistance(range)) throw new IllegalArgumentException("Cabinet settings");
        autoPowerOffOnExit=enabled;idleShutdownSeconds=seconds;screenRenderDistance=range;setChanged();
        server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    public void setAutoPowerOffOnExit(boolean value) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || autoPowerOffOnExit==value) return;
        autoPowerOffOnExit=value;setChanged();
        level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
    }
    /** Ephemeral server-issued indicator, never a persisted power/launch authority. */
    public boolean visualPowered() { return visualPowered; }
    public cn.piq.fcarcade.cabinet.CabinetGameProfile displayProfile() { return displayProfile; }
    public void setVisualPowered(boolean powered) {
        if (!(level instanceof ServerLevel server)||!server.getServer().isSameThread()
                ||isRemoved()||!server.hasChunkAt(worldPosition)||server.getBlockEntity(worldPosition)!=this
                ||visualPowered==powered) return;
        visualPowered=powered;
        server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    public int placedDefaultMode() { return placedDefaultMode; }
    public int observationRange(int fallback) { return cn.piq.fcarcade.config.GameConsoleAdminPolicy.persistedRange(observationRange, fallback); }
    public void setObservationRange(int range) {
        if (!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||isRemoved()||!server.hasChunkAt(worldPosition)||server.getBlockEntity(worldPosition)!=this
                ||!cn.piq.fcarcade.config.GameConsoleAdminPolicy.validRange(range)) throw new IllegalStateException("Inactive cabinet or invalid range");
        observationRange=range;setChanged();server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    public void setCoinRequired(boolean required) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || coinRequired==required) return;
        coinRequired=required;setChanged();
        server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    /** Server-issued display data only. Runtime/link authority stays in CabinetLinks SavedData. */
    public UUID visualLinkId() { return visualLinkId; }
    public CabinetTarget visualLinkPeer() { return visualLinkPeer; }
    public void setVisualLink(UUID pair, CabinetTarget peer) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return;
        if (pair == null || !validVisualPeer(peer)) { pair = null; peer = null; }
        if (java.util.Objects.equals(pair,visualLinkId) && java.util.Objects.equals(peer,visualLinkPeer)) return;
        visualLinkId=pair;visualLinkPeer=peer;
        server.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    private boolean validVisualPeer(CabinetTarget peer) {
        if(peer==null||peer.identity().equals(cabinetId)||peer.anchor().equals(worldPosition)
                ||level!=null&&!peer.dimension().equals(level.dimension().location()))return false;
        double dx=(double)peer.anchor().getX()-worldPosition.getX(),dy=(double)peer.anchor().getY()-worldPosition.getY(),dz=(double)peer.anchor().getZ()-worldPosition.getZ();
        return dx*dx+dy*dy+dz*dz<=256;
    }

    /** Provider metadata only; changing this never rewrites the existing NES ROM selection or saves. */
    public void setCabinetBackend(ResourceLocation backend) {
        if (CabinetBackends.find(backend) == null) throw new IllegalArgumentException("Unknown cabinet backend");
        if (backend.equals(cabinetBackend)) return;
        cabinetBackend = backend;
        setChanged();
        if (level != null && !level.isClientSide)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override public void onLoad() {
        super.onLoad();
        if(level instanceof ServerLevel server&&!adminDefaultsInitialized) {
            adminDefaultsInitialized=true;
            placedDefaultMode=cn.piq.fcarcade.config.GameConsoleAdminSettings.defaultMode(server.getServer());
            observationRange=cn.piq.fcarcade.config.GameConsoleAdminSettings.defaultRange(server.getServer());
        }
        if (level instanceof ServerLevel server) { setChanged(); CabinetLinks.loaded(server,worldPosition,cabinetId); }
    }

    @Override public void onChunkUnloaded() {
        if (level instanceof ServerLevel server) ServerCabinets.removed(server, worldPosition, cabinetId);
        super.onChunkUnloaded();
    }

    public void setSkin(String hash, String name) {
        String normalizedHash = hash == null ? "" : hash;
        String normalizedName = name == null ? "" : name.strip();
        if (!normalizedHash.isEmpty()
                && !normalizedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
        if (normalizedHash.equals(skinHash)
                && normalizedName.equals(skinName)) {
            return;
        }
        skinHash = normalizedHash;
        skinName = normalizedName;
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
        // Disk loads/server-side NBT can never restore an active runtime session.
        visualPowered=level!=null&&level.isClientSide&&tag.getBoolean("VisualCabinetPowered");
        displayProfile=cn.piq.fcarcade.cabinet.CabinetGameProfile.EMPTY;
        if(level!=null&&level.isClientSide)try{displayProfile=cn.piq.fcarcade.cabinet.CabinetGameProfiles.read(tag.getCompound("VisualGameProfile"));}catch(IllegalArgumentException ignored){}
        adminDefaultsInitialized=true;
        placedDefaultMode=tag.contains("PlacedSyncDefault",3)&&cn.piq.fcarcade.config.GameConsoleAdminPolicy.validMode(tag.getInt("PlacedSyncDefault"))?tag.getInt("PlacedSyncDefault"):-1;
        observationRange=cn.piq.fcarcade.config.GameConsoleAdminPolicy.persistedRange(tag.getInt("ConsoleWatchRange"),0);
        String loadedHash = tag.getString(SKIN_HASH_TAG);
        skinHash = loadedHash.matches("[0-9a-f]{64}") ? loadedHash : "";
        skinName = tag.getString(SKIN_NAME_TAG);
        if (tag.hasUUID("CabinetId")) cabinetId = tag.getUUID("CabinetId");
        String backend = tag.getString("CabinetBackend");
        ResourceLocation parsed = backend.length() <= 128 ? ResourceLocation.tryParse(backend) : null;
        // Keep valid unknown IDs while an addon is absent; never silently boot its ROM with NES.
        cabinetBackend = parsed == null ? CabinetBackends.NES : parsed;
        coinRequired = tag.getBoolean("CoinRequired");
        autoPowerOffOnExit = !tag.contains("AutoPowerOffOnExit") || tag.getBoolean("AutoPowerOffOnExit");
        idleShutdownSeconds = cn.piq.fcarcade.cabinet.CabinetPowerSettings.seconds(tag.getInt("IdleShutdownSeconds"));
        screenRenderDistance = cn.piq.fcarcade.cabinet.CabinetPowerSettings.renderDistance(tag.getInt("ScreenRenderDistance"));
        visualLinkId=null;visualLinkPeer=null;
        var visual=tag.getCompound("VisualCabinetLink");
        if(visual.hasUUID("Pair")&&visual.hasUUID("Id")&&visual.contains("X",3)&&visual.contains("Y",3)&&visual.contains("Z",3))try{
            var dimension=ResourceLocation.tryParse(visual.getString("Dimension"));
            if(dimension!=null){var peer=new CabinetTarget(dimension,new BlockPos(visual.getInt("X"),visual.getInt("Y"),visual.getInt("Z")),visual.getUUID("Id"),visual.getBoolean("Dual"));
                if(validVisualPeer(peer)){visualLinkId=visual.getUUID("Pair");visualLinkPeer=peer;}}
        }catch(IllegalArgumentException ignored){/* Malformed visual tags never affect link authority. */}
    }

    @Override
    protected void saveAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.saveAdditional(tag, registries);
        if(placedDefaultMode>=0)tag.putInt("PlacedSyncDefault",placedDefaultMode);
        if(observationRange!=0)tag.putInt("ConsoleWatchRange",observationRange);
        tag.putUUID("CabinetId", cabinetId);
        tag.putString("CabinetBackend", cabinetBackend.toString());
        tag.putBoolean("CoinRequired", coinRequired);
        tag.putBoolean("AutoPowerOffOnExit", autoPowerOffOnExit);
        tag.putInt("IdleShutdownSeconds",idleShutdownSeconds);
        tag.putInt("ScreenRenderDistance",screenRenderDistance);
        if (!skinHash.isEmpty()) {
            tag.putString(SKIN_HASH_TAG, skinHash);
            tag.putString(SKIN_NAME_TAG, skinName);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag=saveWithoutMetadata(registries);
        tag.putBoolean("VisualCabinetPowered",visualPowered);
        tag.put("VisualGameProfile",cn.piq.fcarcade.cabinet.CabinetGameProfiles.write(cn.piq.fcarcade.cabinet.CabinetGameProfiles.forCabinet(this)));
        if(visualLinkId!=null&&validVisualPeer(visualLinkPeer)){
            var peer=visualLinkPeer;var visual=new CompoundTag();visual.putUUID("Pair",visualLinkId);visual.putUUID("Id",peer.identity());
            visual.putString("Dimension",peer.dimension().toString());visual.putInt("X",peer.anchor().getX());visual.putInt("Y",peer.anchor().getY());visual.putInt("Z",peer.anchor().getZ());visual.putBoolean("Dual",peer.dual());tag.put("VisualCabinetLink",visual);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }
}
