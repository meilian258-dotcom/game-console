package cn.piq.fcarcade.client;

import cn.piq.fcarcade.audio.NoSignalTone;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Uses Minecraft's sound engine: block/master sliders, spatial attenuation and pause all apply. */
public final class TelevisionTone extends AbstractTickableSoundInstance {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "tv_no_signal");
    private static final Map<HomeTvBlockEntity, TelevisionTone> ACTIVE = new IdentityHashMap<>();
    private static int ticks;
    private final HomeTvBlockEntity television;
    private TelevisionTone(HomeTvBlockEntity tv) {
        super(SoundEvent.createVariableRangeEvent(ID), SoundSource.BLOCKS, RandomSource.create());
        television = tv; looping = true; delay = 0; relative = false;
        x = tv.getBlockPos().getX() + .5; y = tv.getBlockPos().getY() + .5; z = tv.getBlockPos().getZ() + .5;
        volume = tv.audioGain();
    }
    @Override public WeighedSoundEvents resolve(SoundManager manager) {
        // NeoForge's SoundInstance stream hook permits generated PCM; no missing .ogg lookup.
        sound = new Sound(ID, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 12);
        var event = new WeighedSoundEvents(ID, "subtitle.piq_fc_arcade.tv_no_signal");
        event.addSound(sound);
        return event;
    }
    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new Stream());
    }
    @Override public void tick() {
        if (!valid(television)) stop();
        else volume = television.audioGain();
    }
    private static boolean valid(HomeTvBlockEntity tv) {
        if (PrivateHomeClient.ownsDisplay(tv)) return false;
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && tv.getLevel() == mc.level && !tv.isRemoved()
                && mc.level.hasChunkAt(tv.getBlockPos()) && mc.level.getBlockEntity(tv.getBlockPos()) == tv
                && tv.noSignalToneEnabled() && !tv.muted() && !tv.emptyConsolePowered()
                && NoSignalTone.audible(tv.powered(), tv.signalPresent(), tv.volume(),
                        mc.player.distanceToSqr(tv.getBlockPos().getCenter()));
    }
    static void tickAll(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        ticks++;
        var wanted = HomeTvBlockEntity.clientLoaded().stream().filter(TelevisionTone::valid)
                .sorted(Comparator.comparingDouble(tv -> mc.player.distanceToSqr(tv.getBlockPos().getCenter())))
                .limit(8).toList();
        ACTIVE.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey()) && !entry.getValue().isStopped()) return false;
            entry.getValue().stop(); mc.getSoundManager().stop(entry.getValue()); return true;
        });
        if (mc.isPaused()) return; // Do not create fresh, unpaused channels behind the pause menu.
        for (var tv : wanted) {
            var tone = ACTIVE.get(tv);
            // Retry only once a second after resource reload / muted engine / device recovery.
            if (tone == null || ticks % 20 == 0 && !mc.getSoundManager().isActive(tone)) {
                if (tone != null) { tone.stop(); mc.getSoundManager().stop(tone); }
                tone = new TelevisionTone(tv); ACTIVE.put(tv, tone); mc.getSoundManager().play(tone);
            }
        }
    }
    static void closeAll() {
        var manager = Minecraft.getInstance().getSoundManager();
        ACTIVE.values().forEach(tone -> { tone.stop(); manager.stop(tone); }); ACTIVE.clear();
    }
    public static final class Stream implements AudioStream {
        private long cursor;
        private volatile boolean closed;
        @Override public AudioFormat getFormat() { return new AudioFormat(NoSignalTone.SAMPLE_RATE, 16, 1, true, false); }
        @Override public ByteBuffer read(int bytes) {
            if (bytes < 0 || bytes > 1_048_576) throw new IllegalArgumentException("Invalid PCM request");
            var data = ByteBuffer.allocateDirect(closed ? 0 : bytes & ~1).order(ByteOrder.LITTLE_ENDIAN);
            while (data.remaining() >= 2) data.putShort(NoSignalTone.sample(cursor++));
            return data.flip();
        }
        @Override public void close() { closed = true; }
    }
}
