package cn.piq.replaycompat.client;

import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Verifies actual compiled render calls and installed API signatures without initializing Minecraft. */
public final class ScreenPresentationBytecodeTest {
    private static int checks;
    private static final String FRAME = "cn/piq/replaycompat/api/ScreenFrame";
    private static final String IMAGE = "com/mojang/blaze3d/platform/NativeImage";
    private static final String TEXTURE = "net/minecraft/client/renderer/texture/DynamicTexture";
    private static final String EVENT = "net/neoforged/neoforge/client/event/RenderLevelStageEvent";
    public static void main(String[] args) throws Exception {
        var render = read("cn/piq/replaycompat/client/ReplayScreens");
        var cache = read("cn/piq/replaycompat/client/ScreenPresentationCache");
        for (var node : new ClassNode[]{render, cache}) {
            for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                check(!(call.owner.equals(FRAME) && call.name.equals("abgr")), "render/cache must not clone pixel arrays");
                check(!call.owner.contains("WatchClient"), "render/cache must not run live watching");
                check(!call.owner.contains("Unsafe") && !call.owner.contains("MemoryUtil"), "no raw-memory API");
            }
        }
        check(calls(method(render, "fill", "(L"+IMAGE+";L"+FRAME+";)V"), FRAME, "pixelAbgr", "(I)I"), "readonly pixel accessor");
        check(calls(method(cache, "find", "(L"+FRAME+";I)Lcn/piq/replaycompat/client/ScreenPresentationCache$Image;"),
                FRAME, "samePixels", "(L"+FRAME+";)Z"), "hash hits use exact pixel comparison");
        var end = method(render, "end", "(L"+EVENT+";)V");
        check(calls(end, EVENT, "getFrustum", "()Lnet/minecraft/client/renderer/culling/Frustum;"), "actual render-pass frustum");
        check(calls(end, EVENT, "getCamera", "()Lnet/minecraft/client/Camera;"), "actual render-pass camera");
        check(calls(end, "cn/piq/fcarcade/api/replay/ReplayPresentation", "screenMaterial", "(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"), "use versioned public screen material");
        check(calls(method(render, "drop", "(I)V"), "net/minecraft/client/renderer/texture/TextureManager", "release",
                "(Lnet/minecraft/resources/ResourceLocation;)V"), "release registered textures");
        var frustum = read("net/minecraft/client/renderer/culling/Frustum");
        check((method(frustum, "isVisible", "(Lnet/minecraft/world/phys/AABB;)Z").access & Opcodes.ACC_PUBLIC) != 0, "real public frustum API");
        check((method(read(EVENT), "getFrustum", "()Lnet/minecraft/client/renderer/culling/Frustum;").access & Opcodes.ACC_PUBLIC) != 0, "real event API");
        check(calls(method(read(TEXTURE), "<init>", "(L"+IMAGE+";)V"), TEXTURE, "upload", "()V"), "populated-image constructor uploads");
        System.out.println("ScreenPresentationBytecodeTest: " + checks + " assertions passed; no GPU initialized");
    }
    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) return true;
        return false;
    }
    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        for (var method : owner.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        throw new AssertionError("Missing actual method " + owner.name + "." + name + descriptor);
    }
    private static ClassNode read(String name) throws IOException {
        try (var input = ScreenPresentationBytecodeTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("Missing " + name);
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static void check(boolean valid, String message) { checks++; if (!valid) throw new AssertionError(message); }
}
