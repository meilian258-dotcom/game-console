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

    /** Call after the addon's own cartridge transaction; does not grant link mutation. */
    public final void notifyHardwareChanged() {
        if (level instanceof ServerLevel serverLevel && serverLevel.getServer().isSameThread() && !isRemoved()
                && serverLevel.hasChunkAt(worldPosition) && serverLevel.getBlockEntity(worldPosition) == this) {
            changed();
            HomeApplianceService.cartridgeChanged(serverLevel, worldPosition);
        }
    }
}
