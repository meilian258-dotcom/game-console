package cn.piq.replaycompat.capture;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Properties;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Reads provider resources without initializing any Minecraft or device class. */
public final class ReplayApiContract {
    public static final String DESCRIPTOR = "META-INF/game-console/replay-api.properties";
    @FunctionalInterface public interface Resources { InputStream open(String name) throws IOException; }
    private static final int PUBLIC_STATIC = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    private static final int PUBLIC_ABSTRACT = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
    private static final int METHOD_KIND = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT;
    private ReplayApiContract() {}
    public static void verify(Resources resources) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(read(resources, DESCRIPTOR, 4096)));
        for (var expected : Map.of("format","1","provider","piq_fc_arcade","minecraft","1.21.1",
                "guard","1","final-pcm","1","world-surface","1").entrySet())
            require(expected.getValue().equals(properties.getProperty(expected.getKey())), "不支持的接口 " + expected.getKey());
        var guard = api(resources, "cn/piq/retro/replay/ReplayGuard");
        for (String signature : new String[]{"install(Ljava/util/function/BooleanSupplier;)V", "playing()Z",
                "enterReplay()V", "exitReplay()V", "checkEntry()V", "epoch()J", "valid(J)Z",
                "registerEntryCheck(Ljava/lang/String;Ljava/lang/Runnable;)V"}) method(guard,signature,PUBLIC_STATIC);
        var retirement = node(resources, "cn/piq/retro/replay/ReplayGuard$Retirement");
        require((retirement.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0, "Retirement 不是可构造的类");
        method(retirement,"<init>()V",Opcodes.ACC_PUBLIC);
        method(retirement,"block(Ljava/lang/Runnable;)Z",Opcodes.ACC_PUBLIC);
        var audio = api(resources, "cn/piq/retro/replay/ReplayAudio");
        method(audio,"install(Lcn/piq/retro/replay/ReplayAudio$Service;)V",PUBLIC_STATIC);
        var service = node(resources, "cn/piq/retro/replay/ReplayAudio$Service");
        require((service.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT))
                == (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT), "PCM service 不是抽象接口");
        for (String signature : new String[]{"captureToken(Ljava/lang/Object;)Ljava/lang/Object;", "playing()Z",
                "capture(Ljava/lang/Object;Ljava/lang/Object;[BIIII)V", "reset(Ljava/lang/Object;)V", "close(Ljava/lang/Object;)V"}) {
            method(audio,signature,PUBLIC_STATIC); method(service,signature,PUBLIC_ABSTRACT);
        }
        var presentation = api(resources, "cn/piq/fcarcade/api/replay/ReplayPresentation");
        method(presentation,"install(Lcn/piq/fcarcade/api/replay/ReplayPresentation$Observer;)V",PUBLIC_STATIC);
        method(presentation,"screenMaterial(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;",PUBLIC_STATIC);
        var observer = node(resources, "cn/piq/fcarcade/api/replay/ReplayPresentation$Observer");
        require((observer.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT))
                == (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT), "屏幕 observer 不是抽象接口");
        for (String signature : new String[]{"material(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/RenderType;)V",
                "anchor(Lnet/minecraft/core/BlockPos;)V", "clearAnchor()V"}) {
            method(observer,signature,PUBLIC_ABSTRACT); method(presentation,signature,PUBLIC_STATIC);
        }
    }
    private static ClassNode api(Resources resources, String name) throws IOException {
        var node = node(resources,name);
        require(node.fields.stream().anyMatch(field -> field.name.equals("API_VERSION") && field.desc.equals("I")
                && (field.access & (PUBLIC_STATIC | Opcodes.ACC_FINAL)) == (PUBLIC_STATIC | Opcodes.ACC_FINAL)
                && Integer.valueOf(1).equals(field.value)),name + " API_VERSION 不是 1");
        return node;
    }
    private static ClassNode node(Resources resources, String name) throws IOException {
        var node = new ClassNode(); new ClassReader(read(resources,name+".class",131072)).accept(node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        require(name.equals(node.name) && (node.access & Opcodes.ACC_PUBLIC) != 0, "接口类身份不匹配 " + name);
        return node;
    }
    private static byte[] read(Resources resources,String name,int limit) throws IOException {
        try (var input = resources.open(name)) {
            require(input != null, "缺少 " + name);
            byte[] data=input.readNBytes(limit+1); require(data.length<=limit,"接口资源过大 " + name); return data;
        }
    }
    private static void method(ClassNode node,String signature,int access) {
        require(node.methods.stream().anyMatch(method -> (method.name+method.desc).equals(signature)
                && (method.access & access) == access && (method.access & METHOD_KIND) == (access & METHOD_KIND)),
                "接口签名不匹配 " + node.name + "." + signature);
    }
    private static void require(boolean value,String detail) {
        if (!value) throw new IllegalStateException("方块电玩 Replay 需要公共回放接口 v1；" + detail
                + "。请使用配套主模组和 Replay 补丁，不能仅删除版本限制。旧 FC76.46–52 使用 test.5。");
    }
}
