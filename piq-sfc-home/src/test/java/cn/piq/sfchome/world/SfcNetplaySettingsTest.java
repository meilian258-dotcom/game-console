package cn.piq.sfchome.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcNetplaySettingsTest {
    @Test void legacyFlagAlreadyMeantJniAndAbsentOldDevicesStayLocal(){
        var tag=new CompoundTag();assertFalse(SfcNetplaySettings.read(tag));
        tag.putBoolean("SfcNetplayExperimental",true);assertTrue(SfcNetplaySettings.read(tag));
        tag.putBoolean("SfcNetplayExperimental",false);assertFalse(SfcNetplaySettings.read(tag));
    }
    @Test void newExplicitChoiceOverridesLegacyAndMalformedNewFlagDoesNotReenable(){
        var tag=new CompoundTag();tag.putBoolean("SfcNetplayExperimental",true);
        tag.putBoolean("SfcNetplayJni",false);assertFalse(SfcNetplaySettings.read(tag));
        tag.putString("SfcNetplayJni","true");assertFalse(SfcNetplaySettings.read(tag));
        tag.putBoolean("SfcNetplayJni",true);assertTrue(SfcNetplaySettings.read(tag));
    }
    @Test void roundTripMirrorsTheSameOldRouteWithoutChangingSaveOrHardwareIdentity(){
        var tag=new CompoundTag();tag.putString("HardwareId","existing-hardware");tag.putString("SfcSave","existing-slot");
        for(boolean enabled:new boolean[]{true,false}){
            SfcNetplaySettings.write(tag,enabled);
            assertEquals(enabled,SfcNetplaySettings.read(tag));assertEquals(enabled,tag.getBoolean("SfcNetplayExperimental"));
            assertEquals("existing-hardware",tag.getString("HardwareId"));assertEquals("existing-slot",tag.getString("SfcSave"));
        }
    }
    @Test void capabilitiesAdvertiseOnlyActualJniAndRespectTheExistingServerPolicies(){
        for(boolean media:new boolean[]{false,true})for(boolean local:new boolean[]{false,true})for(boolean hosted:new boolean[]{false,true}){
            int mask=SfcNetplaySettings.supportedModes(media,local,hosted);
            assertEquals(media,(mask&1)!=0);assertEquals(0,mask&2);assertEquals(hosted,(mask&4)!=0);
            assertEquals(0,mask&8);assertEquals(local,(mask&16)!=0);
        }
    }
}
