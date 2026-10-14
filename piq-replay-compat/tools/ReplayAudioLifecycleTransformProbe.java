import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Real production Mixin application, plus isolated execution of the merged export wrapper.
 * Only ReplayBridge calls in the executable fixture are substituted with counters; the
 * actual transformed production callback's control flow and exception handlers are retained.
 * Compile with the existing KeyboardMixinTransformProbe read-only QA host. No MC/audio opens. */
public final class ReplayAudioLifecycleTransformProbe {
    private static final String BRIDGE = "cn/piq/replaycompat/replay/ReplayBridge";
    private static int checks, wrapperCases;
    private static Method fixture;
    private static Object receiver;
    private static void require(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static ClassNode node(byte[] bytes) { ClassNode result = new ClassNode(); new ClassReader(bytes).accept(result, 0); return result; }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] resource(KeyboardMixinTransformProbe.Host host, String name) throws Exception {
        try (var stream = host.getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("Required bytecode missing: " + name);
            return stream.readAllBytes();
        }
    }
    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    private static List<String> instructions(MethodNode method) throws Exception {
        Method reader = KeyboardMixinTransformProbe.class.getDeclaredMethod("instructions", MethodNode.class);
        reader.setAccessible(true); return (List<String>) reader.invoke(null, method);
    }
    private static boolean calls(MethodNode method, String name) {
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE) && call.name.equals(name)) return true;
        return false;
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[new-receipt.json]");
        System.setProperty("mixin.bootstrapService", KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service", KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();
        var environment = MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var host = (KeyboardMixinTransformProbe.Host) MixinService.getService();
        var transformer = host.transformer(); MixinExtrasBootstrap.init();
        Mixins.addConfiguration("piq_audio_lifecycle_probe.mixins.json");
        List<String> receipts = new ArrayList<>();
        for (String target : List.of("com.replaymod.replay.ReplayHandler", "com.replaymod.render.rendering.VideoRenderer")) {
            String adapter = "cn.piq.replaycompat.mixin." + (target.endsWith("ReplayHandler")
                    ? "ReplayAudioTimelineMixin" : "ReplayVideoRendererMixin");
            byte[] mixin = resource(host, adapter);
            byte[] original = resource(host, target), transformed = transformer.transformClass(environment, target, original);
            require(transformed != null && !Arrays.equals(original, transformed), "Actual Replay target transformed");
            ClassNode before = node(original), after = node(transformed);
            if (target.endsWith("ReplayHandler")) {
                for (String[] selector : List.of(new String[]{"doJump", "(IZ)V"}, new String[]{"setQuickMode", "(Z)V"})) {
                    MethodNode source = method(before, selector[0], selector[1]), actual = method(after, selector[0], selector[1]);
                    List<String> oldCode = instructions(source), newCode = instructions(actual);
                    int prefix = newCode.size() - oldCode.size();
                    require(prefix > 0 && newCode.subList(prefix, newCode.size()).equals(oldCode), "Timeline HEAD preserves complete original body: " + selector[0]);
                    int found = 0;
                    for (var instruction : actual.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(after.name)) {
                        var candidates = after.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc) && calls(m, "timelineJump")).toList();
                        found += candidates.size();
                    }
                    require(found == 1, "One timeline invalidation per actual method");
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, actual);
                }
            } else {
                MethodNode originalRender = method(before, "renderVideo", "()Z");
                require(originalRender.exceptions.contains("java/lang/Throwable"), "Reviewed Replay renderer declares Throwable");
                require(after.methods.stream().anyMatch(m -> !m.name.equals("renderVideo") && m.desc.equals("()Z")
                        && equalInstructions(originalRender, m)), "Original render body retained behind MixinExtras");
                var wrappers = after.methods.stream().filter(m -> calls(m, "beginExport") && calls(m, "endExport")).toList();
                require(wrappers.size() == 1, "Exactly one merged mute/finally wrapper");
                MethodNode wrapper = wrappers.getFirst();
                require(wrapper.desc.equals("(Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z"), "Operation<Boolean> wrapper descriptor");
                require(wrapper.exceptions.contains("java/lang/Throwable"), "Merged wrapper retains Throwable declaration");
                require(wrapper.tryCatchBlocks.stream().anyMatch(block -> block.type == null), "Merged wrapper retains catch-all finally region");
                require(after.methods.stream().filter(m -> calls(m, "prepareAt")).count() == 1, "Existing export screen barrier retained once");
                for (MethodNode actual : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, actual);
                setupFixture(wrapper); executeCases();
            }
            require(host.reads.contains(adapter.replace('.', '/') + ".class"), "Transformer used actual compiled lifecycle mixin");
            receipts.add("{\"target\":\"" + target + "\",\"original_sha256\":\"" + sha(original)
                    + "\",\"mixin_sha256\":\"" + sha(mixin) + "\",\"transformed_sha256\":\"" + sha(transformed) + "\"}");
        }
        require(host.minecraftClassesDefined == 0, "No Minecraft class definitions");
        String result = "{\"ok\":true,\"checks\":" + checks + ",\"actual_replay_targets\":2,\"wrapper_cases\":" + wrapperCases
                + ",\"real_sponge_mixinextras_transform\":true,\"basic_verifier_passed\":true,\"timeline_original_bodies_retained\":true"
                + ",\"renderer_original_body_retained\":true,\"fixture_substitution\":\"ReplayBridge calls only; transformed wrapper control flow unchanged\""
                + ",\"minecraft_classes_defined\":0,\"audio_devices_opened\":0,\"results\":[" + String.join(",", receipts) + "]}";
        if (args.length == 1) Files.writeString(Path.of(args[0]), result + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(result);
    }
    private static boolean equalInstructions(MethodNode expected, MethodNode actual) {
        try { return expected.tryCatchBlocks.size() == actual.tryCatchBlocks.size() && instructions(expected).equals(instructions(actual)); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    public static final class Counters {
        static int depth, began, ended; static Throwable beginFailure;
        public static void beginExport() { depth++; began++; if (beginFailure != null) sneaky(beginFailure); }
        public static void endExport() { depth--; ended++; }
        static void reset() { depth = began = ended = 0; beginFailure = null; }
    }
    private static void setupFixture(MethodNode production) throws Exception {
        MethodNode copy = new MethodNode(production.access, production.name, production.desc,
                production.signature, production.exceptions.toArray(String[]::new)); production.accept(copy);
        copy.name = "run"; copy.access = Opcodes.ACC_PUBLIC;
        for (var instruction : copy.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) {
            require(Set.of("beginExport", "endExport").contains(call.name), "Only lifecycle counters substituted");
            call.owner = org.objectweb.asm.Type.getInternalName(Counters.class);
        }
        String name = "ReplayAudioLifecycleFixture";
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0); constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd(); copy.accept(writer); writer.visitEnd();
        class FixtureLoader extends ClassLoader {
            FixtureLoader() { super(ReplayAudioLifecycleTransformProbe.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }
        Class<?> loaded = new FixtureLoader().define(writer.toByteArray());
        receiver = loaded.getConstructor().newInstance(); fixture = loaded.getMethod("run", Operation.class);
    }
    private static boolean invoke(Operation<Boolean> operation) throws Throwable {
        try { return (boolean) fixture.invoke(receiver, operation); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void executeCases() throws Exception {
        for (boolean value : new boolean[]{true, false}) {
            Counters.reset(); int[] calls = {0};
            try { require(invoke(args -> { calls[0]++; require(args.length == 0 && Counters.depth == 1, "Mute before original render"); return value; }) == value, "Original boolean preserved"); }
            catch (Throwable unexpected) { throw new AssertionError(unexpected); }
            require(calls[0] == 1 && Counters.depth == 0 && Counters.began == 1 && Counters.ended == 1, "Normal/false return always unmutes"); wrapperCases++;
        }
        for (Throwable failure : List.of(new IllegalStateException("runtime sentinel"), new AssertionError("error sentinel"), new java.io.IOException("checked sentinel"))) {
            Counters.reset(); Throwable caught = null;
            try { invoke(args -> { require(Counters.depth == 1, "Mute before failing original render"); return sneaky(failure); }); }
            catch (Throwable actual) { caught = actual; }
            require(caught == failure && Counters.depth == 0 && Counters.began == 1 && Counters.ended == 1, "Exact original Throwable preserved and unmutes"); wrapperCases++;
        }
        Counters.reset();
        try {
            require(invoke(args -> {
                require(Counters.depth == 1, "Outer mute depth");
                try { require(invoke(inner -> { require(Counters.depth == 2, "Nested mute depth"); return false; }) == false, "Inner false result"); }
                catch (Throwable unexpected) { throw new AssertionError(unexpected); }
                require(Counters.depth == 1, "Inner return retains outer mute"); return true;
            }), "Outer result");
        } catch (Throwable unexpected) { throw new AssertionError(unexpected); }
        require(Counters.depth == 0 && Counters.began == 2 && Counters.ended == 2, "Nested exports balance"); wrapperCases++;
        // beginExport increments the production guard before querying ReplaySender.
        // Failure of that query must still restore the guard, before original render starts.
        Counters.reset(); Throwable failure = new IllegalStateException("begin sentinel"); Counters.beginFailure = failure;
        Throwable caught = null;
        try { invoke(args -> { throw new AssertionError("Original cannot run after begin failure"); }); }
        catch (Throwable actual) { caught = actual; }
        require(caught == failure && Counters.depth == 0 && Counters.began == 1 && Counters.ended == 1, "Begin failure must also unmute"); wrapperCases++;
    }
    @SuppressWarnings("unchecked")
    private static <R, T extends Throwable> R sneaky(Throwable failure) throws T { throw (T) failure; }
}
