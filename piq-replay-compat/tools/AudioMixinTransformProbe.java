import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.*;

/** Real Sponge transform on installed endpoint bytecode; never opens a device or defines MC.
 * Compile with piq-fc-arcade/tools/qa/KeyboardMixinTransformProbe.java and run with
 * tools/mixin-probe-resources, production classes and exact dependencies on the classpath.
 * Optional argument is a new receipt JSON path; existing receipts are never overwritten. */
public final class AudioMixinTransformProbe {
    private static final String LINE = "javax/sound/sampled/SourceDataLine";
    private static final String BRIDGE = "cn/piq/replaycompat/audio/capture/JavaSoundCapture";
    private static final String PUBLIC_OUTPUT = "cn/piq/fcarcade/audio/ReplayAudioOutput";
    private static final List<String> PUBLIC_TARGETS = List.of("cn.piq.fcarcade.client.cabinet.CabinetAudio", "cn.piq.fcarcade.audio.NesAudioPlayer");
    private static final Set<String> OPERATIONS = Set.of("write", "flush", "close");
    private static final Map<String, String> TARGETS = new LinkedHashMap<>();
    static {
        TARGETS.put("Sfc", "cn.piq.sfcarcade.audio.SfcAudioPlayer");
        TARGETS.put("Gba", "cn.piq.gba.client.GbaHandheldAudio");
        TARGETS.put("NativeArcade", "cn.piq.nativearcade.client.NativeArcadeAudio");
        TARGETS.put("Computer", "cn.piq.computer.client.StreamSpeaker");
        TARGETS.put("Pvz", "cn.piq.pvz.runtime.PvzRuntime");
        TARGETS.put("PvzJni", "cn.piq.pvz.runtime.PvzJniRuntime");
    }
    private static int checks;
    private static void require(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static ClassNode node(byte[] bytes) { ClassNode result = new ClassNode(); new ClassReader(bytes).accept(result, 0); return result; }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] resource(KeyboardMixinTransformProbe.Host host, String name) throws Exception {
        try (var stream = host.getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("Reviewed target required for transform probe: " + name);
            return stream.readAllBytes();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[new-receipt.json]");
        System.setProperty("mixin.bootstrapService", KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service", KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();
        var environment = MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var host = (KeyboardMixinTransformProbe.Host) MixinService.getService();
        var transformer = host.transformer();
        require(transformer.getClass().getName().equals("org.spongepowered.asm.mixin.transformer.MixinTransformer"), "Real Sponge transformer");
        Mixins.addConfiguration("piq_audio_probe.mixins.json");
        List<String> receipts = new ArrayList<>(); int totalWrites = 0, totalFlushes = 0, totalCloses = 0;
        // The new host already captures final PCM explicitly. These classes must remain
        // byte-identical through Sponge, not gain a second copy of the old redirect.
        for (String target : PUBLIC_TARGETS) {
            byte[] original = resource(host, target);
            byte[] transformed = transformer.transformClass(environment, target, original);
            require(transformed == null || Arrays.equals(original, transformed), "Public owner must not be transformed twice: " + target);
            ClassNode owner = node(original); Map<String, Integer> counts = new HashMap<>();
            Set<String> covered = new HashSet<>();
            for (MethodNode method : owner.methods) {
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                    require(!(call.owner.equals(LINE) && OPERATIONS.contains(call.name)), "Public output cannot bypass capture");
                    require(!call.owner.startsWith("cn/piq/replaycompat/"), "Host cannot depend on optional recorder");
                    if (!call.owner.equals(PUBLIC_OUTPUT)) continue;
                    require(OPERATIONS.contains(call.name), "Only final public output operations");
                    require(call.getOpcode() == Opcodes.INVOKESTATIC && !call.itf, "Exact public output opcode");
                    String descriptor = call.name.equals("write") ? "(Ljavax/sound/sampled/SourceDataLine;[BII)I" : "(Ljavax/sound/sampled/SourceDataLine;)V";
                    require(call.desc.equals(descriptor), "Exact public output descriptor");
                    counts.merge(call.name, 1, Integer::sum); covered.add(method.name + method.desc + ":" + call.name);
                }
                new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
            }
            int writes = counts.getOrDefault("write", 0), flushes = counts.getOrDefault("flush", 0), closes = counts.getOrDefault("close", 0);
            require(writes == 1 && flushes > 0 && closes > 0, "Public write and lifecycle coverage");
            if (target.endsWith("NesAudioPlayer")) {
                for (String endpoint : List.of("playbackLoop()V:write", "playbackLoop()V:flush", "playbackLoop()V:close", "clear()V:flush", "close()V:flush", "close()V:close"))
                    require(covered.contains(endpoint), "Required public NES endpoint " + endpoint);
            } else {
                for (String endpoint : List.of("run()V:write", "run()V:flush", "run()V:close"))
                    require(covered.contains(endpoint), "Required public cabinet endpoint " + endpoint);
                require(covered.stream().allMatch(call -> call.startsWith("run()V:")), "Cabinet audio stays on worker");
            }
            totalWrites += writes; totalFlushes += flushes; totalCloses += closes;
            receipts.add("{\"target\":\"" + target + "\",\"route\":\"public_api\",\"original_sha256\":\"" + sha(original)
                    + "\",\"writes\":" + writes + ",\"flushes\":" + flushes + ",\"closes\":" + closes + ",\"merged_wrappers\":0}");
        }
        for (var entry : TARGETS.entrySet()) {
            String target = entry.getValue();
            byte[] original = resource(host, target), mixin = resource(host, "cn.piq.replaycompat.mixin.CaptureAudio" + entry.getKey());
            byte[] transformed = transformer.transformClass(environment, target, original);
            require(transformed != null && !Arrays.equals(original, transformed), "Target actually transformed: " + target);
            ClassNode before = node(original), after = node(transformed);
            Map<String, String> wrappers = new HashMap<>();
            for (MethodNode method : after.methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) {
                    require(OPERATIONS.contains(call.name), "Only final output bridge operations");
                    require(call.getOpcode() == Opcodes.INVOKESTATIC && !call.itf, "Correct bridge opcode");
                    require(wrappers.put(method.name + method.desc, call.name) == null, "One bridge call per redirect");
                    require(before.methods.stream().noneMatch(m -> m.name.equals(method.name) && m.desc.equals(method.desc)), "Merged wrapper is new");
                }
            }
            require(new HashSet<>(wrappers.values()).size() == wrappers.size(), "Exactly one merged wrapper per operation");
            Map<String, Integer> counts = new HashMap<>();
            for (MethodNode originalMethod : before.methods) {
                MethodNode actual = after.methods.stream().filter(m -> m.name.equals(originalMethod.name) && m.desc.equals(originalMethod.desc)).findFirst().orElseThrow();
                require(actual.access == originalMethod.access, "Original method access preserved");
                require(actual.tryCatchBlocks.size() == originalMethod.tryCatchBlocks.size(), "Original exception regions retained");
                List<String> expectedCalls = new ArrayList<>(), actualCalls = new ArrayList<>();
                for (var instruction : originalMethod.instructions) if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(LINE) && OPERATIONS.contains(call.name)) {
                        expectedCalls.add("CAPTURE:" + call.name); counts.merge(call.name, 1, Integer::sum);
                    } else expectedCalls.add(callKey(call));
                }
                for (var instruction : actual.instructions) if (instruction instanceof MethodInsnNode call) {
                    require(!(call.owner.equals(LINE) && OPERATIONS.contains(call.name)), "No missed direct final audio call");
                    String wrapped = call.owner.equals(after.name) ? wrappers.get(call.name + call.desc) : null;
                    actualCalls.add(wrapped == null ? callKey(call) : "CAPTURE:" + wrapped);
                }
                require(expectedCalls.equals(actualCalls), "All original invocation order and exact audio call count preserved: " + originalMethod.name);
                // Structural verifier uses the real transformed method instructions without
                // resolving or initializing Minecraft or any emulator implementation.
                new Analyzer<>(new BasicVerifier()).analyze(after.name, actual);
            }
            for (MethodNode wrapper : after.methods) if (wrappers.containsKey(wrapper.name + wrapper.desc))
                new Analyzer<>(new BasicVerifier()).analyze(after.name, wrapper);
            int writes = counts.getOrDefault("write", 0), flushes = counts.getOrDefault("flush", 0), closes = counts.getOrDefault("close", 0);
            require(writes == 1 && closes >= 1, "One final write and complete close handling per target");
            totalWrites += writes; totalFlushes += flushes; totalCloses += closes;
            receipts.add("{\"target\":\"" + target + "\",\"original_sha256\":\"" + sha(original)
                    + "\",\"mixin_sha256\":\"" + sha(mixin) + "\",\"transformed_sha256\":\"" + sha(transformed)
                    + "\",\"writes\":" + writes + ",\"flushes\":" + flushes + ",\"closes\":" + closes
                    + ",\"merged_wrappers\":" + wrappers.size() + "}");
        }
        require(host.minecraftClassesDefined == 0, "No Minecraft class definitions");
        String result = "{\"ok\":true,\"checks\":" + checks + ",\"targets\":" + (TARGETS.size() + PUBLIC_TARGETS.size())
                + ",\"legacy_mixin_targets\":" + TARGETS.size() + ",\"public_api_targets\":" + PUBLIC_TARGETS.size()
                + ",\"writes\":" + totalWrites + ",\"flushes\":" + totalFlushes + ",\"closes\":" + totalCloses
                + ",\"real_sponge_transform\":true,\"basic_verifier_passed\":true,\"original_invocation_order_preserved\":true"
                + ",\"minecraft_classes_defined\":0,\"audio_devices_opened\":0,\"results\":[" + String.join(",", receipts) + "]}";
        if (args.length == 1) Files.writeString(Path.of(args[0]), result + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(result);
    }
    private static String callKey(MethodInsnNode call) {
        return call.getOpcode() + ":" + call.owner + "." + call.name + call.desc + ":" + call.itf;
    }
}
