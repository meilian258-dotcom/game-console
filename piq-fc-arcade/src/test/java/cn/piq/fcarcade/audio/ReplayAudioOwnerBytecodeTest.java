package cn.piq.fcarcade.audio;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Reads actual compiled owners without initializing Minecraft, native cores or JavaSound. */
class ReplayAudioOwnerBytecodeTest {
    private static final String OUTPUT = "cn/piq/fcarcade/audio/ReplayAudioOutput";
    private static final String LINE = "javax/sound/sampled/SourceDataLine";
    private record Call(String method, int opcode, String owner, String name, String descriptor) {}
    private static List<Call> calls(String owner) throws IOException {
        var calls = new ArrayList<Call>();
        try (var input = ReplayAudioOwnerBytecodeTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input, owner);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        public void visitMethodInsn(int opcode, String target, String call, String desc, boolean itf) {
                            calls.add(new Call(name + descriptor, opcode, target, call, desc));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
    private static void hooked(List<Call> calls, String method, String name) {
        String descriptor = name.equals("write") ? "(Ljavax/sound/sampled/SourceDataLine;[BII)I" : "(Ljavax/sound/sampled/SourceDataLine;)V";
        assertTrue(calls.stream().anyMatch(c -> c.method.equals(method) && c.opcode == Opcodes.INVOKESTATIC
                && c.owner.equals(OUTPUT) && c.name.equals(name) && c.descriptor.equals(descriptor)), method + " -> " + name);
    }
    private static void noBypass(List<Call> calls) {
        assertFalse(calls.stream().anyMatch(c -> c.owner.equals(LINE) && List.of("write", "flush", "close").contains(c.name)), "unhooked final audio endpoint");
        assertFalse(calls.stream().anyMatch(c -> c.owner.startsWith("cn/piq/replaycompat/")), "host must not depend on optional replay addon");
    }
    @Test void nesEveryFinalWriteFlushAndCloseUsesPublicOutput() throws Exception {
        var calls = calls("cn/piq/fcarcade/audio/NesAudioPlayer"); noBypass(calls);
        for (String name : List.of("write", "flush", "close")) hooked(calls, "playbackLoop()V", name);
        hooked(calls, "clear()V", "flush");
        hooked(calls, "close()V", "flush"); hooked(calls, "close()V", "close");
        assertEquals(1, calls.stream().filter(c -> c.owner.equals(OUTPUT) && c.name.equals("write")).count());
    }
    @Test void cabinetFinalOutputUsesPublicApiWithoutMovingDeviceCallsToLifecycleCaller() throws Exception {
        var calls = calls("cn/piq/fcarcade/client/cabinet/CabinetAudio"); noBypass(calls);
        for (String name : List.of("write", "flush", "close")) hooked(calls, "run()V", name);
        assertEquals(1, calls.stream().filter(c -> c.owner.equals(OUTPUT) && c.name.equals("write")).count());
        assertFalse(calls.stream().anyMatch(c -> List.of("reset()V", "close()V").contains(c.method)
                && (c.owner.equals(OUTPUT) || c.owner.equals(LINE))));
    }
    @Test void outputOwnsExactlyOneOriginalDeviceOperationAndNoAddonDependency() throws Exception {
        var calls = calls(OUTPUT);
        for (String name : List.of("write", "flush", "close"))
            assertEquals(1, calls.stream().filter(c -> c.owner.equals(LINE) && c.name.equals(name)).count(), name);
        assertFalse(calls.stream().anyMatch(c -> c.owner.startsWith("cn/piq/replaycompat/")));
        var bridge = calls(OUTPUT + "$1");
        for (String name : List.of("captureToken", "capture", "reset", "close"))
            assertEquals(1, bridge.stream().filter(c -> c.owner.equals("cn/piq/retro/replay/ReplayAudio") && c.name.equals(name)).count(), name);
    }
}
