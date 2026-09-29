package cn.piq.fcarcade.core.wasm;

import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WasmNesCoreOwnershipTest {
    @Test
    void nativeCoreRejectsAllOperationsIncludingCloseOnAnotherThread() throws Exception {
        try (WasmNesCore core = new WasmNesCore()) {
            List<Runnable> calls = List.of(
                    () -> core.loadRom(new byte[0]), core::reset,
                    () -> core.setControllerState(0, 1), core::runFrame,
                    () -> core.copyFrameRgba(new byte[NesCore.RGBA_BYTES]),
                    () -> core.copyAudioSamples(new float[1]),
                    () -> core.copyCpuRam(new byte[NesCore.CPU_RAM_BYTES]),
                    core::saveTransientState,
                    () -> core.loadTransientState(new byte[0]), core::close);
            var rejected = new CopyOnWriteArrayList<Throwable>();
            Thread other = new Thread(() -> {
                for (Runnable call : calls) {
                    try { call.run(); }
                    catch (Throwable error) { rejected.add(error); }
                }
            }, "FC-ownership-regression");
            other.start();
            other.join(5000);
            assertFalse(other.isAlive());
            assertEquals(calls.size(), rejected.size());
            for (Throwable error : rejected) {
                assertInstanceOf(IllegalStateException.class, error);
                assertTrue(error.getMessage().contains("线程"));
            }
        }
    }
}
