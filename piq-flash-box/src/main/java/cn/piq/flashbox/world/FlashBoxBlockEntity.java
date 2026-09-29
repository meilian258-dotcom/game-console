package cn.piq.flashbox.world;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.flashbox.FlashBoxMod;
import cn.piq.flashbox.registry.FlashBoxRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Owns only public physical identity/link state, never local paths or SWF contents. */
public final class FlashBoxBlockEntity extends ExternalHomeConsoleBlockEntity {
    public static final ResourceLocation SYSTEM_ID = ResourceLocation.fromNamespaceAndPath(FlashBoxMod.MOD_ID, "flash");
    public FlashBoxBlockEntity(BlockPos pos, BlockState state) { super(FlashBoxRegistries.BOX_ENTITY.get(), pos, state, SYSTEM_ID); }
    // No cartridge mechanic in this prototype; do not claim an empty cartridge powers a public session.
    @Override public boolean hasInsertedCartridge() { return true; }
}
