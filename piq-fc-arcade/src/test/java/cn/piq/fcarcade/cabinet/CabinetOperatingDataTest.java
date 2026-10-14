package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetOperatingDataTest {
    private final CabinetTarget target=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),true);
    private CabinetGameManifest game(int n){return new CabinetGameManifest("test:arcade",List.of(new CabinetGameManifest.Entry("kov.zip",Integer.toHexString(n).repeat(64),100)));}
    @Test void reopenSameCabinetContentKeepsGenerationAcrossSaveReload(){
        var data=new CabinetOperatingData();var first=data.prepare(target,game(1));assertFalse(data.current(first));data.commit(first);
        var loaded=CabinetOperatingData.load(data.save(new CompoundTag(),null),null);
        assertEquals(first.generation(),loaded.prepare(target,game(1)).generation());assertTrue(loaded.current(first));
    }
    @Test void failedOrCancelledNewSelectionDoesNotChangeOldGeneration(){
        var data=new CabinetOperatingData();var first=data.prepare(target,game(1));data.commit(first);var rejected=data.prepare(target,game(2));
        assertTrue(data.current(first));assertFalse(data.current(rejected));assertEquals(first.generation(),data.prepare(target,game(1)).generation());
    }
    @Test void committedGameSwitchStartsNewGenerationAndNeverRevivesOldSettings(){
        var data=new CabinetOperatingData();var first=data.prepare(target,game(1));data.commit(first);var second=data.prepare(target,game(2));data.commit(second);
        assertFalse(data.current(first));assertTrue(data.current(second));var back=data.prepare(target,game(1));data.commit(back);
        assertNotEquals(first.generation(),back.generation());assertThrows(IllegalStateException.class,()->data.commit(first));assertTrue(data.current(back));
    }
    @Test void stalePreparedDifferentGameCannotRollBackNewGeneration(){
        var data=new CabinetOperatingData();var old=data.prepare(target,game(1));data.commit(old);
        var stale=data.prepare(target,game(2));var latest=data.prepare(target,game(3));data.commit(latest);
        assertThrows(IllegalStateException.class,()->data.commit(stale));assertTrue(data.current(latest));
    }
    @Test void reusedPositionWithNewCabinetIdentityCannotUsePriorSettings(){
        var data=new CabinetOperatingData();var old=data.prepare(target,game(1));data.commit(old);
        var replaced=new CabinetTarget(target.dimension(),target.anchor(),UUID.randomUUID(),true);
        assertNotEquals(old.key(),data.prepare(replaced,game(1)).key());assertNotEquals(old.generation(),data.prepare(replaced,game(1)).generation());
    }
}
