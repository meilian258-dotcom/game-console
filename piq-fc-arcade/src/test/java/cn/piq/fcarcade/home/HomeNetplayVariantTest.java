package cn.piq.fcarcade.home;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeNetplayVariantTest {
    @Test void oldNbtKeepsEmptyVariantWithoutChangingItsModeOrJniFlag(){
        var tag=new CompoundTag();tag.putBoolean("NetplayExperimental",true);tag.putBoolean("NetplayJniTrial",true);
        assertEquals("",HomeConsoleBlockEntity.VariantData.read(tag));
        assertTrue(tag.getBoolean("NetplayExperimental"));assertTrue(tag.getBoolean("NetplayJniTrial"));
    }
    @Test void declaredAndUnknownVariantsRoundTripExactlyAndStayPerDevice(){
        for(String value:new String[]{"","fceumm-v1","future-upstream-core-v9"}){
            var tag=new CompoundTag();tag.putString("HomeSyncMode","LOCAL_SYNC");
            HomeConsoleBlockEntity.VariantData.write(tag,value);
            assertEquals(value,HomeConsoleBlockEntity.VariantData.read(tag.copy()));
            assertEquals("",HomeConsoleBlockEntity.VariantData.read(new CompoundTag()));
            assertEquals("LOCAL_SYNC",tag.getString("HomeSyncMode"));
        }
    }
}
