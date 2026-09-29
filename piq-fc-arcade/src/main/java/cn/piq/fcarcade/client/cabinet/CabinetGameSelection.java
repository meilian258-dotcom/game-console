package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetRomBindings;
import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Shared by generic and legacy addon cabinets; only the client config holds paths. */
public final class CabinetGameSelection {
    private static final CabinetRomBindings STORE=new CabinetRomBindings(FMLPaths.CONFIGDIR.get().resolve("piq-retro-game-selection.dat"));
    private CabinetGameSelection(){}
    /** Capture on the client thread. No active connection/world means no usable selection key. */
    public static Optional<CabinetRomBindings.Key> key(ResourceLocation dimension,UUID device,ResourceLocation backend){
        var mc=Minecraft.getInstance();
        if(mc.getConnection()==null||mc.level==null||mc.player==null||dimension==null||device==null||backend==null
                ||!mc.level.dimension().location().equals(dimension))return Optional.empty();
        String context;
        var server=mc.getSingleplayerServer();
        if(server!=null)context="world:"+server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        else{
            var remote=mc.getCurrentServer();
            if(remote==null||remote.ip==null||remote.ip.isBlank())return Optional.empty();
            context="server:"+remote.ip.strip(); // Keep explicit protocol/address/port; no DNS or default-server fallback.
        }
        try{return Optional.of(new CabinetRomBindings.Key(context,dimension.toString(),device,backend.toString()));}
        catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    /** Worker-thread file I/O only. A stored path is not trusted game content. */
    public static Optional<Path> load(CabinetRomBindings.Key key)throws IOException{
        try{return STORE.load(key).map(path->cn.piq.retro.storage.ConsoleStorage.rebind(FMLPaths.GAMEDIR.get(),path));}
        catch(java.io.UncheckedIOException failure){throw failure.getCause();}
    }
    /** Worker-thread call only, after an explicit picker confirmation and current-session recheck. */
    public static void remember(CabinetRomBindings.Key key,Path rom)throws IOException{STORE.remember(key,rom);}
    public static void validate(Path rom,Set<String> extensions,Set<String> excluded)throws IOException{
        LocalRomLibrary.validateFile(rom);
        if(!Files.isReadable(rom))throw new IOException("Game file is not readable");
        String name=rom.getFileName().toString().toLowerCase(Locale.ROOT);
        if(extensions==null||extensions.isEmpty()||extensions.size()>16)throw new IOException("Provider game extensions are unavailable");
        boolean matches=false;
        for(String extension:extensions){
            if(extension==null||!extension.matches("\\.?[A-Za-z0-9]{1,12}"))throw new IOException("Invalid provider game extension");
            String suffix=(extension.startsWith(".")?extension:"."+extension).toLowerCase(Locale.ROOT);
            matches|=name.endsWith(suffix);
        }
        if(!matches)throw new IOException("Saved game does not match this emulator");
        if(excluded!=null&&excluded.stream().filter(Objects::nonNull).anyMatch(value->value.toLowerCase(Locale.ROOT).equals(name)))
            throw new IOException("System/BIOS files cannot be selected as games");
    }
}
