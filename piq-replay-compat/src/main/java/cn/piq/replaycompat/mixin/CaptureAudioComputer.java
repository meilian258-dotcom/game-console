package cn.piq.replaycompat.mixin;

import cn.piq.replaycompat.audio.capture.JavaSoundCapture;
import javax.sound.sampled.SourceDataLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Capture only accepted, post-volume PCM at this optional endpoint. */
@Pseudo
@Mixin(targets="cn.piq.computer.client.StreamSpeaker", remap=false)
public abstract class CaptureAudioComputer {
    @Redirect(method="play()V", at=@At(value="INVOKE",
            target="Ljavax/sound/sampled/SourceDataLine;write([BII)I"), require=1)
    private int piqReplay$write(SourceDataLine line, byte[] data, int offset, int length) {
        return JavaSoundCapture.write(line, data, offset, length);
    }

    @Redirect(method={"play()V"}, at=@At(value="INVOKE",
            target="Ljavax/sound/sampled/SourceDataLine;flush()V"), require=1)
    private void piqReplay$flush(SourceDataLine line) { JavaSoundCapture.flush(line); }

    @Redirect(method={"play()V", "close()V"}, at=@At(value="INVOKE",
            target="Ljavax/sound/sampled/SourceDataLine;close()V"), require=1)
    private void piqReplay$close(SourceDataLine line) { JavaSoundCapture.close(line); }
}
