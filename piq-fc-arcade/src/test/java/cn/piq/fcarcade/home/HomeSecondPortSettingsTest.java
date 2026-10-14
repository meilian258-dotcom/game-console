package cn.piq.fcarcade.home;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production NBT adapter without booting a world or a native core. */
class HomeSecondPortSettingsTest {
    @Test void missingDefaultsAllowIndependentlyOfEveryLegacyApprovalValue(){
        var tag=new CompoundTag();assertTrue(HomeEndpointBlockEntity.Defaults.secondPort(tag));
        for(boolean legacy:new boolean[]{false,true}){
            tag.putBoolean("HomeJoinApproval",legacy);
            assertTrue(HomeEndpointBlockEntity.Defaults.secondPort(tag));
        }
    }
    @Test void explicitValuesRoundTripWithoutChangingLegacyModeOrApproval(){
        for(boolean allowed:new boolean[]{false,true})for(boolean legacy:new boolean[]{false,true}){
            var tag=new CompoundTag();tag.putBoolean("HomeJoinApproval",legacy);tag.putString("HomeSyncMode","MEDIA");
            HomeEndpointBlockEntity.Defaults.secondPort(tag,allowed);
            var reloaded=tag.copy();assertEquals(allowed,HomeEndpointBlockEntity.Defaults.secondPort(reloaded));
            assertEquals(legacy,reloaded.getBoolean("HomeJoinApproval"));assertEquals("MEDIA",reloaded.getString("HomeSyncMode"));
        }
    }
    @Test void malformedPresentDataFailsClosed(){
        var tag=new CompoundTag();tag.putString("HomeSecondPortAllowed","true");assertFalse(HomeEndpointBlockEntity.Defaults.secondPort(tag));
        tag.putInt("HomeSecondPortAllowed",1);assertFalse(HomeEndpointBlockEntity.Defaults.secondPort(tag));
        tag.put("HomeSecondPortAllowed",new CompoundTag());assertFalse(HomeEndpointBlockEntity.Defaults.secondPort(tag));
    }
    @Test void separateMachinesAndSuccessiveChoicesDoNotShareAllowance(){
        var one=new CompoundTag();var two=new CompoundTag();HomeEndpointBlockEntity.Defaults.secondPort(one,false);
        assertFalse(HomeEndpointBlockEntity.Defaults.secondPort(one));assertTrue(HomeEndpointBlockEntity.Defaults.secondPort(two));
        HomeEndpointBlockEntity.Defaults.secondPort(one,true);HomeEndpointBlockEntity.Defaults.secondPort(two,false);
        assertTrue(HomeEndpointBlockEntity.Defaults.secondPort(one));assertFalse(HomeEndpointBlockEntity.Defaults.secondPort(two));
    }
}
