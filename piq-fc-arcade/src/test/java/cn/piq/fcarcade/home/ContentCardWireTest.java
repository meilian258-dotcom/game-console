package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardWireTest {
    private final ResourceLocation system=ResourceLocation.fromNamespaceAndPath("example","system");
    @Test void maximumPageAndChunkFitServerboundLimitAndRoundTrip(){
        var entries=java.util.stream.IntStream.range(0,8).mapToObj(i->new ContentCardStore.Entry("a".repeat(64),"游".repeat(125)+".md",ContentCardStore.MAX_BYTES,"名".repeat(128))).toList();
        for(int op:new int[]{ContentCardNetwork.LIST,ContentCardNetwork.COVER_LIST,ContentCardNetwork.DATA}){
            var original=new ContentCardNetwork.Message(op,system,UUID.randomUUID(),new BlockPos(-18,-60,6),"b".repeat(64),"状".repeat(256),ContentCardStore.MAX_BYTES,0,
                op==ContentCardNetwork.DATA?new byte[ContentCardStore.CHUNK]:new byte[0],entries);
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{ContentCardNetwork.Message.CODEC.encode(b,original);assertTrue(b.readableBytes()<32767);
                var actual=ContentCardNetwork.Message.CODEC.decode(b);assertEquals(original.op(),actual.op());assertEquals(original.entries(),actual.entries());
                assertEquals(original.entries().stream().map(ContentCardStore.Entry::displayName).toList(),actual.entries().stream().map(ContentCardStore.Entry::displayName).toList());
                assertArrayEquals(original.data(),actual.data());assertEquals(original.token(),actual.token());assertEquals(0,b.readableBytes());
            }finally{b.release();}
        }
    }
    @Test void oversizeAndMalformedCannotAllocateTransfer(){
        assertThrows(IllegalArgumentException.class,()->ContentCardNetwork.msg(2,system,UUID.randomUUID(),BlockPos.ZERO,"a".repeat(64),"a.md",ContentCardStore.MAX_BYTES+1,0,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->ContentCardNetwork.msg(3,system,UUID.randomUUID(),BlockPos.ZERO,"","",0,0,new byte[ContentCardStore.CHUNK+1]));
        assertThrows(IllegalArgumentException.class,()->ContentCardNetwork.msg(3,system,UUID.randomUUID(),BlockPos.ZERO,"wrong","",0,0,new byte[0]));
    }
    @Test void previewSnapshotRenameAndExplicitUploadTitleRoundTrip(){
        var card=new ContentCardStore.Entry("a".repeat(64),"a".repeat(64)+".md",2048,"游戏库的中文名字.md");
        for(int op:new int[]{ContentCardNetwork.CARD,ContentCardNetwork.RENAME,ContentCardNetwork.UPLOAD}){
            var payload=new ContentCardNetwork.Message(op,system,UUID.randomUUID(),BlockPos.ZERO,card.hash(),"显示名称",7,0,
                    op==ContentCardNetwork.UPLOAD?"改名之后".getBytes(java.nio.charset.StandardCharsets.UTF_8):new byte[0],op==ContentCardNetwork.CARD?List.of(card):List.of());
            var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{ContentCardNetwork.Message.CODEC.encode(buf,payload);var decoded=ContentCardNetwork.Message.CODEC.decode(buf);
                assertEquals(op,decoded.op());assertEquals(payload.name(),decoded.name());assertEquals(payload.entries(),decoded.entries());
                if(op==ContentCardNetwork.CARD)assertEquals(card.displayName(),decoded.entries().getFirst().displayName());
                assertEquals(payload.size(),decoded.size());assertArrayEquals(payload.data(),decoded.data());assertEquals(0,buf.readableBytes());
            }finally{buf.release();}
        }
    }
    @Test void newCoverPreferenceAndResetOperationsRoundTripWithoutIncreasingBounds(){
        for(int op:new int[]{ContentCardNetwork.RESET,ContentCardNetwork.COVER_WRITE,ContentCardNetwork.COVER_UPLOAD,ContentCardNetwork.SAVE_MODE,ContentCardNetwork.PLAYERS,ContentCardNetwork.CARD_OPTIONS,ContentCardNetwork.SAVE_LIBRARY}){
            var message=ContentCardNetwork.msg(op,system,UUID.randomUUID(),BlockPos.ZERO,"a".repeat(64),"",32,2,new byte[0]);
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{ContentCardNetwork.Message.CODEC.encode(b,message);assertTrue(b.readableBytes()<256);
                var actual=ContentCardNetwork.Message.CODEC.decode(b);assertEquals(op,actual.op());assertEquals(2,actual.offset());assertEquals(message.token(),actual.token());
            }finally{b.release();}
        }
        assertThrows(IllegalArgumentException.class,()->ContentCardNetwork.msg(ContentCardNetwork.SAVE_LIBRARY+1,system,UUID.randomUUID(),BlockPos.ZERO,"","",0,0,new byte[0]));
    }
    @Test void partialScanDiagnosticsFitExistingListWireAlongsideMaximumPage(){
        var entries=java.util.stream.IntStream.range(0,8).mapToObj(i->new ContentCardStore.Entry("a".repeat(64),"游".repeat(120)+".md",ContentCardStore.MAX_BYTES)).toList();
        var failures=java.util.stream.IntStream.range(0,256).mapToObj(i->new ContentCardStore.Failure("游".repeat(128),"错".repeat(160))).toList();
        var diagnostics=new ContentCardStore.Scan(entries,failures).diagnostics();
        for(int op:new int[]{ContentCardNetwork.LIST,ContentCardNetwork.COVER_LIST}){
            var original=new ContentCardNetwork.Message(op,system,UUID.randomUUID(),BlockPos.ZERO,"", "状".repeat(256),8,0,diagnostics,entries);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{
                ContentCardNetwork.Message.CODEC.encode(buffer,original);assertTrue(buffer.readableBytes()<32767);
                var decoded=ContentCardNetwork.Message.CODEC.decode(buffer);assertEquals(entries,decoded.entries());assertArrayEquals(diagnostics,decoded.data());assertEquals(0,buffer.readableBytes());
            }finally{buffer.release();}
        }
    }
}
