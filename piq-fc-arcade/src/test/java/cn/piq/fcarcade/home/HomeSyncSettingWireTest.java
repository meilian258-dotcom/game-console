package cn.piq.fcarcade.home;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeSyncSettingWireTest {
    private static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    private static HomeSyncNetwork.Setting setting(List<String> reasons,int mode,int mask){
        return new HomeSyncNetwork.Setting(UUID.randomUUID(),7,ResourceLocation.parse("minecraft:overworld"),new BlockPos(1,2,3),UUID.randomUUID(),"MD",
                mode,mask,true,"已读取",true,true,false,true,true,reasons);
    }
    @Test void roundTripsEachModeAndAuthorityWithFiveDistinctReasons(){
        var reasons=List.of("", "附属未实现本地同步", "附属未实现托管", "尚未实现Netplay", "不适用");
        for(int mode=0;mode<5;mode++)for(int mask=0;mask<32;mask++){
            var expected=setting(reasons,mode,mask);var b=buffer();
            try{HomeSyncNetwork.Setting.CODEC.encode(b,expected);assertEquals(expected,HomeSyncNetwork.Setting.CODEC.decode(b));assertEquals(0,b.readableBytes());}
            finally{b.release();}
        }
    }
    @Test void truncatedPacketsRejectIncludingLegacyWithoutReasons(){
        var b=buffer();
        try{
            HomeSyncNetwork.Setting.CODEC.encode(b,setting(List.of("a","b","c","d","e"),0,1));
            byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);
            for(int length=0;length<bytes.length;length++){
                var cut=buffer();try{cut.writeBytes(bytes,0,length);assertThrows(RuntimeException.class,()->HomeSyncNetwork.Setting.CODEC.decode(cut));}finally{cut.release();}
            }
        }finally{b.release();}
    }
    @Test void fixedCountLengthAndDefensiveCopy(){
        assertThrows(IllegalArgumentException.class,()->setting(List.of("a"),0,1));
        assertThrows(IllegalArgumentException.class,()->setting(Collections.nCopies(6,""),0,1));
        assertThrows(IllegalArgumentException.class,()->setting(Collections.nCopies(5,"x".repeat(257)),0,1));
        var mutable=new ArrayList<>(Collections.nCopies(5,"valid"));var value=setting(mutable,0,1);mutable.set(0,"changed");
        assertEquals("valid",value.modeReasons().get(0));assertThrows(UnsupportedOperationException.class,()->value.modeReasons().set(0,"changed"));
    }
    @Test void maximumUnicodeReasonsRemainBelowEightKiB(){
        var value=new HomeSyncNetwork.Setting(UUID.randomUUID(),Integer.MAX_VALUE,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"界".repeat(128),4,31,true,"界".repeat(256),false,true,true,true,false,Collections.nCopies(5,"界".repeat(256)));
        var b=buffer();try{HomeSyncNetwork.Setting.CODEC.encode(b,value);assertTrue(b.readableBytes()<8192);assertEquals(value,HomeSyncNetwork.Setting.CODEC.decode(b));}finally{b.release();}
    }
    @Test void oversizedReasonOnWireRejected(){
        var b=buffer();try{
            HomeSyncNetwork.Setting.CODEC.encode(b,setting(Collections.nCopies(5,""),0,1));
            b.writerIndex(b.writerIndex()-5);b.writeUtf("x".repeat(257));
            for(int i=0;i<4;i++)b.writeUtf("");
            assertThrows(RuntimeException.class,()->HomeSyncNetwork.Setting.CODEC.decode(b));
        }finally{b.release();}
    }
}
