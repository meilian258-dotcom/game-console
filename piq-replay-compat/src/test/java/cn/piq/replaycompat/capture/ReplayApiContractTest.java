package cn.piq.replaycompat.capture;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Mutates real provider bytecode/resources, never initializes MC or emulator classes. */
public final class ReplayApiContractTest {
    private static int checks;
    private static final String GUARD = "cn/piq/retro/replay/ReplayGuard.class";
    private static final String RETIREMENT = "cn/piq/retro/replay/ReplayGuard$Retirement.class";
    private static final String AUDIO = "cn/piq/retro/replay/ReplayAudio.class";
    private static final String SERVICE = "cn/piq/retro/replay/ReplayAudio$Service.class";
    private static final String PRESENTATION = "cn/piq/fcarcade/api/replay/ReplayPresentation.class";
    private static final String OBSERVER = "cn/piq/fcarcade/api/replay/ReplayPresentation$Observer.class";
    private static final HashMap<String,byte[]> overrides = new HashMap<>();
    private static java.io.InputStream resource(String name) {
        if (overrides.containsKey(name)) {
            var value=overrides.get(name); return value==null?null:new ByteArrayInputStream(value);
        }
        return ReplayApiContractTest.class.getClassLoader().getResourceAsStream(name);
    }
    private static void valid() throws Exception { ReplayApiContract.verify(ReplayApiContractTest::resource); checks++; }
    private static void rejected(Runnable mutate) throws Exception {
        overrides.clear(); mutate.run();
        try { valid(); throw new AssertionError("unknown/broken contract admitted"); }
        catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("公共回放接口 v1")) throw new AssertionError(expected);
            checks++;
        } finally { overrides.clear(); }
    }
    private static void change(String owner, java.util.function.Consumer<ClassNode> mutation) {
        try (var input=ReplayApiContractTest.class.getClassLoader().getResourceAsStream(owner)) {
            var node=new ClassNode(); new ClassReader(input).accept(node,0); mutation.accept(node);
            var writer=new ClassWriter(0); node.accept(writer); overrides.put(owner,writer.toByteArray());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    /** Every required method is independently mutated; extra flags like synchronized remain allowed. */
    private static void methods(String owner, String... signatures) throws Exception {
        for (String signature : signatures) {
            rejected(()->change(owner,node->node.methods.removeIf(m->(m.name+m.desc).equals(signature))));
            rejected(()->change(owner,node->node.methods.stream().filter(m->(m.name+m.desc).equals(signature))
                    .forEach(m->m.desc="(I"+m.desc.substring(1))));
            for (int access : new int[]{Opcodes.ACC_PUBLIC, Opcodes.ACC_STATIC, Opcodes.ACC_ABSTRACT})
                rejected(()->change(owner,node->node.methods.stream().filter(m->(m.name+m.desc).equals(signature))
                        .forEach(m->m.access ^= access)));
        }
    }
    public static void main(String[] args) throws Exception {
        valid();
        byte[] descriptor;
        try(var input=resource(ReplayApiContract.DESCRIPTOR)) { descriptor=input.readAllBytes(); }
        String text=new String(descriptor,StandardCharsets.ISO_8859_1);
        rejected(()->overrides.put(ReplayApiContract.DESCRIPTOR,null));
        for (String owner : new String[]{GUARD,RETIREMENT,AUDIO,SERVICE,PRESENTATION,OBSERVER}) {
            rejected(()->overrides.put(owner,null));
            rejected(()->change(owner,node->node.access &= ~Opcodes.ACC_PUBLIC));
        }
        rejected(()->overrides.put(ReplayApiContract.DESCRIPTOR,text.replace("guard=1","guard=2").getBytes(StandardCharsets.ISO_8859_1)));
        rejected(()->overrides.put(ReplayApiContract.DESCRIPTOR,text.replace("final-pcm=1","final-pcm=0").getBytes(StandardCharsets.ISO_8859_1)));
        rejected(()->overrides.put(ReplayApiContract.DESCRIPTOR,text.replace("minecraft=1.21.1","minecraft=1.21.2").getBytes(StandardCharsets.ISO_8859_1)));
        rejected(()->overrides.put(ReplayApiContract.DESCRIPTOR,new byte[4097]));
        for (String owner : new String[]{GUARD,AUDIO,PRESENTATION}) {
            rejected(()->change(owner,node->node.fields.stream().filter(f->f.name.equals("API_VERSION")).forEach(f->f.value=2)));
            rejected(()->change(owner,node->node.fields.removeIf(f->f.name.equals("API_VERSION"))));
        }
        for (String owner : new String[]{SERVICE,OBSERVER}) {
            rejected(()->change(owner,node->node.access &= ~Opcodes.ACC_INTERFACE));
            rejected(()->change(owner,node->node.access &= ~Opcodes.ACC_ABSTRACT));
        }
        rejected(()->change(RETIREMENT,node->node.access |= Opcodes.ACC_ABSTRACT));
        rejected(()->change(RETIREMENT,node->node.access |= Opcodes.ACC_INTERFACE));
        methods(GUARD,"install(Ljava/util/function/BooleanSupplier;)V","playing()Z","enterReplay()V",
                "exitReplay()V","checkEntry()V","epoch()J","valid(J)Z",
                "registerEntryCheck(Ljava/lang/String;Ljava/lang/Runnable;)V");
        methods(RETIREMENT,"<init>()V","block(Ljava/lang/Runnable;)Z");
        methods(AUDIO,"install(Lcn/piq/retro/replay/ReplayAudio$Service;)V");
        for (String owner : new String[]{AUDIO,SERVICE})
            methods(owner,"captureToken(Ljava/lang/Object;)Ljava/lang/Object;","playing()Z",
                    "capture(Ljava/lang/Object;Ljava/lang/Object;[BIIII)V","reset(Ljava/lang/Object;)V","close(Ljava/lang/Object;)V");
        methods(PRESENTATION,"install(Lcn/piq/fcarcade/api/replay/ReplayPresentation$Observer;)V",
                "screenMaterial(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;");
        for (String owner : new String[]{PRESENTATION,OBSERVER})
            methods(owner,"material(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/RenderType;)V",
                    "anchor(Lnet/minecraft/core/BlockPos;)V","clearAnchor()V");
        // The mod's release number is not the compatibility key, including a later major.
        for(String version:new String[]{"0.31.0-alpha.76.53","0.31.0-alpha.76.99","0.32.0","1.0"}) {
            overrides.put(ReplayApiContract.DESCRIPTOR,(text+"\nmod-version="+version+"\n").getBytes(StandardCharsets.ISO_8859_1)); valid();
        }
        overrides.clear(); valid();
        System.out.println("ReplayApiContractTest: "+checks+" descriptor + real provider bytecode checks; no MC initialization");
    }
}
