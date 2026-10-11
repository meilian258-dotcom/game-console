// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.home.flow.HomeLaunchNetwork;
import cn.piq.fcarcade.netplay.NetplayLegacySlot;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.fcarcade.netplay.NetplaySaveStore;
import cn.piq.sfchome.core.SfcNetplayProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcNetplaySaveCatalogTest {
    @TempDir Path temporary;
    private static final String ROM="a".repeat(64),OWNER="sfc-personal|test-owner";

    private NetplaySaveState.Identity current(){return NetplaySaveState.identity(SfcNetplayProfile.current().template(),ROM,Map.of());}
    private NetplaySaveState.Identity legacy(){return NetplaySaveState.identity(SfcNetplayProfile.profile(),ROM,Map.of());}
    private Path directory(NetplaySaveState.Identity identity){return temporary.resolve(identity.profile()).resolve(identity.content());}
    private SfcNetplaySaveCatalog catalog(){return new SfcNetplaySaveCatalog(OWNER,ROM,this::directory);}
    private static byte[] checkpoint(NetplaySaveState.Identity identity,long frame){
        return NetplaySaveState.encode(new NetplaySaveState.Parts(identity,frame,new byte[]{1,2,3},new byte[]{4},new byte[0]));
    }
    private byte[] save(NetplaySaveState.Identity identity,long frame)throws IOException{
        byte[] bytes=checkpoint(identity,frame);
        try(var store=new NetplaySaveStore(directory(identity),identity)){store.write(bytes);}
        return bytes;
    }
    private static HomeLaunchNetwork.Choice choice(HomeLaunchNetwork.Row row,boolean resume){
        return new HomeLaunchNetwork.Choice(row.slot(),row.version(),row.name(),row.players(),resume);
    }

    @Test void absentProgressShowsOnlyNewCoreWithoutCreatingDirectories()throws Exception{
        var rows=catalog().inspect();
        assertEquals(1,rows.size());var row=rows.getFirst();
        assertEquals(1,row.slot());assertTrue(row.name().contains("Snes9x"));
        assertFalse(row.occupied());assertTrue(row.startNewAllowed());assertFalse(row.metadataEditable());
        assertTrue(row.accepts(choice(row,false)));assertFalse(row.accepts(choice(row,true)));
        try(var files=Files.list(temporary)){assertEquals(0,files.count());}
    }

    @Test void noSaveIsExplicitCurrentCoreAndDoesNotReadOrCreateEitherSave()throws Exception{
        var plan=catalog().withoutSaving();
        assertEquals(SfcNetplayProfile.current(),plan.variant());assertEquals(current(),plan.identity());
        assertFalse(plan.enabled());assertFalse(plan.resume());assertNull(plan.directory());assertEquals("",plan.version());
        assertEquals(OWNER+"|"+current().profile()+"|"+current().content(),plan.slot());
        try(var files=Files.list(temporary)){assertEquals(0,files.count());}
    }

    @Test void legacyProgressKeepsOriginalIdentityAndCanOnlyResume()throws Exception{
        byte[] before=save(legacy(),21);var saves=catalog();var rows=saves.inspect();
        assertEquals(2,rows.size());var row=rows.get(1);
        assertEquals(2,row.slot());assertTrue(row.name().contains("Mesen-S"));assertTrue(row.name().contains("风险"));
        assertTrue(row.occupied());assertFalse(row.startNewAllowed());assertFalse(row.metadataEditable());
        assertFalse(row.accepts(choice(row,false)));assertTrue(row.accepts(choice(row,true)));
        var selected=saves.select(choice(row,true));
        assertEquals(SfcNetplayProfile.legacy(),selected.variant());assertEquals(legacy(),selected.identity());
        assertEquals(directory(legacy()),selected.directory());assertTrue(selected.resume());
        assertNotEquals(current(),selected.identity());assertNotEquals(saves.slotKey(1),selected.slot());
        assertArrayEquals(before,Files.readAllBytes(directory(legacy()).resolve("checkpoint.bin")));
        assertFalse(Files.exists(directory(current())));
    }

    @Test void forgedLegacyFreshStartAndAbsentLegacyResumeAreRejected()throws Exception{
        var saves=catalog();
        assertThrows(IllegalArgumentException.class,()->saves.select(new HomeLaunchNetwork.Choice(2,"","Mesen-S",1,false)));
        assertThrows(IOException.class,()->saves.select(new HomeLaunchNetwork.Choice(2,"","Mesen-S",1,true)));
        byte[] before=save(legacy(),20);var row=saves.inspect().get(1);
        assertThrows(IllegalArgumentException.class,()->saves.select(choice(row,false)));
        assertArrayEquals(before,Files.readAllBytes(directory(legacy()).resolve("checkpoint.bin")));
    }

    @Test void currentAndLegacyRemainSeparateThroughActualLeaseAndWrite()throws Exception{
        byte[] old=save(legacy(),30);var saves=catalog();var selected=saves.select(choice(saves.inspect().getFirst(),false));
        assertEquals(SfcNetplayProfile.current(),selected.variant());assertTrue(selected.enabled());assertFalse(selected.resume());
        assertFalse(Files.exists(directory(current()))); // Selecting never acquires a writer or creates an empty save.
        byte[] next=checkpoint(current(),90);
        try(var lease=NetplayLegacySlot.lease(selected.directory(),selected.identity(),selected.version(),selected.resume())){
            assertNull(lease.read());lease.write(next);
        }
        assertArrayEquals(next,NetplaySaveStore.readOnly(directory(current()),current()));
        assertArrayEquals(old,NetplaySaveStore.readOnly(directory(legacy()),legacy()));
        var rows=saves.inspect();assertTrue(rows.getFirst().occupied());assertTrue(rows.get(1).occupied());
    }

    @Test void currentResumeReadsCurrentAndFreshSelectionPreservesBothUntilSuccessfulWrite()throws Exception{
        byte[] old=save(legacy(),11),now=save(current(),12);var saves=catalog();var row=saves.inspect().getFirst();
        var resume=saves.select(choice(row,true));
        try(var lease=NetplayLegacySlot.lease(resume.directory(),resume.identity(),resume.version(),true)){assertArrayEquals(now,lease.read());}
        var fresh=saves.select(choice(row,false));
        try(var lease=NetplayLegacySlot.lease(fresh.directory(),fresh.identity(),fresh.version(),false)){assertNull(lease.read());}
        assertArrayEquals(now,NetplaySaveStore.readOnly(directory(current()),current()));
        assertArrayEquals(old,NetplaySaveStore.readOnly(directory(legacy()),legacy()));
    }

    @Test void staleSelectionAndLateLeaseRecheckCannotOverwriteNewerProgress()throws Exception{
        save(current(),1);var saves=catalog();var oldChoice=choice(saves.inspect().getFirst(),true);
        byte[] newer=save(current(),2);assertThrows(IOException.class,()->saves.select(oldChoice));
        var plan=saves.select(choice(saves.inspect().getFirst(),true));newer=save(current(),3);
        assertThrows(IOException.class,()->NetplayLegacySlot.lease(plan.directory(),plan.identity(),plan.version(),plan.resume()));
        assertArrayEquals(newer,NetplaySaveStore.readOnly(directory(current()),current()));
    }

    @Test void immutableCoreLabelAndPlayerMetadataAreRecheckedInAdapter()throws Exception{
        var saves=catalog();var row=saves.inspect().getFirst();
        assertThrows(IOException.class,()->saves.select(new HomeLaunchNetwork.Choice(1,row.version(),"Mesen-S",1,false)));
        assertThrows(IOException.class,()->saves.select(new HomeLaunchNetwork.Choice(1,row.version(),row.name(),2,false)));
        assertThrows(IllegalArgumentException.class,()->saves.slotKey(3));
        assertThrows(IllegalArgumentException.class,()->saves.select(new HomeLaunchNetwork.Choice(3,"","unknown",1,false)));
        assertThrows(IllegalArgumentException.class,()->saves.select(null));
    }

    @Test void swappedCoreCheckpointFailsClosedAndIsNotConvertedOrTreatedAsEmpty()throws Exception{
        byte[] old=save(legacy(),55);Files.createDirectories(directory(current()));
        Path incorrect=directory(current()).resolve("checkpoint.bin");Files.write(incorrect,old);
        assertThrows(IOException.class,()->catalog().inspect());
        assertArrayEquals(old,Files.readAllBytes(incorrect));
        assertArrayEquals(old,NetplaySaveStore.readOnly(directory(legacy()),legacy()));
    }

    @Test void invalidLegacyCheckpointIsNotHiddenOrReplacedByNewCore()throws Exception{
        save(legacy(),1);Path target=directory(legacy()).resolve("checkpoint.bin");byte[] invalid={1,2,3};Files.write(target,invalid);
        assertThrows(IOException.class,()->catalog().inspect());assertArrayEquals(invalid,Files.readAllBytes(target));
        assertFalse(Files.exists(directory(current())));
    }

    @Test void ownerAndContentRemainPartOfAuthorityWithoutAcceptingNetworkPaths(){
        var first=catalog().withoutSaving();
        var otherOwner=new SfcNetplaySaveCatalog("sfc-card|test-card",ROM,this::directory).withoutSaving();
        var otherContent=new SfcNetplaySaveCatalog(OWNER,"b".repeat(64),this::directory).withoutSaving();
        assertEquals(first.identity(),otherOwner.identity());assertNotEquals(first.slot(),otherOwner.slot());
        assertEquals(first.identity().profile(),otherContent.identity().profile());
        assertNotEquals(first.identity().content(),otherContent.identity().content());assertNotEquals(first.slot(),otherContent.slot());
    }

    @Test void accidentalSharedDirectoryBetweenCoreIdentitiesIsRejected(){
        assertThrows(IllegalArgumentException.class,()->new SfcNetplaySaveCatalog(OWNER,ROM,identity->temporary));
    }
}
