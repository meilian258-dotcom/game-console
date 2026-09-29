package cn.piq.pvz.world;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.pvz.PvzMod;
import cn.piq.pvz.registry.PvzRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Owns only public physical identity/link state, never local paths or PvZ contents. */
public final class PvzBlockEntity extends ExternalHomeConsoleBlockEntity {
    public static final ResourceLocation SYSTEM_ID = ResourceLocation.fromNamespaceAndPath(PvzMod.MOD_ID, "pvz");
    public PvzBlockEntity(BlockPos pos, BlockState state) { super(PvzRegistries.BOX_ENTITY.get(), pos, state, SYSTEM_ID); }
    // No cartridge mechanic in this prototype; do not claim an empty cartridge powers a public session.
    @Override public boolean hasInsertedCartridge() { return true; }
}
