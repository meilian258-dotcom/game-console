package cn.piq.sfchome.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SfcNetplaySaveCodecTest {
    @Test void libraryActionRoundTripsAndRejectsPathsOrAdditionalMutatingFields(){
        UUID token=UUID.randomUUID();String rom="a".repeat(64);
        var action=new SfcHomeNetwork.EditorAction(token,SfcHomeNetwork.SAVE_LIBRARY,rom,"",0,0,new byte[0]);
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{SfcHomeNetwork.EditorAction.CODEC.encode(b,action);byte[] bytes=new byte[b.readableBytes()];b.getBytes(0,bytes);
            var decoded=SfcHomeNetwork.EditorAction.CODEC.decode(b);assertEquals(token,decoded.token());assertEquals(rom,decoded.hash());assertEquals(SfcHomeNetwork.SAVE_LIBRARY,decoded.operation());assertEquals(0,b.readableBytes());
            for(int length=0;length<bytes.length;length++){var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(bytes,length)),RegistryAccess.EMPTY);try{assertThrows(RuntimeException.class,()->SfcHomeNetwork.EditorAction.CODEC.decode(cut));}finally{cut.release();}}
        }finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.EditorAction(token,SfcHomeNetwork.SAVE_LIBRARY,"","",0,0,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.EditorAction(token,SfcHomeNetwork.SAVE_LIBRARY,rom,"other-owner",0,0,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.EditorAction(token,SfcHomeNetwork.SAVE_LIBRARY,rom,"",1,0,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.EditorAction(token,SfcHomeNetwork.SAVE_LIBRARY,rom,"",0,1,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new SfcHomeNetwork.EditorAction(token,SfcHomeNetwork.SAVE_LIBRARY,rom,"",0,0,new byte[]{1}));
    }
    SfcHomeNetwork.Editor editor(int mode){return new SfcHomeNetwork.Editor(UUID.randomUUID(),true,"","a".repeat(64),"测试卡带","",2,true,1,List.of(),List.of(),mode);}
    @Test void allOwnershipModesRoundTripAndOldDefaultsRemainCard(){
        for(int mode=0;mode<3;mode++){
            var value=editor(mode);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{SfcHomeNetwork.Editor.CODEC.encode(b,value);byte[] raw=new byte[b.readableBytes()];b.getBytes(0,raw);assertEquals(value,SfcHomeNetwork.Editor.CODEC.decode(b));assertEquals(0,b.readableBytes());
                for(int n=0;n<raw.length;n++){var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(raw,n)),RegistryAccess.EMPTY);try{assertThrows(RuntimeException.class,()->SfcHomeNetwork.Editor.CODEC.decode(cut));}finally{cut.release();}}
            }finally{b.release();}
        }
        assertEquals(2,new SfcHomeNetwork.Editor(UUID.randomUUID(),true,"","","",List.of()).saveMode());
        assertThrows(IllegalArgumentException.class,()->editor(-1));assertThrows(IllegalArgumentException.class,()->editor(3));
    }
    @Test void saveSelectionActionKeepsExactEditorBinding(){
        for(int mode=0;mode<3;mode++){
            var a=new SfcHomeNetwork.EditorAction(UUID.randomUUID(),SfcHomeNetwork.SET_SAVE_MODE,"a".repeat(64),"",mode,0,new byte[0]);
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{SfcHomeNetwork.EditorAction.CODEC.encode(b,a);var got=SfcHomeNetwork.EditorAction.CODEC.decode(b);assertEquals(a.token(),got.token());assertEquals(a.operation(),got.operation());assertEquals(mode,got.total());assertEquals(a.hash(),got.hash());}finally{b.release();}
        }
    }
}
