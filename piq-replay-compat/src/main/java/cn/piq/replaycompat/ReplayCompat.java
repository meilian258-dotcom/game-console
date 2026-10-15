package cn.piq.replaycompat;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenReplay;
import cn.piq.replaycompat.replay.ReplayBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import java.util.List;

@Mod(value="piq_replay_compat",dist=Dist.CLIENT)
public final class ReplayCompat {
    public ReplayCompat(){
        ReplayBridge.init();
        cn.piq.retro.replay.ReplayGuard.install(ReplayBridge::playing);
        cn.piq.fcarcade.api.replay.ReplayPresentation.install(new cn.piq.fcarcade.api.replay.ReplayPresentation.Observer() {
            public void material(net.minecraft.resources.ResourceLocation texture, net.minecraft.client.renderer.RenderType type) {
                cn.piq.replaycompat.capture.CaptureHooks.material(texture, type);
            }
            public void anchor(net.minecraft.core.BlockPos position) { cn.piq.replaycompat.capture.CaptureHooks.anchor(position); }
            public void clearAnchor() { cn.piq.replaycompat.capture.CaptureHooks.clearAnchor(); }
        });
        cn.piq.retro.replay.ReplayAudio.install(new cn.piq.retro.replay.ReplayAudio.Service() {
            public Object captureToken(Object source) { return cn.piq.replaycompat.api.AudioReplay.captureToken(source); }
            public boolean playing() { return cn.piq.retro.replay.ReplayGuard.playing(); }
            public void capture(Object token,Object source,byte[] pcm,int offset,int length,int sampleRate,int channels) {
                cn.piq.replaycompat.api.AudioReplay.capture(token, source, pcm, offset, length, sampleRate, channels);
            }
            public void reset(Object source) { cn.piq.replaycompat.api.AudioReplay.reset(source); }
            public void close(Object source) { cn.piq.replaycompat.api.AudioReplay.close(source); }
        });
        ScreenReplay.install(new ScreenReplay.Service(){
            public boolean recording(){return ReplayBridge.recording();}
            public boolean playing(){return cn.piq.retro.replay.ReplayGuard.playing();}
            public long recordingTime(){return ReplayBridge.recordingTime();}
            public void submit(ScreenFrame f){ReplayBridge.submit(f);}
            public void close(String source,long time){ReplayBridge.close(source,time);}
            public List<ScreenFrame> at(long time){return ReplayBridge.at(time);}
        });
    }
}
