package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetNetplayVariantTest {
    private static final ResourceLocation BACKEND=ResourceLocation.parse("test:trusted_variant");
    private static final CabinetTarget TARGET=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),true);
    private NetplayVariant variant(String id,boolean saves){return new NetplayVariant(id,id,new NetplayProfile(getClass(),"/test.dll","a".repeat(64),"contract.zip",Map.of(),1,48000,1024,4),saves);}
    private void register(){CabinetNetplay.registerVariants(BACKEND,"legacy",List.of(variant("legacy",true),variant("retroarch-stock-v1",false)));}
    @Test void serverDefaultIsLegacyAndStrictRegisteredCatalogRejectsUnknownOrMissingContract(){
        register();assertEquals("legacy",CabinetNetplay.defaultVariant(BACKEND));assertEquals(2,CabinetNetplay.variants(BACKEND).size());
        var selected=CabinetNetplay.variant(BACKEND,"retroarch-stock-v1");assertNotNull(selected);assertNull(CabinetNetplay.variant(BACKEND,"unknown"));
        assertDoesNotThrow(()->CabinetNetplay.verify(BACKEND,selected.launch(),selected.profile("dino.zip")));
        assertThrows(IllegalArgumentException.class,()->CabinetNetplay.verify(BACKEND,NetplayLaunchSpec.UNSPECIFIED,selected.profile("dino.zip")));
        assertThrows(IllegalArgumentException.class,()->CabinetNetplay.verify(BACKEND,new NetplayLaunchSpec("unknown","a".repeat(64),false),selected.profile("dino.zip")));
        assertThrows(IllegalArgumentException.class,()->CabinetNetplay.registerVariants(BACKEND,"missing",List.of(selected)));
        assertThrows(IllegalArgumentException.class,()->CabinetNetplay.registerVariants(BACKEND,selected.id(),List.of(selected,selected)));
    }
    @Test void worldPreferenceRoundTripsWithoutMigratingOrDeletingLegacySaveChoice(){
        register();var row=new CompoundTag();row.putString("Key","test-key");row.putInt("Mode",1);row.putBoolean("Netplay",true);row.putInt("SaveMode",2);row.putString("NetplayVariant","retroarch-stock-v1");
        var rows=new ListTag();rows.add(row);var tag=new CompoundTag();tag.put("Modes",rows);
        var loaded=CabinetSyncSettings.load(tag,null);var saved=loaded.save(new CompoundTag(),null).getList("Modes",10).getCompound(0);
        assertEquals(2,saved.getInt("SaveMode"));assertEquals("retroarch-stock-v1",saved.getString("NetplayVariant"));assertTrue(saved.getBoolean("Netplay"));
        assertEquals(0,CabinetSyncSettings.effectiveSaveMode(BACKEND,"retroarch-stock-v1",saved.getInt("SaveMode")));
        assertEquals(2,CabinetSyncSettings.effectiveSaveMode(BACKEND,"legacy",saved.getInt("SaveMode")));
        row.remove("NetplayVariant");assertFalse(CabinetSyncSettings.load(tag,null).save(new CompoundTag(),null).getList("Modes",10).getCompound(0).contains("NetplayVariant"));
        row.putString("NetplayVariant","removed-version");assertEquals("removed-version",CabinetSyncSettings.load(tag,null).save(new CompoundTag(),null).getList("Modes",10).getCompound(0).getString("NetplayVariant"));
        row.putString("NetplayVariant","../untrusted");assertEquals("unavailable",CabinetSyncSettings.load(tag,null).save(new CompoundTag(),null).getList("Modes",10).getCompound(0).getString("NetplayVariant"));
        assertEquals(0,CabinetSyncSettings.effectiveSaveMode(BACKEND,"unavailable",2));
    }
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(b,value);byte[] raw=new byte[b.readableBytes()];b.getBytes(0,raw);assertEquals(value,codec.decode(b));assertEquals(0,b.readableBytes());
            for(int n=0;n<raw.length;n++){var shortBuf=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(raw,n)),RegistryAccess.EMPTY);try{assertThrows(RuntimeException.class,()->codec.decode(shortBuf));}finally{shortBuf.release();}}
        }finally{b.release();}
    }
    @Test void actualServerSelectionAndControllerGrantCodecsRejectAllTruncations(){
        var selected=variant("retroarch-stock-v1",false);var room=UUID.randomUUID();var member=UUID.randomUUID();var token=UUID.randomUUID();
        var assignment=new CabinetRoomNetwork.Assignment(room,member,member,0,2,TARGET,BACKEND,TARGET,null,CabinetSyncMode.LOCAL_SYNC,false);
        roundTrip(CabinetRoomNetwork.NetplayStart.CODEC,new CabinetRoomNetwork.NetplayStart(assignment,(1L<<50)+999,token,selected.launch()));
        assertThrows(IllegalArgumentException.class,()->new CabinetRoomNetwork.NetplayStart(assignment,999,token,selected.launch()));
        roundTrip(CabinetSyncNetwork.Mode.CODEC,new CabinetSyncNetwork.Mode(TARGET,BACKEND,-1,token,-1,-1,-1,-1,selected.id()));
        roundTrip(CabinetSyncNetwork.Setting.CODEC,new CabinetSyncNetwork.Setting(TARGET,BACKEND,3,true,true,"test",false,true,"",token,false,false,"",false,CabinetPowerSettings.DEFAULT_SECONDS,CabinetPowerSettings.DEFAULT_RENDER_DISTANCE,0,selected.id()));
        assertThrows(IllegalArgumentException.class,()->new CabinetSyncNetwork.Mode(TARGET,BACKEND,3,token,-1,-1,-1,-1,selected.id()));
        assertThrows(IllegalArgumentException.class,()->new CabinetSyncNetwork.Mode(TARGET,BACKEND,-1,token,1,-1,-1,-1,selected.id()));
        assertThrows(IllegalArgumentException.class,()->new CabinetSyncNetwork.Mode(TARGET,BACKEND,-1,token,-1,-1,-1,-1,"/core.dll"));
    }
}
