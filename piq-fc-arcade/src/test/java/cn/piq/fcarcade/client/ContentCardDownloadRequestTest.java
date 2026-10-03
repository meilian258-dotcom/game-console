package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.content.*;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real pure state used by the MC adapter; not a claim of multi-client game validation. */
class ContentCardDownloadRequestTest {
    private final ResourceLocation system=ResourceLocation.parse("example:md");
    private final UUID token=UUID.randomUUID();private final Object connection=new Object();
    private final byte[] bytes={1,2,3,4};private final String hash=ContentCardStore.hash(bytes);
    private ContentCardDownloadRequest request(int size){return new ContentCardDownloadRequest(true,system,token,BlockPos.ZERO,hash,size,8,connection,10);}
    private ContentCardNetwork.Message message(int op,String hash,int size,int offset,byte[] bytes){
        return ContentCardNetwork.msg(op,system,token,BlockPos.ZERO,hash,"test.md",size,offset,bytes);
    }
    private void offer(ContentCardDownloadRequest request){request.offer(message(ContentCardNetwork.DOWNLOAD_ONLY,hash,4,0,new byte[0]),connection);}
    private byte[] complete(ContentCardDownloadRequest request){offer(request);return request.data(message(ContentCardNetwork.DATA,hash,4,0,bytes),connection);}
    @Test void onlyClientThreadCanCaptureExpectationAndBadSizesNeverAllocate(){
        assertThrows(IllegalStateException.class,()->new ContentCardDownloadRequest(false,system,token,BlockPos.ZERO,hash,4,8,connection,0));
        for(int size:new int[]{-1,9,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->request(size));
        var unknownSize=request(0);offer(unknownSize);assertFalse(unknownSize.result.isDone());
        var bounded=request(0);assertThrows(IllegalArgumentException.class,()->bounded.offer(message(ContentCardNetwork.DOWNLOAD_ONLY,hash,9,0,new byte[0]),connection));
    }
    @Test void expectedHashSizeNoncePositionSystemAndExactConnectionMustMatch(){
        assertThrows(IllegalArgumentException.class,()->request(4).offer(message(ContentCardNetwork.DOWNLOAD_ONLY,"b".repeat(64),4,0,new byte[0]),connection));
        assertThrows(IllegalArgumentException.class,()->request(4).offer(message(ContentCardNetwork.DOWNLOAD_ONLY,hash,3,0,new byte[0]),connection));
        assertThrows(IllegalStateException.class,()->request(4).offer(message(ContentCardNetwork.DOWNLOAD_ONLY,hash,4,0,new byte[0]),new Object()));
        for(var wrong:new ContentCardNetwork.Message[]{
                ContentCardNetwork.msg(ContentCardNetwork.DOWNLOAD_ONLY,system,UUID.randomUUID(),BlockPos.ZERO,hash,"test.md",4,0,new byte[0]),
                ContentCardNetwork.msg(ContentCardNetwork.DOWNLOAD_ONLY,system,token,new BlockPos(1,0,0),hash,"test.md",4,0,new byte[0]),
                ContentCardNetwork.msg(ContentCardNetwork.DOWNLOAD_ONLY,ResourceLocation.parse("other:md"),token,BlockPos.ZERO,hash,"test.md",4,0,new byte[0])})
            assertThrows(IllegalStateException.class,()->request(4).offer(wrong,connection));
    }
    @Test void repeatedOfferDuplicateOrOutOfOrderDataCannotBecomeASecondDownload(){
        var r=request(4);offer(r);assertThrows(IllegalArgumentException.class,()->offer(r));
        assertNull(r.data(message(ContentCardNetwork.DATA,hash,4,0,new byte[]{1,2}),connection));
        assertThrows(IllegalArgumentException.class,()->r.data(message(ContentCardNetwork.DATA,hash,4,0,new byte[]{1,2}),connection));
        assertThrows(IllegalArgumentException.class,()->r.data(message(ContentCardNetwork.DATA,hash,4,3,new byte[]{4}),connection));
        assertArrayEquals(bytes,r.data(message(ContentCardNetwork.DATA,hash,4,2,new byte[]{3,4}),connection));
        assertThrows(IllegalStateException.class,()->r.data(message(ContentCardNetwork.DATA,hash,4,2,new byte[]{3,4}),connection));
    }
    @Test void cancellationDuringValidationOrGrantRevocationCannotPublishLateBytes(){
        var cancelled=request(4);var pending=complete(cancelled);cancelled.close(null);
        assertThrows(CancellationException.class,cancelled.result::join);assertFalse(cancelled.publish(connection,pending));
        var revoked=request(4);var old=complete(revoked);var reason=new IllegalStateException("seat/watch revoked");revoked.close(reason);
        assertSame(reason,assertThrows(CompletionException.class,revoked.result::join).getCause());assertFalse(revoked.publish(connection,old));
        var directlyCancelled=request(4);var data=complete(directlyCancelled);directlyCancelled.result.cancel(false);assertFalse(directlyCancelled.publish(connection,data));
    }
    @Test void reconnectAndOneShotPublicationDoNotCompleteANewRequest(){
        var old=request(4);var pending=complete(old);assertFalse(old.publish(new Object(),pending));
        old.close(null);var replacement=request(4);assertFalse(old.publish(connection,pending));assertFalse(replacement.result.isDone());
        var success=complete(replacement);assertTrue(replacement.publish(connection,success));assertArrayEquals(bytes,replacement.result.join());
        assertFalse(replacement.publish(connection,success));replacement.close(new IllegalStateException("late stop"));assertArrayEquals(bytes,replacement.result.join());
    }
    @Test void timeoutIsBoundedAndCarriesARecognizableCause(){
        var r=request(0);assertFalse(r.expired(120_000_000_010L));assertTrue(r.expired(120_000_000_011L));
        r.close(new TimeoutException("Content download timed out"));assertInstanceOf(TimeoutException.class,assertThrows(CompletionException.class,r.result::join).getCause());
    }
    @Test void publicCancellationRouteReleasesMainThreadSlotBeforeImmediateSeatReplacement(){
        var observer=request(4);var pending=complete(observer);
        var current=new ContentCardDownloadRequest[]{observer};var queued=new ArrayDeque<Runnable>();
        Runnable cleanup=()->{if(current[0]==observer){current[0]=null;observer.close(null);}};
        observer.result.whenComplete((bytes,error)->{if(observer.result.isCancelled())queued.add(cleanup);});
        ContentCardClient.cancelDownload(token,observer,true,cleanup);
        assertNull(current[0],"No dependence on executor inline behavior during observer to seat handoff");
        assertThrows(CancellationException.class,observer.result::join);
        var seat=request(4);current[0]=seat;
        while(!queued.isEmpty())queued.remove().run();
        assertSame(seat,current[0],"Queued old cleanup cannot release the replacement slot");
        assertFalse(observer.publish(connection,pending));assertFalse(seat.result.isDone());
    }
    @Test void publicCancellationRouteFromWorkerOnlyCancelsFutureUntilClientCleanupRuns(){
        var observer=request(4);var current=new ContentCardDownloadRequest[]{observer};
        var queued=new ArrayDeque<Runnable>();var cleaned=new boolean[]{false};
        Runnable cleanup=()->{cleaned[0]=true;if(current[0]==observer){current[0]=null;observer.close(null);}};
        observer.result.whenComplete((bytes,error)->{if(observer.result.isCancelled())queued.add(cleanup);});
        ContentCardClient.cancelDownload(token,observer,false,()->fail("Worker must not invoke client cleanup"));
        assertTrue(observer.result.isCancelled());assertSame(observer,current[0]);assertFalse(cleaned[0]);
        assertEquals(1,queued.size());queued.remove().run();assertNull(current[0]);assertTrue(cleaned[0]);
    }
    @Test void publicCancellationRouteIgnoresOtherAndExpiredRequestTokens(){
        var current=request(4);
        for(boolean clientThread:new boolean[]{true,false}){
            ContentCardClient.cancelDownload(UUID.randomUUID(),current,clientThread,()->fail("Wrong request cleanup"));
            ContentCardClient.cancelDownload(token,null,clientThread,()->fail("Missing request cleanup"));
        }
        assertFalse(current.result.isDone());
    }
    @Test void failedStopTransportCannotLeaveCancellationOrAuthorizationFailurePending(){
        var cancelled=request(4);var pending=complete(cancelled);
        assertDoesNotThrow(()->ContentCardClient.finishDownloadCancellation(cancelled,null,()->{throw new IllegalStateException("disconnected during STOP");}));
        assertThrows(CancellationException.class,cancelled.result::join);assertFalse(cancelled.publish(connection,pending));
        var revoked=request(4);var denied=new IllegalStateException("grant revoked");
        assertDoesNotThrow(()->ContentCardClient.finishDownloadCancellation(revoked,denied,()->{throw new IllegalStateException("channel already gone");}));
        assertSame(denied,assertThrows(CompletionException.class,revoked.result::join).getCause());
    }
}
