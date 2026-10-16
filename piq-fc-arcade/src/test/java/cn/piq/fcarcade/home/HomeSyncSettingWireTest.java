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
    @Test void secondPortStateAndCapabilityRoundTripIndependentlyOfOldApproval(){
        for(boolean allowed:new boolean[]{false,true})for(boolean supported:new boolean[]{false,true})for(boolean approval:new boolean[]{false,true}){
            var expected=new HomeSyncNetwork.Setting(UUID.randomUUID(),1,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"SFC",4,18,true,"",true,true,approval,true,true,Collections.nCopies(5,""),allowed,supported);
            var b=buffer();try{HomeSyncNetwork.Setting.CODEC.encode(b,expected);assertEquals(expected,HomeSyncNetwork.Setting.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
        }
    }
    @Test void secondPortRequestsRoundTripAndRejectMixedOrTruncatedWire(){
        for(int allowed=0;allowed<2;allowed++){
            var expected=new HomeSyncNetwork.Request(UUID.randomUUID(),5,-1,-1,-1,allowed);var b=buffer();
            try{HomeSyncNetwork.Request.CODEC.encode(b,expected);assertEquals(expected,HomeSyncNetwork.Request.CODEC.decode(b));assertEquals(0,b.readableBytes());
                b.readerIndex(0);b.writerIndex(b.writerIndex()-4);assertThrows(RuntimeException.class,()->HomeSyncNetwork.Request.CODEC.decode(b));
            }finally{b.release();}
        }
        assertEquals(-1,new HomeSyncNetwork.Request(UUID.randomUUID(),0,4,-1,-1).secondPort());
        var b=buffer();try{b.writeUUID(UUID.randomUUID());b.writeVarInt(1);b.writeInt(4);b.writeInt(-1);b.writeInt(-1);b.writeInt(1);assertThrows(IllegalArgumentException.class,()->HomeSyncNetwork.Request.CODEC.decode(b));}finally{b.release();}
    }
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
        assertThrows(IllegalArgumentException.class,()->setting(Collections.nCopies(7,""),0,1));
        assertThrows(IllegalArgumentException.class,()->setting(Collections.nCopies(5,"x".repeat(257)),0,1));
        var mutable=new ArrayList<>(Collections.nCopies(5,"valid"));var value=setting(mutable,0,1);mutable.set(0,"changed");
        assertEquals("valid",value.modeReasons().get(0));assertThrows(UnsupportedOperationException.class,()->value.modeReasons().set(0,"changed"));
        assertEquals(6,value.modeReasons().size());assertFalse(value.modeReasons().get(5).isBlank());
        assertEquals("",value.netplayPadVariant());
        assertThrows(IllegalArgumentException.class,()->setting(Collections.nCopies(5,""),0,32));
    }
    @Test void maximumUnicodeReasonsRemainBelowEightKiB(){
        var value=new HomeSyncNetwork.Setting(UUID.randomUUID(),Integer.MAX_VALUE,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"界".repeat(128),5,63,true,"界".repeat(256),false,true,true,true,false,Collections.nCopies(6,"界".repeat(256)),true,true,"界".repeat(128));
        var b=buffer();try{HomeSyncNetwork.Setting.CODEC.encode(b,value);assertTrue(b.readableBytes()<8192);assertEquals(value,HomeSyncNetwork.Setting.CODEC.decode(b));}finally{b.release();}
    }
    @Test void oversizedReasonOnWireRejected(){
        var b=buffer();try{
            var value=new HomeSyncNetwork.Setting(UUID.randomUUID(),1,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"FC",5,32,true,"",false,true,false,true,true,Collections.nCopies(6,""),true,true,"");
            HomeSyncNetwork.Setting.CODEC.encode(b,value);
            // Empty reasons occupy one byte each, followed by two booleans and the empty variant.
            b.writerIndex(b.writerIndex()-9);b.writeUtf("x".repeat(257));
            for(int i=0;i<5;i++)b.writeUtf("");
            b.writeBoolean(true);b.writeBoolean(true);b.writeUtf("");
            assertThrows(RuntimeException.class,()->HomeSyncNetwork.Setting.CODEC.decode(b));
        }finally{b.release();}
    }
    @Test void officialModeAndUnknownVariantRoundTripWithoutFallback(){
        for(String variant:List.of("fceumm-v1","unknown-future-core")){
            var value=new HomeSyncNetwork.Setting(UUID.randomUUID(),9,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"FC",
                    5,61,true,"独立保存",false,true,false,true,true,Collections.nCopies(6,""),true,true,variant);
            var b=buffer();try{HomeSyncNetwork.Setting.CODEC.encode(b,value);assertEquals(value,HomeSyncNetwork.Setting.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
        }
        var request=new HomeSyncNetwork.Request(UUID.randomUUID(),4,5,-1,-1);
        var b=buffer();try{HomeSyncNetwork.Request.CODEC.encode(b,request);assertEquals(request,HomeSyncNetwork.Request.CODEC.decode(b));}finally{b.release();}
    }
    @Test void variantIsBoundedAndEveryOfficialPacketTruncationIsRejected(){
        var value=new HomeSyncNetwork.Setting(UUID.randomUUID(),1,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"FC",5,32,true,"",false,true,false,true,true,Collections.nCopies(6,""),true,true,"unknown-future-core");
        var b=buffer();try{
            HomeSyncNetwork.Setting.CODEC.encode(b,value);
            byte[] bytes=new byte[b.readableBytes()];b.getBytes(0,bytes);
            for(int length=0;length<bytes.length;length++){
                var cut=buffer();try{cut.writeBytes(bytes,0,length);assertThrows(RuntimeException.class,()->HomeSyncNetwork.Setting.CODEC.decode(cut));}finally{cut.release();}
            }
            // Replace the complete final UTF field, ensuring the decoder rejects its own size bound.
            b.writerIndex(b.writerIndex()-value.netplayPadVariant().length()-1);b.writeUtf("x".repeat(129));
            assertThrows(RuntimeException.class,()->HomeSyncNetwork.Setting.CODEC.decode(b));
        }finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new HomeSyncNetwork.Setting(UUID.randomUUID(),1,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"FC",5,32,true,"",false,true,false,true,true,Collections.nCopies(6,""),true,true,"x".repeat(129)));
    }
}
