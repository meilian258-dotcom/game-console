import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cn.piq.retro.replay.ReplayGuard;

/** Actual Sponge/MixinExtras NEW transform and execution of its merged callback.
 * The executable fixture retains the production callback, pure ReplayGuard, normal-close
 * callback and forced-stop control flow. Only ReplayFile/ReplayHandler types and track
 * dependencies are replaced. No Minecraft, core, ReplayHandler constructor or device opens.
 */
public final class ReplayOpenGuardTransformProbe {
    private static final String TARGET = "com/replaymod/replay/ReplayModReplay";
    private static final String HANDLER = "com/replaymod/replay/ReplayHandler";
    private static final String FILE = "com/replaymod/replaystudio/replay/ReplayFile";
    private static final String GUARD = "cn/piq/retro/replay/ReplayGuard";
    private static final String BRIDGE = "cn/piq/replaycompat/replay/ReplayBridge";
    private static final String FIXTURE = "ReplayOpenGuardExecutableFixture";
    private static final String REJECTION = "cn/piq/replaycompat/replay/ReplayEntryRejection";
    private static final String REJECTION_FIXTURE = "ReplayEntryRejectionExecutableFixture";
    private static final String START = "(L" + FILE + ";ZZ)L" + HANDLER + ";";
    private static int checks, cases;
    private static Class<?> executable;
    private static Object receiver;
    private static Method callback, closed, forced, reject;
    private static void require(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static byte[] resource(KeyboardMixinTransformProbe.Host host, String name) throws Exception {
        try (var input = host.getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Required production bytes missing: " + name);
            return input.readAllBytes();
        }
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static ClassNode node(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static List<AbstractInsnNode> ops(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>(); for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction); return result;
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        return false;
    }
    @SuppressWarnings("unchecked")
    private static List<String> instructions(MethodNode method) throws Exception {
        Method reader = KeyboardMixinTransformProbe.class.getDeclaredMethod("instructions", MethodNode.class);
        reader.setAccessible(true); return (List<String>) reader.invoke(null, method);
    }
    private static MethodNode copy(MethodNode method) {
        var result = new MethodNode(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(String[]::new)); method.accept(result); return result;
    }
    private static int find(List<AbstractInsnNode> code, java.util.function.Predicate<AbstractInsnNode> filter) {
        for (int i = 0; i < code.size(); i++) if (filter.test(code.get(i))) return i;
        throw new AssertionError("Expected bytecode anchor missing");
    }
    /** Collapse only the NEW expression; all other instructions and branch targets must match. */
    private static List<String> collapsed(MethodNode original, int start, int end) throws Exception {
        MethodNode copy = copy(original); var code = ops(copy);
        var marker = new MethodInsnNode(Opcodes.INVOKESTATIC, "QAConstructorExpression", "newHandler", "()V", false);
        copy.instructions.insertBefore(code.get(start), marker);
        for (int i = start; i < end; i++) copy.instructions.remove(code.get(i));
        return instructions(copy);
    }
    private static void verifyBody(ClassNode before, ClassNode after, MethodNode wrapper) throws Exception {
        MethodNode source = method(before, "startReplay", START), actual = method(after, "startReplay", START);
        var old = ops(source); var changed = ops(actual);
        int start = find(old, i -> i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(HANDLER));
        int finish = find(old, i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(TARGET) && f.name.equals("replayHandler"));
        int newFinish = find(changed, i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(TARGET) && f.name.equals("replayHandler"));
        require(start < finish && start < newFinish, "NEW rewrite has an isolated constructor expression");
        require(collapsed(source, start, finish).equals(collapsed(actual, start, newFinish)), "Every original startReplay instruction and branch outside NEW preserved");
        require(actual.access == source.access && actual.exceptions.equals(source.exceptions)
                && actual.tryCatchBlocks.size() == source.tryCatchBlocks.size(), "Original startReplay signature and exception regions retained");
        int wrapperCalls = 0;
        for (int i = start; i < newFinish; i++) if (changed.get(i) instanceof MethodInsnNode call) {
            require(call.owner.equals(TARGET) && call.name.equals(wrapper.name) && call.desc.equals(wrapper.desc), "Only actual merged guard called inside rewritten NEW");
            wrapperCalls++;
        }
        require(wrapperCalls == 1, "Exactly one constructor wrapper invocation");
        int endReplay = find(old, i -> i instanceof MethodInsnNode c && c.owner.equals(HANDLER) && c.name.equals("endReplay"));
        int warning = find(old, i -> i instanceof MethodInsnNode c && c.owner.endsWith("GuiModCompatWarning") && c.name.equals("display"));
        require(endReplay < warning && warning < start, "Old endReplay and compatibility warning remain before guarded NEW");
        for (MethodNode original : before.methods) {
            if (original.name.equals("startReplay") && original.desc.equals(START)) continue;
            if (original.name.equals("forcefullyStopReplay") && original.desc.equals("()V")) {
                var oldCode = instructions(original); var newCode = instructions(method(after, original.name, original.desc));
                int prefix = newCode.size() - oldCode.size();
                require(prefix > 0 && newCode.subList(prefix, newCode.size()).equals(oldCode), "Forced-stop HEAD keeps entire original stop body");
            } else require(instructions(original).equals(instructions(method(after, original.name, original.desc))), "Unrelated original method preserved: " + original.name + original.desc);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[new-receipt.json]");
        System.setProperty("mixin.bootstrapService", KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service", KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();
        var environment = MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var host = (KeyboardMixinTransformProbe.Host) MixinService.getService(); var transformer = host.transformer();
        MixinExtrasBootstrap.init();
        Mixins.addConfiguration("piq_open_guard_probe.mixins.json"); // Includes the production API descriptor plugin.
        byte[] original = resource(host, TARGET), mixin = resource(host, "cn/piq/replaycompat/mixin/ReplayOpenGuardMixin");
        byte[] bridge = resource(host, BRIDGE), guard = resource(host, GUARD), rejection = resource(host, REJECTION);
        byte[] transformed = transformer.transformClass(environment, TARGET.replace('/', '.'), original);
        require(transformed != null && !Arrays.equals(original, transformed), "Real Sponge transformed actual ReplayModReplay");
        ClassNode before = node(original), after = node(transformed);
        var wrappers = after.methods.stream().filter(m -> calls(m, GUARD, "enterReplay")).toList();
        require(wrappers.size() == 1, "One actual merged entry guard"); MethodNode wrapper = wrappers.getFirst();
        require(wrapper.desc.equals("(L" + FILE + ";ZLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)L" + HANDLER + ";"), "Exact constructor-operation callback signature");
        require(wrapper.exceptions.contains("java/lang/Throwable"), "Merged wrapper can preserve checked and unchecked failures");
        require(wrapper.tryCatchBlocks.stream().anyMatch(t -> "java/lang/Throwable".equals(t.type)), "Merged catch-all cleanup retained");
        int constructions = 0;
        for (MethodNode actual : after.methods) for (var instruction : actual.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HANDLER) && call.name.equals("<init>")) {
                require(call.desc.equals("(L" + FILE + ";Z)V") && call.getOpcode() == Opcodes.INVOKESPECIAL, "Original constructor remains in operation factory");
                constructions++;
            }
        require(constructions == 1, "Exactly one actual original construction retained behind wrapper");
        verifyBody(before, after, wrapper);
        for (MethodNode actual : after.methods) new Analyzer<>(new BasicVerifier()).analyze(after.name, actual);
        require(host.reads.contains("cn/piq/replaycompat/mixin/ReplayOpenGuardMixin.class"), "Actual compiled mixin consumed by transformer");
        require(host.reads.contains("cn/piq/replaycompat/mixin/ReplayForceStopMixin.class"), "Actual compiled force-stop mixin consumed by transformer");
        verifyRejection(node(rejection)); setupFixture(wrapper, node(bridge), rejection); executeCases(); executeRejectionCases();
        require(host.minecraftClassesDefined == 0, "No Minecraft class definitions requested");
        String result = "{\"ok\":true,\"checks\":" + checks + ",\"behavior_cases\":" + cases
                + ",\"real_sponge_mixinextras_transform\":true,\"production_api_plugin_enabled\":true,\"basic_verifier_passed\":true"
                + ",\"original_start_body_except_new_preserved\":true,\"old_end_and_warning_order_preserved\":true,\"real_pure_guard_executed\":true"
                + ",\"actual_rejection_helper_executed\":true,\"fixture_substitution\":\"ReplayFile/Handler types, UI endpoints and track dependencies only; merged callback, rejection helper and lifecycle control flow retained\""
                + ",\"minecraft_classes_defined\":0,\"cores_started\":0,\"audio_devices_opened\":0"
                + ",\"target_sha256\":\"" + sha(original) + "\",\"mixin_sha256\":\"" + sha(mixin)
                + "\",\"transformed_sha256\":\"" + sha(transformed) + "\",\"bridge_sha256\":\"" + sha(bridge)
                + "\",\"guard_sha256\":\"" + sha(guard) + "\",\"rejection_helper_sha256\":\"" + sha(rejection) + "\"}";
        if (args.length == 1) Files.writeString(Path.of(args[0]), result + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(result);
    }

    public static final class FakePlayback {
        static int closes; static boolean fail;
        public void close() { closes++; if (fail) throw new IllegalStateException("track close sentinel"); }
    }
    public static final class Counters {
        static final List<String> events = new ArrayList<>();
        static int checks, failAt, audioCloses, warnings; static Throwable rejection;
        public static void check() {
            events.add("check:" + ReplayGuard.playing());
            if (++checks == failAt) sneaky(rejection);
        }
        public static void audioClose() { events.add("audio-close"); audioCloses++; }
        public static void warn(String message, Throwable failure) { warnings++; }
        static void reset() { events.clear(); checks = failAt = audioCloses = warnings = 0; rejection = null; FakePlayback.closes = 0; FakePlayback.fail = false; }
    }
    private static void verifyRejection(ClassNode helper) {
        MethodNode reject = method(helper, "reject", "(L" + TARGET + ";Ljava/lang/IllegalStateException;)Ljava/io/IOException;");
        require((reject.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC), "Rejection helper has exact public static descriptor");
        require(calls(reject, "java/io/IOException", "<init>") && calls(reject, "java/io/IOException", "addSuppressed"), "Actual helper constructs checked failure and retains display failure");
        require(reject.tryCatchBlocks.stream().anyMatch(t -> "java/lang/RuntimeException".equals(t.type))
                && reject.tryCatchBlocks.stream().anyMatch(t -> "java/lang/LinkageError".equals(t.type)), "UI failure catches retain checked contract");
        require(calls(reject, "net/minecraft/client/Minecraft", "setScreen"), "Actual helper displays a screen");
        require(helper.methods.stream().anyMatch(m -> calls(m, "com/replaymod/replay/gui/screen/GuiReplayViewer", "<init>")
                && calls(m, "com/replaymod/replay/gui/screen/GuiReplayViewer", "display")), "Retry callback creates and displays a new viewer");
        for (MethodNode method : helper.methods) for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call)
            require(!call.owner.equals(GUARD) && !call.owner.equals(BRIDGE) && !Set.of("save", "close").contains(call.name)
                    && !call.owner.startsWith("java/nio/file/"), "UI rejection must not mutate guard, tracks or recording files");
    }
    public interface FakeComponent {
        String value();
        static FakeComponent literal(String value) { return () -> value; }
    }
    public static class FakeScreen { }
    public static final class FakeAlert extends FakeScreen {
        final Runnable retry; final FakeComponent title, reason, button; final boolean narrate;
        public FakeAlert(Runnable retry, FakeComponent title, FakeComponent reason, FakeComponent button, boolean narrate) {
            this.retry = retry; this.title = title; this.reason = reason; this.button = button; this.narrate = narrate;
        }
    }
    public static final class FakeMinecraft {
        static final FakeMinecraft INSTANCE = new FakeMinecraft(); static FakeScreen screen; static Throwable fail;
        public static FakeMinecraft getInstance() { return INSTANCE; }
        public void setScreen(FakeScreen value) { if (fail != null) sneaky(fail); screen = value; }
    }
    public static final class FakeViewer {
        static Object owner; static int created, displayed;
        public FakeViewer(Object mod) { owner = mod; created++; }
        public void display() { displayed++; }
    }
    private static void setupFixture(MethodNode wrapper, ClassNode bridge, byte[] rejection) throws Exception {
        var normal = bridge.methods.stream().filter(m -> m.name.startsWith("lambda$") && m.desc.equals("(L" + HANDLER + ";)V") && calls(m, GUARD, "exitReplay")).toList();
        require(normal.size() == 1, "Actual normal-close callback identified by exitReplay");
        MethodNode force = method(bridge, "forcefullyStopPlayback", "()V");
        require(calls(force, GUARD, "exitReplay"), "Actual force-stop releases guard");
        var writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FIXTURE, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "handler", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "playback", "L" + org.objectweb.asm.Type.getInternalName(FakePlayback.class) + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "EXPORTS", "Ljava/util/concurrent/atomic/AtomicInteger;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "status", "Ljava/lang/String;", null, null).visitEnd();
        var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd();
        Remapper remap = new Remapper() {
            public String map(String type) {
                if (type.equals(FILE) || type.equals(HANDLER)) return "java/lang/Object";
                if (type.equals(BRIDGE) || type.equals(TARGET)) return FIXTURE;
                if (type.equals("cn/piq/replaycompat/replay/PlaybackSession")) return org.objectweb.asm.Type.getInternalName(FakePlayback.class);
                return type;
            }
        };
        for (MethodNode source : List.of(wrapper, normal.getFirst(), force)) {
            MethodNode cloned = copy(source);
            cloned.name = source == wrapper ? "open" : source == force ? "forcefullyStopPlayback" : "closed";
            cloned.access = Opcodes.ACC_PUBLIC | (source == wrapper ? 0 : Opcodes.ACC_STATIC);
            for (var instruction : cloned.instructions) if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("cn/piq/replaycompat/audio/AudioBridge") && call.name.equals("close")) { call.owner = org.objectweb.asm.Type.getInternalName(Counters.class); call.name = "audioClose"; }
                if (call.owner.equals(BRIDGE) && call.name.equals("warn")) call.owner = org.objectweb.asm.Type.getInternalName(Counters.class);
                if (call.owner.equals(REJECTION)) {
                    call.owner = REJECTION_FIXTURE;
                    call.desc = call.desc.replace("L" + TARGET + ";", "Ljava/lang/Object;");
                }
            }
            var target = writer.visitMethod(cloned.access, cloned.name, remap.mapMethodDesc(cloned.desc), null, cloned.exceptions.toArray(String[]::new));
            cloned.accept(new MethodRemapper(target, remap));
        }
        writer.visitEnd();
        class FixtureLoader extends ClassLoader {
            FixtureLoader() { super(ReplayOpenGuardTransformProbe.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }
        var loader = new FixtureLoader(); var rejectionWriter = new ClassWriter(0);
        new ClassReader(rejection).accept(new ClassRemapper(rejectionWriter, new Remapper() {
            public String map(String type) {
                return switch (type) {
                    case REJECTION -> REJECTION_FIXTURE;
                    case TARGET -> "java/lang/Object";
                    case "net/minecraft/client/Minecraft" -> org.objectweb.asm.Type.getInternalName(FakeMinecraft.class);
                    case "net/minecraft/client/gui/screens/Screen" -> org.objectweb.asm.Type.getInternalName(FakeScreen.class);
                    case "net/minecraft/client/gui/screens/AlertScreen" -> org.objectweb.asm.Type.getInternalName(FakeAlert.class);
                    case "net/minecraft/network/chat/Component", "net/minecraft/network/chat/MutableComponent" -> org.objectweb.asm.Type.getInternalName(FakeComponent.class);
                    case "com/replaymod/replay/gui/screen/GuiReplayViewer" -> org.objectweb.asm.Type.getInternalName(FakeViewer.class);
                    default -> type;
                };
            }
        }), 0);
        Class<?> rejectionClass = loader.define(rejectionWriter.toByteArray());
        reject = rejectionClass.getMethod("reject", Object.class, IllegalStateException.class);
        executable = loader.define(writer.toByteArray()); receiver = executable.getConstructor().newInstance();
        callback = executable.getMethod("open", Object.class, boolean.class, Operation.class);
        closed = executable.getMethod("closed", Object.class); forced = executable.getMethod("forcefullyStopPlayback");
        field("EXPORTS", new java.util.concurrent.atomic.AtomicInteger());
        ReplayGuard.registerEntryCheck("qa-open-transform", Counters::check);
    }
    private static void field(String name, Object value) throws Exception { executable.getField(name).set(null, value); }
    private static Object field(String name) throws Exception { return executable.getField(name).get(null); }
    private static Object invoke(Object file, boolean async, Operation<Object> operation) throws Throwable {
        try { return callback.invoke(receiver, file, async, operation); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void reset() throws Exception {
        ReplayGuard.exitReplay(); Counters.reset(); field("handler", null); field("playback", null);
        FakeMinecraft.screen = null; FakeMinecraft.fail = null; FakeViewer.owner = null; FakeViewer.created = FakeViewer.displayed = 0;
    }
    private static void executeCases() throws Exception {
        for (boolean async : new boolean[]{true, false}) {
            reset(); Object file = new Object(), handler = new Object(); long epoch = ReplayGuard.epoch(); int[] originals = {0};
            try {
                require(invoke(file, async, args -> {
                    originals[0]++; Counters.events.add("constructor");
                    require(args.length == 2 && args[0] == file && args[1].equals(async), "Original constructor args preserved");
                    require(ReplayGuard.playing() && !ReplayGuard.valid(epoch), "Protected before original constructor"); return handler;
                }) == handler, "Original handler identity preserved");
            } catch (Throwable failure) { throw new AssertionError(failure); }
            require(originals[0] == 1 && Counters.events.equals(List.of("check:false", "check:true", "constructor")), "Two checks enclose guard admission before constructor");
            require(ReplayGuard.playing() && Counters.audioCloses == 0, "Successful construction retains guard");
            field("handler", handler);
            closed.invoke(null, new Object()); require(ReplayGuard.playing() && field("handler") == handler, "Foreign close cannot release active handler");
            closed.invoke(null, handler); require(!ReplayGuard.playing() && field("handler") == null && !ReplayGuard.valid(epoch), "Actual normal-close callback exits without reviving pre-entry work");
            cases++;
        }
        for (Throwable failure : List.of(new IllegalStateException("runtime sentinel"), new AssertionError("error sentinel"), new java.io.IOException("checked sentinel"))) {
            reset(); long epoch = ReplayGuard.epoch(); field("playback", new FakePlayback()); Throwable caught = null;
            try { invoke(new Object(), true, args -> { require(ReplayGuard.playing(), "Failure occurs while protected"); return sneaky(failure); }); }
            catch (Throwable actual) { caught = actual; }
            require(caught == failure, "Exact original constructor Throwable preserved");
            require(!ReplayGuard.playing() && !ReplayGuard.valid(epoch) && Counters.audioCloses == 1 && FakePlayback.closes == 1, "Actual forced-stop flow releases only entered failure and closes tracks"); cases++;
        }
        for (boolean existing : new boolean[]{false, true}) {
            reset(); if (existing) ReplayGuard.enterReplay();
            Object handler = new Object(); field("handler", handler); field("playback", new FakePlayback()); long epoch = ReplayGuard.epoch();
            Counters.failAt = 1; Counters.rejection = new IllegalStateException("first admission rejected"); Throwable caught = null;
            try { invoke(new Object(), true, args -> { throw new AssertionError("Rejected entry must not construct"); }); }
            catch (Throwable actual) { caught = actual; }
            require(caught instanceof java.io.IOException && caught.getCause() == Counters.rejection
                    && ReplayGuard.epoch() == epoch && ReplayGuard.playing() == existing, "First checked rejection preserves cause and does not enter or exit another session");
            require(Counters.audioCloses == 0 && FakePlayback.closes == 0 && field("handler") == handler, "First rejection preserves existing track ownership"); cases++;
        }
        reset(); long epoch = ReplayGuard.epoch(); Counters.failAt = 2; Counters.rejection = new IllegalStateException("admission raced"); Throwable caught = null;
        try { invoke(new Object(), true, args -> { throw new AssertionError("Second rejection must not construct"); }); }
        catch (Throwable actual) { caught = actual; }
        require(caught instanceof java.io.IOException && caught.getCause() == Counters.rejection
                && !ReplayGuard.playing() && !ReplayGuard.valid(epoch) && Counters.audioCloses == 1, "Post-enter checked rejection releases its own guard and rejects stale work"); cases++;
        reset(); ReplayGuard.enterReplay(); field("handler", new Object()); field("playback", new FakePlayback()); FakePlayback.fail = true;
        forced.invoke(null); require(!ReplayGuard.playing() && field("handler") == null && Counters.warnings == 1, "Track-close failure cannot strand guard"); cases++;
        reset();
    }
    private static void executeRejectionCases() throws Exception {
        for (String reason : Arrays.asList("owner still closing", "  ", null)) {
            reset(); Object mod = new Object(); long epoch = ReplayGuard.epoch(); var original = new IllegalStateException(reason);
            var failure = (java.io.IOException) reject.invoke(null, mod, original);
            require(failure.getCause() == original && failure.getSuppressed().length == 0, "Actual helper preserves admission cause: " + Arrays.toString(failure.getSuppressed()));
            require(FakeMinecraft.screen instanceof FakeAlert, "Actual helper creates retry alert");
            var alert = (FakeAlert) FakeMinecraft.screen;
            require(!alert.reason.value().isBlank() && (reason == null || reason.isBlank() || alert.reason.value().contains(reason)), "Actual reason or safe fallback displayed");
            require(FakeViewer.created == 0, "Viewer is recreated only on explicit retry button");
            alert.retry.run(); require(FakeViewer.created == 1 && FakeViewer.displayed == 1 && FakeViewer.owner == mod, "Actual retry callback reconstructs viewer with the same owner");
            require(!ReplayGuard.playing() && ReplayGuard.epoch() == epoch, "UI helper alone never changes guard epoch"); cases++;
        }
        for (Throwable display : List.of(new IllegalStateException("screen unavailable"), new NoClassDefFoundError("screen linkage sentinel"))) {
            reset(); FakeMinecraft.fail = display; var original = new IllegalStateException("entry refused");
            var failure = (java.io.IOException) reject.invoke(null, new Object(), original);
            require(failure.getCause() == original && failure.getSuppressed().length == 1 && failure.getSuppressed()[0] == display, "Actual UI failure remains suppressed on checked rejection"); cases++;
        }
        reset();
    }
    @SuppressWarnings("unchecked") private static <R, T extends Throwable> R sneaky(Throwable failure) throws T { throw (T) failure; }
}
