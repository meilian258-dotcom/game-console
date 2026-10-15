package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.capture.IrisBufferContract;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Do not resolve optional addon classes with Class.forName (which would load them before mixing). */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage){
        try { cn.piq.replaycompat.capture.ReplayApiContract.verify(name -> MixinService.getService().getResourceAsStream(name)); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("无法核验方块电玩公共回放接口",invalid); }
    }
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String target,String mixin){
        if(target.equals(IrisBufferContract.TARGET)) {
            try(var bytes=MixinService.getService().getResourceAsStream(target.replace('.','/')+".class")) {
                if(bytes==null)return false;
                // Gate the supplied Iris implementation, not a class already rewritten by
                // this or another Mixin. ModLauncher's getClassNode(false) is unsupported;
                // resource access reads bytes without loading or transforming the target.
                ClassNode node=new ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(node,org.objectweb.asm.ClassReader.SKIP_DEBUG|org.objectweb.asm.ClassReader.SKIP_FRAMES);
                if(IrisBufferContract.supported(node))return true;
                org.apache.logging.log4j.LogManager.getLogger("PIQ-ReplayScreen").warn(
                        "Iris screen capture adapter disabled: unreviewed buffer implementation ("+IrisBufferContract.fingerprint(node)
                        +"). Supported target: Iris 1.8.14-beta.1+mc1.21.1. Screen capture through Iris is not supported on this build.");
                return false;
            } catch(java.io.IOException invalid){throw new IllegalStateException("Cannot inspect optional Iris screen adapter",invalid);}
        }
        if(!target.startsWith("cn.piq."))return true;
        try {MixinService.getService().getBytecodeProvider().getClassNode(target);return true;}
        catch(ClassNotFoundException missing){return false;}
        catch(java.io.IOException invalid){throw new IllegalStateException("Cannot inspect optional screen adapter target "+target,invalid);}
    }
    @Override public void acceptTargets(Set<String> mine,Set<String> other){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String name,ClassNode node,String mixin,IMixinInfo info){}
    @Override public void postApply(String name,ClassNode node,String mixin,IMixinInfo info){}
}
