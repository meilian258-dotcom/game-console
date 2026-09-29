// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.core;

import cn.piq.retro.libretro.*;
import cn.piq.sfcarcade.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests transport replacement, state and recreation with no native calls. */
class LibretroSfcRuntimeInjectionTest {
    @Test void hardResetAndSramUseFreshSelectedRuntimeAndPreserveMemory(){
        List<Fake> sessions=new ArrayList<>();
        try(var core=new LibretroSfcCore(()->{var next=new Fake();sessions.add(next);return next;})){
            core.loadRom(SfcRomImage.fromBytes(new byte[32768]));assertEquals(1,sessions.size());
            core.runFrame(new SfcControllerState(256),SfcControllerState.NONE);
            assertArrayEquals(new int[]{256,0},sessions.getFirst().controls.getFirst().pads());
            byte[] save=core.saveState();sessions.getFirst().state[0]=55;core.loadState(save);assertEquals(3,sessions.getFirst().state[0]);
            sessions.getFirst().ram[0]=27;core.reset(true);assertEquals(2,sessions.size());
            assertTrue(sessions.getFirst().closed);assertArrayEquals(new byte[]{27},core.saveSram());
            core.loadSram(new byte[]{91});assertEquals(3,sessions.size());assertTrue(sessions.get(1).closed);
            assertArrayEquals(new byte[]{91},core.saveSram());
        }
        assertTrue(sessions.getLast().closed);
    }
    @Test void invalidOutputClosesSelectedRuntimeAndNeverBuildsProcessFallback(){
        Fake wrong=new Fake();wrong.av=new LibretroProcess.Info(256,224,256,224,4f/3,60,44100,1);
        try(var core=new LibretroSfcCore(()->wrong)){
            assertThrows(IllegalStateException.class,()->core.loadRom(SfcRomImage.fromBytes(new byte[32768])));
            assertTrue(wrong.closed);
        }
    }
    @Test void diagnosticIsVisibleDuringBlockedInitializationWithoutEnteringNativeOwner()throws Exception{
        var created=new AtomicReference<LibretroSfcCore>();var runtime=new AtomicReference<Fake>();var failure=new AtomicReference<Throwable>();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var owner=new Thread(()->{
            try(var core=new LibretroSfcCore(()->{var fake=new Fake();fake.started=started;fake.release=release;runtime.set(fake);return fake;})){
                created.set(core);core.loadRom(SfcRomImage.fromBytes(new byte[32768]));
            }catch(Throwable error){failure.set(error);}
        });owner.start();
        try{
            assertTrue(started.await(3,TimeUnit.SECONDS));runtime.get().diagnostic="JNI初始化超时";
            assertEquals("JNI初始化超时",created.get().diagnosticError());assertFalse(runtime.get().closed);
        }finally{release.countDown();owner.join(3000);}
        assertFalse(owner.isAlive());assertNull(failure.get());assertTrue(runtime.get().closed);
    }
    private static final class Fake implements LibretroRuntime {
        final Thread owner=Thread.currentThread();volatile boolean closed;volatile String diagnostic="";
        CountDownLatch started,release;
        LibretroProcess.Info av=new LibretroProcess.Info(256,224,256,224,4f/3,60,32040,1);
        byte[] ram={7},state={3};List<LibretroProcess.Controls> controls=List.of();
        private void own(){assertSame(owner,Thread.currentThread());assertFalse(closed);}
        public LibretroProcess.Info load(byte[] bytes){
            own();if(started!=null){started.countDown();try{if(!release.await(4,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}}
            return av;
        }
        public LibretroProcess.Info info(){own();return av;}
        public String coreVersion(){own();return "fixture";}
        public String diagnosticError(){return diagnostic;}
        public LibretroProcess.Output run(List<LibretroProcess.Controls> frames,int mask){return runWithMemory(frames,mask,-1);}
        public LibretroProcess.Output runWithMemory(List<LibretroProcess.Controls> frames,int mask,int id){
            own();controls=frames;return new LibretroProcess.Output(av,false,new byte[256*224*4],new short[]{3,4},new byte[0]);
        }
        public LibretroProcess.Info reset(){own();return av;}
        public byte[] serialize(){own();return state.clone();}
        public void restore(byte[] value){own();state=value.clone();}
        public byte[] memory(int id){own();return id==0?ram.clone():new byte[0];}
        public LibretroSaveMemory saveMemory(){own();return new LibretroSaveMemory(ram,new byte[0]);}
        public void restoreSaveMemory(LibretroSaveMemory memory){own();ram=memory.ram();}
        public byte[] persistenceIdentity(){own();return new byte[32];}
        public void close(){assertSame(owner,Thread.currentThread());closed=true;}
    }
}
