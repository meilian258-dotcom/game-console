package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.world.DualCabinetBlock;
import cn.piq.fcarcade.world.DualCabinetStructure;
import cn.piq.fcarcade.world.LegacyFcArcadeBlock;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import java.util.Objects;
import java.util.UUID;

/** Stable physical identity, never just a reusable world coordinate. */
public record CabinetTarget(ResourceLocation dimension, BlockPos anchor, UUID identity, boolean dual) {
    public CabinetTarget {
        Objects.requireNonNull(dimension); Objects.requireNonNull(anchor); Objects.requireNonNull(identity);
        if (dimension.toString().length() > 128) throw new IllegalArgumentException("Dimension id too long");
        anchor = anchor.immutable();
    }

    /** Does not load chunks and accepts only the two generic main-mod cabinets. */
    public static CabinetTarget resolve(Level level, BlockPos clicked) {
        if (level == null || clicked == null || !level.hasChunkAt(clicked)) return null;
        BlockPos dualAnchor = DualCabinetStructure.resolveAnchor(level, clicked);
        BlockPos portraitAnchor = cn.piq.fcarcade.world.PortraitCabinetBlock.resolveAnchor(level,clicked);
        BlockPos anchor = dualAnchor == null ? (portraitAnchor==null?clicked:portraitAnchor) : dualAnchor;
        if (!level.hasChunkAt(anchor) || !(level.getBlockEntity(anchor) instanceof LegacyFcArcadeBlockEntity entity)) return null;
        var block = level.getBlockState(anchor).getBlock();
        boolean dual = block instanceof DualCabinetBlock;
        if (!dual && !(block instanceof LegacyFcArcadeBlock) && portraitAnchor==null) return null;
        if (dual && !DualCabinetStructure.complete(level, anchor)) return null;
        return new CabinetTarget(level.dimension().location(), anchor, entity.cabinetId(), dual);
    }

    public boolean matches(Level level) {
        return level != null && dimension.equals(level.dimension().location()) && equals(resolve(level, anchor));
    }
}
