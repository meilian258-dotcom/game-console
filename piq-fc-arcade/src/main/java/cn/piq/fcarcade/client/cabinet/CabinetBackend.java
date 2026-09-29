// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.cabinet.CabinetRomBindings;
import cn.piq.retro.api.RetroEmulatorFactory;
import net.minecraft.network.Connection;
import java.util.UUID;
import java.nio.file.Path;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;

/** Register only during client setup. open runs off the game thread and returns a bounded async core. */
public interface CabinetBackend {
    String description();
    String extensions();
    default String unavailableReason(){return null;}
    /** Mode-specific runtime availability; legacy adapters preserve their existing check. */
    default String syncUnavailableReason(){return unavailableReason();}
    default String netplayUnavailableReason(){return "此附属尚未接入 Netplay";}
    record NetplayContent(cn.piq.fcarcade.netplay.NetplayProfile profile,byte[] rom,java.util.Map<String,byte[]> auxiliary){}
    @FunctionalInterface interface NetplayFactory {NetplayContent open(Path rom)throws Exception;}
    /** Main-thread capture, off-thread bounded content validation. No new input authority. */
    default NetplayFactory prepareNetplayFactory(){throw new UnsupportedOperationException("Netplay unsupported");}
    default Path defaultRom(){return null;}
    /** Explicit directory and suffix metadata; never parse the human-readable extensions() label. */
    default Path romDirectory(){Path fallback=defaultRom();return fallback==null?cn.piq.retro.storage.ConsoleStorage.root(FMLPaths.GAMEDIR.get()).resolve("piq-cabinet/roms"):fallback.toAbsolutePath().getParent();}
    default Set<String> romExtensions(){return Set.of(".nes",".sfc",".smc",".zip");}
    default Set<String> romExcludedNames(){return Set.of();}
    /** Capture immutable per-launch context on the client thread, before any worker I/O.
     * The returned factory must not fetch a later Minecraft world/player/connection.
     * Existing adapters keep their registered factory and behavior unchanged. */
    default RetroEmulatorFactory prepareFactory(RetroEmulatorFactory registered, CabinetRomBindings.Key selection,
                                                UUID playerId, Connection connection) throws Exception {return registered;}
    @FunctionalInterface interface SyncFactory {
        cn.piq.fcarcade.cabinet.CabinetSyncCore open(Path rom) throws Exception;
        /** Cancellation signal may run on the client thread before open() has returned. */
        default void requestClose() {}
    }
    /** Capture immutable launch paths/context on the client thread. No core or blocking I/O here. */
    default SyncFactory prepareSyncFactory(CabinetRomBindings.Key selection, UUID playerId,
                                          Connection connection) throws Exception {return this::openSync;}
    CabinetEmulator open(Path rom) throws Exception;
    /** Dedicated synchronous worker only; existing async adapters explicitly remain media-only. */
    default cn.piq.fcarcade.cabinet.CabinetSyncCore openSync(Path rom) throws Exception {
        throw new UnsupportedOperationException("This backend has not verified deterministic local synchronization");
    }
}
