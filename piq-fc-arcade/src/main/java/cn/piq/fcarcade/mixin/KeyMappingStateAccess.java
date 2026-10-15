// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Local transient state only: never changes a key binding or options.txt. */
@Mixin(KeyMapping.class)
public interface KeyMappingStateAccess {
    @Accessor("isDown") void piq$setDown(boolean down);
    @Accessor("clickCount") void piq$setClicks(int clicks);
}
