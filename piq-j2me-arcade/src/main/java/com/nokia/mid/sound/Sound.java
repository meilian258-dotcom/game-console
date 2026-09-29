package com.nokia.mid.sound;

import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

/**
 * Compatibility implementation of Nokia Sound.
 *
 * <p>The first J2ME arcade iteration intentionally keeps vendor audio muted;
 * it preserves lifecycle/state calls so games can start without competing
 * with Minecraft's audio mixer.</p>
 */
public class Sound implements PlayerListener {
    public static final int FORMAT_TONE = 1;
    public static final int FORMAT_WAV = 5;
    public static final int SOUND_PLAYING = 0;
    public static final int SOUND_STOPPED = 1;
    public static final int SOUND_UNINITIALIZED = 3;

    public Player temp;

    private int state = SOUND_UNINITIALIZED;
    private int gain = 200;
    private SoundListener listener;

    public Sound(byte[] data, int type) {
        init(data, type);
    }

    public Sound(int frequency, long duration) {
        init(frequency, duration);
    }

    public void init(int frequency, long duration) {
        state = SOUND_STOPPED;
    }

    public void init(byte[] data, int type) {
        state = SOUND_STOPPED;
    }

    public int getState() {
        return state;
    }

    public void play(int loop) {
        state = SOUND_PLAYING;
        notifyListener(SOUND_PLAYING);
    }

    public void stop() {
        state = SOUND_STOPPED;
        notifyListener(SOUND_STOPPED);
    }

    public void resume() {
        play(1);
    }

    public void release() {
        if (temp != null) {
            temp.close();
            temp = null;
        }
        state = SOUND_UNINITIALIZED;
        notifyListener(SOUND_UNINITIALIZED);
    }

    public void setGain(int gain) {
        this.gain = Math.max(0, Math.min(255, gain));
    }

    public int getGain() {
        return gain;
    }

    public static int getConcurrentSoundCount(int type) {
        return 1;
    }

    public static int[] getSupportedFormats() {
        return new int[] {FORMAT_TONE, FORMAT_WAV};
    }

    public void setSoundListener(SoundListener listener) {
        this.listener = listener;
    }

    @Override
    public void playerUpdate(Player player, String event, Object eventData) {
        if (PlayerListener.STARTED.equals(event)) {
            state = SOUND_PLAYING;
            notifyListener(SOUND_PLAYING);
        } else if (PlayerListener.STOPPED.equals(event) || PlayerListener.END_OF_MEDIA.equals(event)) {
            state = SOUND_STOPPED;
            notifyListener(SOUND_STOPPED);
        } else if (PlayerListener.CLOSED.equals(event)) {
            state = SOUND_UNINITIALIZED;
            notifyListener(SOUND_UNINITIALIZED);
        }
    }

    private void notifyListener(int event) {
        SoundListener current = listener;
        if (current != null) {
            current.soundStateChanged(this, event);
        }
    }
}
