package cn.piq.fcarcade.netplay;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static cn.piq.fcarcade.netplay.NetplaySaveNetwork.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real packet codecs + production authority transitions + actual atomic disk store. No MC server stubs. */
class NetplaySaveSessionTest {
    @TempDir Path root;
    final NetplaySaveState.Identity identity=NetplaySaveState.identity(NetplayProfile.fc(),"a".repeat(64),Map.of());
    byte[] state(long frame,int n){byte[] data=new byte[n];new Random(frame).nextBytes(data);return NetplaySaveState.encode(new NetplaySaveState.Parts(identity,frame,data,new byte[]{90},new byte[0]));}
    static Message wire(Message m){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{Message.CODEC.encode(b,m);assertTrue(b.readableBytes()<32767);var got=Message.CODEC.decode(b);assertEquals(0,b.readableBytes());return got;}finally{b.release();}}
    final class Harness {
        final long id=(1L<<50)+77;final UUID ticket=UUID.randomUUID();UUID tx=UUID.randomUUID();
        final ArrayDeque<Runnable> io=new ArrayDeque<>(),main=new ArrayDeque<>();final List<Message> replies=new ArrayList<>();
        long now=10_000_000_000L;boolean live=true,released,failWrite;int writes;byte[] saved;
        final NetplaySaveSession session;
        Harness(boolean enabled,Path directory){
            session=new NetplaySaveSession(id,ticket,identity,enabled?()->{
                var store=new NetplaySaveStore(directory,identity);
                return new NetplaySaveSession.Storage(){
                    public byte[] read()throws Exception{return store.read();}
                    public void write(byte[] b)throws Exception{if(failWrite)throw new IOException("disk unavailable");store.write(b);writes++;saved=b;}
                    public void close()throws Exception{store.close();}
                };
            }:null,io::add,main::add,m->replies.add(wire(m)),()->live,()->now,()->released=true);
        }
        Message message(int kind,int value,int at,byte[] b,String text){return new Message(id,ticket,tx,kind,value,at,b,text);}
        void send(int kind,int value,int at,byte[] b,String text){session.receive(wire(message(kind,value,at,b,text)));}
        void small(int kind){send(kind,0,0,new byte[0],"");}
        void flush(){while(!io.isEmpty()||!main.isEmpty()){while(!io.isEmpty())io.remove().run();while(!main.isEmpty())main.remove().run();}}
        Message last(){return replies.getLast();}
        void read(){send(READ,0,0,new byte[0],identity.profile()+":"+identity.content());flush();}
        byte[] download()throws Exception{
            read();assertEquals(LOAD,last().kind());var a=new NetplaySaveTransfer.Assembly(last().value());
            while(!a.complete()){replies.clear();send(NEXT,0,a.offset(),new byte[0],"");for(var m:replies){assertEquals(DOWNLOAD,m.kind());a.append(m.offset(),m.bytes());}}
            send(NEXT,0,a.offset(),new byte[0],"");assertEquals(DONE,last().kind());return NetplaySaveTransfer.unpack(a.finish());
        }
        void upload(byte[] b)throws Exception{
            now+=1_100_000_000L;tx=UUID.randomUUID();byte[] packed=NetplaySaveTransfer.pack(b);send(BEGIN,packed.length,0,new byte[0],"");assertEquals(READY,last().kind());
            for(int at=0;at<packed.length;){int end=Math.min(packed.length,at+NetplaySaveTransfer.CHUNK);send(UPLOAD,0,at,Arrays.copyOfRange(packed,at,end),"");at=end;now+=50_000_000L;}
        }
    }
    @Test void realCodecTransferDurableAckRestoreAndFinalSave()throws Exception{
        var h=new Harness(true,root.resolve("slot"));h.read();assertEquals(EMPTY,h.last().kind());
        byte[] one=state(100,250000);h.upload(one);assertEquals(0,h.writes);assertNotEquals(SAVED,h.last().kind());
        h.io.remove().run();assertEquals(1,h.writes);assertNotEquals(SAVED,h.last().kind());h.flush();assertEquals(SAVED,h.last().kind());
        h.session.retire();byte[] two=state(200,250000);h.upload(two);h.flush();assertEquals(SAVED,h.last().kind());
        h.tx=UUID.randomUUID();h.small(FINISH);assertEquals(DONE,h.last().kind());assertFalse(h.released);h.flush();assertTrue(h.released);
        var next=new Harness(true,root.resolve("slot"));assertArrayEquals(two,next.download());next.small(CANCEL);next.flush();
        assertArrayEquals(one,Files.readAllBytes(root.resolve("slot/checkpoint.previous.bin")));
    }
    @Test void wrongTicketSessionAndStaleTransactionNeverWrite()throws Exception{
        var h=new Harness(true,root.resolve("auth"));
        for(var bad:List.of(new Message(h.id,UUID.randomUUID(),h.tx,READ,0,0,new byte[0],identity.profile()+":"+identity.content()),new Message(h.id+1,h.ticket,h.tx,READ,0,0,new byte[0],identity.profile()+":"+identity.content()))){h.session.receive(wire(bad));}
        assertTrue(h.replies.isEmpty());assertTrue(h.io.isEmpty());h.read();
        byte[] packed=NetplaySaveTransfer.pack(state(1,4));h.tx=UUID.randomUUID();h.send(BEGIN,packed.length,0,new byte[0],"");
        h.session.receive(wire(new Message(h.id,h.ticket,UUID.randomUUID(),UPLOAD,0,0,packed,"")));assertEquals(0,h.writes);assertTrue(h.io.isEmpty());
        h.send(UPLOAD,0,0,packed,"");h.flush();assertEquals(1,h.writes);
    }
    @Test void disabledDoesNotTouchFilesystem(){var h=new Harness(false,root.resolve("none"));h.read();assertEquals(DISABLED,h.last().kind());assertTrue(h.released);assertFalse(Files.exists(root.resolve("none")));}
    @Test void mismatchedContentAndCorruptDiskFailWithoutStartingFresh()throws Exception{
        var h=new Harness(true,root.resolve("bad"));h.send(READ,0,0,new byte[0],"wrong");h.flush();assertEquals(ERROR,h.last().kind());assertFalse(Files.exists(root.resolve("bad")));
        Files.createDirectories(root.resolve("bad"));Files.write(root.resolve("bad/checkpoint.bin"),new byte[]{42});
        var broken=new Harness(true,root.resolve("bad"));broken.read();assertEquals(ERROR,broken.last().kind());assertArrayEquals(new byte[]{42},Files.readAllBytes(root.resolve("bad/checkpoint.bin")));assertTrue(broken.released);
    }
    @Test void failedWriteRetainsPreviousAndNeverAcknowledgesSuccess()throws Exception{
        var h=new Harness(true,root.resolve("failure"));h.read();byte[] one=state(1,33);h.upload(one);h.flush();h.failWrite=true;
        h.upload(state(2,34));h.flush();assertEquals(ERROR,h.last().kind());assertEquals(1,h.writes);assertArrayEquals(one,Files.readAllBytes(root.resolve("failure/checkpoint.bin")));assertTrue(h.released);
    }
    @Test void duplicateFrameAndDuplicateChunkRejected()throws Exception{
        var h=new Harness(true,root.resolve("duplicate"));h.read();h.upload(state(8,30));h.flush();h.upload(state(8,40));h.flush();assertEquals(ERROR,h.last().kind());assertEquals(1,h.writes);
        var b=new Harness(true,root.resolve("chunk"));b.read();byte[] data=NetplaySaveTransfer.pack(state(10,40000));b.send(BEGIN,data.length,0,new byte[0],"");var first=Arrays.copyOf(data,NetplaySaveTransfer.CHUNK);b.send(UPLOAD,0,0,first,"");b.send(UPLOAD,0,0,first,"");b.flush();assertEquals(ERROR,b.last().kind());assertEquals(0,b.writes);
    }
    @Test void retirementAllowsInflightPlusFinalCommitAndExpiryReleases()throws Exception{
        var h=new Harness(true,root.resolve("retire"));h.read();h.session.retire();h.upload(state(1,30));h.flush();
        h.upload(state(2,31));h.flush();assertEquals(SAVED,h.last().kind());
        h.now+=2_000_000_000L;h.tx=UUID.randomUUID();h.send(BEGIN,12,0,new byte[0],"");h.flush();assertEquals(ERROR,h.last().kind());assertEquals(2,h.writes);
        var expired=new Harness(true,root.resolve("expired"));expired.read();expired.session.retire();expired.now+=71_000_000_000L;expired.session.tick();expired.flush();assertTrue(expired.released);
    }
    @Test void cancelAndDisconnectKeepWriterUntilQueuedIoReleases()throws Exception{
        var h=new Harness(true,root.resolve("cancel"));h.read();h.upload(state(1,30));h.small(CANCEL);assertFalse(h.released);h.flush();assertEquals(0,h.writes);assertTrue(h.released);
        var disconnected=new Harness(true,root.resolve("disconnect"));disconnected.read();disconnected.live=false;disconnected.session.tick();disconnected.flush();assertTrue(disconnected.released);
        try(var reopened=new NetplaySaveStore(root.resolve("disconnect"),identity)){assertNull(reopened.read());}
    }
    @Test void transferTimesOutAndNoUnboundedFloodIsAccepted()throws Exception{
        var h=new Harness(true,root.resolve("timeout"));h.read();h.send(BEGIN,100,0,new byte[0],"");h.now+=41_000_000_000L;h.session.tick();h.flush();assertEquals(ERROR,h.last().kind());assertTrue(h.released);
        var flood=new Harness(true,root.resolve("flood"));flood.read();flood.send(BEGIN,100000,0,new byte[0],"");
        for(int i=0;i<5;i++)flood.send(UPLOAD,0,i*16384,new byte[16384],"");flood.flush();assertEquals(ERROR,flood.last().kind());assertEquals(0,flood.writes);
    }
    @Test void codecRejectsTruncationAndExtraneousFields(){
        var h=new Harness(false,root);var m=h.message(BEGIN,50000,0,new byte[0],"");
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{Message.CODEC.encode(b,m);byte[] raw=new byte[b.readableBytes()];b.getBytes(0,raw);
            for(int i=0;i<raw.length;i++){var cut=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(raw,i)),RegistryAccess.EMPTY);try{assertThrows(RuntimeException.class,()->Message.CODEC.decode(cut));}finally{cut.release();}}
        }finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->h.message(UPLOAD,0,0,new byte[0],""));
        assertThrows(IllegalArgumentException.class,()->h.message(FINISH,123,0,new byte[0],""));
        assertThrows(IllegalArgumentException.class,()->h.message(BEGIN,0,0,new byte[0],""));
    }
}
