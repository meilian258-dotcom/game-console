package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.ControllerCapturePolicy;
import cn.piq.fcarcade.client.PrivateHomeClient;
import cn.piq.fcarcade.client.privateplay.PrivateEngine;
import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.client.KeyboardInput;
import cn.piq.sfchome.data.SfcControllerData;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.file.Path;
import java.util.UUID;

/** Reads only an existing physical controller receipt; never grants a public runtime seat. */
public final class SfcPrivateProvider implements PrivateHomeClient.Provider {
    @Override public String label(){return "SFC";}
    @Override public String storageKey(){return "sfc";}
    @Override public boolean publicBusy(){return SfcHomeClient.currentSession()!=null;}
    @Override public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.SFC;}
    @Override public int[][] keys(){return KeyboardInput.keys(SfcHomeKeys.KEYS);}
    @Override public PrivateEngine create(Path rom,Path saveRoot){SfcLocalWatchClient.controlStarting();return new SfcPrivateEngine(rom,saveRoot);}
    @Override public boolean supportsJniTrial(){return true;}
    @Override public PrivateEngine create(Path rom,Path saveRoot,cn.piq.retro.libretro.LibretroRuntimes.Backend backend){
        SfcLocalWatchClient.controlStarting();return new SfcPrivateEngine(rom,saveRoot,backend);
    }
    /** Raw physical identity only for duplicate detection: malformed/count-two copies still count. */
    @Override public UUID identity(ItemStack stack){
        if(!SfcControllerData.isController(stack))return null;
        var data=stack.get(DataComponents.CUSTOM_DATA);if(data==null)return null;
        var tag=data.copyTag();return tag.hasUUID("SfcLease")?tag.getUUID("SfcLease"):null;
    }
    @Override public UUID lease(ItemStack stack){
        if(!SfcControllerData.isController(stack)||stack.getCount()!=1)return null;
        var data=stack.get(DataComponents.CUSTOM_DATA);if(data==null)return null;
        var tag=data.copyTag();
        if(!tag.hasUUID("SfcLease")||!tag.contains("SfcPort",Tag.TAG_INT))return null;
        int port=tag.getInt("SfcPort");if(port<0||port>1)return null;
        UUID lease=tag.getUUID("SfcLease");
        return lease.getMostSignificantBits()==0&&lease.getLeastSignificantBits()==0?null:lease;
    }
    @Override public boolean matches(Player player,ItemStack stack,BlockEntity endpoint){
        UUID lease=lease(stack);
        if(lease==null||player==null||!player.isAlive()||player.isSpectator()
                ||!(endpoint instanceof SfcHomeConsoleBlockEntity console)||console.isRemoved()
                ||console.getLevel()!=player.level()||console.visualPowered()||SfcHomeClient.currentSession()!=null)return false;
        // lease() already requires the typed port: absent or differently typed NBT must never become P1.
        int port=stack.get(DataComponents.CUSTOM_DATA).copyTag().getInt("SfcPort");
        return ControllerCapturePolicy.receipt(player.getUUID(),lease,port,console.controllerVisualPlayer(port),
                console.controllerVisualLease(port),console.controllerDocked(port),player.distanceToSqr(console.getBlockPos().getCenter()));
    }
}
