package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.content.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardDownloadsTest {
    private static final ResourceLocation SYSTEM=ResourceLocation.parse("example:md");
    private final Object connection=new Object();
    private final byte[] bytes={1,2,3,4};
    private ContentCardDownloadRequest request(UUID token,Object origin){return new ContentCardDownloadRequest(true,SYSTEM,token,BlockPos.ZERO,ContentCardStore.hash(bytes),4,8,origin,0);}
    private ContentCardNetwork.Message message(ContentCardDownloadRequest r,int op){return ContentCardNetwork.msg(op,r.system,r.token,r.pos,r.hash,"game.md",4,0,op==ContentCardNetwork.DATA?bytes:new byte[0]);}
    @Test void controlAndTwoObserversDownloadIndependentlyAndCompleteExactlyOnce(){
        var registry=new ContentCardDownloads();var control=request(UUID.randomUUID(),connection);var a=request(UUID.randomUUID(),connection);var b=request(UUID.randomUUID(),connection);
        for(var r:List.of(a,control,b)){registry.add(r);var offer=message(r,ContentCardNetwork.DOWNLOAD_ONLY);assertSame(r,registry.find(offer,connection));r.offer(offer,connection);}
        for(var r:List.of(b,control,a)){
            var data=message(r,ContentCardNetwork.DATA);assertSame(r,registry.find(data,connection));var decoded=r.data(data,connection);
            assertTrue(r.publish(connection,decoded));assertFalse(r.publish(connection,decoded));assertTrue(registry.remove(r));assertArrayEquals(bytes,r.result.join());
        }
        assertTrue(registry.snapshot().isEmpty());
    }
    @Test void cancellingOneLeaseDuringValidationDoesNotStopControlOrOtherScreen(){
        var registry=new ContentCardDownloads();var a=request(UUID.randomUUID(),connection);var b=request(UUID.randomUUID(),connection);
        registry.add(a);registry.add(b);a.offer(message(a,ContentCardNetwork.DOWNLOAD_ONLY),connection);var pending=a.data(message(a,ContentCardNetwork.DATA),connection);
        ContentCardClient.cancelDownload(a.token,a,true,()->{if(registry.remove(a))ContentCardClient.finishDownloadCancellation(a,null,()->{});});
        assertTrue(a.result.isCancelled());assertFalse(a.publish(connection,pending));assertSame(b,registry.get(b.token));assertFalse(b.result.isDone());
        assertNull(registry.find(message(a,ContentCardNetwork.STOP),connection));
    }
    @Test void retiredConnectionOrLateCleanupCannotAddressReplacement(){
        var registry=new ContentCardDownloads();var old=request(UUID.randomUUID(),connection);registry.add(old);registry.remove(old);old.close(null);
        var replacement=request(old.token,new Object());registry.add(replacement);
        assertNull(registry.find(message(old,ContentCardNetwork.DATA),connection));assertFalse(registry.remove(old));assertSame(replacement,registry.get(old.token));
        assertNull(registry.find(ContentCardNetwork.msg(ContentCardNetwork.DATA,SYSTEM,old.token,new BlockPos(1,0,0),old.hash,"game.md",4,0,bytes),replacement.connection));
        assertFalse(replacement.result.isDone());
    }
    @Test void capacityAndDuplicateTokenFailWithoutEvictingAnyExistingReader(){
        var registry=new ContentCardDownloads();var first=request(UUID.randomUUID(),connection);registry.add(first);
        assertThrows(IllegalStateException.class,()->registry.add(request(first.token,connection)));
        for(int i=1;i<ContentCardDownloads.LIMIT;i++)registry.add(request(UUID.randomUUID(),connection));
        assertThrows(IllegalStateException.class,()->registry.add(request(UUID.randomUUID(),connection)));
        assertEquals(4,registry.snapshot().size());assertSame(first,registry.get(first.token));
    }
}
