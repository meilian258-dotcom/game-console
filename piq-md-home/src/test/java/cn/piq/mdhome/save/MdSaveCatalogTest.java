package cn.piq.mdhome.save;

import cn.piq.fcarcade.netplay.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MdSaveCatalogTest {
    @TempDir Path root;
    static final String ROM="a".repeat(64),OTHER="b".repeat(64);
    static final UUID PLAYER=UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"),CARD=UUID.fromString("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb");
    MdSaveCatalog catalog(){return new MdSaveCatalog(root);}
    @Test void liveSecondPortDecisionNeverChangesSaveOwnershipOrLabel(){var original=new MdPublicSaves.SavePlan(MdSaveCatalog.identity(ROM),true,true,1,false,"physical-owner","中文存档",2,"old-version",root);var joined=original.withJoin(true);assertEquals(1,joined.savePlayers());assertTrue(joined.allowSecondPort());assertEquals(original.ownerKey(),joined.ownerKey());assertEquals(original.name(),joined.name());assertEquals(original.expectedVersion(),joined.expectedVersion());assertEquals(original.identity(),joined.identity());assertEquals(original,joined.withJoin(false));}
    String owner(){return MdSaveCatalog.personal(PLAYER,1);}
    byte[] bytes(String rom,long frame){return NetplaySaveState.encode(new NetplaySaveState.Parts(MdSaveCatalog.identity(rom),frame,new byte[]{1,2,3},new byte[]{4,5},new byte[0]));}
    MdSaveCatalog.Row write(String owner,String rom,String name)throws Exception{
        var c=catalog();var row=c.read(owner);try(var lease=c.lease(owner,MdSaveCatalog.identity(rom),row==null?"":row.version(),name,2,false)){assertNull(lease.read());lease.write(bytes(rom,50));}return c.read(owner);
    }
    @Test void personalHasThreeSlotsButOneOwnerWideLockAndCardIsIndependent(){
        var c=catalog();assertThrows(IllegalArgumentException.class,()->MdSaveCatalog.personal(PLAYER,4));
        assertEquals(c.lockKey(owner()),c.lockKey(MdSaveCatalog.personal(PLAYER,3)));
        assertNotEquals(MdSaveCatalog.id(owner()),MdSaveCatalog.id(MdSaveCatalog.personal(PLAYER,3)));
        assertNotEquals(c.lockKey(owner()),c.lockKey(MdSaveCatalog.cartridge(CARD)));
        assertThrows(IllegalArgumentException.class,()->MdSaveCatalog.cartridge(new UUID(0,0)));
    }
    @Test void nonexistentReadDoesNotCreateFilesAndMetadataStateCommitTogether()throws Exception{
        var c=catalog();assertNull(c.read(owner()));try(var files=Files.list(root)){assertEquals(0,files.count());}
        var row=write(owner(),ROM,"我的进度");assertEquals("我的进度",row.name());assertEquals(ROM,row.identity().content());assertEquals(2,row.players());assertEquals(50,row.frame());
        try(var lease=c.lease(owner(),MdSaveCatalog.identity(ROM),row.version(),row.name(),2,true)){assertArrayEquals(bytes(ROM,50),lease.read());}
    }
    @Test void freshDifferentGameDoesNotTouchOldBeforeFirstSuccessfulSave()throws Exception{
        var c=catalog();var old=write(owner(),ROM,"旧进度");
        try(var lease=c.lease(owner(),MdSaveCatalog.identity(OTHER),old.version(),"新游戏",1,false)){assertNull(lease.read());assertEquals(old.version(),c.read(owner()).version());}
        assertEquals(old.version(),c.read(owner()).version());
        try(var lease=c.lease(owner(),MdSaveCatalog.identity(OTHER),old.version(),"新游戏",1,false)){lease.write(bytes(OTHER,0));}
        var changed=c.read(owner());assertEquals(OTHER,changed.identity().content());assertEquals("新游戏",changed.name());assertEquals(0,changed.frame());
        assertTrue(Files.exists(root.resolve(MdSaveCatalog.id(owner())).resolve("checkpoint.previous.bin")));
    }
    @Test void mismatchedIdentityStaleMetadataAndBadCheckpointNeverReplaceExisting()throws Exception{
        var c=catalog();var old=write(owner(),ROM,"A");
        assertThrows(IOException.class,()->c.lease(owner(),MdSaveCatalog.identity(OTHER),old.version(),"B",1,true));
        assertThrows(IOException.class,()->c.lease(owner(),MdSaveCatalog.identity(ROM),"","B",1,false));
        try(var lease=c.lease(owner(),MdSaveCatalog.identity(ROM),old.version(),"A",1,true)){assertThrows(IOException.class,()->lease.write(bytes(OTHER,90)));}
        assertEquals(old.version(),c.read(owner()).version());c.rename(old,"新名称");assertThrows(IOException.class,()->c.rename(old,"覆盖旧版本"));assertThrows(IOException.class,()->c.delete(old));assertEquals("新名称",c.read(owner()).name());
    }
    @Test void revokeBeforeMutationPreservesVersionAndDeletedProgressIsRecoverable()throws Exception{
        var c=catalog();var old=write(owner(),ROM,"A");
        assertThrows(IOException.class,()->c.rename(old,"B",()->false));assertThrows(IOException.class,()->c.delete(old,()->false));assertEquals(old.version(),c.read(owner()).version());
        c.delete(old);assertNull(c.read(owner()));try(var files=Files.list(root.resolve(old.id()).resolve("deleted"))){assertEquals(1,files.count());}
    }
    @Test void listOnlyCurrentRomAndOwnerUnlessOperatorAndCardsRemainDistinct()throws Exception{
        var c=catalog();write(owner(),ROM,"个人1");write(MdSaveCatalog.personal(PLAYER,2),OTHER,"个人2");write(MdSaveCatalog.cartridge(CARD),ROM,"卡带");write(MdSaveCatalog.personal(UUID.randomUUID(),1),ROM,"他人");
        assertEquals(2,c.list(ROM,PLAYER,CARD,false).rows().size());assertEquals(3,c.list(ROM,PLAYER,CARD,true).rows().size());assertEquals(1,c.list(OTHER,PLAYER,CARD,false).rows().size());
    }
    @Test void corruptedEnvelopeIsNotSilentlyEmptyOrReplaced()throws Exception{
        var c=catalog();var old=write(owner(),ROM,"A");Path file=root.resolve(old.id()).resolve("checkpoint.bin");byte[] bad=Files.readAllBytes(file);bad[bad.length-1]^=1;Files.write(file,bad);
        assertThrows(IOException.class,()->c.read(owner()));assertThrows(IOException.class,()->c.lease(owner(),MdSaveCatalog.identity(ROM),old.version(),"B",1,false));assertArrayEquals(bad,Files.readAllBytes(file));
    }
    @Test void ownerGateBlocksOtherSlotAndQueuedRevocationUntilWorkerFinishes()throws Exception{
        var c=catalog();var gates=new MdSaveTransactions();String key=c.lockKey(owner()),otherSlot=c.lockKey(MdSaveCatalog.personal(PLAYER,3));
        var hold=gates.reserve(key,()->true);assertThrows(IllegalStateException.class,()->gates.start(otherSlot,()->fail("must not start")));
        hold.revoke();assertFalse(hold.allowed());assertTrue(gates.busy(otherSlot));hold.close();gates.start(otherSlot,()->{});
        assertThrows(IllegalStateException.class,()->gates.reserve(key,()->false));assertFalse(gates.busy(key));
    }
    @Test void delayedMutationRevalidatesAuthorizationAndCannotDeleteAfterRevocation()throws Exception{
        var c=catalog();var old=write(owner(),ROM,"A");var gate=new MdSaveTransactions();var permit=gate.reserve(c.lockKey(owner()),()->true);
        var authorized=new AtomicBoolean(true);var queue=new ArrayBlockingQueue<Runnable>(1);var done=new CompletableFuture<Void>();
        queue.add(()->{try(permit){if(!authorized.get())permit.revoke();c.delete(old,permit::allowed);done.complete(null);}catch(Exception e){done.completeExceptionally(e);}});
        authorized.set(false);assertThrows(IllegalStateException.class,()->gate.start(c.lockKey(MdSaveCatalog.personal(PLAYER,2)),()->{}));queue.take().run();
        assertThrows(ExecutionException.class,()->done.get(1,TimeUnit.SECONDS));assertEquals(old.version(),c.read(owner()).version());assertFalse(gate.busy(c.lockKey(owner())));
    }
    @Test void failedMutationAlwaysReleasesGateAndDoubleCloseCannotRevokeReplacement()throws Exception{
        var gate=new MdSaveTransactions();String key=catalog().lockKey(owner());var first=gate.reserve(key,()->true);
        assertThrows(IOException.class,()->{try(first){throw new IOException("disk failure");}});assertFalse(gate.busy(key));
        var next=gate.reserve(key,()->true);first.close();assertTrue(gate.busy(key));assertTrue(next.allowed());next.close();assertFalse(gate.busy(key));
    }
    @Test void selectionPacketsBoundSlotsVersionsAndNoSaveHasNoSlots(){
        var token=UUID.randomUUID();assertDoesNotThrow(()->new MdSaveNetwork.Selection(token,ROM,"MD",0,2,List.of(),"",false));
        var slot=new MdSaveNetwork.Slot(1,"","Save 1","",1,0,true);
        assertThrows(IllegalArgumentException.class,()->new MdSaveNetwork.Selection(token,ROM,"MD",0,2,List.of(slot),"",false));
        assertThrows(IllegalArgumentException.class,()->new MdSaveNetwork.Selection(token,ROM,"MD",2,2,List.of(slot),"",false));
        assertThrows(IllegalArgumentException.class,()->new MdSaveNetwork.Action(token,4,"","Save",1,false,false));
        assertThrows(IllegalArgumentException.class,()->new MdSaveNetwork.Action(token,1,"forged","Save",1,false,false));
    }
}
