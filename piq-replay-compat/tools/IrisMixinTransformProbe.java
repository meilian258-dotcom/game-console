import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import cn.piq.replaycompat.capture.IrisBufferContract;

/** Real Sponge/MixinExtras application using the existing read-only classpath QA host.
 * Compile together with piq-fc-arcade/tools/qa/KeyboardMixinTransformProbe.java.
 * Add tools/mixin-probe-resources, compiled production classes and pinned dependencies
 * to the runtime classpath. No Minecraft class, window, or emulator is initialized. */
public final class IrisMixinTransformProbe {
    public static void main(String[] args)throws Exception {
        System.setProperty("mixin.bootstrapService",KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service",KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();
        var environment=MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var host=(KeyboardMixinTransformProbe.Host)MixinService.getService();
        var transformer=host.transformer();MixinExtrasBootstrap.init();
        Mixins.addConfiguration("piq_iris_probe.mixins.json");
        byte[] original;
        try(var bytes=host.getResourceAsStream(IrisBufferContract.TARGET.replace('.','/')+".class")) {
            if(bytes==null)throw new AssertionError("Pinned Iris input is required for this probe");
            original=bytes.readAllBytes();
        }
        byte[] transformed=transformer.transformClass(environment,IrisBufferContract.TARGET,original);
        if(transformed==null||Arrays.equals(original,transformed))throw new AssertionError("Iris was not transformed");
        var before=new ClassNode();new ClassReader(original).accept(before,0);
        var after=new ClassNode();new ClassReader(transformed).accept(after,0);
        for(String name:List.of("wrap","flush")) {
            long calls=after.methods.stream().flatMap(m->Arrays.stream(m.instructions.toArray()))
                    .filter(i->i instanceof MethodInsnNode call&&call.owner.equals("cn/piq/replaycompat/capture/CaptureHooks")&&call.name.equals(name)).count();
            if(calls!=1)throw new AssertionError("Expected exactly one merged capture "+name+" call, got "+calls);
        }
        // The original getBuffer/endBatch bodies must remain behind MixinExtras' call-through.
        for(String descriptor:List.of(IrisBufferContract.GET,IrisBufferContract.END)) {
            MethodNode source=before.methods.stream().filter(m->m.desc.equals(descriptor)&&m.name.equals(descriptor.equals(IrisBufferContract.GET)?"getBuffer":"endBatch")).findFirst().orElseThrow();
            var candidates=after.methods.stream().filter(m->m.desc.equals(descriptor)&&!m.name.equals(source.name)
                    &&m.instructions.size()>=source.instructions.size()-5).toList();
            boolean found=false;
            for(MethodNode candidate:candidates) {
                MethodNode clone=new MethodNode(source.access,source.name,source.desc,null,null);candidate.accept(clone);
                clone.name=source.name;clone.access=source.access;
                ClassNode pair=new ClassNode();pair.name=before.name;pair.superName=before.superName;
                for(MethodNode other:before.methods)if((other.name.equals("getBuffer")&&other.desc.equals(IrisBufferContract.GET))
                        ||(other.name.equals("endBatch")&&other.desc.equals(IrisBufferContract.END)))pair.methods.add(other==source?clone:other);
                if(IrisBufferContract.supported(pair)){found=true;break;}
            }
            if(!found)throw new AssertionError("Original body not retained: "+source.name);
        }
        if(host.minecraftClassesDefined!=0)throw new AssertionError("Minecraft class was defined during bytecode probe");
        System.out.println("IrisMixinTransformProbe: real Sponge + MixinExtras transform applied; exactly one wrap/flush call; both original method bodies retained; zero Minecraft classes defined");
    }
}
