// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetNetplayContentBindingTest {
    private static final String BACKEND="piq_native_arcade:arcade";
    private final Object connection=new Object();
    private final UUID player=UUID.randomUUID(),lease=UUID.randomUUID();
    private final CabinetNetplayContentBinding binding=new CabinetNetplayContentBinding(connection,player,lease,BACKEND);
    private final AtomicInteger registrations=new AtomicInteger();
    private CabinetGameManifest game(String name,int revision,boolean bios){
        var main=new CabinetGameManifest.Entry(name,CabinetGameManifest.digest(new byte[]{(byte)revision}),32);
        return new CabinetGameManifest(BACKEND,bios?List.of(main,
                new CabinetGameManifest.Entry("neogeo.zip",CabinetGameManifest.digest(new byte[]{99}),64)):List.of(main));
    }
    private boolean bind(CabinetGameManifest manifest,boolean ready){
        return binding.bind(connection,player,lease,BACKEND,ready,manifest,registrations::incrementAndGet);
    }
    @Test void startsUnboundAndRegistersOnlyTheNewlyVerifiedGame(){
        var old=game("oldgame.zip",1,false);var selected=game("newgame.zip",2,true);
        assertFalse(binding.matches(old));assertFalse(binding.matches(selected));assertEquals(0,registrations.get());
        assertTrue(bind(selected,false));assertTrue(binding.matches(selected));assertFalse(binding.matches(old));
        assertEquals(1,registrations.get());
    }
    @Test void firstContentMayIncludeBiosAddedSinceTheOldCabinetSelection(){
        var old=game("samsho.zip",1,false);var completedUpload=game("samsho.zip",1,true);
        assertEquals(old.gameHash(),completedUpload.gameHash());assertNotEquals(old.contentId(),completedUpload.contentId());
        assertTrue(bind(completedUpload,false));assertTrue(binding.matches(completedUpload));assertFalse(binding.matches(old));
    }
    @Test void repeatedIdenticalEndIsIdempotentBeforeAndAfterReady(){
        assertTrue(bind(game("samsho.zip",1,true),false));
        assertFalse(bind(game("samsho.zip",1,true),false));
        assertFalse(bind(game("samsho.zip",1,true),true));assertEquals(1,registrations.get());
    }
    @Test void differentGameCannotReplaceAnAlreadyBoundRun(){
        var first=game("samsho.zip",1,true);assertTrue(bind(first,false));
        assertThrows(IllegalStateException.class,()->bind(game("samsho2.zip",2,true),false));
        assertThrows(IllegalStateException.class,()->bind(game("samsho2.zip",2,true),true));
        assertTrue(binding.matches(first));assertEquals(1,registrations.get());
    }
    @Test void sameRomWithChangedBiosCannotRebind(){
        var first=game("samsho.zip",1,false);assertTrue(bind(first,false));
        assertThrows(IllegalStateException.class,()->bind(game("samsho.zip",1,true),false));
        assertTrue(binding.matches(first));assertEquals(1,registrations.get());
    }
    @Test void firstBindingAfterReadyIsRejected(){
        var game=game("samsho.zip",1,true);
        assertThrows(IllegalStateException.class,()->bind(game,true));
        assertFalse(binding.matches(game));assertEquals(0,registrations.get());
    }
    @Test void guestAndWatchLeasesCannotRegisterOrReplaceHostContent(){
        var game=game("samsho.zip",1,true);
        assertThrows(IllegalStateException.class,()->binding.bind(connection,UUID.randomUUID(),UUID.randomUUID(),BACKEND,false,game,registrations::incrementAndGet));
        assertThrows(IllegalStateException.class,()->binding.bind(connection,player,UUID.randomUUID(),BACKEND,false,game,registrations::incrementAndGet));
        assertEquals(0,registrations.get());assertTrue(bind(game,false));
        assertThrows(IllegalStateException.class,()->binding.bind(connection,UUID.randomUUID(),UUID.randomUUID(),BACKEND,true,game,registrations::incrementAndGet));
        assertTrue(binding.matches(game));assertEquals(1,registrations.get());
    }
    @Test void replacementConnectionCannotUseTheOldHostLeaseEvenIfEqual(){
        String original=new String("connection"),replacement=new String("connection");
        var exact=new CabinetNetplayContentBinding(original,player,lease,BACKEND);
        assertEquals(original,replacement);assertFalse(exact.owns(replacement,player,lease,BACKEND));
        assertThrows(IllegalStateException.class,()->exact.bind(replacement,player,lease,BACKEND,false,game("samsho.zip",1,true),registrations::incrementAndGet));
        assertEquals(0,registrations.get());
    }
    @Test void backendAndManifestBackendMustBothMatch(){
        var game=game("samsho.zip",1,true);
        assertThrows(IllegalStateException.class,()->binding.bind(connection,player,lease,"other:arcade",false,game,registrations::incrementAndGet));
        var foreign=new CabinetGameManifest("other:arcade",game.files());
        assertThrows(IllegalStateException.class,()->bind(foreign,false));assertEquals(0,registrations.get());
    }
    @Test void registrarFailureDoesNotPretendContentWasBound(){
        var first=game("samsho.zip",1,true);
        assertThrows(IllegalStateException.class,()->binding.bind(connection,player,lease,BACKEND,false,first,()->{throw new IllegalStateException("save slot busy");}));
        assertFalse(binding.matches(first));assertTrue(bind(first,false));assertEquals(1,registrations.get());
    }
    @Test void cancellationBeforeBindingRejectsLateEnd(){
        binding.retire();var game=game("samsho.zip",1,true);
        assertThrows(IllegalStateException.class,()->bind(game,false));assertFalse(binding.matches(game));assertEquals(0,registrations.get());
    }
    @Test void cancellationAfterBindingRevokesEvenAnIdenticalLateEnd(){
        var game=game("samsho.zip",1,true);assertTrue(bind(game,false));binding.retire();binding.retire();
        assertFalse(binding.matches(game));assertThrows(IllegalStateException.class,()->bind(game,false));
        assertThrows(IllegalStateException.class,()->bind(game("samsho2.zip",2,true),false));assertEquals(1,registrations.get());
    }
    @Test void registrarCannotReenterAndRegisterTwice(){
        var game=game("samsho.zip",1,true);
        assertTrue(binding.bind(connection,player,lease,BACKEND,false,game,()->{
            registrations.incrementAndGet();assertThrows(IllegalStateException.class,()->bind(game,false));
        }));
        assertTrue(binding.matches(game));assertEquals(1,registrations.get());
    }
    @Test void retirementDuringRegistrarNeverPublishesBoundContent(){
        var game=game("samsho.zip",1,true);
        assertThrows(IllegalStateException.class,()->binding.bind(connection,player,lease,BACKEND,false,game,binding::retire));
        assertFalse(binding.matches(game));assertThrows(IllegalStateException.class,()->bind(game,false));
    }
}
