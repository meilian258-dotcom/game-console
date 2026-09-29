package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.fixtures.NativeSaveTestRom;
import cn.piq.fcarcade.session.NesPersistentState;
import cn.piq.retro.libretro.LibretroSaveMemory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveResume65Test {
    private GenericLibretroNesCore start(byte[] rom) {
        var core=new GenericLibretroNesCore(false);
        try{core.loadRom(rom);return core;}catch(RuntimeException e){core.close();throw e;}
    }
    @Test void realCoreResumesOldAndNewFormatsAndCapturesNativeProgress() {
        byte[] old, saved;
        try(var core=start(NativeSaveTestRom.bytes())) {
            core.runFrame(); old=core.saveTransientState(); saved=core.savePersistentState();
            var parts=NesPersistentState.decode(saved); assertArrayEquals(old,parts.snapshot());
            assertEquals(0x5a,parts.memory().ram()[0]&255);
        }
        for(byte[] value:new byte[][]{old,saved})try(var core=start(NativeSaveTestRom.bytes())) {
            core.loadPersistentState(value); assertArrayEquals(old,core.saveTransientState());
            core.runFrame(); assertEquals(0x5a,NesPersistentState.decode(core.savePersistentState()).memory().ram()[0]&255);
        }
    }
    @Test void exactResumeWinsOverDifferentNativeBytesInTheBundle() {
        byte[] saved;
        try(var core=start(NativeSaveTestRom.bytes())){core.runFrame();saved=core.savePersistentState();}
        var parts=NesPersistentState.decode(saved); byte[] changed=parts.memory().ram();changed[0]=99;
        byte[] staged=NesPersistentState.encode(parts.snapshot(),parts.identity(),new LibretroSaveMemory(changed,parts.memory().rtc()));
        try(var core=start(NativeSaveTestRom.bytes())){
            core.loadPersistentState(staged);
            assertEquals(0x5a,NesPersistentState.decode(core.savePersistentState()).memory().ram()[0]&255);
            assertArrayEquals(parts.snapshot(),core.saveTransientState());
        }
    }
    @Test void wrongGameOrChecksumCannotMutateRunningCore() {
        byte[] saved;
        try(var core=start(NativeSaveTestRom.bytes())){core.runFrame();saved=core.savePersistentState();}
        byte[] other=NativeSaveTestRom.bytes();other[100]=1;
        try(var core=start(other)){
            byte[] before=core.saveTransientState();
            assertThrows(IllegalArgumentException.class,()->core.loadPersistentState(saved));
            assertArrayEquals(before,core.saveTransientState());
        }
        byte[] broken=saved.clone();broken[55]^=1;
        try(var core=start(NativeSaveTestRom.bytes())){
            byte[] before=core.saveTransientState();
            assertThrows(IllegalArgumentException.class,()->core.loadPersistentState(broken));
            assertArrayEquals(before,core.saveTransientState());
        }
    }
    @Test void differentArtifactUsesCompatibleSnapshotWithoutImportingMemory() {
        byte[] saved;
        try(var core=start(NativeSaveTestRom.bytes())){core.runFrame();saved=core.savePersistentState();}
        var parts=NesPersistentState.decode(saved);byte[] identity=parts.identity();identity[0]^=1;
        byte[] value=NesPersistentState.encode(parts.snapshot(),identity,new LibretroSaveMemory(new byte[]{99},new byte[]{99}));
        try(var core=start(NativeSaveTestRom.bytes())){
            core.loadPersistentState(value);assertArrayEquals(parts.snapshot(),core.saveTransientState());
        }
    }
}
