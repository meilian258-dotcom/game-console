package cn.piq.fcarcade;

import cn.piq.fcarcade.session.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamcoSessionNetwork43Test {
    private static ArcadeSessionPayload session(NesCoreVariant variant,ArcadeMode mode,ArcadeRole role){
        return new ArcadeSessionPayload(BlockPos.ZERO,99,mode,role,2,"P1 P2",16,16,100,"ab".repeat(32),4,false,true,variant);
    }
    @Test void newCoreRoundTripsForBothPlayersAndSpectatorInEachOriginalMode(){
        for(var mode:ArcadeMode.values())for(var role:ArcadeRole.values()){
            var payload=session(NesCoreVariant.MAPPER19_V1,mode,role);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{ArcadeSessionPayload.STREAM_CODEC.encode(buffer,payload);assertEquals(payload,ArcadeSessionPayload.STREAM_CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());}
            finally{buffer.release();}
        }
    }
    @Test void libretroRoundTripsForAllRolesWithGunLimitedToAuthoritativeFrames(){
        for(var variant:new NesCoreVariant[]{NesCoreVariant.LIBRETRO_V1,NesCoreVariant.LIBRETRO_ZAPPER_V1})
            for(var mode:ArcadeMode.values())for(var role:ArcadeRole.values()){
                if(variant.isZapper()&&mode!=ArcadeMode.LOCKSTEP){assertThrows(IllegalArgumentException.class,()->session(variant,mode,role));continue;}
                var payload=session(variant,mode,role);
                var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
                try{ArcadeSessionPayload.STREAM_CODEC.encode(buffer,payload);assertEquals(payload,ArcadeSessionPayload.STREAM_CODEC.decode(buffer));assertEquals(0,buffer.readableBytes());}
                finally{buffer.release();}
            }
    }
    @Test void gunKeepsItsOldAuthoritativeFrameRestriction(){
        assertThrows(IllegalArgumentException.class,()->session(NesCoreVariant.ZAPPER_V1,ArcadeMode.STREAM,ArcadeRole.PLAYER_ONE));
        assertNotNull(session(NesCoreVariant.LEGACY,ArcadeMode.STREAM,ArcadeRole.PLAYER_ONE));
    }
    @Test void oldWireOrdinalsAndConnectionVersionAreExplicit(){
        assertEquals(0,NesCoreVariant.LEGACY.ordinal());assertEquals(1,NesCoreVariant.ZAPPER_V1.ordinal());
        assertEquals("45",FcNetwork.PROTOCOL_VERSION);
    }
}
