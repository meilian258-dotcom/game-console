package cn.piq.replaycompat.audio.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Every final device call is covered exactly once; targets are read, never initialized. */
public final class AudioCaptureBytecodeTest {
    private static final String LINE = "javax/sound/sampled/SourceDataLine";
    private static final String BRIDGE = "cn/piq/replaycompat/audio/capture/JavaSoundCapture";
    private static final String PUBLIC_OUTPUT = "cn/piq/fcarcade/audio/ReplayAudioOutput";
    private static final List<String> ADAPTERS = List.of("Sfc", "Gba",
            "NativeArcade", "Computer", "Pvz", "PvzJni");
    private static int assertions, targets, missing, writes, flushes, closes, publicTargets;

    public static void main(String[] args) throws Exception {
        Path config = Path.of("piq-replay-compat/src/main/resources/piq_replay_compat.mixins.json");
        if (!Files.isRegularFile(config)) config = Path.of("src/main/resources/piq_replay_compat.mixins.json");
        String manifest = Files.readString(config);
        publicEndpoints(manifest);
        Set<String> seen = new HashSet<>();
        for (String name : ADAPTERS) {
            String adapter = "CaptureAudio" + name;
            check(manifest.indexOf('"' + adapter + '"') >= 0, "Unconfigured audio adapter " + adapter);
            ClassNode mixin = read("cn/piq/replaycompat/mixin/" + adapter);
            check(mixin != null, "Missing compiled adapter " + adapter);
            check(annotation(mixin.visibleAnnotations, "/Pseudo;") != null
                    || annotation(mixin.invisibleAnnotations, "/Pseudo;") != null, "Optional target must be @Pseudo");
            AnnotationNode targetAnnotation = annotation(mixin.invisibleAnnotations, "/Mixin;");
            if (targetAnnotation == null) targetAnnotation = annotation(mixin.visibleAnnotations, "/Mixin;");
            List<?> names = list(value(targetAnnotation, "targets"));
            equal(1, names.size());
            String targetName = names.getFirst().toString().replace('.', '/');
            check(targetName.startsWith("cn/piq/"), "Must never transform JDK/global audio");
            check(seen.add(targetName), "Duplicate capture endpoint " + targetName);
            ClassNode target = read(targetName);
            if (target == null) {
                check(!targetName.startsWith("cn/piq/fcarcade/"), "Missing required FC target");
                missing++; continue;
            }
            targets++;
            List<Hook> hooks = hooks(mixin);
            int targetWrites = 0;
            for (MethodNode method : target.methods) {
                for (var instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(LINE)
                            || !Set.of("write", "flush", "close").contains(call.name)) continue;
                    equal(Opcodes.INVOKEINTERFACE, call.getOpcode()); check(call.itf, "Expected JavaSound interface call");
                    String selector = "L" + call.owner + ";" + call.name + call.desc;
                    int matches = 0;
                    for (Hook hook : hooks) {
                        if (hook.selectors.contains(method.name + method.desc) && hook.target.equals(selector)) {
                            matches++;
                            Type[] original = Type.getArgumentTypes(call.desc), redirected = Type.getArgumentTypes(hook.method.desc);
                            equal(original.length + 1, redirected.length);
                            equal(Type.getObjectType(LINE), redirected[0]);
                            for (int i = 0; i < original.length; i++) equal(original[i], redirected[i + 1]);
                            equal(Type.getReturnType(call.desc), Type.getReturnType(hook.method.desc));
                        }
                    }
                    equal(1, matches); // Includes compiler-duplicated finally calls.
                    switch (call.name) {
                        case "write" -> { writes++; targetWrites++; }
                        case "flush" -> flushes++;
                        case "close" -> closes++;
                    }
                }
            }
            equal(1, targetWrites);
            for (Hook hook : hooks) for (String selector : hook.selectors) {
                List<MethodNode> methods = target.methods.stream().filter(m -> (m.name + m.desc).equals(selector)).toList();
                equal(1, methods.size());
                check((methods.getFirst().access & Opcodes.ACC_STATIC) == 0, "Expected instance audio endpoint");
                boolean matched = false;
                for (var instruction : methods.getFirst().instructions)
                    if (instruction instanceof MethodInsnNode call
                            && hook.target.equals("L" + call.owner + ";" + call.name + call.desc)) matched = true;
                check(matched, "Hook has no exact production invocation: " + targetName + "." + selector);
            }
        }
        check(writes >= 2 && flushes > 0 && closes > 0, "Required final output coverage missing");
        System.out.println("AudioCaptureBytecodeTest: " + assertions + " assertions; " + targets
                + " actual targets, " + writes + " writes, " + flushes + " flushes, " + closes
                + " closes covered exactly once; " + publicTargets + " public API owners; "
                + missing + " optional absent legacy targets not validated");
    }

    private static void publicEndpoints(String manifest) throws IOException {
        for (String retired : List.of("CaptureAudioNes", "CaptureAudioCabinet"))
            check(!manifest.contains('"' + retired + '"'), "Public output must not also use legacy Mixin " + retired);
        for (String owner : List.of("cn/piq/fcarcade/audio/NesAudioPlayer", "cn/piq/fcarcade/client/cabinet/CabinetAudio")) {
            ClassNode target = read(owner); check(target != null, "Required public audio owner missing: " + owner);
            int ownerWrites = 0, ownerFlushes = 0, ownerCloses = 0;
            Set<String> covered = new HashSet<>();
            for (MethodNode method : target.methods) for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                check(!(call.owner.equals(LINE) && Set.of("write", "flush", "close").contains(call.name)), "Uncaptured public final output " + owner);
                check(!call.owner.startsWith("cn/piq/replaycompat/"), "Host cannot depend on optional addon");
                if (!call.owner.equals(PUBLIC_OUTPUT)) continue;
                equal(Opcodes.INVOKESTATIC, call.getOpcode());
                String descriptor = call.name.equals("write") ? "(Ljavax/sound/sampled/SourceDataLine;[BII)I" : "(Ljavax/sound/sampled/SourceDataLine;)V";
                equal(descriptor, call.desc); check(!call.itf, "Output is a class, not an interface");
                covered.add(method.name + method.desc + ":" + call.name);
                switch (call.name) {
                    case "write" -> { writes++; ownerWrites++; }
                    case "flush" -> { flushes++; ownerFlushes++; }
                    case "close" -> { closes++; ownerCloses++; }
                    default -> throw new AssertionError("Unknown public audio operation " + call.name);
                }
            }
            equal(1, ownerWrites); check(ownerFlushes > 0 && ownerCloses > 0, "Public lifecycle must be covered");
            if (owner.endsWith("/NesAudioPlayer")) {
                for (String required : List.of("playbackLoop()V:write", "playbackLoop()V:flush", "playbackLoop()V:close", "clear()V:flush", "close()V:flush", "close()V:close"))
                    check(covered.contains(required), "Missing public NES endpoint " + required);
            } else {
                for (String required : List.of("run()V:write", "run()V:flush", "run()V:close"))
                    check(covered.contains(required), "Missing public cabinet endpoint " + required);
                check(covered.stream().allMatch(call -> call.startsWith("run()V:")), "Cabinet device operations must remain on its worker");
            }
            targets++; publicTargets++;
        }
    }

    private record Hook(MethodNode method, Set<String> selectors, String target) {}
    private static List<Hook> hooks(ClassNode mixin) {
        List<Hook> hooks = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            AnnotationNode redirect = annotation(method.visibleAnnotations, "/Redirect;");
            if (redirect == null) redirect = annotation(method.invisibleAnnotations, "/Redirect;");
            if (redirect == null) continue;
            equal(1, value(redirect, "require"));
            AnnotationNode at = (AnnotationNode) value(redirect, "at");
            equal("INVOKE", value(at, "value"));
            String target = value(at, "target").toString();
            check(target.startsWith("L" + LINE + ";"), "Unexpected capture target");
            String operation = target.substring(target.indexOf(';') + 1, target.indexOf('('));
            int forwards = 0;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                equal(BRIDGE, call.owner); equal(operation, call.name);
                equal(method.desc, call.desc); equal(Opcodes.INVOKESTATIC, call.getOpcode()); forwards++;
            }
            equal(1, forwards);
            Set<String> selectors = new HashSet<>();
            for (Object selector : list(value(redirect, "method"))) check(selectors.add(selector.toString()), "Duplicate selector");
            hooks.add(new Hook(method, selectors, target));
        }
        check(hooks.size() == 2 || hooks.size() == 3, "Expected one write, one close, optional flush hook");
        return hooks;
    }
    private static ClassNode read(String name) throws IOException {
        try (var input = AudioCaptureBytecodeTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) return null;
            ClassNode result = new ClassNode(); new ClassReader(input).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result;
        }
    }
    private static AnnotationNode annotation(List<AnnotationNode> annotations, String suffix) {
        if (annotations != null) for (AnnotationNode node : annotations) if (node.desc.endsWith(suffix)) return node;
        return null;
    }
    private static Object value(AnnotationNode annotation, String key) {
        if (annotation != null && annotation.values != null)
            for (int i = 0; i < annotation.values.size(); i += 2)
                if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean value, String detail) { assertions++; if (!value) throw new AssertionError(detail); }
}
