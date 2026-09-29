// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.retro.libretro.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes adapter contracts without entering a native library or Minecraft. */
class LibretroRuntimeInjectionTest {
    private static byte[] rom(){byte[] out=new byte[16+16384];out[0]='N';out[1]='E';out[2]='S';out[3]=26;out[4]=1;return out;}
    @Test void injectedRuntimeKeepsInputAudioMemoryAndStateSemantics(){
        FakeRuntime runtime=new FakeRuntime();
        try(var core=new GenericLibretroNesCore(false,runtime)){
            core.loadRom(rom());core.setControllerState(0,1);core.setControllerState(1,2);core.runFrame();
            assertArrayEquals(new int[]{256,1},runtime.controls.getFirst().pads());assertEquals(2,runtime.requestedMemory);
            byte[] picture=new byte[NesCore.RGBA_BYTES],ram=new byte[NesCore.CPU_RAM_BYTES];float[] audio=new float[4096];
            core.copyFrameRgba(picture);core.copyCpuRam(ram);assertEquals(1,core.copyAudioSamples(audio));
            assertEquals(42,picture[0]);assertEquals(9,ram[0]);assertEquals(.5f,audio[0]);
            byte[] saved=core.savePersistentState();runtime.ram[0]=21;runtime.state[0]=11;
            core.loadPersistentState(saved);assertEquals(7,runtime.ram[0]);assertEquals(3,runtime.state[0]);
            byte[] invalid=saved.clone();invalid[12]^=1;assertThrows(IllegalArgumentException.class,()->core.loadPersistentState(invalid));
        }
        assertTrue(runtime.closed);
    }
    @Test void injectedRuntimeStillRejectsWrongOwnerBeforeCallingIt()throws Exception{
        FakeRuntime runtime=new FakeRuntime();var core=new GenericLibretroNesCore(false,runtime);
        try{
            core.loadRom(rom());int prior=runtime.calls;var failure=new AtomicReference<Throwable>();
            var thread=new Thread(()->{try{core.runFrame();}catch(Throwable e){failure.set(e);}});thread.start();thread.join();
            assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(prior,runtime.calls);
        }finally{core.close();}
    }
    @Test void rejectedGeometryClosesSelectedRuntimeInsteadOfFallingBack(){
        FakeRuntime runtime=new FakeRuntime();runtime.av=new LibretroProcess.Info(1,1,1,1,1,60,44100,1);
        var core=new GenericLibretroNesCore(false,runtime);
        assertThrows(IllegalStateException.class,()->core.loadRom(rom()));assertTrue(runtime.closed);
    }
    @Test void diagnosticCanBeReadWithoutInvokingOwnerThreadMethods()throws Exception{
        FakeRuntime runtime=new FakeRuntime();var core=new GenericLibretroNesCore(false,runtime);
        try{
            runtime.diagnostic="JNI初始化超时";int before=runtime.calls;var observed=new AtomicReference<String>();
            var reader=new Thread(()->observed.set(core.diagnosticError()));reader.start();reader.join();
            assertEquals(runtime.diagnostic,observed.get());assertEquals(before,runtime.calls);
        }finally{core.close();}
    }
    private static final class FakeRuntime implements LibretroRuntime {
        LibretroProcess.Info av=new LibretroProcess.Info(256,240,256,240,4f/3,60.0988,44100,1);
        final Thread owner=Thread.currentThread();boolean closed;int requestedMemory,calls;volatile String diagnostic="";
        byte[] state={3},ram={7};List<LibretroProcess.Controls> controls=List.of();
        private void own(){assertSame(owner,Thread.currentThread());assertFalse(closed);calls++;}
        public LibretroProcess.Info load(byte[] bytes){own();return av;}
        public LibretroProcess.Info info(){own();return av;}
        public String coreVersion(){own();return "fixture";}
        public String diagnosticError(){return diagnostic;}
        public LibretroProcess.Output run(List<LibretroProcess.Controls> frames,int mask){return runWithMemory(frames,mask,-1);}
        public LibretroProcess.Output runWithMemory(List<LibretroProcess.Controls> frames,int mask,int id){
            own();controls=frames;requestedMemory=id;byte[] video=new byte[NesCore.RGBA_BYTES],memory=new byte[NesCore.CPU_RAM_BYTES];
            video[0]=42;memory[0]=9;return new LibretroProcess.Output(av,false,video,new short[]{16384,16384},memory);
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
