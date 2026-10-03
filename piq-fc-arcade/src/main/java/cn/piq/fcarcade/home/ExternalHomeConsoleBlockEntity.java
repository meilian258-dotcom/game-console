package cn.piq.fcarcade.home;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** External cartridge/session ownership; the base alone owns the AV link and refund. */
public abstract class ExternalHomeConsoleBlockEntity extends HomeEndpointBlockEntity {
    private final ResourceLocation systemId;

    protected ExternalHomeConsoleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                             ResourceLocation systemId) {
        super(type, pos, state);
        this.systemId = Objects.requireNonNull(systemId);
        if (HomeSystems.NES_SYSTEM.equals(systemId)) throw new IllegalArgumentException("NES system ID is reserved");
    }

    @Override final HomeLinkLedger.Kind kind() { return HomeLinkLedger.Kind.CONSOLE; }
    public final ResourceLocation systemId() { return systemId; }
    public final BlockPos televisionPos() { return peerPos(); }
    /** Single-cell default. Overrides must only inspect already loaded hardware. */
    public boolean isHardwareComplete() { return true; }
    /** Conservative default preserves unadapted addons: only an explicit empty slot can enter idle power. */
    public boolean hasInsertedCartridge() { return true; }
    /** Explicit opt-in by adapted addons; old addons remain unable to select Netplay. */
    public boolean netplayExperimental(){return false;}
    public void netplayExperimental(boolean value){if(value)throw new UnsupportedOperationException("Netplay not supported");}
    /** Separate from the RetroArch lane. Adapted addons must persist this choice themselves. */
    public boolean netplayJniTrial(){return false;}
    public void netplayJniTrial(boolean value){if(value)throw new UnsupportedOperationException("JNI Netplay not supported");}

    /** Addon migration for a formerly unavailable mode; never changes an existing supported choice.
     * This is not a player permission bypass or a way to enable a server-disabled runtime. */
    protected final boolean useSupportedSynchronizationFallback(cn.piq.fcarcade.cabinet.CabinetSyncMode fallback) {
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||isRemoved())return false;
        var hooks=HomeSystems.applianceHooks(systemId);
        if(hooks==null||!hooks.synchronizationSettingsAvailable()||hooks.isRunning(server,this)||hooks.pendingStart(server,this))return false;
        int supported=hooks.synchronizationSupportedModes(server,this);
        if((supported&(1<<synchronizationMode().ordinal()))!=0||(supported&(1<<fallback.ordinal()))==0||netplayExperimental()||netplayJniTrial())return false;
        synchronizationMode(fallback);return true;
    }

    /** Call after the addon's own cartridge transaction; does not grant link mutation. */
    public final void notifyHardwareChanged() {
        if (level instanceof ServerLevel serverLevel && serverLevel.getServer().isSameThread() && !isRemoved()
                && serverLevel.hasChunkAt(worldPosition) && serverLevel.getBlockEntity(worldPosition) == this) {
            changed();
            HomeApplianceService.cartridgeChanged(serverLevel, worldPosition);
        }
    }
}
