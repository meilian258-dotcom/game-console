// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.events;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/** Close only this level/anchor/identity. Unloading stops playback, not ownership or drops. */
public final class NativeCabinetClosedEvent extends Event {
    private final ServerLevel level;private final BlockPos anchor;private final UUID assemblyId;
    public NativeCabinetClosedEvent(ServerLevel level,BlockPos anchor,UUID assemblyId){this.level=level;this.anchor=anchor.immutable();this.assemblyId=assemblyId;}
    public ServerLevel level(){return level;}public BlockPos anchor(){return anchor;}public UUID assemblyId(){return assemblyId;}
}
