// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.events;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted on the server thread only after complete identity and protection checks.
 * A client-only integrated-server listener may schedule openAt, rechecking assemblyId after dispatch.
 * This is not a multiplayer launch packet and carries no ROM path or native command. */
public final class NativeCabinetUseEvent extends Event {
    private final ServerLevel level;private final ServerPlayer player;private final BlockPos anchor;private final UUID assemblyId;
    private boolean handled;
    public NativeCabinetUseEvent(ServerLevel level,ServerPlayer player,BlockPos anchor,UUID assemblyId){this.level=level;this.player=player;this.anchor=anchor.immutable();this.assemblyId=assemblyId;}
    public ServerLevel level(){return level;}public ServerPlayer player(){return player;}public BlockPos anchor(){return anchor;}public UUID assemblyId(){return assemblyId;}
    public boolean handled(){return handled;}public void markHandled(){handled=true;}
}
